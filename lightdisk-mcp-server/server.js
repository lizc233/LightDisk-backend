/**
 * LightDisk MCP Server（stdio 传输）
 *
 * 标准 Model Context Protocol 服务器：任何 MCP 客户端（Java Agent / Claude Desktop 等）
 * 都可以通过 initialize → tools/list → tools/call 调用这里的工具。
 *
 * 设计原则：
 *  - 工具只做「确定性计算」（统计/抽取），不生成内容，从根上避免幻觉；
 *  - 不直接触碰文件系统/数据库，敏感数据一律由调用方（Java 侧已鉴权）作为参数传入。
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const server = new McpServer({
  name: "lightdisk-tools",
  version: "1.0.0",
});

/** 分词：中文按 2-gram 兜底 + 英数按单词，长度>=2 才计入 */
function tokenize(text) {
  const tokens = [];
  const latinWords = String(text).match(/[A-Za-z0-9_]{2,}/g) || [];
  tokens.push(...latinWords.map((w) => w.toLowerCase()));
  const cjkRuns = String(text).match(/[\u4e00-\u9fa5]+/g) || [];
  for (const run of cjkRuns) {
    if (run.length <= 3) {
      tokens.push(run);
      continue;
    }
    // 长中文串做 2-gram，保留覆盖率与频次意义
    for (let i = 0; i + 2 <= run.length; i++) {
      tokens.push(run.slice(i, i + 2));
    }
  }
  return tokens;
}

function topTokens(text, topK) {
  const freq = new Map();
  for (const t of tokenize(text)) {
    freq.set(t, (freq.get(t) || 0) + 1);
  }
  return [...freq.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, topK)
    .map(([word, count]) => ({ word, count }));
}

/** 按句切分（中英文句号/问叹号/换行） */
function splitSentences(text) {
  return String(text)
    .split(/(?<=[。！？!?；;\n])/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

// ---------- 工具 1：text_stats ----------
server.tool(
  "text_stats",
  "统计文本规模：字符数/行数/句子数/高频词 TopN（确定性计算，无模型参与）",
  { text: z.string().describe("要统计的文本内容"), topK: z.number().int().min(1).max(50).default(10).describe("高频词个数") },
  async ({ text, topK }) => {
    const lines = String(text).split(/\n/).length;
    const sentences = splitSentences(text).length;
    const top = topTokens(text, topK);
    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(
            {
              chars: text.length,
              lines,
              sentences,
              topWords: top,
            },
            null,
            2
          ),
        },
      ],
    };
  }
);

// ---------- 工具 2：extractive_summary ----------
server.tool(
  "extractive_summary",
  "抽取式摘要：给句子打分并挑出代表性原句（只抽取原文，绝不生成新内容）",
  {
    text: z.string().describe("原文"),
    title: z.string().default("").describe("文件名等标题，可选，用于提示首句"),
    maxSentences: z.number().int().min(1).max(10).default(3).describe("摘要句数"),
  },
  async ({ text, title, maxSentences }) => {
    const sentences = splitSentences(text);
    if (sentences.length === 0) {
      return { content: [{ type: "text", text: "（原文没有可摘要的句子）" }] };
    }
    const top = topTokens(text, 30);
    const weight = new Map(top.map((t, i) => [t.word, top.length - i]));
    const scored = sentences.map((s, idx) => {
      let score = 0;
      for (const t of tokenize(s)) score += weight.get(t) || 0;
      // 位置先验：开头句更重要
      const positionBonus = idx === 0 ? 1.5 : idx < 3 ? 1.2 : 1.0;
      const norm = score * positionBonus / Math.sqrt(s.length + 8);
      return { idx, s, score: norm };
    });
    const picked = scored
      .sort((a, b) => b.score - a.score)
      .slice(0, maxSentences)
      .sort((a, b) => a.idx - b.idx);
    const head = title ? `《${title}》摘要（MCP 抽取式，只取原文句子）：\n` : "摘要（MCP 抽取式，只取原文句子）：\n";
    return {
      content: [
        {
          type: "text",
          text: head + picked.map((p) => `· ${p.s}`).join("\n"),
        },
      ],
    };
  }
);

// ---------- 工具 3：keyword_extract ----------
server.tool(
  "keyword_extract",
  "关键词抽取：词频排序输出 TopK 关键词",
  { text: z.string().describe("原文"), topK: z.number().int().min(1).max(30).default(8).describe("关键词个数") },
  async ({ text, topK }) => {
    const top = topTokens(text, topK);
    return {
      content: [
        { type: "text", text: top.map((t) => `${t.word}(${t.count})`).join("、") || "（没有有效关键词）" },
      ],
    };
  }
);

// ---------- 启动 ----------
const transport = new StdioServerTransport();
await server.connect(transport);
// 不要往 stdout 打任何日志（会污染 JSON-RPC 通道），调试信息走 stderr
console.error("[lightdisk-mcp-server] started on stdio");
