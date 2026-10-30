import { useState } from 'react';
import type { LifeContent } from '../../types/lifeReview';
import { lifeReferenceGroups, lifeReviewTime, lifeReviewWindow } from '../../features/review/lifeReviewPresentation';
import { Drawer } from '../common/Drawer';
import { Button } from '../common/Button';
import { ReviewDisclosure } from './ReviewDisclosure';
import styles from './LifeReviewDetails.module.css';

interface Props { content: LifeContent; timezone: string; mode: 'references' | 'version'; onClose: () => void }

/** 同一版本的只读材料与版本详情；打开、筛选均不重新采集或调用模型。 */
export function LifeReviewDetails({ content: c, timezone, mode, onClose }: Props) {
  const [category, setCategory] = useState('ALL');
  const groups = lifeReferenceGroups(c);
  const count = groups.reduce((total, group) => total + group.entries.length, 0);
  const zone = c.timezone || timezone;
  const time = (value: string) => lifeReviewTime(value, zone);
  const title = mode === 'references' ? '参考记录' : '版本信息';
  return <Drawer open title={title} description={`${c.day} · 第${c.version}版`} onClose={onClose}>
    <div className={styles.body}>
      {mode === 'references' ? <>
        <p className={styles.note}>这是本版实际读取的材料快照，不是完整聊天记录，也不会自动追加新消息。材料不等于已确认事实。</p>
        <dl className={styles.metadata}>
          <div><dt>近期原文范围</dt><dd>{lifeReviewWindow(c.coverage)}</dd></div>
          <div><dt>资料截止</dt><dd>{time(c.sourceAsOf)}（{zone}）</dd></div>
        </dl>
        <p className={styles.note}>聊天仅取部分普通会话中的用户原文；条数和字数有限。财务、学习及待办为归属日数值；目标可能早于近期窗口。</p>
        <nav className={styles.filters} aria-label="参考记录分类">
          <Button size="sm" variant={category === 'ALL' ? 'secondary' : 'ghost'} aria-pressed={category === 'ALL'} onClick={() => setCategory('ALL')}>全部 · {count}</Button>
          {groups.map(group => <Button key={group.key} size="sm" variant={category === group.key ? 'secondary' : 'ghost'}
            aria-pressed={category === group.key} onClick={() => setCategory(group.key)}>{group.title} · {group.entries.length}</Button>)}
        </nav>
        {count === 0 && <p className={styles.empty}>此版本没有保存参考记录，不代表当天没有发生其他事情。</p>}
        {groups.filter(g => category === 'ALL' || category === g.key).map(group => <section key={group.key} aria-label={group.title} className={styles.group}>
          <h3>{group.title}<span>{group.entries.length} 条</span></h3>
          {group.entries.map(entry => <article className={styles.entry} key={entry.id}>
            <p className={styles.date}>{time(entry.time)}</p><p>{entry.text}</p>
            {entry.truncated && <p className={styles.note}>仅展示本版读取的片段，原文未完整读取。</p>}
          </article>)}
        </section>)}
      </> : <>
        <dl className={styles.metadata}>
          <div><dt>复盘归属日</dt><dd>{c.day} · 第{c.version}版</dd></div>
          <div><dt>近期原文范围</dt><dd>{lifeReviewWindow(c.coverage)}</dd></div>
          <div><dt>资料截止</dt><dd>{time(c.sourceAsOf)}</dd></div>
          <div><dt>最早可主动呈现</dt><dd>{time(c.availableAt)}</dd></div>
          <div><dt>时区</dt><dd>{zone}</dd></div>
          <div><dt>固定模型</dt><dd>{c.modelName || '未记录'}</dd></div>
          <div><dt>已读取材料</dt><dd>{groups.map(g => `${g.title} ${g.entries.length} 条`).join('、') || '无参考记录'}</dd></div>
        </dl>
        <p className={styles.note}>呈现时间是最早允许主动联系的时间，不代表已经发送或已读。补充经历会创建新版本，原版本及已发送副本保留。</p>
        {c.message && <section className={styles.group}><h3>本次处理说明</h3><p>{c.message}</p></section>}
        <ReviewDisclosure title="技术排查详情" description="仅在排查问题时使用，包含读取限制和内部来源编号。">
          <p className={styles.note}>模型连接编号：{c.providerId || '未记录'}</p>
          <p className={styles.raw}>{c.coverage || '此版本未记录详细读取说明'}</p>
          {c.materials.map(m => <p className={styles.raw} key={m.id}>{m.id} → {m.sourceId}</p>)}
          {c.facts?.map(f => <p className={styles.raw} key={f.id}>{f.id} → {f.domain} / {f.metric}</p>)}
          {c.items.map((item, index) => <p className={styles.raw} key={index}>正文第{index + 1}条依据：{item.evidenceIds.join('、') || '未记录'}</p>)}
        </ReviewDisclosure>
      </>}
    </div>
  </Drawer>;
}
