import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { AgentResetBoundary } from './components/reset/AgentResetBoundary'
import { AppBootGate } from './components/reset/AppBootGate'

// macOS 使用 Tauri Overlay 标题栏时，前端为标题栏内容预留同一套安全内边距。
if (typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
  && /Macintosh|Mac OS X/.test(navigator.userAgent)) {
  document.documentElement.dataset.windowChrome = 'macos-overlay';
}

// 首帧立即挂载统一启动加载屏，异步完成偏好、后端连接与重置屏障检查，避免白屏或重置页闪现。
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppBootGate>
      <AgentResetBoundary>
        <App />
      </AgentResetBoundary>
    </AppBootGate>
  </StrictMode>,
)
