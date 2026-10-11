# lightdisk-mcp-server

LightDisk AI Agent 的 **标准 MCP（Model Context Protocol）服务器**（阶段 4 亮点）。

- 传输方式：**stdio**（Java 侧用 ProcessBuilder 拉起本进程，走换行分隔的 JSON-RPC 2.0）
- SDK：官方 `@modelcontextprotocol/sdk`（Node 22）
- 理论上任何 MCP 客户端（如 Claude Desktop）都可以直接挂载本 server

## 工具列表

| 工具 | 参数 | 说明 |
|---|---|---|
| `text_stats` | `text` | 文本统计：字符/行数、高频词 TopN |
| `extractive_summary` | `text, maxSentences?` | 抽取式摘要：按句打分选代表句，**只抽取不生成**，杜绝幻觉 |
| `keyword_extract` | `text, topK?` | 关键词抽取（词频 + 长度过滤） |

## 本地运行

```bash
npm install
node server.js          # stdio 模式，等待 MCP 客户端接入
```

## Java 侧对接

`application.yml`：

```yaml
lightdisk:
  ai:
    mcp:
      enabled: true
      command: node
      args: D:/AI_LightDisk/LightDisk/lightdisk-mcp-server/server.js
```

Java 侧 `McpToolClient` 负责：`initialize` → `tools/list` → `tools/call`，
进程随应用关闭自动销毁；MCP 不可用时 AI Agent 自动回落本地实现（离线降级）。
