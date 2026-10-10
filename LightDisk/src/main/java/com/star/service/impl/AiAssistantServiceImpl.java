package com.star.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.star.exception.BusinessException;
import com.star.mapper.FileMapper;
import com.star.mapper.FolderMapper;
import com.star.mapper.ShareMapper;
import com.star.mapper.StorageFileMapper;
import com.star.model.dto.AiChatRequestDTO;
import com.star.model.entity.File;
import com.star.model.entity.Folder;
import com.star.model.entity.Share;
import com.star.model.entity.StorageFile;
import com.star.model.vo.AiCapabilityVO;
import com.star.model.vo.AiChatResponseVO;
import com.star.model.vo.AiSourceVO;
import com.star.model.vo.AiStageVO;
import com.star.service.AiAssistantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DEFAULT_TOP_K = 5;
    private static final int RECYCLE_RETENTION_DAYS = 30;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9._-]+");

    private final FileMapper fileMapper;
    private final FolderMapper folderMapper;
    private final ShareMapper shareMapper;
    private final StorageFileMapper storageFileMapper;

    @Override
    public AiChatResponseVO chat(Long userId, AiChatRequestDTO requestDTO) {
        String query = requestDTO == null ? null : StrUtil.trim(requestDTO.getQuery());
        if (userId == null) {
            throw new BusinessException("登录状态无效，请重新登录");
        }
        if (StrUtil.isBlank(query)) {
            throw new BusinessException("请输入问题后再试");
        }

        IntentType intentType = detectIntent(query);
        int topK = normalizeTopK(requestDTO == null ? null : requestDTO.getTopK());

        if (intentType == IntentType.RAG_KNOWLEDGE) {
            return buildKnowledgeResponse(query);
        }
        if (intentType == IntentType.SHARE_SEARCH) {
            return buildShareResponse(userId, query, topK);
        }
        if (intentType == IntentType.RECYCLE_SEARCH) {
            return buildRecycleResponse(userId, query, topK);
        }
        if (intentType == IntentType.DOC_SUMMARY) {
            return buildSummaryResponse(userId, query, topK);
        }
        if (intentType == IntentType.DATA_EXTRACTION) {
            return buildExtractionResponse(query);
        }
        if (intentType == IntentType.GENERAL_QA) {
            return buildGeneralQaResponse(query);
        }
        return buildFileResponse(userId, query, topK);
    }

    private AiChatResponseVO buildFileResponse(Long userId, String query, int topK) {
        List<String> keywords = extractKeywords(query);
        List<Folder> folders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 0)
                        .orderByDesc(Folder::getCreatedAt)
        );
        Map<Long, Folder> folderMap = folders.stream()
                .collect(Collectors.toMap(Folder::getId, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<File> files = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 1)
                        .orderByDesc(File::getCreatedAt)
        );
        Map<Long, StorageFile> storageMap = loadStorageMap(files);

        List<AiSourceVO> sources = files.stream()
                .map(file -> buildFileSource(file, folderMap.get(file.getFolderId()), storageMap.get(file.getStorageId())))
                .sorted(scoreComparator(keywords))
                .limit(topK)
                .collect(Collectors.toList());

        if (sources.isEmpty() || topScore(sources, keywords) <= 0) {
            return buildEmptyResponse(
                    "file_search",
                    "我没有在你的当前网盘记录里找到明显匹配的文件。",
                    Arrays.asList(
                            "可以换成更具体的文件名，例如“实训报告”或“答辩PPT”",
                            "如果文件在回收站，可以直接问“回收站里有没有 xx”",
                            "如果是分享链接，可以问“我分享过哪些文件”"
                    ),
                    buildStages("query_rewrite", "multi_recall", "rerank", "answer")
            );
        }

        StringBuilder answer = new StringBuilder();
        answer.append("我按“文件名关键词 + 目录路径”帮你检索到了这些结果：");
        for (int i = 0; i < sources.size(); i++) {
            AiSourceVO source = sources.get(i);
            answer.append("<br>").append(i + 1).append(". <b>")
                    .append(escapeHtml(source.getTitle()))
                    .append("</b> 位于 ")
                    .append(escapeHtml(source.getPath()));
            if (StrUtil.isNotBlank(source.getSummary())) {
                answer.append("，").append(escapeHtml(source.getSummary()));
            }
        }
        answer.append("<br><br>这版没有接真实大模型，所以“文档总结”我先退化为“帮你定位文档位置 + 给出最近相关文件”。");

        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("mock-rag");
        response.setIntent("file_search");
        response.setAnswer(answer.toString());
        response.setTraceSummary("Query 改写 -> 文件名检索 -> 路径补全 -> 简单重排 -> 返回来源");
        response.setStages(buildStages("thinking", "query_rewrite", "multi_recall", "rerank", "answer"));
        response.setSources(sources);
        response.setCapabilityCards(buildProjectCapabilityCards());
        response.setFollowUpQuestions(Arrays.asList(
                "最近上传的 PDF 在哪里",
                "我分享过哪些文件",
                "回收站里有哪些快过期文件",
                "为什么 RAG 要做多路召回"
        ));
        return response;
    }

    private AiChatResponseVO buildShareResponse(Long userId, String query, int topK) {
        List<String> keywords = extractKeywords(query);
        List<Share> shares = shareMapper.selectList(
                new LambdaQueryWrapper<Share>()
                        .eq(Share::getUserId, userId)
                        .orderByDesc(Share::getCreatedAt)
        );

        if (shares.isEmpty()) {
            return buildEmptyResponse(
                    "share_search",
                    "你当前还没有分享记录。",
                    Arrays.asList("去网盘中创建一个分享链接后再回来问我", "也可以先问“最近上传的文件在哪里”"),
                    buildStages("thinking", "share_scan", "answer")
            );
        }

        Map<Long, File> fileMap = loadFileMapByIds(
                shares.stream()
                        .filter(item -> item.getItemType() != null && item.getItemType() == 1)
                        .map(Share::getItemId)
                        .collect(Collectors.toSet())
        );

        Map<Long, Folder> folderMap = loadFolderMapByIds(
                shares.stream()
                        .filter(item -> item.getItemType() != null && item.getItemType() == 2)
                        .map(Share::getItemId)
                        .collect(Collectors.toSet())
        );

        List<AiSourceVO> sources = new ArrayList<AiSourceVO>();
        for (Share share : shares) {
            AiSourceVO source = new AiSourceVO();
            source.setSourceType("share");
            source.setSourceId(share.getId());
            source.setTitle(resolveShareTitle(share, fileMap, folderMap));
            source.setPath("/share/" + share.getShareCode());
            source.setSummary(buildShareSummary(share));
            sources.add(source);
        }

        sources = sources.stream()
                .sorted(scoreComparator(keywords))
                .limit(topK)
                .collect(Collectors.toList());

        StringBuilder answer = new StringBuilder("我扫描了你的分享记录，命中这些结果：");
        for (int i = 0; i < sources.size(); i++) {
            AiSourceVO source = sources.get(i);
            answer.append("<br>").append(i + 1).append(". <b>")
                    .append(escapeHtml(source.getTitle()))
                    .append("</b> -> ")
                    .append(escapeHtml(source.getPath()))
                    .append("，")
                    .append(escapeHtml(source.getSummary()));
        }
        answer.append("<br><br>这部分模拟的是 Agent 调用“分享索引”工具，而不是直接让大模型胡猜。");

        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("mock-agent");
        response.setIntent("share_search");
        response.setAnswer(answer.toString());
        response.setTraceSummary("意图识别 -> 分享索引检索 -> 过期状态判断 -> 排序输出");
        response.setStages(buildStages("thinking", "intent", "share_scan", "rerank", "answer"));
        response.setSources(sources);
        response.setCapabilityCards(buildProjectCapabilityCards());
        response.setFollowUpQuestions(Arrays.asList(
                "哪些分享已经过期",
                "最近上传的文件在哪里",
                "回收站里有哪些内容"
        ));
        return response;
    }

    private AiChatResponseVO buildRecycleResponse(Long userId, String query, int topK) {
        List<String> keywords = extractKeywords(query);
        List<File> recycleFiles = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 0)
                        .orderByDesc(File::getDeleteTime)
        );
        List<Folder> recycleFolders = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 1)
                        .orderByDesc(Folder::getDeleteTime)
        );

        Map<Long, Folder> folderMap = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
        ).stream().collect(Collectors.toMap(Folder::getId, item -> item, (left, right) -> left));
        Map<Long, StorageFile> storageMap = loadStorageMap(recycleFiles);

        List<AiSourceVO> sources = new ArrayList<AiSourceVO>();
        for (File file : recycleFiles) {
            AiSourceVO source = buildFileSource(file, folderMap.get(file.getFolderId()), storageMap.get(file.getStorageId()));
            source.setSourceType("recycle-file");
            source.setSummary(buildRecycleSummary(file.getDeleteTime(), source.getSummary()));
            sources.add(source);
        }
        for (Folder folder : recycleFolders) {
            AiSourceVO source = new AiSourceVO();
            source.setSourceType("recycle-folder");
            source.setSourceId(folder.getId());
            source.setTitle(folder.getFolderName());
            source.setPath(buildFolderPath(folder));
            source.setSummary(buildRecycleSummary(folder.getDeleteTime(), "文件夹"));
            sources.add(source);
        }

        sources = sources.stream()
                .sorted(scoreComparator(keywords))
                .limit(topK)
                .collect(Collectors.toList());

        if (sources.isEmpty() || topScore(sources, keywords) <= 0) {
            return buildEmptyResponse(
                    "recycle_search",
                    "回收站里没有找到明显匹配的项目。",
                    Arrays.asList("可以直接问“回收站里有哪些快过期文件”", "也可以换成更具体的文件名"),
                    buildStages("thinking", "recycle_scan", "answer")
            );
        }

        StringBuilder answer = new StringBuilder("我查了你的回收站，结果如下：");
        for (int i = 0; i < sources.size(); i++) {
            AiSourceVO source = sources.get(i);
            answer.append("<br>").append(i + 1).append(". <b>")
                    .append(escapeHtml(source.getTitle()))
                    .append("</b> 位于 ")
                    .append(escapeHtml(source.getPath()))
                    .append("，")
                    .append(escapeHtml(source.getSummary()));
        }
        answer.append("<br><br>这里是真查回收站表状态，不是纯文案假聊。");

        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("mock-rag");
        response.setIntent("recycle_search");
        response.setAnswer(answer.toString());
        response.setTraceSummary("回收站召回 -> 剩余有效期计算 -> 风险排序 -> 返回来源");
        response.setStages(buildStages("thinking", "recycle_scan", "expiry_check", "answer"));
        response.setSources(sources);
        response.setCapabilityCards(buildProjectCapabilityCards());
        response.setFollowUpQuestions(Arrays.asList(
                "哪些文件快过期了",
                "我分享过哪些文件",
                "为什么要做幻觉规避"
        ));
        return response;
    }

    private AiChatResponseVO buildSummaryResponse(Long userId, String query, int topK) {
        List<File> files = fileMapper.selectList(
                new LambdaQueryWrapper<File>()
                        .eq(File::getUserId, userId)
                        .eq(File::getStatus, 1)
                        .orderByDesc(File::getCreatedAt)
        );
        Map<Long, Folder> folderMap = folderMapper.selectList(
                new LambdaQueryWrapper<Folder>()
                        .eq(Folder::getUserId, userId)
                        .eq(Folder::getIsDeleted, 0)
        ).stream().collect(Collectors.toMap(Folder::getId, item -> item, (left, right) -> left));
        Map<Long, StorageFile> storageMap = loadStorageMap(files);

        List<String> keywords = extractKeywords(query);
        List<AiSourceVO> docSources = files.stream()
                .filter(this::isDocFile)
                .map(file -> buildFileSource(file, folderMap.get(file.getFolderId()), storageMap.get(file.getStorageId())))
                .sorted(scoreComparator(keywords))
                .limit(topK)
                .collect(Collectors.toList());

        String answer;
        if (docSources.isEmpty()) {
            answer = "当前没有可用于“文档总结”的文档型文件，所以我先不给你编总结，避免幻觉。你可以先上传 PDF、DOC、TXT 再试。";
        } else {
            StringBuilder builder = new StringBuilder();
            builder.append("这版没有接真实总结模型，所以我把“总结文档”退化为“帮你定位最相关文档”。");
            for (int i = 0; i < docSources.size(); i++) {
                AiSourceVO source = docSources.get(i);
                builder.append("<br>").append(i + 1).append(". <b>")
                        .append(escapeHtml(source.getTitle()))
                        .append("</b> 位于 ")
                        .append(escapeHtml(source.getPath()))
                        .append("，")
                        .append(escapeHtml(source.getSummary()));
            }
            builder.append("<br><br>这样做的好处是演示时不会乱编文档内容，也能把“幻觉规避”讲清楚。");
            answer = builder.toString();
        }

        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("mock-rag");
        response.setIntent("doc_summary");
        response.setAnswer(answer);
        response.setTraceSummary("文档类召回 -> 路径定位 -> 幻觉规避 -> 返回来源");
        response.setStages(buildStages("thinking", "query_rewrite", "doc_recall", "hallucination_guard", "answer"));
        response.setSources(docSources);
        response.setCapabilityCards(buildProjectCapabilityCards());
        response.setFollowUpQuestions(Arrays.asList(
                "最近上传的 PDF 在哪里",
                "RAG 为什么能降低幻觉",
                "文档切割应该怎么做"
        ));
        return response;
    }

    private AiChatResponseVO buildExtractionResponse(String query) {
        String answer = "当前演示版不做真实结构化抽取，我会先给出规则化说明：<br>"
                + "1. 如果后续接 OCR/解析器，可抽取合同金额、日期、姓名、标题。<br>"
                + "2. 现在为了课设演示，我采用“固定字段模板 + 结果缓存”的轻量方案。<br>"
                + "3. 真正接入时，可以把“文档切割 -> 向量召回 -> 结构化字段校验”作为下一阶段。";

        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("mock-agent");
        response.setIntent("data_extraction");
        response.setAnswer(answer);
        response.setTraceSummary("意图识别 -> 规则模板 -> 风险提示 -> 输出");
        response.setStages(buildStages("thinking", "intent", "rule_match", "answer"));
        response.setSources(Collections.<AiSourceVO>emptyList());
        response.setCapabilityCards(buildProjectCapabilityCards());
        response.setFollowUpQuestions(Arrays.asList(
                "什么是多路召回",
                "为什么要做 Query 改写",
                "今日金价"
        ));
        return response;
    }

    private AiChatResponseVO buildGeneralQaResponse(String query) {
        String lowerQuery = query.toLowerCase();
        String answer;
        if (lowerQuery.contains("金价")) {
            answer = "AI thinking...<br>searching cached market snippets...<br>当前演示版不联网，所以我不给出实时金价数字，避免伪造行情。<br>"
                    + "如果你是做答辩展示，可以强调这里走的是“Query 改写 -> 缓存挡板 -> 工具调用降级”的链路。";
        } else {
            answer = "这是一个常识问答演示位。当前版本优先展示 Agent 工作流，而不是追求真实联网结果。<br>"
                    + "如果后续接搜索插件，可以在这里加“搜索 -> 摘要 -> 引用来源”的闭环。";
        }

        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("mock-agent");
        response.setIntent("general_qa");
        response.setAnswer(answer);
        response.setTraceSummary("Query 改写 -> 缓存挡板 -> 工具降级 -> 安全回答");
        response.setStages(buildStages("thinking", "query_rewrite", "cache_lookup", "tool_fallback", "answer"));
        response.setSources(Collections.<AiSourceVO>emptyList());
        response.setCapabilityCards(buildProjectCapabilityCards());
        response.setFollowUpQuestions(Arrays.asList(
                "今日金价",
                "为什么要做幻觉规避",
                "Agent 和 RAG 的区别"
        ));
        return response;
    }

    private AiChatResponseVO buildKnowledgeResponse(String query) {
        List<AiCapabilityVO> capabilityCards = buildKnowledgeCards(query);

        StringBuilder answer = new StringBuilder();
        answer.append("这个问题更适合走“项目知识库”而不是文件检索，我整理成面试可讲版本：");
        for (int i = 0; i < capabilityCards.size(); i++) {
            AiCapabilityVO card = capabilityCards.get(i);
            answer.append("<br><br><b>").append(i + 1).append(". ")
                    .append(escapeHtml(card.getTitle()))
                    .append("</b><br>")
                    .append(escapeHtml(card.getSummary()))
                    .append("<br>")
                    .append(escapeHtml(card.getDetail()));
        }
        answer.append("<br><br>你可以把当前项目表述为：基于网盘业务数据构建轻量 Agent + Mock RAG 演示链路，支持 Query 改写、多路召回、简单重排、来源回显和幻觉规避。");

        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("knowledge-base");
        response.setIntent("rag_knowledge");
        response.setAnswer(answer.toString());
        response.setTraceSummary("知识意图识别 -> 术语卡片召回 -> 面试化改写 -> 输出");
        response.setStages(buildStages("thinking", "intent", "knowledge_recall", "answer"));
        response.setSources(Collections.<AiSourceVO>emptyList());
        response.setCapabilityCards(capabilityCards);
        response.setFollowUpQuestions(Arrays.asList(
                "什么是多路召回",
                "为什么要做 Query 改写",
                "如何规避大模型幻觉",
                "Agent 和 RAG 的区别"
        ));
        return response;
    }

    private AiChatResponseVO buildEmptyResponse(String intent, String answer, List<String> followUps, List<AiStageVO> stages) {
        AiChatResponseVO response = new AiChatResponseVO();
        response.setMode("mock-rag");
        response.setIntent(intent);
        response.setAnswer(answer);
        response.setTraceSummary("低风险返回：未命中时不编造答案");
        response.setStages(stages);
        response.setSources(Collections.<AiSourceVO>emptyList());
        response.setCapabilityCards(buildProjectCapabilityCards());
        response.setFollowUpQuestions(followUps);
        return response;
    }

    private IntentType detectIntent(String query) {
        String lowerQuery = query.toLowerCase();
        if (containsAny(lowerQuery, "rag", "agent", "向量", "召回", "重排", "改写", "幻觉", "切割", "小林coding", "小林 coding")) {
            return IntentType.RAG_KNOWLEDGE;
        }
        if (containsAny(lowerQuery, "分享", "share", "链接", "提取码")) {
            return IntentType.SHARE_SEARCH;
        }
        if (containsAny(lowerQuery, "回收站", "删除", "恢复", "过期")) {
            return IntentType.RECYCLE_SEARCH;
        }
        if (containsAny(lowerQuery, "总结", "摘要", "概括")) {
            return IntentType.DOC_SUMMARY;
        }
        if (containsAny(lowerQuery, "提取", "抽取", "字段", "金额", "数据")) {
            return IntentType.DATA_EXTRACTION;
        }
        if (containsAny(lowerQuery, "翻译", "金价", "天气", "汇率", "常识")) {
            return IntentType.GENERAL_QA;
        }
        return IntentType.FILE_SEARCH;
    }

    private List<AiStageVO> buildStages(String... keys) {
        List<AiStageVO> stages = new ArrayList<AiStageVO>();
        for (String key : keys) {
            AiStageVO stage = new AiStageVO();
            stage.setKey(key);
            stage.setLabel(resolveStageLabel(key));
            stage.setDetail(resolveStageDetail(key));
            stages.add(stage);
        }
        return stages;
    }

    private String resolveStageLabel(String key) {
        if ("thinking".equals(key)) {
            return "AI Thinking";
        }
        if ("intent".equals(key)) {
            return "Intent Routing";
        }
        if ("query_rewrite".equals(key)) {
            return "Query Rewrite";
        }
        if ("multi_recall".equals(key)) {
            return "Multi Recall";
        }
        if ("rerank".equals(key)) {
            return "Rerank";
        }
        if ("answer".equals(key)) {
            return "Answer";
        }
        if ("share_scan".equals(key)) {
            return "Share Search";
        }
        if ("recycle_scan".equals(key)) {
            return "Recycle Search";
        }
        if ("expiry_check".equals(key)) {
            return "Expiry Check";
        }
        if ("doc_recall".equals(key)) {
            return "Doc Recall";
        }
        if ("hallucination_guard".equals(key)) {
            return "Guardrail";
        }
        if ("rule_match".equals(key)) {
            return "Rule Match";
        }
        if ("cache_lookup".equals(key)) {
            return "Cache Lookup";
        }
        if ("tool_fallback".equals(key)) {
            return "Tool Fallback";
        }
        if ("knowledge_recall".equals(key)) {
            return "Knowledge Recall";
        }
        return key;
    }

    private String resolveStageDetail(String key) {
        if ("thinking".equals(key)) {
            return "接收问题并准备执行链路";
        }
        if ("intent".equals(key)) {
            return "识别文件检索、回收站、分享或知识问答";
        }
        if ("query_rewrite".equals(key)) {
            return "把自然语言改写为更适合检索的关键词";
        }
        if ("multi_recall".equals(key)) {
            return "从文件名、目录路径等多路召回候选";
        }
        if ("rerank".equals(key)) {
            return "按关键词命中和时间简单重排";
        }
        if ("answer".equals(key)) {
            return "组织低幻觉答案并回显来源";
        }
        if ("share_scan".equals(key)) {
            return "扫描分享表并判断过期状态";
        }
        if ("recycle_scan".equals(key)) {
            return "扫描回收站项目";
        }
        if ("expiry_check".equals(key)) {
            return "计算剩余有效期";
        }
        if ("doc_recall".equals(key)) {
            return "召回 PDF、DOC、TXT 等文档类文件";
        }
        if ("hallucination_guard".equals(key)) {
            return "缺少真实内容时拒绝乱编";
        }
        if ("rule_match".equals(key)) {
            return "按规则模板输出";
        }
        if ("cache_lookup".equals(key)) {
            return "模拟缓存挡板";
        }
        if ("tool_fallback".equals(key)) {
            return "没有联网工具时降级处理";
        }
        if ("knowledge_recall".equals(key)) {
            return "召回 RAG/Agent 面试知识卡";
        }
        return "";
    }

    private List<AiCapabilityVO> buildProjectCapabilityCards() {
        List<AiCapabilityVO> cards = new ArrayList<AiCapabilityVO>();
        cards.add(buildCapability("RAG 原理", "先检索后生成，降低大模型脱离事实的概率。", "当前项目先用文件名、路径、分享、回收站做轻量检索，后续可替换成向量检索。"));
        cards.add(buildCapability("多路召回", "同时查文件、回收站、分享等多种索引。", "这样比只查一个表更稳，也更符合真实检索系统设计。"));
        cards.add(buildCapability("幻觉规避", "命中不足时不编内容，只返回可验证来源。", "文档总结尚未接真实模型时，退化为文档定位。"));
        cards.add(buildCapability("Agent 感知", "展示 thinking、searching、rerank、answer 执行轨迹。", "即使不接真实大模型，也能完整演示工具链路和调度感。"));
        return cards;
    }

    private List<AiCapabilityVO> buildKnowledgeCards(String query) {
        List<AiCapabilityVO> allCards = new ArrayList<AiCapabilityVO>();
        allCards.add(buildCapability("RAG 原理", "RAG = Retrieval Augmented Generation，本质是“先检索，再让模型基于证据回答”。", "优势是能把回答锚定到私有数据，减少纯参数记忆导致的幻觉。"));
        allCards.add(buildCapability("文档切割", "长文档不能整篇塞给模型，通常要按段落、标题、页码或 token 长度切块。", "切块过大召回噪声高，切块过小上下文断裂，所以常配合 overlap。"));
        allCards.add(buildCapability("向量数据库", "把文本块编码成向量后存入 Milvus、FAISS、pgvector 等，用于语义检索。", "当前项目未接真实向量库，但架构上已把“召回层”和“回答层”分离，后续可平滑替换。"));
        allCards.add(buildCapability("检索优化", "除了向量相似度，还会用元数据过滤、BM25、热度、时间权重做综合排序。", "本项目演示版用了关键词匹配和时间重排，便于答辩时说明可扩展方向。"));
        allCards.add(buildCapability("多路召回", "同时走关键词召回、向量召回、规则索引召回，再做 merge 和 rerank。", "这样能兼顾精确命中和语义泛化，比单路检索更稳。"));
        allCards.add(buildCapability("Query 改写", "把用户自然语言转成更适合检索的表达，例如补充同义词、拆分关键词。", "例如“文档在哪里”可以改写成“查文件名、查目录路径、优先文档类型”。"));
        allCards.add(buildCapability("幻觉规避", "核心思路是回答必须尽量绑定来源，命中不足时宁可拒答也不编。", "当前项目将“总结文档”降级为“定位文档”，就是一种工程化 guardrail。"));
        allCards.add(buildCapability("Agent 与 RAG", "RAG 偏重检索增强，Agent 偏重工具编排和多步执行。", "这个项目的 AI 助手把两者结合：外层是 Agent 的流程感，内层是 Mock RAG 的检索回显。"));

        List<String> keywords = extractKeywords(query);
        List<AiCapabilityVO> matched = allCards.stream()
                .sorted(new Comparator<AiCapabilityVO>() {
                    @Override
                    public int compare(AiCapabilityVO left, AiCapabilityVO right) {
                        return knowledgeScore(right, keywords) - knowledgeScore(left, keywords);
                    }
                })
                .limit(4)
                .collect(Collectors.toList());
        return matched.isEmpty() ? allCards.subList(0, 4) : matched;
    }

    private int knowledgeScore(AiCapabilityVO capabilityVO, List<String> keywords) {
        int score = 0;
        String text = (capabilityVO.getTitle() + " " + capabilityVO.getSummary() + " " + capabilityVO.getDetail()).toLowerCase();
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                score += keyword.length() > 2 ? 5 : 2;
            }
        }
        return score;
    }

    private AiCapabilityVO buildCapability(String title, String summary, String detail) {
        AiCapabilityVO card = new AiCapabilityVO();
        card.setTitle(title);
        card.setSummary(summary);
        card.setDetail(detail);
        return card;
    }

    private Map<Long, StorageFile> loadStorageMap(List<File> files) {
        Set<Long> storageIds = files.stream()
                .map(File::getStorageId)
                .filter(item -> item != null && item > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (storageIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return storageFileMapper.selectBatchIds(storageIds).stream()
                .collect(Collectors.toMap(StorageFile::getId, item -> item, (left, right) -> left));
    }

    private AiSourceVO buildFileSource(File file, Folder folder, StorageFile storageFile) {
        AiSourceVO source = new AiSourceVO();
        source.setSourceType("file");
        source.setSourceId(file.getId());
        source.setTitle(file.getFileName());
        source.setPath(buildFilePath(folder, file.getFileName()));
        source.setSummary(buildFileSummary(file, storageFile));
        return source;
    }

    private String buildFileSummary(File file, StorageFile storageFile) {
        StringBuilder summary = new StringBuilder();
        if (storageFile != null && storageFile.getFileSize() != null) {
            summary.append(formatSize(storageFile.getFileSize()));
        }
        if (file.getCreatedAt() != null) {
            if (summary.length() > 0) {
                summary.append("，");
            }
            summary.append("上传于 ").append(TIME_FORMATTER.format(file.getCreatedAt()));
        }
        return summary.toString();
    }

    private String buildShareSummary(Share share) {
        List<String> parts = new ArrayList<String>();
        if (share.getCreatedAt() != null) {
            parts.add("创建于 " + TIME_FORMATTER.format(toLocalDateTime(share.getCreatedAt())));
        }
        if (share.getExpireTime() != null) {
            parts.add(share.getExpireTime().isBefore(LocalDateTime.now())
                    ? "已过期"
                    : "有效至 " + TIME_FORMATTER.format(share.getExpireTime()));
        }
        if (share.getExtractCode() != null) {
            parts.add("提取码 " + share.getExtractCode());
        }
        return String.join("，", parts);
    }

    private String buildRecycleSummary(LocalDateTime deleteTime, String suffix) {
        List<String> parts = new ArrayList<String>();
        if (StrUtil.isNotBlank(suffix)) {
            parts.add(suffix);
        }
        if (deleteTime != null) {
            parts.add("删除于 " + TIME_FORMATTER.format(deleteTime));
            parts.add("剩余 " + calculateExpireDays(deleteTime) + " 天");
        }
        return String.join("，", parts);
    }

    private int calculateExpireDays(LocalDateTime deleteTime) {
        if (deleteTime == null) {
            return 0;
        }
        LocalDateTime expireTime = deleteTime.plusDays(RECYCLE_RETENTION_DAYS);
        long remain = java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now().toLocalDate(), expireTime.toLocalDate());
        return (int) Math.max(remain, 0);
    }

    private String buildFilePath(Folder folder, String fileName) {
        String folderPath = folder == null ? "/" : buildFolderPath(folder);
        if ("/".equals(folderPath)) {
            return "/" + fileName;
        }
        return folderPath + fileName;
    }

    private String buildFolderPath(Folder folder) {
        if (folder == null || StrUtil.isBlank(folder.getPath())) {
            return "/";
        }
        return folder.getPath();
    }

    private String resolveShareTitle(Share share, Map<Long, File> fileMap, Map<Long, Folder> folderMap) {
        if (share.getItemType() != null && share.getItemType() == 1) {
            File file = fileMap.get(share.getItemId());
            return file == null ? "未知文件" : file.getFileName();
        }
        Folder folder = folderMap.get(share.getItemId());
        return folder == null ? "未知文件夹" : folder.getFolderName();
    }

    private Comparator<AiSourceVO> scoreComparator(final List<String> keywords) {
        return new Comparator<AiSourceVO>() {
            @Override
            public int compare(AiSourceVO left, AiSourceVO right) {
                int rightScore = sourceScore(right, keywords);
                int leftScore = sourceScore(left, keywords);
                if (rightScore != leftScore) {
                    return rightScore - leftScore;
                }
                return StrUtil.nullToDefault(left.getTitle(), "").compareToIgnoreCase(StrUtil.nullToDefault(right.getTitle(), ""));
            }
        };
    }

    private int topScore(List<AiSourceVO> sources, List<String> keywords) {
        if (sources.isEmpty()) {
            return 0;
        }
        return sourceScore(sources.get(0), keywords);
    }

    private int sourceScore(AiSourceVO source, List<String> keywords) {
        int score = 0;
        String text = (StrUtil.nullToDefault(source.getTitle(), "") + " " + StrUtil.nullToDefault(source.getPath(), "") + " " + StrUtil.nullToDefault(source.getSummary(), "")).toLowerCase();
        for (String keyword : keywords) {
            String lowerKeyword = keyword.toLowerCase();
            if (text.contains(lowerKeyword)) {
                score += lowerKeyword.length() > 2 ? 10 : 4;
            }
        }
        if ("file".equals(source.getSourceType()) || "recycle-file".equals(source.getSourceType())) {
            score += 1;
        }
        return score;
    }

    private List<String> extractKeywords(String query) {
        String normalized = StrUtil.blankToDefault(query, "");
        normalized = normalized.replace("帮我找", " ")
                .replace("帮我查", " ")
                .replace("帮我看看", " ")
                .replace("在哪里", " ")
                .replace("在哪", " ")
                .replace("哪个目录", " ")
                .replace("回收站里", " ")
                .replace("分享过", " ")
                .replace("哪些", " ")
                .replace("文件", " ")
                .replace("文档", " ")
                .replace("最近上传的", " ")
                .replace("最近", " ");

        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        Set<String> result = new LinkedHashSet<String>();
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() <= 1) {
                continue;
            }
            if (isStopWord(token)) {
                continue;
            }
            result.add(token);
        }
        if (result.isEmpty() && StrUtil.isNotBlank(query)) {
            result.add(query.trim());
        }
        return new ArrayList<String>(result);
    }

    private Map<Long, File> loadFileMapByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return fileMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(File::getId, item -> item, (left, right) -> left));
    }

    private Map<Long, Folder> loadFolderMapByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return folderMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Folder::getId, item -> item, (left, right) -> left));
    }

    private boolean isStopWord(String token) {
        return Arrays.asList("帮我", "一下", "这个", "那个", "文档", "文件", "哪里", "在哪", "找到", "看看", "最近", "我的", "一下子").contains(token);
    }

    private boolean containsAny(String source, String... keywords) {
        for (String keyword : keywords) {
            if (source.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDocFile(File file) {
        String fileName = StrUtil.blankToDefault(file.getFileName(), "").toLowerCase();
        return fileName.endsWith(".pdf")
                || fileName.endsWith(".doc")
                || fileName.endsWith(".docx")
                || fileName.endsWith(".txt")
                || fileName.endsWith(".md")
                || fileName.endsWith(".ppt")
                || fileName.endsWith(".pptx");
    }

    private LocalDateTime toLocalDateTime(java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, 10);
    }

    private String formatSize(Long size) {
        long value = size == null ? 0L : size;
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024 * 1024) {
            return String.format("%.1f KB", value / 1024.0);
        }
        if (value < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", value / 1024.0 / 1024.0);
        }
        return String.format("%.1f GB", value / 1024.0 / 1024.0 / 1024.0);
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private enum IntentType {
        FILE_SEARCH,
        SHARE_SEARCH,
        RECYCLE_SEARCH,
        DOC_SUMMARY,
        DATA_EXTRACTION,
        GENERAL_QA,
        RAG_KNOWLEDGE
    }
}
