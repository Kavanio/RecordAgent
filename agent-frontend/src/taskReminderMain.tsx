import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AutomationProvider } from './context/AutomationContext';
import { MessageProvider } from './components/common/Message';
import { TaskReminderWindow } from './components/task/TaskReminderWindow';
import { initApiBaseUrl } from './services/api';
import './index.css';

// 提醒窗口使用独立轻量入口，避免加载聊天、资料和报表页面代码。
initApiBaseUrl({ reuseEstablishedConnection: true }).then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <MessageProvider>
        <AutomationProvider>
          <TaskReminderWindow />
        </AutomationProvider>
      </MessageProvider>
    </StrictMode>,
  );
}).catch((error: unknown) => {
  const root = document.getElementById('root');
  if (root) root.textContent = `任务提醒暂时无法连接：${error instanceof Error ? error.message : String(error)}`;
});
