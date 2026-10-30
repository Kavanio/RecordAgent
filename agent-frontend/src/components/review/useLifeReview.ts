import { useCallback, useEffect, useRef, useState } from 'react';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import { fetchFullModelConfig } from '../../services/api';
import type { FullModelConfig } from '../../types/model';
import type { LifeCandidate, LifeContent, LifeHabit, LifeSettings } from '../../types/lifeReview';
import { useMessage } from '../common/Message';

/** 设置快照与写入操作编排；组件卸载后不发布迟到加载结果。 */
export function useLifeReview() {
  const [settings, setSettings] = useState<LifeSettings | null>(null);
  const [history, setHistory] = useState<LifeContent[]>([]);
  const [candidates, setCandidates] = useState<LifeCandidate[]>([]);
  const [habit, setHabit] = useState<LifeHabit | null>(null);
  const [archives, setArchives] = useState<string[]>([]);
  const [providers, setProviders] = useState<FullModelConfig['providers']>([]);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const live = useRef(false);
  const sequence = useRef(0);
  const { showMessage } = useMessage();
  const load = useCallback(async () => {
    const seq = ++sequence.current;
    try {
      const [s, h, c, habitValue, a, models] = await Promise.all([
        lifeReviewApi.settings(), lifeReviewApi.history(), lifeReviewApi.candidates(), lifeReviewApi.habit(),
        lifeReviewApi.archives(), fetchFullModelConfig(),
      ]);
      if (!live.current || seq !== sequence.current) return;
      setSettings(s); setHistory(h); setCandidates(c); setHabit(habitValue); setArchives(a);
      setProviders(models?.providers || []); setError(''); setDirty(false);
    } catch (e) {
      if (live.current && seq === sequence.current) setError(e instanceof Error ? e.message : '生活复盘读取失败');
    } finally { if (live.current && seq === sequence.current) setLoading(false); }
  }, []);
  useEffect(() => { live.current = true; void load(); return () => { live.current = false; }; }, [load]);
  async function act(work: () => Promise<unknown>, message: string) {
    if (busy) return;
    setBusy(true);
    try { await work(); if (live.current) { showMessage('success', message); await load(); } }
    catch (e) { if (live.current) showMessage('error', e instanceof Error ? e.message : '操作未完成'); }
    finally { if (live.current) setBusy(false); }
  }
  function patch(next: Partial<LifeSettings>) { setSettings(s => s ? { ...s, ...next } : s); setDirty(true); }
  return { settings, history, candidates, habit, archives, providers, busy, loading, error, dirty, patch, load, act };
}
