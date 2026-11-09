// 临时调试：观察 SSE 订阅的原始输出格式
const response = await fetch("http://127.0.0.1:8081/api/v1/approvals/hitl-events", {
  headers: { "X-Ontos-Tenant": "demo", "X-Ontos-User": "dbg-sse",
    "X-Ontos-Session": "s", "X-Ontos-Turn": "1" },
});
const reader = response.body.getReader();
const decoder = new TextDecoder();
let out = "";
const timer = setTimeout(() => { console.log(JSON.stringify(out)); process.exit(0); }, 2500);
try {
  while (out.length < 600) {
    const { done, value } = await reader.read();
    if (done) break;
    out += decoder.decode(value, { stream: true });
  }
} catch { /* timeout */ }
clearTimeout(timer);
console.log(JSON.stringify(out));
process.exit(0);
