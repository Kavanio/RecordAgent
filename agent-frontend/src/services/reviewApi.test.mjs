import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { createServer } from 'vite';

let server, api, calls;
const originalFetch = globalThis.fetch;
before(async () => {
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  api = await server.ssrLoadModule('/src/services/reviewApi.ts');
});
after(async () => { globalThis.fetch = originalFetch; await server.close(); });
beforeEach(() => {
  calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    return Response.json({ code: 200, data: { enabled: false, authorized: false, generationAvailable: false } });
  };
});
afterEach(() => { globalThis.fetch = originalFetch; });

test('读取设置仅调用一次GET，不启动授权、采集或模型', async () => {
  const settings = await api.fetchReviewSettings();
  assert.equal(settings.generationAvailable, false);
  assert.equal(calls.length, 1);
  assert.ok(calls[0].url.endsWith('/agent/reviews/settings'));
  assert.equal(calls[0].init.method, 'GET');
  assert.equal(calls[0].init.body, undefined);
});

test('明确确认按原值发送，保存配置不会隐式授予权限', async () => {
  const input = { expectedRevision: 0, enabled: false, domains: ['STUDY'], periods: ['DAILY_REPORT'], historyDays: 30, dailyLimit: 1, providerId: 'p', modelName: 'm' };
  await api.saveReviewSettings(input);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].init.method, 'PUT');
  assert.deepEqual(JSON.parse(calls[0].init.body), input);
  await api.grantReviewConsent(1, 'report-review-v1', false);
  assert.equal(JSON.parse(calls[1].init.body).confirmed, false);
});

test('标识符安全编码，不允许ID改变接口路径', async () => {
  await api.revokeReviewConsent('id/with?query', 2);
  assert.ok(calls[0].url.endsWith('/grants/id%2Fwith%3Fquery/revoke'));
  assert.deepEqual(JSON.parse(calls[0].init.body), { expectedRevision: 2 });
});

test('HTTP成功但业务失败时显示中文错误，不重试写请求', async () => {
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return Response.json({ code: 400, message: '授权已撤销', data: null });
  };
  await assert.rejects(api.captureReviewFacts('2026-09-01', '2026-09-01', 'Asia/Shanghai'), /授权已撤销/);
  assert.equal(calls.length, 1);
});

test('非JSON代理错误和网络超时不会泄漏供应商正文', async () => {
  globalThis.fetch = async () => new Response('PRIVATE_PROXY_ERROR', { status: 502 });
  await assert.rejects(api.fetchReviewSettings(), /返回了无效响应/);
  globalThis.fetch = async () => { throw Object.assign(new Error('private'), { name: 'TimeoutError' }); };
  await assert.rejects(api.fetchReviewSettings(), /请求超时/);
});

test('64位事实值保持字符串，清除操作保留明确确认与版本', async () => {
  globalThis.fetch = async () => Response.json({ code: 200, data: { facts: [{ value: '9223372036854775807' }] } });
  const result = await api.fetchReviewFact('snapshot');
  assert.equal(result.facts[0].value, '9223372036854775807');
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return Response.json({ code: 200, data: { deletedSnapshots: 1, deletedGoals: 2, settingsRevision: 3 } });
  };
  await api.eraseReviewData(2, true);
  assert.deepEqual(JSON.parse(calls[0].init.body), { expectedSettingsRevision: 2, confirmed: true });
});
