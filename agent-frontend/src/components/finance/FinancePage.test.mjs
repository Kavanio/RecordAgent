import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

// 运行真实组件，以可控响应覆盖失败和竞态；不会读写用户财务数据。
let server, dom, React, createRoot, FinancePage, MessageProvider, gsap;
let root, container, respond, reducedMotion = true;
const mediaQueries = [];
const originalFetch = globalThis.fetch;
const response = (data) => ({ ok: true, json: async () => ({ code: 200, data }) });
const overview = {
  totalAssets: 12345, monthIncome: 6000, monthExpense: 1200, monthYield: 15,
  accounts: [{ id: 'wallet', name: '日常账户', accountType: 'wechat_balance', currency: 'CNY', balance: 12345, interestEnabled: false, annualRatePercent: 0, lastAccrualDate: '2026-09-25', version: 1 }],
  transactions: [],
};
const chart = (range = 'month', totalExpense = 1200) => ({
  range, from: '2026-09-01', to: '2026-09-25', totalExpense, totalIncome: 6000,
  days: [{ date: '2026-09-01', total: totalExpense, income: 6000, categories: [{ category: '餐饮', amount: totalExpense }] }],
});
const insights = (range = 'month') => ({ range, from: '2026-09-01', to: '2026-09-25', totalExpense: 1200,
  accounts: [{ accountId: 'wallet', accountName: '日常账户', amount: 1200 }],
  transactions: [{ id: 'meal', note: '午餐', category: '餐饮', date: '2026-09-25', amount: 1200, source: 'finance' }],
  categories: [{ category: '餐饮', amount: 1200 }] });
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'SVGElement', 'Event', 'MouseEvent', 'FormData']) globalThis[key] = dom.window[key];
  globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
  globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window);
  globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window);
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  Object.defineProperty(dom.window.HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 800 });
  globalThis.ResizeObserver = class { observe() {} disconnect() {} };
  dom.window.matchMedia = (query) => {
    const listeners = new Set();
    const result = { media: query, matches: !reducedMotion, addListener: (fn) => listeners.add(fn), removeListener: (fn) => listeners.delete(fn),
      addEventListener: (_, fn) => listeners.add(fn), removeEventListener: (_, fn) => listeners.delete(fn),
      notify() { result.matches = !reducedMotion; listeners.forEach((fn) => fn(result)); } };
    mediaQueries.push(result);
    return result;
  };
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  ({ gsap } = await import('gsap'));
  server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
  ({ FinancePage } = await server.ssrLoadModule('/src/components/finance/FinancePage.tsx'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
});
after(async () => {
  gsap?.ticker.sleep();
  await server?.close();
  dom?.window.close();
  globalThis.fetch = originalFetch;
});
beforeEach(() => {
  reducedMotion = true;
  respond = (url) => {
    if (url.pathname.endsWith('/overview')) return response(overview);
    if (url.pathname.endsWith('/categories')) return response({ expense: [], income: [] });
    if (url.pathname.endsWith('/expense-chart')) return response(chart(url.searchParams.get('range')));
    if (url.pathname.endsWith('/expense-insights')) return response(insights(url.searchParams.get('range')));
    throw new Error(`未预期请求：${url}`);
  };
  globalThis.fetch = async (url, init) => respond(new URL(String(url)), init);
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
});
afterEach(async () => {
  await React.act(async () => root.unmount());
  container.remove();
});
async function render() {
  await React.act(async () => root.render(React.createElement(React.StrictMode, null,
    React.createElement(MessageProvider, null, React.createElement(FinancePage)))));
}
async function click(name) {
  const button = [...document.querySelectorAll('button')].find((item) => (item.getAttribute('aria-label') || item.textContent) === name);
  assert.ok(button, `找不到按钮：${name}`);
  await React.act(async () => button.click());
}
const summary = () => container.querySelector('[aria-label="本月财务概览"]');
const trend = () => [...container.querySelectorAll('section')].find((item) => item.querySelector('h2')?.textContent === '收支趋势');
const totals = () => [...trend().querySelectorAll('dd')].map((item) => item.textContent);
const analysis = () => container.querySelector('section[aria-label="支出分析"]');
async function selectCategory(value) {
  const select = [...document.querySelectorAll('select')].find((item) => [...item.options].some((option) => option.value === '__custom_category__'));
  await React.act(async () => {
    select.value = value;
    select.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
  });
}

test('首次概览失败不伪造零资产，图表仍独立可用，重试后恢复', async () => {
  const normal = respond;
  respond = (url) => { if (url.pathname.endsWith('/overview')) throw new Error('暂时无法连接'); return normal(url); };
  await render();
  assert.match(summary().textContent, /财务概览加载失败/);
  assert.doesNotMatch(summary().textContent, /0\.00|0 个/);
  assert.doesNotMatch(container.textContent, /还没有流水记录|暂无账户/);
  assert.match(totals()[0], /1,200\.00/);
  respond = normal;
  await click('重新加载概览');
  assert.match(summary().textContent, /12,345\.00/);
  assert.doesNotMatch(summary().textContent, /加载失败/);
});

test('历史分类挂起和失败都不阻塞概览，表单允许使用默认分类并重试', async () => {
  const normal = respond, categories = deferred();
  respond = (url) => url.pathname.endsWith('/categories') ? categories.promise : normal(url);
  await render();
  assert.match(summary().textContent, /12,345\.00/);
  await React.act(async () => categories.reject(new Error('分类服务不可用')));
  await click('记一笔');
  assert.match(document.body.textContent, /仍可使用默认分类/);
  assert.ok(document.querySelector('input[name="amount"]'));
  respond = normal;
  await click('重试分类');
  assert.doesNotMatch(document.body.textContent, /历史分类加载失败/);
});

test('刷新失败保留上次资产，并明确标记数据尚未更新', async () => {
  await render();
  const normal = respond;
  respond = (url) => { if (url.pathname.endsWith('/overview')) throw new Error('刷新失败'); return normal(url); };
  await click('刷新财务数据');
  assert.match(summary().textContent, /以下为上次读取的数据/);
  assert.match(summary().textContent, /12,345\.00/);
});

test('切换区间立即隐藏旧金额和图形，失败后就地重试展示新数据', async () => {
  await render();
  const normal = respond, week = deferred();
  respond = (url) => url.searchParams.get('range') === 'week' ? week.promise : normal(url);
  await click('本周');
  assert.deepEqual(totals(), ['—', '—', '—']);
  assert.equal(trend().querySelectorAll('[data-finance-bar-reveal]').length, 0);
  await React.act(async () => week.reject(new Error('趋势请求失败')));
  assert.deepEqual(totals(), ['—', '—', '—']);
  assert.match(trend().textContent, /收支趋势加载失败/);
  respond = (url) => url.searchParams.get('range') === 'week' ? response(chart('week', 80)) : normal(url);
  await click('重新加载趋势');
  assert.match(totals()[0], /80\.00/);
});

test('快速从本周切换到今年，迟到的本周响应不能覆盖今年', async () => {
  await render();
  const normal = respond, week = deferred(), year = deferred();
  respond = (url) => url.searchParams.get('range') === 'week' ? week.promise : url.searchParams.get('range') === 'year' ? year.promise : normal(url);
  await click('本周');
  await click('今年');
  await React.act(async () => year.resolve(response(chart('year', 2400))));
  await React.act(async () => week.resolve(response(chart('week', 80))));
  assert.match(totals()[0], /2,400\.00/);
  assert.equal([...trend().querySelectorAll('button')].find((b) => b.textContent === '今年').getAttribute('aria-pressed'), 'true');
});

test('减少动态效果时图表直接显示终态，键盘可读取明细', async () => {
  await render();
  const bar = trend().querySelector('[data-finance-bar-reveal]');
  assert.equal(Number(bar.getAttribute('height')), Number(bar.dataset.financeBarHeight));
  assert.equal(gsap.globalTimeline.getChildren().length, 0);
  const svg = trend().querySelector('svg[aria-label="收支趋势图"]');
  await React.act(async () => svg.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Home', bubbles: true })));
  assert.match(trend().querySelector('[aria-atomic="true"]').textContent, /2026年9月1日/);
});

test('动画入场完成后恢复真实几何，卸载会清理动画', async () => {
  reducedMotion = false;
  await render();
  const animations = gsap.globalTimeline.getChildren();
  assert.ok(animations.length > 0);
  const bar = trend().querySelector('[data-finance-bar-reveal]');
  animations.forEach((animation) => animation.progress(1));
  assert.ok(Math.abs(Number(bar.getAttribute('height')) - Number(bar.dataset.financeBarHeight)) < .001);
  await React.act(async () => root.render(null));
  assert.equal(gsap.globalTimeline.getChildren().length, 0);
});

test('动画中开启减少动态效果，会立即还原完整图表', async () => {
  reducedMotion = false;
  await render();
  const bar = trend().querySelector('[data-finance-bar-reveal]');
  reducedMotion = true;
  await React.act(async () => mediaQueries.forEach((query) => query.notify()));
  assert.equal(Number(bar.getAttribute('height')), Number(bar.dataset.financeBarHeight));
  assert.equal(gsap.globalTimeline.getChildren().length, 0);
});

test('保存成功但概览刷新失败时关闭表单，保留成功反馈和刷新错误，不被分类刷新阻塞', async () => {
  await render();
  await click('记一笔');
  const normal = respond, categories = deferred();
  let writes = 0;
  respond = (url, init) => {
    if (init?.method === 'POST') { writes += 1; return response({}); }
    if (url.pathname.endsWith('/overview')) throw new Error('概览刷新失败');
    if (url.pathname.endsWith('/categories')) return categories.promise;
    return normal(url);
  };
  const form = document.querySelector('form');
  form.querySelector('[name="amount"]').value = '25';
  form.querySelector('[name="note"]').value = '午餐';
  await React.act(async () => form.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  assert.equal(writes, 1);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.match(document.body.textContent, /流水已保存/);
  assert.match(summary().textContent, /以下为上次读取的数据/);
});

test('分类颜色不随区间内分类增减变化，图例与柱形采用相同配色', async () => {
  await render();
  const originalColor = trend().querySelector('[data-finance-category="餐饮"]').getAttribute('fill');
  const normal = respond;
  respond = (url) => {
    if (url.searchParams.get('range') === 'week') {
      const nextChart = chart('week');
      nextChart.days[0].categories = [{ category: '交通', amount: 200 }, { category: '餐饮', amount: 1000 }];
      return response(nextChart);
    }
    return normal(url);
  };
  await click('本周');
  assert.equal(trend().querySelector('[data-finance-category="餐饮"]').getAttribute('fill'), originalColor);
  const legend = [...trend().querySelectorAll('[aria-label="支出分类图例"] > span')].find((item) => item.textContent === '餐饮');
  assert.equal(legend.querySelector('circle').getAttribute('fill'), originalColor);
});

test('分类请求失败时图表仍直接使用同次响应中的已保存颜色', async () => {
  const normal = respond;
  respond = (url) => {
    if (url.pathname.endsWith('/categories')) throw new Error('分类暂时不可用');
    if (url.pathname.endsWith('/expense-chart')) return response({ ...chart(), categoryColors: { 餐饮: '#A855F7' } });
    return normal(url);
  };
  await render();
  assert.equal(trend().querySelector('[data-finance-category="餐饮"]').getAttribute('fill'), '#A855F7');
});

test('账户支出柱图与单笔、分类排行可切换，切换期间时隐藏旧数据', async () => {
  await render();
  assert.match(analysis().textContent, /日常账户/);
  assert.match(analysis().textContent, /午餐/);
  await click('支出分类');
  assert.doesNotMatch(analysis().textContent, /午餐/);
  assert.match(analysis().textContent, /餐饮/);
  const normal = respond, week = deferred();
  respond = (url) => url.pathname.endsWith('/expense-insights') && url.searchParams.get('range') === 'week' ? week.promise : normal(url);
  await click('分析本周');
  assert.doesNotMatch(analysis().textContent, /1,200\.00/);
  await React.act(async () => week.resolve(response(insights('week'))));
  assert.match(analysis().textContent, /1,200\.00/);
});

test('新增自定义分类选色随记账提交，重新打开页面后读取保存的颜色', async () => {
  const normal = respond;
  let saved;
  respond = (url, init) => {
    if (init?.method === 'POST') { saved = JSON.parse(init.body); return response({}); }
    if (saved && url.pathname.endsWith('/categories')) return response({ expense: ['宠物'], income: [], colors: { expense: { 宠物: saved.categoryColor } } });
    if (saved && url.pathname.endsWith('/expense-chart')) {
      const nextChart = chart(url.searchParams.get('range'));
      nextChart.days[0].categories = [{ category: saved.category, amount: nextChart.totalExpense }];
      return response(nextChart);
    }
    return normal(url);
  };
  await render();
  await click('记一笔');
  await selectCategory('__custom_category__');
  const name = document.querySelector('input[aria-label="自定义分类"]');
  await React.act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(name, '宠物');
    name.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
  });
  await click('使用此分类');
  await click('分类颜色：青蓝');
  const colorInput = document.querySelector('input[aria-label="自定义分类颜色"]');
  await React.act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(colorInput, '#24abef');
    colorInput.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
  });
  assert.equal(saved, undefined, '选色本身不应写入后端');
  const form = document.querySelector('form');
  form.querySelector('[name="amount"]').value = '25';
  form.querySelector('[name="note"]').value = '猫粮';
  await React.act(async () => form.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  assert.equal(saved.category, '宠物');
  assert.equal(saved.categoryColor, '#24ABEF');
  await React.act(async () => root.render(null));
  await render();
  assert.equal(trend().querySelector('[data-finance-category="宠物"]').getAttribute('fill'), '#24ABEF');
  await click('记一笔');
  await selectCategory('宠物');
  assert.equal(document.querySelector('input[aria-label="自定义分类颜色"]').value.toUpperCase(), '#24ABEF');
});

test('取消选色不写入，未修改颜色的记账不会覆盖后端配色', async () => {
  const normal = respond;
  let saved;
  respond = (url, init) => {
    if (init?.method === 'POST') { saved = JSON.parse(init.body); return response({}); }
    if (url.pathname.endsWith('/categories')) return response({ expense: ['餐饮'], income: [], colors: { expense: { 餐饮: '#A855F7' } } });
    return normal(url);
  };
  await render();
  await click('记一笔');
  await click('分类颜色：天蓝');
  await click('取消');
  assert.equal(saved, undefined);
  await click('记一笔');
  assert.equal(document.querySelector('input[type="color"]').value.toUpperCase(), '#A855F7');
  const form = document.querySelector('form');
  form.querySelector('[name="amount"]').value = '10';
  form.querySelector('[name="note"]').value = '早餐';
  await React.act(async () => form.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  assert.equal(Object.hasOwn(saved, 'categoryColor'), false);
});
