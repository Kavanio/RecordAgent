import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Button } from '../common/Button';
import { LoadingTree } from '../common/LoadingTree';
import { MessageProvider } from '../common/Message';
import { BackendConnectionSettings } from '../settings/BackendConnectionSettings';
import { initApiBaseUrl, isTauri } from '../../services/api';
import { initDesktopPreferences } from '../../services/desktopPreferences';
import { BootSplashContext } from './bootSplashContext';
import styles from './AppBootGate.module.css';

type SplashPhase = 'visible' | 'fading' | 'hidden';

const SLOW_LABEL_DELAY_MS = 1800;
const FADE_DURATION_MS = 220;

/**
 * 桌面与浏览器主窗口启动引导层：
 * 首帧立即渲染统一的大树加载动画，异步完成偏好与后端连接初始化，
 * 并通过 BootSplashContext 与后续重置屏障、配置检查共享同一颗连续播放的大树，
 * 消除启动白屏、多阶段动画重播与重置界面闪现。
 */
export function AppBootGate({ children }: { children: ReactNode }) {
  const [bootReady, setBootReady] = useState(false);
  const [bootError, setBootError] = useState('');
  const [bootAttempt, setBootAttempt] = useState(0);
  const [connectionSettingsOpen, setConnectionSettingsOpen] = useState(false);
  const [slowHint, setSlowHint] = useState(false);
  const [splashPhase, setSplashPhase] = useState<SplashPhase>('visible');

  // 使用 ref 记录当前仍未就绪的阶段标识，在微任务结算后确认是否所有阶段均已释放，避免交接帧闪退。
  const holdsRef = useRef<Set<string>>(new Set(['boot']));
  const settleQueuedRef = useRef(false);
  const bootReadyRef = useRef(false);
  bootReadyRef.current = bootReady;

  const scheduleSettle = useCallback(() => {
    if (settleQueuedRef.current) return;
    settleQueuedRef.current = true;
    queueMicrotask(() => {
      settleQueuedRef.current = false;
      if (!bootReadyRef.current) return;
      if (holdsRef.current.size === 0) {
        setSplashPhase((current) => (current === 'visible' ? 'fading' : current));
      } else {
        setSplashPhase('visible');
      }
    });
  }, []);

  const setHold = useCallback((id: string, active: boolean) => {
    if (active) {
      holdsRef.current.add(id);
      setSplashPhase('visible');
      return;
    }
    holdsRef.current.delete(id);
    scheduleSettle();
  }, [scheduleSettle]);

  useEffect(() => {
    if (splashPhase !== 'fading') return;
    const timer = setTimeout(() => setSplashPhase('hidden'), FADE_DURATION_MS);
    return () => clearTimeout(timer);
  }, [splashPhase]);

  useEffect(() => {
    let cancelled = false;
    holdsRef.current.add('boot');
    setBootReady(false);
    setBootError('');
    setSlowHint(false);
    setSplashPhase('visible');

    const slowTimer = setTimeout(() => {
      if (!cancelled) setSlowHint(true);
    }, SLOW_LABEL_DELAY_MS);

    Promise.all([initDesktopPreferences(), initApiBaseUrl()])
      .then(() => {
        if (cancelled) return;
        bootReadyRef.current = true;
        holdsRef.current.delete('boot');
        setBootReady(true);
        scheduleSettle();
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        setBootError(cause instanceof Error ? cause.message : '应用初始化失败，请重试');
      });

    return () => {
      cancelled = true;
      clearTimeout(slowTimer);
    };
  }, [bootAttempt, scheduleSettle]);

  const contextValue = useMemo(() => ({ setHold }), [setHold]);

  if (connectionSettingsOpen) {
    return (
      <MessageProvider>
        <main className={styles.errorPage} aria-label="后端连接恢复">
          <section className={`${styles.errorCard} ${styles.recoveryCard}`}>
            <Button variant="outline" onClick={() => setConnectionSettingsOpen(false)}>返回启动检查</Button>
            <BackendConnectionSettings />
          </section>
        </main>
      </MessageProvider>
    );
  }

  if (bootError) {
    return (
      <main className={styles.errorPage} aria-label="应用启动失败">
        <section className={styles.errorCard}>
          <h1>应用启动遇到问题</h1>
          <p className={styles.errorText} role="alert">{bootError}</p>
          <p>{isTauri()
            ? '请检查后端服务状态或连接配置后重试；保存新的连接配置后需正常重启应用。'
            : '请检查本地开发后端服务是否正常运行后重试。'}</p>
          <div className={styles.actions}>
            <Button variant="outline" onClick={() => setBootAttempt((value) => value + 1)}>重试启动</Button>
            {isTauri() && (
              <Button variant="outline" onClick={() => setConnectionSettingsOpen(true)}>后端连接设置</Button>
            )}
          </div>
        </section>
      </main>
    );
  }

  const label = slowHint ? '正在连接服务并准备环境…' : '正在启动 ButvanAgent…';

  return (
    <BootSplashContext.Provider value={contextValue}>
      {bootReady ? children : null}
      {splashPhase !== 'hidden' && (
        <div
          className={`${styles.overlay} ${splashPhase === 'fading' ? styles.fading : ''}`}
          data-tauri-drag-region
          aria-hidden={splashPhase === 'fading' ? true : undefined}
        >
          <LoadingTree size="large" label={label} />
        </div>
      )}
    </BootSplashContext.Provider>
  );
}
