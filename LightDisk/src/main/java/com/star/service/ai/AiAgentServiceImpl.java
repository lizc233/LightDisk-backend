package com.star.service.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.star.config.AiProperties;
import com.star.exception.BusinessException;
import com.star.model.dto.AiChatRequestDTO;
import com.star.model.vo.AiChatResponseVO;
import com.star.model.vo.AiCriticVO;
import com.star.model.vo.AiSourceVO;
import com.star.model.vo.AiStageVO;
import com.star.service.AiAssistantService;
import com.star.service.impl.AiAssistantServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LightDisk 轻量 Agent（阶段 1-3：真模型 + 自研工具层 + 复核 Agent）。
 *
 * <pre>
 *   用户提问
 *     ↓
 *   ① 意图理解   LLM JSON 模式分类（LOCATE / SUMMARIZE / EDIT / QA），失败回落规则分类
 *     ↓
 *   ② 权限校验   按 VIP 等级判定：普通=只能问位置，初级+=可总结，高级+=可编辑
 *     ↓
 *   ③ 工具调用   服务端校验参数后执行（查文件位置 / 读文本内容 / 写回文件）
 *     ↓
 *   ④ 生成回答   LLM 基于工具事实作答（无模型时回落内置规则引擎）
 *     ↓
 *   ⑤ 复核 Agent  规则校验（幻觉/越权/工具失败）+ 模型复核，不通过则修正重写
 * </pre>
 *
 * 降级策略：{@code lightdisk.ai.enabled=false} 或 API Key 缺失 / 调用失败时，
 * 自动回落 {@link AiAssistantServiceImpl}（纯本地规则 + 数据库检索），系统仍然可用。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class AiAgentServiceImpl implements AiAssistantService {

    /** 文件名样式：xxx.ext */
    private static final Pattern FILE_NAME_PATTERN =
            Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9_\\-（）()]{1,40}\\.[A-Za-z0-9]{1,8}");

    private final AiAssistantServiceImpl ruleEngine;
    private final AiToolService toolService;
    private final LlmClient llmClient;
    private final AiProperties aiProperties;
    private final McpToolClient mcpToolClient;

    @Override
    public AiChatResponseVO chat(Long userId, AiChatRequestDTO requestDTO) {
        String query = requestDTO == null ? null : StrUtil.trim(requestDTO.getQuery());
        if (userId == null) {
            throw new BusinessException("登录状态无效，请重新登录");
        }
        if (StrUtil.isBlank(query)) {
            throw new BusinessException("请输入问题后再试");
        }

        int level = toolService.levelOf(userId);
        boolean online = aiProperties.usable();

        // 规则引擎先跑一遍：它负责数据库侧检索，产出的 sources / 能力卡片前端已经在渲染，
        // 在线模式只把「回答」换成模型基于这些事实生成的内容。
        AiChatResponseVO base = ruleEngine.chat(userId, requestDTO);

        List<AiStageVO> stages = new ArrayList<AiStageVO>();

        // ==================== ① 意图理解 ====================
        Plan plan = online ? classifyByLlm(query) : ruleClassify(query, base);
        stages.add(stage("intent", "意图理解", String.format("识别为「%s」%s，检索关键词：%s",
                intentName(plan.intent),
                plan.reason == null ? "" : "（" + plan.reason + "）",
                StrUtil.isBlank(plan.keyword) ? "无" : plan.keyword)));

        // ==================== ② 权限校验 ====================
        String deny = null;
        if ("SUMMARIZE".equals(plan.intent) && !toolService.canSummarize(level)) {
            deny = "总结/阅读文件内容是初级用户及以上的能力。你当前是"
                    + toolService.permissionName(level) + "，请先升级 VIP 等级后再试。";
        } else if ("EDIT".equals(plan.intent) && !toolService.canEdit(level)) {
            deny = "让 AI 直接编辑网盘文件是高级用户及以上的能力。你当前是"
                    + toolService.permissionName(level) + "，请先升级 VIP 等级后再试。";
        } else if ("EDIT".equals(plan.intent) && !online) {
            deny = "当前处于离线规则引擎模式（未配置或未启用大模型），出于数据安全考虑不执行 AI 写文件操作。";
        }
        stages.add(stage("permission", "权限校验", deny == null
                ? "通过（当前身份：" + toolService.permissionName(level) + "）"
                : "未通过：" + deny));

        // ==================== ③ 工具调用 ====================
        List<Map<String, Object>> toolResults = new ArrayList<Map<String, Object>>();
        String toolStageDetail = "未调用工具（通用问答）";
        AiSourceVO toolSource = null;
        String editSummary = null;
        boolean mcpUsed = false;

        if (deny == null) {
            if ("LOCATE".equals(plan.intent)) {
                List<Map<String, Object>> files = toolService.searchFiles(userId, plan.keyword, 8);
                if (files.isEmpty()) {
                    files = toolService.listRecentFiles(userId, 5);
                    toolStageDetail = "search_files(关键词=「" + StrUtil.nullToEmpty(plan.keyword) + "」) 无命中，回退为最近文件 "
                            + files.size() + " 条";
                } else {
                    toolStageDetail = "search_files(关键词=「" + StrUtil.nullToEmpty(plan.keyword) + "」) 命中 "
                            + files.size() + " 条";
                }
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("tool", "search_files");
                r.put("ok", true);
                r.put("items", files);
                toolResults.add(r);
            } else if ("SUMMARIZE".equals(plan.intent)) {
                Map<String, Object> target = resolveTargetFile(userId, plan);
                if (target == null) {
                    toolStageDetail = "read_text_file：未找到匹配文件";
                } else {
                    Long fileId = asLong(target.get("fileId"));
                    Map<String, Object> read = toolService.readTextFile(userId, fileId);
                    read.put("tool", "read_text_file");
                    read.put("fileInfo", target);
                    toolResults.add(read);
                    toolStageDetail = "read_text_file(" + target.get("path") + target.get("name") + ") → "
                            + (Boolean.TRUE.equals(read.get("readable"))
                            ? "读取 " + read.get("charCount") + " 字符" : "不可读：" + read.get("reason"));
                    toolSource = new AiSourceVO();
                    toolSource.setSourceType("file");
                    toolSource.setSourceId(fileId);
                    toolSource.setTitle(String.valueOf(target.get("name")));
                    toolSource.setPath(String.valueOf(target.get("path")));
                }
            } else if ("EDIT".equals(plan.intent)) {
                Map<String, Object> target = resolveTargetFile(userId, plan);
                if (target == null) {
                    toolStageDetail = "write_text_file：未找到匹配文件";
                } else {
                    Long fileId = asLong(target.get("fileId"));
                    Map<String, Object> read = toolService.readTextFile(userId, fileId);
                    String oldContent = read.get("content") == null ? "" : String.valueOf(read.get("content"));
                    String newContent = generateEditedContent(plan, String.valueOf(target.get("name")), oldContent);
                    if (StrUtil.isBlank(newContent)) {
                        toolStageDetail = "模型未能生成新的文件内容，已放弃写入";
                    } else {
                        Map<String, Object> write = toolService.writeTextFile(userId, fileId, newContent, null);
                        write.put("tool", "write_text_file");
                        write.put("fileInfo", target);
                        toolResults.add(write);
                        boolean ok = Boolean.TRUE.equals(write.get("ok"));
                        toolStageDetail = "write_text_file(" + target.get("path") + target.get("name")
                                + ") → " + (ok ? "写入成功（写时复制，新 md5=" + write.get("newMd5") + "）"
                                : "失败：" + write.get("error"));
                        if (ok) {
                            editSummary = "已按你的要求修改文件「" + target.get("name") + "」，"
                                    + "新内容 " + write.get("bytes") + " 字节，保存走写时复制（不覆盖其他用户共享的同一份物理文件）。";
                        }
                        toolSource = new AiSourceVO();
                        toolSource.setSourceType("file");
                        toolSource.setSourceId(fileId);
                        toolSource.setTitle(String.valueOf(target.get("name")));
                        toolSource.setPath(String.valueOf(target.get("path")));
                    }
                }
            }
            if (toolStageDetail.equals("未调用工具（通用问答）")) {
                toolStageDetail = "未调用工具";
            }
        } else {
            toolStageDetail = "权限不足，跳过工具调用";
        }
        stages.add(stage("tools", "工具调用", toolStageDetail));

        // ==================== ④ 生成回答 ====================
        String answer;
        if (deny != null) {
            answer = deny;
        } else if (editSummary != null) {
            answer = editSummary;
        } else if (online) {
            String llmAnswer = synthesize(query, plan, toolResults, base);
            answer = StrUtil.isBlank(llmAnswer) ? base.getAnswer() : llmAnswer;
        } else if ("SUMMARIZE".equals(plan.intent) && hasFileContent(toolResults)) {
            // 离线也要能用：优先走 MCP 工具（标准协议、确定性抽取），不可用再回落本地摘要
            Map<String, Object> read = firstReadable(toolResults);
            String mcpSummary = summarizeViaMcp(read);
            if (mcpSummary != null) {
                answer = mcpSummary;
                mcpUsed = true;
            } else {
                answer = localSummary(toolResults);
            }
        } else {
            answer = str(base.getAnswer()) + "\n\n（提示：当前为离线规则引擎模式，回答来自本地检索，未调用大模型。）";
        }
        stages.add(stage("generate", "回答生成", online && deny == null
                ? "模型 " + aiProperties.getModel() + " 基于工具结果生成"
                : (deny != null ? "返回权限提示"
                : (mcpUsed ? "MCP 工具 extractive_summary 抽取式摘要（标准 MCP 协议调用 Node 工具服务器）"
                : ("SUMMARIZE".equals(plan.intent) && hasFileContent(toolResults)
                        ? "本地抽取式摘要（MCP 不可用，降级内置实现）" : "内置规则引擎生成")))));

        // ==================== ⑤ 复核 Agent ====================
        AiCriticVO critic = review(query, answer, toolResults, plan, deny, online);
        if (Boolean.FALSE.equals(critic.getPassed()) && deny == null && online) {
            String repaired = repair(query, answer, toolResults, critic.getIssues());
            if (StrUtil.isNotBlank(repaired)) {
                answer = repaired;
                critic.setRepaired(true);
            } else {
                // 模型修正也失败 → 回落到确定性的规则回答，宁可少说也不胡说
                answer = str(base.getAnswer()) + "\n\n（复核未通过，已回退为本地检索结果作答）";
                critic.setRepaired(true);
            }
        }
        stages.add(stage("review", "复核 Agent", critic.getPassed()
                ? "通过（" + ("llm".equals(critic.getMode()) ? "模型复核" : "规则复核") + "）"
                : "发现问题：" + StrUtil.join("；", critic.getIssues() == null ? new ArrayList<String>() : critic.getIssues())
                + (Boolean.TRUE.equals(critic.getRepaired()) ? "，已修正重写" : "")));

        // ==================== 组装响应 ====================
        AiChatResponseVO vo = base;
        vo.setMode(online ? "agent" : "offline");
        vo.setIntent(plan.intent);
        vo.setAnswer(answer);
        vo.setStages(stages);
        vo.setAgentModel(online ? aiProperties.getModel() : null);
        vo.setUserLevel(level);
        vo.setUserLevelName(toolService.permissionName(level));
        vo.setCritic(critic);
        vo.setTraceSummary(buildTraceSummary(plan, toolResults, critic, online));
        if (toolSource != null && (vo.getSources() == null || vo.getSources().isEmpty())) {
            List<AiSourceVO> sources = new ArrayList<AiSourceVO>();
            sources.add(toolSource);
            vo.setSources(sources);
        }
        return vo;
    }

    // ==================== 意图理解 ====================

    /** LLM JSON 模式意图分类（DeepSeek 无 embeddings 接口，用「LLM 分类 + 规则兜底」替代） */
    private Plan classifyByLlm(String query) {
        String system = "你是云盘助手 LightDisk 的意图理解模块。只输出 JSON，不要解释。\n"
                + "字段：intent(LOCATE=找文件位置/文件列表, SUMMARIZE=阅读或总结文件内容, EDIT=修改/写入文件内容, QA=其他通用问答)，"
                + "keyword(检索文件用的关键词，可为空)，targetFileName(用户明确点名的文件名，可为空)，"
                + "instruction(EDIT 时的改写要求原文，可为空)，reason(一句话理由)。";
        List<Map<String, String>> messages = llmClient.messages(system,
                "用户问题：" + query, "{\"intent\":\"LOCATE\",\"keyword\":\"\",\"targetFileName\":\"\",\"instruction\":\"\",\"reason\":\"\"}");
        String text = llmClient.chat(messages, true);
        JsonNode node = llmClient.parseJson(text);
        Plan plan = new Plan();
        if (node == null) {
            plan.intent = "QA";
            plan.keyword = "";
            plan.reason = "模型分类失败，回落通用问答";
            return plan;
        }
        String intent = node.path("intent").asText("QA").toUpperCase();
        plan.intent = normalizeIntent(intent);
        plan.keyword = node.path("keyword").asText("");
        plan.targetFileName = node.path("targetFileName").asText("");
        plan.instruction = node.path("instruction").asText("");
        plan.reason = node.path("reason").asText("模型分类");
        // 模型可能编造：关键词必须出现在用户问题里，否则丢弃（官方提示模型会幻觉参数）
        if (StrUtil.isNotBlank(plan.keyword) && !query.contains(plan.keyword.trim())) {
            List<String> tokens = splitTokens(plan.keyword);
            boolean hit = false;
            for (String token : tokens) {
                if (query.contains(token)) { hit = true; break; }
            }
            if (!hit) {
                plan.reason = plan.reason + "（关键词为模型推断，已按原句回退）";
                plan.keyword = "";
            }
        }
        return plan;
    }

    private Plan ruleClassify(String query, AiChatResponseVO base) {
        Plan plan = new Plan();
        plan.intent = StrUtil.isBlank(base.getIntent()) ? "QA" : normalizeIntent(base.getIntent().toUpperCase());
        plan.keyword = "";
        plan.reason = "离线规则分类";
        return plan;
    }

    private String normalizeIntent(String intent) {
        if (intent == null) return "QA";
        if (intent.startsWith("LOCATE") || intent.contains("FILE") || intent.contains("SHARE") || intent.contains("RECYCLE")) {
            return "LOCATE";
        }
        if (intent.contains("SUMMARY") || intent.contains("SUMMARIZE") || intent.contains("EXTRACT")) {
            return "SUMMARIZE";
        }
        if (intent.contains("EDIT") || intent.contains("WRITE")) {
            return "EDIT";
        }
        return "QA";
    }

    // ==================== 目标文件解析 ====================

    private Map<String, Object> resolveTargetFile(Long userId, Plan plan) {
        if (StrUtil.isNotBlank(plan.targetFileName)) {
            List<Map<String, Object>> hit = toolService.searchFiles(userId, plan.targetFileName, 5);
            if (!hit.isEmpty()) return hit.get(0);
        }
        if (StrUtil.isNotBlank(plan.keyword)) {
            List<Map<String, Object>> hit = toolService.searchFiles(userId, plan.keyword, 5);
            if (!hit.isEmpty()) return hit.get(0);
        }
        List<Map<String, Object>> recent = toolService.listRecentFiles(userId, 1);
        return recent.isEmpty() ? null : recent.get(0);
    }

    // ==================== 生成 / 修正 ====================

    private String synthesize(String query, Plan plan, List<Map<String, Object>> toolResults, AiChatResponseVO base) {
        StringBuilder context = new StringBuilder();
        if (toolResults.isEmpty()) {
            context.append("（本次没有工具调用结果）\n");
        }
        for (Map<String, Object> result : toolResults) {
            context.append("- 工具 ").append(result.get("tool")).append("：\n");
            Object items = result.get("items");
            if (items instanceof List) {
                for (Object item : (List<?>) items) {
                    if (item instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) item;
                        context.append("    · ").append(m.get("name")).append("　路径 ").append(m.get("path"))
                                .append("　大小 ").append(m.get("sizeText")).append("　更新 ").append(m.get("updatedAt"))
                                .append("\n");
                    }
                }
            }
            if (result.get("content") != null) {
                context.append("    内容（可能被截断）：").append(StrUtil.sub(String.valueOf(result.get("content")), 0, 4000)).append("\n");
            }
            if (result.get("error") != null) {
                context.append("    失败：").append(result.get("error")).append("\n");
            }
            if (result.get("newMd5") != null) {
                context.append("    写入成功，新内容校验值：").append(result.get("newMd5")).append("\n");
            }
        }

        String system = "你是网盘助手 LightDisk 的回答生成模块。要求：\n"
                + "1) 只依据给出的「工具结果」作答，禁止编造文件名、路径、大小等事实；\n"
                + "2) 工具结果为空就直说没找到，不要假装存在；\n"
                + "3) 用简体中文，简洁分点，必要时给出可点击的下一步建议；\n"
                + "4) 不得泄露与当前用户无关的文件信息。";
        List<Map<String, String>> messages = llmClient.messages(system,
                "用户问题：" + query + "\n意图：" + intentName(plan.intent)
                        + "\n\n工具结果：\n" + context.toString()
                        + "\n本地检索给出的参考回答（可参考但需以工具结果为准）：" + StrUtil.sub(str(base.getAnswer()), 0, 800),
                "好的，我将只依据工具结果作答。");
        return llmClient.chat(messages, false);
    }

    private String generateEditedContent(Plan plan, String fileName, String oldContent) {
        String system = "你在帮用户修改网盘里的文本文件。只输出修改后的【完整文件内容】，不要任何解释、不要 Markdown 代码块包裹。";
        List<Map<String, String>> messages = llmClient.messages(system,
                "文件名：" + fileName + "\n用户的修改要求：" + StrUtil.nullToEmpty(plan.instruction)
                        + "\n\n原文件内容：\n" + StrUtil.sub(oldContent, 0, 6000),
                "收到，我将只输出修改后的完整文件内容。");
        String text = llmClient.chat(messages, false);
        if (StrUtil.isBlank(text)) return null;
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            if (firstLine > 0) cleaned = cleaned.substring(firstLine + 1);
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) cleaned = cleaned.substring(0, fence);
        }
        return cleaned;
    }

    private String repair(String query, String answer, List<Map<String, Object>> toolResults, List<String> issues) {
        StringBuilder context = new StringBuilder();
        for (Map<String, Object> result : toolResults) {
            Object items = result.get("items");
            if (items instanceof List) {
                for (Object item : (List<?>) items) {
                    if (item instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) item;
                        context.append("- ").append(m.get("name")).append("（").append(m.get("path")).append("）\n");
                    }
                }
            }
        }
        String system = "你是修正模块。用户问题与工具事实如下，原回答被复核判定有问题，请严格依据工具事实重写回答，"
                + "不得出现工具结果之外的任何文件名或数据，简体中文，简洁分点。";
        List<Map<String, String>> messages = llmClient.messages(system,
                "用户问题：" + query + "\n工具事实：\n" + context.toString()
                        + "\n原回答：" + StrUtil.sub(answer, 0, 1500)
                        + "\n复核发现的问题：" + StrUtil.join("；", issues),
                "好的，我将严格依据工具事实重写。");
        return llmClient.chat(messages, false);
    }

    // ==================== 复核 Agent ====================

    private AiCriticVO review(String query, String answer, List<Map<String, Object>> toolResults,
                              Plan plan, String deny, boolean online) {
        AiCriticVO critic = new AiCriticVO();
        List<String> issues = new ArrayList<String>();

        // 规则 1：越权回答必须明确说明权限/等级
        if (deny != null) {
            String a = str(answer);
            if (!(a.contains("权限") || a.contains("等级") || a.contains("VIP") || a.contains("升级"))) {
                issues.add("权限不足但回答未说明原因");
            }
        }

        // 规则 2：幻觉检测 —— 回答里出现的文件名必须在工具结果或检索结果中真实存在
        Set<String> knownNames = collectKnownNames(toolResults);
        if (!knownNames.isEmpty()) {
            Matcher matcher = FILE_NAME_PATTERN.matcher(str(answer));
            while (matcher.find()) {
                String mentioned = matcher.group();
                if (isLikelyVersionToken(mentioned)) {
                    continue; // 版本号（如 Spring Boot 2.7、v1.0）不是文件名，跳过
                }
                if (!knownNames.contains(mentioned) && !mentioned.contains("examples") ) {
                    issues.add("回答提到未检索到的文件「" + mentioned + "」，疑似幻觉");
                    break;
                }
            }
        }

        // 规则 3：工具失败不能说成功
        boolean toolFailed = false;
        for (Map<String, Object> result : toolResults) {
            if (Boolean.FALSE.equals(result.get("ok"))) toolFailed = true;
        }
        if (toolFailed && (str(answer).contains("成功") || str(answer).contains("已保存"))) {
            issues.add("工具执行失败但回答声称成功");
        }

        critic.setMode(online ? "llm" : "rule");
        critic.setReviewer(online ? aiProperties.getModel() : null);

        if (online && issues.isEmpty() && deny == null) {
            // 模型复核：让 reviewer 判断回答是否忠实于工具事实
            StringBuilder facts = new StringBuilder();
            for (Map<String, Object> result : toolResults) {
                Object items = result.get("items");
                if (items instanceof List) {
                    for (Object item : (List<?>) items) {
                        if (item instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) item;
                            facts.append("- ").append(m.get("name")).append(" | ").append(m.get("path")).append("\n");
                        }
                    }
                }
                if (result.get("newMd5") != null) {
                    facts.append("- 写入成功 newMd5=").append(result.get("newMd5")).append("\n");
                }
            }
            String system = "你是复核 Agent。判断「回答」是否完全忠于「工具事实」，是否存在编造文件名/路径/大小、"
                    + "夸大工具能力、答非所问。只输出 JSON：{\"passed\":true|false,\"issues\":[\"问题1\"]}";
            List<Map<String, String>> messages = llmClient.messages(system,
                    "用户问题：" + query + "\n工具事实：\n" + (facts.length() == 0 ? "（无工具调用）" : facts.toString())
                            + "\n回答：" + StrUtil.sub(str(answer), 0, 2000),
                    "{\"passed\":true,\"issues\":[]}");
            JsonNode node = llmClient.parseJson(llmClient.chat(messages, true));
            if (node != null && !node.path("passed").asBoolean(true)) {
                JsonNode issueNodes = node.path("issues");
                if (issueNodes.isArray()) {
                    for (JsonNode issue : issueNodes) {
                        issues.add("模型复核：" + issue.asText());
                    }
                }
                if (issues.isEmpty()) {
                    issues.add("模型复核未通过");
                }
            }
        }

        critic.setIssues(issues);
        critic.setPassed(issues.isEmpty());
        critic.setRepaired(false);
        return critic;
    }

    /** 形如 "2.7" / "v1.0.0" 的版本号：点后不是纯字母扩展名的，不算文件名 */
    private boolean isLikelyVersionToken(String token) {
        int dot = token.lastIndexOf('.');
        String ext = dot >= 0 ? token.substring(dot + 1) : "";
        return !ext.matches("[A-Za-z]{2,}");
    }

    private Set<String> collectKnownNames(List<Map<String, Object>> toolResults) {        Set<String> names = new LinkedHashSet<String>();
        for (Map<String, Object> result : toolResults) {
            Object items = result.get("items");
            if (items instanceof List) {
                for (Object item : (List<?>) items) {
                    if (item instanceof Map) {
                        names.add(String.valueOf(((Map<?, ?>) item).get("name")));
                    }
                }
            }
            if (result.get("fileName") != null) {
                names.add(String.valueOf(result.get("fileName")));
            }
            Object fileInfo = result.get("fileInfo");
            if (fileInfo instanceof Map) {
                names.add(String.valueOf(((Map<?, ?>) fileInfo).get("name")));
            }
        }
        return names;
    }

    // ==================== 工具方法 ====================

    private boolean hasFileContent(List<Map<String, Object>> toolResults) {
        for (Map<String, Object> result : toolResults) {
            if (Boolean.TRUE.equals(result.get("readable")) && result.get("content") != null) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> firstReadable(List<Map<String, Object>> toolResults) {
        for (Map<String, Object> result : toolResults) {
            if (Boolean.TRUE.equals(result.get("readable")) && result.get("content") != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 通过标准 MCP 协议调用 Node 工具服务器的 extractive_summary（阶段 4 亮点）。
     * 失败返回 null，由调用方降级为本地摘要。
     */
    private String summarizeViaMcp(Map<String, Object> read) {
        if (read == null || !mcpToolClient.available()) {
            return null;
        }
        try {
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("text", String.valueOf(read.get("content")));
            Object fileName = read.get("fileName");
            args.put("title", fileName == null ? "" : String.valueOf(fileName));
            args.put("maxSentences", 4);
            String text = mcpToolClient.callTool("extractive_summary", args);
            if (StrUtil.isBlank(text)) {
                return null;
            }
            return text + "\n\n（本摘要由 MCP 工具 extractive_summary 生成：只抽取原文句子，不生成新内容，杜绝幻觉。"
                    + "配置 DeepSeek API Key 后可升级为模型语义摘要。）";
        } catch (Exception e) {
            log.warn("[AI] MCP 摘要失败，回落本地实现: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 离线本地摘要：只做「统计 + 抽取」，不生成任何原文没有的内容（避免幻觉）
     */
    private String localSummary(List<Map<String, Object>> toolResults) {
        Map<String, Object> read = null;
        for (Map<String, Object> result : toolResults) {
            if (Boolean.TRUE.equals(result.get("readable")) && result.get("content") != null) {
                read = result;
                break;
            }
        }
        if (read == null) {
            return "没有读取到可总结的文本内容。";
        }
        String name = String.valueOf(read.get("fileName"));
        String content = String.valueOf(read.get("content"));
        String[] lines = content.split("\n", -1);
        int charCount = content.length();
        StringBuilder sb = new StringBuilder();
        sb.append("以下是《").append(name).append("》的本地摘要（离线模式：只做统计与原文抽取，不生成新内容）：\n\n");
        sb.append("1) 规模：").append(charCount).append(" 字符 / ").append(lines.length).append(" 行");
        if (Boolean.TRUE.equals(read.get("truncated"))) {
            sb.append("（原文较长，仅分析了前 ").append(content.length()).append(" 字符）");
        }
        sb.append("\n2) 开头内容：\n");
        int shown = 0;
        for (String line : lines) {
            if (shown >= 8) break;
            if (line.trim().isEmpty()) continue;
            sb.append("   · ").append(StrUtil.sub(line.trim(), 0, 120)).append("\n");
            shown++;
        }
        // 粗粒度词频（中英文按字符/单词切分），帮助快速抓住高频主题
        Map<String, Integer> freq = new LinkedHashMap<String, Integer>();
        for (String token : content.split("[^\\u4e00-\\u9fa5A-Za-z0-9]+")) {
            String t = token.trim();
            if (t.length() < 2) continue;
            Integer old = freq.get(t);
            freq.put(t, old == null ? 1 : old + 1);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(freq.entrySet());
        entries.sort(new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        StringBuilder hot = new StringBuilder();
        for (int i = 0; i < Math.min(8, entries.size()); i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            if (e.getValue() < 2) break;
            if (hot.length() > 0) hot.append("、");
            hot.append(e.getKey()).append("(").append(e.getValue()).append(")");
        }
        if (hot.length() > 0) {
            sb.append("3) 高频词：").append(hot).append("\n");
        }
        sb.append("\n（提示：配置 DeepSeek API Key 后，这里会由大模型输出语义摘要；当前为离线规则模式。）");
        return sb.toString();
    }

    private AiStageVO stage(String key, String label, String detail) {
        AiStageVO vo = new AiStageVO();
        vo.setKey(key);
        vo.setLabel(label);
        vo.setDetail(detail);
        return vo;
    }

    private String intentName(String intent) {
        if ("LOCATE".equals(intent)) return "查文件位置";
        if ("SUMMARIZE".equals(intent)) return "阅读/总结文件";
        if ("EDIT".equals(intent)) return "修改文件内容";
        return "通用问答";
    }

    private String buildTraceSummary(Plan plan, List<Map<String, Object>> toolResults, AiCriticVO critic, boolean online) {
        List<String> tools = new ArrayList<String>();
        for (Map<String, Object> result : toolResults) {
            tools.add(String.valueOf(result.get("tool")));
        }
        return String.format("%s模式 · 意图=%s · 工具=[%s] · 复核=%s",
                online ? "Agent" : "离线",
                intentName(plan.intent),
                tools.isEmpty() ? "无" : StrUtil.join(",", tools),
                Boolean.TRUE.equals(critic.getPassed()) ? "通过" : "已修正");
    }

    private List<String> splitTokens(String text) {
        List<String> tokens = new ArrayList<String>();
        for (String token : text.split("[\\s,，、;；]+")) {
            if (!token.trim().isEmpty()) tokens.add(token.trim());
        }
        return tokens;
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(String value) {
        return value == null ? "" : value;
    }

    /** 意图理解结果 */
    private static class Plan {
        String intent = "QA";
        String keyword = "";
        String targetFileName = "";
        String instruction = "";
        String reason = "";
    }
}
