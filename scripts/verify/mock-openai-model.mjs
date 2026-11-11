#!/usr/bin/env node
/**
 * 本地 OpenAI 协议桩（M7/W5/T5.1 证据专用）。
 *
 * 口径：**不是模型评测替身**——它固定返回一条合法的 `out_of_capability` 意图提案，
 * 只用来证明「ask 编排的模型调用确实发生、且被 llm_call_trace 记录」。
 * E-7（LLM 路径评测）必须使用真实模型服务，不得引用本桩的结果。
 *
 * 端点：
 *   POST /v1/chat/completions（或任意以 /chat/completions 结尾的路径）→ SSE 流
 *   GET  /v1/models → 静态模型列表（部分 SDK 启动时会探测）
 *   GET  /__stats   → { calls, lastRequest }
 *
 * 用法：node scripts/verify/mock-openai-model.mjs --port 18080
 */
import { createServer } from "node:http";

const arg = (name, fallback) => {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : fallback;
};

const PORT = Number(arg("--port", "18080"));
const MODEL = arg("--model", "stub-model");
// 合法的「能力边界外」提案：契约要求 terminal=out_of_capability 时必须
// out_of_capability=true **且** reason_code ∈ [OUT_OF_MANIFEST, NO_RULE, OUT_OF_SCOPE]。
// 两者缺一都会触发修复轮（已实测：缺 reason_code → MISSING_REASON_CODE，重试 3 次后拒答）。
const CONTENT = JSON.stringify({
  terminal: "out_of_capability",
  out_of_capability: true,
  reason_code: "NO_RULE",
});

const stats = { calls: 0, lastRequest: null };

const sse = (payload) => `data: ${JSON.stringify(payload)}\n\n`;
const chunk = (delta, finishReason = null) => ({
  id: "chatcmpl-stub",
  object: "chat.completion.chunk",
  created: Math.floor(Date.now() / 1000),
  model: MODEL,
  choices: [{ index: 0, delta, finish_reason: finishReason }],
});

const server = createServer((request, response) => {
  if (request.method === "GET" && request.url.includes("/__stats")) {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify(stats));
    return;
  }
  if (request.method === "GET" && request.url.includes("/models")) {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ object: "list", data: [{ id: MODEL, object: "model" }] }));
    return;
  }
  if (request.method !== "POST" || !request.url.includes("/chat/completions")) {
    response.writeHead(404, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: { message: "not found", type: "invalid_request_error" } }));
    return;
  }

  let body = "";
  request.on("data", (piece) => {
    body += piece;
  });
  request.on("end", () => {
    stats.calls += 1;
    try {
      stats.lastRequest = JSON.parse(body);
    } catch {
      stats.lastRequest = { raw: body.slice(0, 200) };
    }

    response.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    response.write(sse(chunk({ role: "assistant", content: "" })));
    // 分片输出，贴近真实流式形态（也覆盖「token 取末个 usage」的逻辑）
    const half = Math.ceil(CONTENT.length / 2);
    for (const piece of [CONTENT.slice(0, half), CONTENT.slice(half)]) {
      response.write(sse(chunk({ content: piece })));
    }
    response.write(
      sse({
        ...chunk({}, "stop"),
        usage: { prompt_tokens: 128, completion_tokens: 16, total_tokens: 144 },
      }),
    );
    response.write("data: [DONE]\n\n");
    response.end();
  });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`[mock-openai] listening on http://127.0.0.1:${PORT}/v1 model=${MODEL}`);
});
