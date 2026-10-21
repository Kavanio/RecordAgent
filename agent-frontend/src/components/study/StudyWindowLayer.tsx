import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { getFeaturePreferences, subscribeFeaturePreferences } from '../../services/featurePreferences';
import { finishStudySession } from '../../services/studyApi';
import { useStudyRealtime } from '../../context/studyRealtimeState';
import { hideDesktopStudyWindow, showDesktopStudyWindow } from '../../services/studyWindow';
import { readPreference, writePreference } from '../../services/desktopPreferences';
import type { StudyWindowMode } from '../../types/preferences';
import { ActiveStudyCard } from './ActiveStudyCard';
import { useMessage } from '../common/Message';
import styles from './StudyWindowLayer.module.css';

const POSITION_KEY = 'butvan.studyWidgetPosition';
const DEFAULT_WIDGET_WIDTH = 336;
const DEFAULT_WIDGET_HEIGHT = 224;
const MIN_WIDGET_WIDTH = 280;
const MIN_WIDGET_HEIGHT = 190;
const VIEWPORT_MARGIN = 18;

interface WidgetGeometry {
  x: number;
  y: number;
  width: number;
  height: number;
}

function defaultGeometry(): WidgetGeometry {
  return {
    x: Math.max(VIEWPORT_MARGIN, window.innerWidth - DEFAULT_WIDGET_WIDTH - VIEWPORT_MARGIN),
    y: Math.max(VIEWPORT_MARGIN, window.innerHeight - DEFAULT_WIDGET_HEIGHT - VIEWPORT_MARGIN),
    width: DEFAULT_WIDGET_WIDTH,
    height: DEFAULT_WIDGET_HEIGHT,
  };
}

function clampGeometry(geometry: WidgetGeometry): WidgetGeometry {
  const maxViewportWidth = Math.max(MIN_WIDGET_WIDTH, window.innerWidth - VIEWPORT_MARGIN * 2);
  const maxViewportHeight = Math.max(MIN_WIDGET_HEIGHT, window.innerHeight - VIEWPORT_MARGIN * 2);
  const width = Math.min(Math.max(MIN_WIDGET_WIDTH, geometry.width), maxViewportWidth);
  const height = Math.min(Math.max(MIN_WIDGET_HEIGHT, geometry.height), maxViewportHeight);
  return {
    width,
    height,
    x: Math.min(Math.max(VIEWPORT_MARGIN, geometry.x), Math.max(VIEWPORT_MARGIN, window.innerWidth - width - VIEWPORT_MARGIN)),
    y: Math.min(Math.max(VIEWPORT_MARGIN, geometry.y), Math.max(VIEWPORT_MARGIN, window.innerHeight - height - VIEWPORT_MARGIN)),
  };
}

function readGeometry(): WidgetGeometry {
  const defaults = defaultGeometry();
  try {
    const saved = JSON.parse(readPreference(POSITION_KEY) ?? '{}') as Partial<WidgetGeometry>;
    if (Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
      return clampGeometry({
        x: saved.x!,
        y: saved.y!,
        width: Number.isFinite(saved.width) ? saved.width! : defaults.width,
        height: Number.isFinite(saved.height) ? saved.height! : defaults.height,
      });
    }
  } catch {
    // 使用右下角默认位置。
  }
  return defaults;
}

/** 主应用中的学习小窗协调层，负责模式切换、会话同步和应用内拖动。 */
export function StudyWindowLayer() {
  const { showMessage } = useMessage();
  const { activeSession: session } = useStudyRealtime();
  const [mode, setMode] = useState<StudyWindowMode>(() => getFeaturePreferences().studyWindowMode);
  const [now, setNow] = useState(Date.now());
  const [saving, setSaving] = useState(false);
  const [finishError, setFinishError] = useState<string | null>(null);
  const [hiddenSessionId, setHiddenSessionId] = useState<string | null>(null);
  const [geometry, setGeometry] = useState(readGeometry);
  const geometryRef = useRef(geometry);
  geometryRef.current = geometry;
  const interactionRef = useRef<{
    kind: 'move' | 'resize';
    pointerId: number;
    captureElement: HTMLElement;
    startX: number;
    startY: number;
    startGeometry: WidgetGeometry;
  } | null>(null);
  const activeSessionId = session?.id;

  useEffect(() => subscribeFeaturePreferences((preferences) => setMode(preferences.studyWindowMode)), []);
  useEffect(() => { if (!session) setHiddenSessionId(null); }, [session]);
  useEffect(() => {
    if (!session) return;
    const interval = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(interval);
  }, [session]);

  useEffect(() => {
    if (mode === 'desktop' && activeSessionId) {
      void showDesktopStudyWindow()
        .then((shown) => {
          if (!shown) showMessage('error', '系统学习小窗仅在桌面应用中可用。');
        })
        .catch((cause) => showMessage('error', cause instanceof Error ? cause.message : '系统学习小窗打开失败。'));
    } else {
      void hideDesktopStudyWindow().catch(() => undefined);
    }
  }, [mode, activeSessionId, showMessage]);

  useEffect(() => {
    const onResize = () => setGeometry((current) => clampGeometry(current));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest('button')) return;
    interactionRef.current = {
      kind: 'move',
      pointerId: event.pointerId,
      captureElement: event.currentTarget,
      startX: event.clientX,
      startY: event.clientY,
      startGeometry: geometryRef.current,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const interaction = interactionRef.current;
    if (!interaction || interaction.pointerId !== event.pointerId) return;
    const deltaX = event.clientX - interaction.startX;
    const deltaY = event.clientY - interaction.startY;
    if (interaction.kind === 'move') {
      setGeometry(clampGeometry({
        ...interaction.startGeometry,
        x: interaction.startGeometry.x + deltaX,
        y: interaction.startGeometry.y + deltaY,
      }));
      return;
    }
    const maxWidth = Math.max(MIN_WIDGET_WIDTH, window.innerWidth - interaction.startGeometry.x - VIEWPORT_MARGIN);
    const maxHeight = Math.max(MIN_WIDGET_HEIGHT, window.innerHeight - interaction.startGeometry.y - VIEWPORT_MARGIN);
    setGeometry({
      ...interaction.startGeometry,
      width: Math.min(maxWidth, Math.max(MIN_WIDGET_WIDTH, interaction.startGeometry.width + deltaX)),
      height: Math.min(maxHeight, Math.max(MIN_WIDGET_HEIGHT, interaction.startGeometry.height + deltaY)),
    });
  };
  const onPointerUp = (event: ReactPointerEvent<HTMLDivElement>) => {
    const interaction = interactionRef.current;
    if (!interaction || interaction.pointerId !== event.pointerId) return;
    interactionRef.current = null;
    writePreference(POSITION_KEY, JSON.stringify(geometryRef.current));
    if (interaction.captureElement.hasPointerCapture(event.pointerId)) {
      interaction.captureElement.releasePointerCapture(event.pointerId);
    }
  };
  const onResizeStart = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    interactionRef.current = {
      kind: 'resize',
      pointerId: event.pointerId,
      captureElement: event.currentTarget,
      startX: event.clientX,
      startY: event.clientY,
      startGeometry: geometryRef.current,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const finish = async () => {
    if (!session) return;
    setSaving(true);
    setFinishError(null);
    try {
      await finishStudySession(session.id, session.version);
    } catch (cause) {
      setFinishError(cause instanceof Error ? cause.message : '结束学习失败，请重试。');
    } finally {
      setSaving(false);
    }
  };

  if (mode !== 'in-app' || !session || hiddenSessionId === session.id) return null;
  const elapsed = Math.max(0, Math.floor((now - new Date(session.startedAt).getTime()) / 1_000));
  return (
    <div
      className={styles.widget}
      style={{
        width: geometry.width,
        height: geometry.height,
        transform: `translate3d(${geometry.x}px, ${geometry.y}px, 0)`,
      }}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
    >
      <ActiveStudyCard
        session={session}
        elapsedSeconds={elapsed}
        compact
        draggable
        saving={saving}
        error={finishError}
        onFinish={() => void finish()}
        onHide={() => setHiddenSessionId(session.id)}
        onResizeStart={onResizeStart}
      />
    </div>
  );
}
