import { useEffect, useState } from 'react';
import { FileArrowUpIcon, PaperclipIcon } from '@phosphor-icons/react';
import { attachmentContentUrl, fetchRecordAttachments, uploadRecordAttachment } from '../../services/recordApi';
import type { RecordEntry, RecordTab, RecordType, SaveRecordInput } from '../../types/record';
import { Select } from '../common/Select';
import { useMessage } from '../common/Message';
import { WritingEditorPage } from '../common/WritingEditorPage';
import styles from './RecordEditor.module.css';

interface Props {
  date: string;
  entry?: RecordEntry | null;
  initialType?: RecordType;
  initialTabId?: string;
  tabs: RecordTab[];
  saving: boolean;
  onSave: (input: SaveRecordInput) => Promise<void>;
  onBack: () => void;
}

function textToHtml(text: string) {
  const escaped = text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  return escaped.split('\n').map((line) => `<p>${line || '<br>'}</p>`).join('');
}

function typeForSystemTab(tab?: RecordTab): RecordType | null {
  if (tab?.systemKey === 'weekly_review') return 'weekly_review';
  if (tab?.systemKey === 'journal') return 'journal';
  if (tab?.systemKey === 'reading') return 'reading';
  if (tab?.systemKey === 'knowledge' || tab?.systemKey === 'interview') return 'learning';
  if (tab?.systemKey === 'quick') return 'quick';
  return null;
}

/** 使用全项目统一写作界面，为个人资料补充分类、标签和附件能力。 */
export function RecordEditor({ date, entry, initialType = 'quick', initialTabId, tabs, saving, onSave, onBack }: Props) {
  const { showMessage } = useMessage();
  const [type, setType] = useState<RecordType>(entry?.type ?? initialType);
  const [recordDate, setRecordDate] = useState(entry?.recordDate ?? date);
  const [tabId, setTabId] = useState(entry?.tabId ?? initialTabId ?? '');
  const [tags, setTags] = useState(entry?.tags.join('，') ?? '');
  const [attachments, setAttachments] = useState<Awaited<ReturnType<typeof fetchRecordAttachments>>>([]);
  const [uploading, setUploading] = useState(false);

  useEffect(() => { if (entry) void fetchRecordAttachments(entry.id).then(setAttachments).catch(() => showMessage('error', '附件加载失败')); }, [entry, showMessage]);

  const tabName = tabs.find((tab) => tab.id === tabId)?.name ?? '全部';
  return <WritingEditorPage backLabel="资料" identity={entry ? '编辑资料' : '新增资料'} detail={`${recordDate} · ${tabName}`}
    initialTitle={entry?.title ?? ''} initialBody={entry?.contentText ?? ''} saving={saving}
    bodyPlaceholder="开始整理知识、问题与思考……" onBack={onBack}
    footer={<div className={styles.details} role="group" aria-label="资料属性">
      <div className={styles.metaControls}>
        <Select
          value={tabId}
          placeholder="选择分类"
          options={tabs.map((tab) => ({ value: tab.id, label: tab.name }))}
          appearance="ghost"
          onChange={(event) => { const nextId = event.target.value; setTabId(nextId); const nextType = typeForSystemTab(tabs.find((tab) => tab.id === nextId)); if (nextType) setType(nextType); }}
          aria-label="资料分类"
        />
        <input type="date" value={recordDate} onChange={(event) => setRecordDate(event.target.value)} aria-label="资料日期" />
      </div>
      <input className={styles.tagsInput} value={tags} onChange={(event) => setTags(event.target.value)} placeholder="添加标签，用逗号分隔" aria-label="资料标签" />
      {entry && <div className={styles.attachments}>{attachments.map((attachment) => <a key={attachment.id} href={attachmentContentUrl(entry.id, attachment.id)} target="_blank" rel="noreferrer">
        {attachment.mediaType.startsWith('image/') ? <img src={attachmentContentUrl(entry.id, attachment.id)} alt={attachment.originalName} /> : <PaperclipIcon size={13} />}<span>{attachment.originalName}</span>
      </a>)}<label><FileArrowUpIcon size={13} />{uploading ? '上传中…' : '添加附件'}<input type="file" disabled={uploading} onChange={async (event) => {
        const file = event.target.files?.[0]; if (!file) return; setUploading(true);
        try { const uploaded = await uploadRecordAttachment(entry.id, file); setAttachments((items) => [...items, uploaded]); }
        catch (reason) { showMessage('error', reason instanceof Error ? reason.message : '附件上传失败'); }
        finally { setUploading(false); event.target.value = ''; }
      }} /></label></div>}
    </div>}
    onSave={({ title, body, html }) => onSave({ recordDate, type, title, contentHtml: html || textToHtml(body), contentText: body,
      tags: tags.split(/[，,]/).map((item) => item.trim()).filter(Boolean), tabId: tabId || undefined })} />;
}
