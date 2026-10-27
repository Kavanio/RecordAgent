import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { startHarness, stopHarness, loadModule } from '../testing/harness.mjs';
let check;
before(async () => {
  await startHarness();
  ({ assertChatStreamResponse: check } = await loadModule('/src/services/chatStreamResponse.ts'));
});
after(stopHarness);

test('Result JSON 的中文校验原因不会误当 SSE 丢失', async () => {
  await assert.rejects(check(new Response(JSON.stringify({ code: 400, message: '当前模型未标注图片识别能力' }),
    { headers: { 'content-type': 'application/json' } })), /图片识别能力/);
});
test('413 和代理 HTML 错误给可操作提示，不显示原始响应', async () => {
  await assert.rejects(check(new Response('private upstream data', { status: 413 })), /12 MiB/);
  await assert.rejects(check(new Response('<secret/>', { status: 502 })), /HTTP 502/);
});
test('正常 SSE 响应继续交给流解析器', async () => {
  await check(new Response('event: done\n\n', { headers: { 'content-type': 'text/event-stream' } }));
});
