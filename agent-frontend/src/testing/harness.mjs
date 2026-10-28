/**
 * 前端测试公共引导（authoritative test harness）。
 *
 * 本文件是 `agent-frontend` 下所有测试的唯一引导入口，职责边界明确：
 * 只提供 jsdom 环境、Vite 模块加载、React 挂载与断言辅助，不包含任何业务数据、
 * 业务断言或组件特化逻辑。测试文件必须复用本模块，禁止再手写 `new JSDOM()`、
 * `createServer()`、`createRoot()` 引导样板。
 *
 * 加载方式统一走 Vite `ssrLoadModule`：与生产构建使用同一套别名（`@/`）、
 * 无扩展名导入和 TypeScript/JSX 转换，避免 Node 原生 ESM 解析器因缺少
 * 扩展名而误报模块缺失。
 */

import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

/** jsdom 需要暴露到全局的浏览器能力；缺失会让组件在 Node 中直接崩溃。 */
const DEFAULT_GLOBALS = [
  'window',
  'document',
  'navigator',
  'location',
  'history',
  'HTMLElement',
  'HTMLInputElement',
  'HTMLTextAreaElement',
  'HTMLFormElement',
  'HTMLButtonElement',
  'Element',
  'Node',
  'Event',
  'CustomEvent',
  'MouseEvent',
  'KeyboardEvent',
  'FocusEvent',
  'File',
  'FileList',
  'Blob',
  'FormData',
  'DOMRect',
  'getComputedStyle',
  'requestAnimationFrame',
  'cancelAnimationFrame',
  'localStorage',
  'sessionStorage',
];

let dom = null;
let server = null;
let react = null;
let createRoot = null;
let originalFetch = null;
/** 被覆盖的全局描述符，用于恢复 Node 自身的只读全局（如 `navigator`）。 */
const originalGlobals = new Map();

/**
 * 启动一次测试环境。每个测试文件在 `before` 中调用一次。
 *
 * @param {object} [options]
 * @param {string} [options.url] jsdom 文档地址，默认 `http://localhost/`。
 * @param {string[]} [options.globals] 额外需要暴露到全局的 jsdom 属性名。
 * @param {boolean} [options.pretendToBeVisual] 是否启用 jsdom 的定时与动画能力，默认开启。
 */
export async function startHarness({ url = 'http://localhost/', globals = [], pretendToBeVisual = true } = {}) {
  if (dom) throw new Error('测试环境已启动，禁止重复初始化');

  dom = new JSDOM('<!doctype html><html><body></body></html>', { url, pretendToBeVisual });
  installBrowserStubs(dom.window);

  for (const key of [...DEFAULT_GLOBALS, ...globals]) {
    // Node 24 中 `navigator` 等全局是只读访问器，必须用 defineProperty 覆盖。
    if (!originalGlobals.has(key)) {
      originalGlobals.set(key, Object.getOwnPropertyDescriptor(globalThis, key) ?? null);
    }
    const value = dom.window[key];
    Object.defineProperty(globalThis, key, {
      configurable: true,
      writable: true,
      // jsdom 的这几个方法内部依赖 `this`，直接赋值会丢掉绑定。
      value: typeof value === 'function' ? value.bind(dom.window) : value,
    });
  }
  // React 19 要求显式声明 act 环境，否则挂载不会同步刷新副作用。
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;

  react = await import('react');
  ({ createRoot } = await import('react-dom/client'));

  server = await createServer({
    server: { middlewareMode: true, hmr: false, ws: false },
    appType: 'custom',
  });

  originalFetch = globalThis.fetch;
  return { react, window: dom.window, document: dom.window.document };
}

/**
 * 补齐 jsdom 未实现的浏览器能力。测试需要自定义行为时，可在用例中覆盖同名方法。
 *
 * @param {object} window jsdom 的 window 对象。
 */
function installBrowserStubs(window) {
  if (typeof window.matchMedia !== 'function') {
    window.matchMedia = (query) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener() {},
      removeEventListener() {},
      addListener() {},
      removeListener() {},
      dispatchEvent: () => false,
    });
  }
  if (typeof window.ResizeObserver !== 'function') {
    window.ResizeObserver = class ResizeObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  if (typeof window.IntersectionObserver !== 'function') {
    window.IntersectionObserver = class IntersectionObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords() { return []; }
    };
  }
  if (typeof window.scrollTo !== 'function') window.scrollTo = () => {};
}

/**
 * 通过 Vite 与生产一致的模块图加载一个源文件。
 *
 * @param {string} path 以 `/src/` 开头的源文件路径。
 * @returns {Promise<Record<string, unknown>>} 该模块的导出集合。
 */
export async function loadModule(path) {
  if (!server) throw new Error('请先调用 startHarness()');
  return server.ssrLoadModule(path);
}

/**
 * 丢弃已加载的模块图缓存，让下一次 `loadModule` 重新执行模块顶层代码。
 *
 * 仅在被测模块持有模块级可变状态（例如偏好缓存、单例注册表）时使用，
 * 用于保证用例互不污染且与执行顺序无关。
 */
export function resetModules() {
  if (!server) throw new Error('请先调用 startHarness()');
  server.moduleGraph.invalidateAll();
}

/** React 的 `act`，用于包裹必须同步刷新状态的操作。 */
export function act(callback) {
  if (!react) throw new Error('请先调用 startHarness()');
  return react.act(callback);
}

/** 已加载的 React 命名空间。 */
export function getReact() {
  if (!react) throw new Error('请先调用 startHarness()');
  return react;
}

/**
 * 把元素挂载进文档并返回操作句柄；结束时必须 `await unmount()`。
 *
 * @param {unknown} [element] 初始 React 元素；省略时先建空容器，再由 `handle.render` 注入。
 */
export async function mount(element = null) {
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);

  const handle = {
    container,
    /** 用新的元素替换整棵树。 */
    async render(next) {
      await act(async () => root.render(next));
    },
    /** 卸载并移除容器，恢复干净文档。 */
    async unmount() {
      await act(async () => root.unmount());
      container.remove();
    },
    /** 单元素查询，找不到即为空。 */
    query(selector) {
      return container.querySelector(selector);
    },
    /** 多元素查询，始终返回数组。 */
    queryAll(selector) {
      return [...container.querySelectorAll(selector)];
    },
    /** 容器内全部文本，便于断言可见文案。 */
    text() {
      return container.textContent ?? '';
    },
    /**
     * 按可见文案点击按钮。
     *
     * @param {string} label 按钮文本（完全匹配）。
     */
    async click(label) {
      const button = [...container.querySelectorAll('button')].find((item) => item.textContent === label);
      if (!button) throw new Error(`找不到按钮：${label}`);
      await act(async () => button.click());
    },
  };

  await handle.render(element);
  return handle;
}

/**
 * 创建可控 fetch 桩。测试通过 `respond` 动态切换响应，不访问真实网络。
 *
 * @param {(url: URL, init?: RequestInit) => Promise<unknown>} [initial] 初始响应函数。
 * @returns {{ respond: (handler: Function) => void, restore: () => void }}
 */
export function useFetchStub(initial) {
  let responder = initial ?? (() => {
    throw new Error('测试未设置 fetch 响应');
  });

  if (!originalFetch) originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => responder(new URL(String(url)), init);

  return {
    respond(handler) {
      responder = handler;
    },
    restore() {
      globalThis.fetch = originalFetch;
    },
  };
}

/**
 * 包装成功响应，匹配后端统一 `{ code, data }` 响应体。
 *
 * @param {unknown} data 业务数据。
 * @param {number} [code] 业务状态码，默认 200。
 */
export function jsonResponse(data, code = 200) {
  return { ok: true, status: 200, json: async () => ({ code, data }) };
}

/** 可手动 resolve/reject 的 Promise，用于验证加载中、竞态和失败分支。 */
export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

/** 关闭 Vite 与 jsdom，恢复全局 fetch 与被覆盖的浏览器全局。每个测试文件在 `after` 中调用一次。 */
export async function stopHarness() {
  await server?.close();
  dom?.window.close();
  if (originalFetch) {
    globalThis.fetch = originalFetch;
    originalFetch = null;
  }
  for (const [key, descriptor] of originalGlobals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else delete globalThis[key];
  }
  originalGlobals.clear();
  server = null;
  dom = null;
  react = null;
  createRoot = null;
}
