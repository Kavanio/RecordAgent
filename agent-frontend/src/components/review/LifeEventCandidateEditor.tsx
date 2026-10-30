import { useState } from 'react';
import type { LifeContent } from '../../types/lifeReview';
import { lifeFactLabel } from '../../features/review/lifeReviewLabels';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { Button } from '../common/Button';
import styles from './LifeEventCandidateEditor.module.css';

interface Props {
  history: LifeContent[]; disabled: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}
/** 人工事件只能选择已获准内容中的真实证据，不接入额外外部信息源。 */
export function LifeEventCandidateEditor({ history, disabled, act }: Props) {
  const [topic, setTopic] = useState('');
  const [contentId, setContentId] = useState('');
  const [evidence, setEvidence] = useState('');
  const [kind, setKind] = useState<'EVENT' | 'RECOVERY'>('EVENT');
  const options = history.filter(c => ['READY', 'DEGRADED'].includes(c.state));
  const content = options.find(c => c.id === contentId);
  const sources = [
    ...(content?.materials || []).map(m => ({ value: m.id, label: `${m.occurredAt} · ${m.text.slice(0, 40)}` })),
    ...(content?.facts || []).map(f => ({ value: f.id, label: `${f.period} · ${lifeFactLabel(f)}` })),
  ];
  return <SettingsGroup title="人工事件候选" description="先选真实依据，再决定是否值得联系。同主题同内容去重；恢复需要关联已呈现的同主题事件。所有候选按普通节奏和冷却处理，不作为紧急告警。">
    <SettingsRow label="事件与依据" layout="stacked" control={<div className={styles.fields}>
      <TextInput aria-label="主动事件主题" placeholder="例如：最近的学习状态" value={topic} maxLength={80} disabled={disabled} onChange={e => setTopic(e.target.value)} />
      <Select aria-label="主动事件来源版本" value={contentId} disabled={disabled}
        options={[{ value: '', label: '选择已有复盘版本' }, ...options.map(c => ({ value: c.id, label: `${c.day} · v${c.version}` }))]}
        onChange={e => { setContentId(e.target.value); setEvidence(''); }} />
      <Select aria-label="主动事件证据" value={evidence} disabled={disabled || !content}
        options={[{ value: '', label: '选择本版本的真实依据' }, ...sources]} onChange={e => setEvidence(e.target.value)} />
      <Select aria-label="主动事件类型" value={kind} disabled={disabled}
        options={[{ value: 'EVENT', label: '有新信息' }, { value: 'RECOVERY', label: '同一事件已恢复' }]}
        onChange={e => setKind(e.target.value === 'RECOVERY' ? 'RECOVERY' : 'EVENT')} />
      <Button size="sm" variant="outline" disabled={disabled || !topic.trim() || !content || !evidence}
        onClick={() => void act(() => lifeReviewApi.event(topic, contentId, evidence, kind), '已记录事件候选，将按有效期和冷却判断呈现')}>加入主动候选</Button>
    </div>} />
  </SettingsGroup>;
}
