import { ReviewDisclosure } from './ReviewDisclosure';
import { ReviewFormModal } from './ReviewFormModal';
import { useRef, useState } from 'react';
import type { LifeContent } from '../../types/lifeReview';
import { lifeFactLabel } from '../../features/review/lifeReviewLabels';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import styles from './LifeContentCard.module.css';

const STATES: Record<string, string> = { PENDING: '等待准备', RUNNING: '正在准备', READY: '已备好',
  DEGRADED: '复盘未生成', SKIPPED: '未生成', UNKNOWN: '结果未知', SENT: '已送达', AVAILABLE: '桌面已留档',
  FAILED: '投递失败', SENDING: '发送中' };
const lifeState = (state: string) => STATES[state] || state;
interface Props {
  content: LifeContent; timezone: string; busy: boolean; authorized: boolean; feishu: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}
/** 展示一个不可变复盘版本；补充、发送与生成是不同动作。 */
export function LifeContentCard({ content: c, timezone, busy, authorized, feishu, act }: Props) {
  const [replyOpen, setReplyOpen] = useState(false);
  const [text, setText] = useState('');
  const [sendOpen, setSendOpen] = useState(false);
  const replyKey = useRef({ text: '', id: '' });
  const delivery = c.deliveries.find(d => d.channel === 'FEISHU');
  const ready = ['READY', 'DEGRADED'].includes(c.state);
  const hasReport = c.state === 'READY' && c.items.length > 0;
  function supplement() {
    if (replyKey.current.text !== text || !replyKey.current.id)
      replyKey.current = { text, id: crypto.randomUUID() };
    void act(async () => { await lifeReviewApi.reply(c.id, replyKey.current.id, text); setText(''); setReplyOpen(false); }, '补充已记录，新版本在后台准备；已发副本保留');
  }
  const time = (value: string) => {
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return `${value}（归属日）`;
    try { return new Date(value).toLocaleString('zh-CN', { timeZone: timezone }); }
    catch { return value; }
  };
  return <article className={styles.card} aria-label={`${c.day} 生活复盘版本${c.version}`}>
    <div className={styles.header}><strong>{c.day} · v{c.version}</strong><span>{lifeState(c.state)}</span></div>
    <p className={styles.muted}>资料截至 {time(c.sourceAsOf)}（{timezone}） · 第{c.version}版</p>
    {!hasReport && ready && <div className={styles.notice} role="status"><strong>本次没有生成复盘正文</strong><p>{c.message}</p>
      <p>已保存参考资料，尚未形成回顾或建议。重复点击“准备今天”会复用此版，不会重新生成。</p>
      <p>可以检查模型配置；如需补充资料并准备新版本，点击下方“补充经历”，可能占用一次调用额度。</p>
    </div>}
    {c.items.map((item, i) => <div key={i}>
      <p>{item.kind === 'ACTION' ? '建议：' : ''}{item.text}</p>
      <p className={styles.muted}>{item.uncertainty}</p>
    </div>)}
    {c.questions.map(q => <p key={q}>{q}</p>)}
    <ReviewDisclosure title="参考记录" description="仅为本版读取的资料，不代表已确认事实或完整的一天。">
      {c.facts?.map(f => <p key={f.id} className={styles.source}>{lifeFactLabel(f)} · {f.period}</p>)}
      {c.materials.length === 0 && <p className={styles.muted}>没有读取到文字材料；不代表当天没有发生其他事情。</p>}
      {c.materials.map(m => <div className={styles.source} key={m.id}>
        <strong>{time(m.occurredAt)}</strong><p>{m.text}</p>
        <span className={styles.muted}>来源：{m.sourceId}{m.truncated ? ' · 已截断' : ''}</span>
      </div>)}
    </ReviewDisclosure>
    <ReviewDisclosure title="版本与诊断信息" description="查看读取范围、模型和内部来源编号。">
      <p className={styles.muted}>可呈现：{time(c.availableAt)}（{timezone}） · 固定模型：{c.providerId} / {c.modelName}</p>
      <p className={styles.muted}>{c.coverage}</p>
      {c.materials.map(m => <p key={m.id} className={styles.muted}>{m.id} → {m.sourceId}</p>)}
      {c.facts?.map(f => <p key={f.id} className={styles.muted}>{f.id} → {lifeFactLabel(f)}</p>)}
      {c.items.map((item, i) => <p key={i}>依据：{item.evidenceIds.join('、')}</p>)}
    </ReviewDisclosure>
    <p className={styles.muted}>{c.deliveries.map(d => `${d.channel === 'DESKTOP' ? '桌面' : '飞书'}：${lifeState(d.state)}`).join(' · ')}</p>
    {!hasReport && delivery && <p className={styles.muted}>以上为历史投递记录，不表示正文生成成功；已发送副本无法撤回。</p>}
    {delivery?.state === 'UNKNOWN' && <p>飞书可能已收到，请人工核对；此处不会自动重发。已读状态未知。</p>}
    {ready && authorized && <>
      <ReviewFormModal open={replyOpen} title="补充这一天的经历" busy={busy} onClose={() => { setReplyOpen(false); setText(''); }}>
      <p className={styles.muted}>补充会准备新版本，保留原文及已发送副本；可能占用一次模型调用额度。</p>
      <label className={styles.muted}>补充这一天的经历（可跳过）
        <textarea className={styles.input} aria-label={`补充${c.day}版本${c.version}`} value={text} maxLength={1000} rows={2}
          disabled={busy} onChange={e => setText(e.target.value)} />
      </label>
      <div className={styles.actions}>
        <Button size="sm" variant="outline" disabled={busy || !text.trim()} onClick={supplement}>记录补充并准备新版本</Button>
      </div></ReviewFormModal>
      <div className={styles.actions}>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => setReplyOpen(true)}>补充经历</Button>
        {hasReport && feishu && (!delivery || delivery.state === 'FAILED') && <Button size="sm" variant="outline" disabled={busy}
          onClick={() => setSendOpen(true)}>{delivery ? '重试原副本' : '发送此版本到本人飞书'}</Button>}
      </div>
    </>}
    <ConfirmDialog open={sendOpen} title="投递生活复盘" tone="info" confirmText="确认发送" busy={busy}
      description="将发送这份已保存版本到已授权的本人飞书，可能包含已授权材料。不会重新调用模型，已发送消息无法撤回。"
      onClose={() => setSendOpen(false)} onConfirm={() => {
        setSendOpen(false); void act(() => lifeReviewApi.send(c.id, delivery?.state === 'FAILED'), '已核对本次投递结果');
      }} />
  </article>;
}
