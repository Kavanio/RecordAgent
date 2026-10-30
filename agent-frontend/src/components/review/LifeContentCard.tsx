import { LifeReviewDetails } from './LifeReviewDetails';
import { ReviewFormModal } from './ReviewFormModal';
import { useRef, useState } from 'react';
import type { LifeContent } from '../../types/lifeReview';
import { lifeReviewTime } from '../../features/review/lifeReviewPresentation';
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
  const [details, setDetails] = useState<'references' | 'version' | null>(null);
  const replyKey = useRef({ text: '', id: '' });
  const delivery = c.deliveries.find(d => d.channel === 'FEISHU');
  const generating = c.state === 'PENDING' || c.state === 'RUNNING';
  const ready = ['READY', 'DEGRADED'].includes(c.state);
  const hasReport = c.state === 'READY' && c.items.length > 0;
  const observations = c.items.filter(item => item.kind !== 'ACTION');
  const actions = c.items.filter(item => item.kind === 'ACTION');
  function supplement() {
    if (replyKey.current.text !== text || !replyKey.current.id)
      replyKey.current = { text, id: crypto.randomUUID() };
    void act(async () => { await lifeReviewApi.reply(c.id, replyKey.current.id, text); setText(''); setReplyOpen(false); }, '补充已记录，正在后台生成新版本，请稍候…');
  }
  const zone = c.timezone || timezone;
  const time = (value: string) => lifeReviewTime(value, zone);
  return <article className={styles.card} aria-label={`${c.day} 生活复盘版本${c.version}`}>
    <div className={styles.header}><strong>{c.day} · v{c.version}</strong><span>{lifeState(c.state)}</span></div>
    <p className={styles.muted}>资料截至 {time(c.sourceAsOf)}（{zone}） · 第{c.version}版</p>
    {generating && <div className={styles.notice} role="status"><strong>正在生成复盘正文…</strong>
      <p>正在后台按已授权范围采集资料并调用模型，预计需要 5～15 秒，完成后会自动刷新展示。</p>
    </div>}
    {!hasReport && ready && <div className={styles.notice} role="status"><strong>本次没有生成复盘正文</strong><p>{c.message}</p>
      <p>已保存参考资料，尚未形成回顾或建议。可点击上方“准备今天的复盘”重新生成，或点击下方“补充经历”准备新版本。</p>
    </div>}
    {observations.length > 0 && <div className={styles.section}>
      <div className={styles.sectionTitle}>今日回顾</div>
      <div className={styles.itemList}>
        {observations.map((item, i) => <div key={i} className={styles.item}>
          <p className={styles.itemText}>{item.text}</p>
          {item.uncertainty && <p className={styles.muted}>{item.uncertainty}</p>}
        </div>)}
      </div>
    </div>}
    {actions.length > 0 && <div className={styles.section}>
      <div className={styles.sectionTitle}>小步建议</div>
      <div className={styles.itemList}>
        {actions.map((item, i) => <div key={i} className={styles.actionItem}>
          <p className={styles.itemText}>{item.text.replace(/^建议[：:]\s*/, '')}</p>
          {item.uncertainty && <p className={styles.muted}>{item.uncertainty}</p>}
        </div>)}
      </div>
    </div>}
    {c.questions.map(q => <p key={q} className={styles.question}>{q}</p>)}
    <div className={styles.details}>
      <Button size="sm" variant="ghost" aria-haspopup="dialog" onClick={() => setDetails('references')}>参考记录</Button>
      <Button size="sm" variant="ghost" aria-haspopup="dialog" onClick={() => setDetails('version')}>版本信息</Button>
    </div>
    {details && <LifeReviewDetails key={details} content={c} timezone={zone} mode={details} onClose={() => setDetails(null)} />}
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
