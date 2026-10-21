import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { initApiBaseUrl } from './services/api'
import { initDesktopPreferences } from './services/desktopPreferences'

// macOS 使用 Tauri Overlay 标题栏时，前端为标题栏内容预留同一套安全内边距。
if (typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
  && /Macintosh|Mac OS X/.test(navigator.userAgent)) {
  document.documentElement.dataset.windowChrome = 'macos-overlay';
}

// 桌面端先加载长期偏好并等待后端 sidecar 就绪，再渲染应用；浏览器开发环境立即返回
Promise.all([initDesktopPreferences(), initApiBaseUrl()]).then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
