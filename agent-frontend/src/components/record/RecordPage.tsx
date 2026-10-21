import { TopBarAction } from '../common/TopBarAction';
import { readPreference, writePreference } from '../../services/desktopPreferences';
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react';
import {
  ArrowCounterClockwiseIcon,
  CheckIcon,
  DownloadSimpleIcon,
  FileTextIcon,
  HeartIcon,
  MagnifyingGlassIcon,
  PlusIcon,
  TrashIcon,
  UploadSimpleIcon,
  XIcon,
} from '@phosphor-icons/react';
import { clearRecordTrash, createRecord, createRecordTab, deleteRecordTab, exportRecordBackup, fetchRecords,
  fetchRecordTabs, fetchRecordTrash, importRecordBackup, reorderRecordTabs, restoreRecord, trashRecord, updateRecord, updateRecordFlags } from '../../services/recordApi';
import type { RecordEntry, RecordTab, RecordType, SaveRecordInput } from '../../types/record';
import { RecordEditor } from './RecordEditor';
import { RECORD_TYPES } from './recordTypes';
import { Skeleton } from '../common/Skeleton';
import { TopBar } from '../common/TopBar';
import { Button } from '../common/Button';
import { TextInput } from '../common/TextInput';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { useMessage } from '../common/Message';
import { Modal } from '../common/Modal';
import styles from './RecordPage.module.css';

const TYPE_LABELS = Object.fromEntries(RECORD_TYPES.map((item) => [item.value, item.label])) as Record<RecordType, string>;
type EditorTarget = { entry?: RecordEntry; tabId?: string; type: RecordType };
const SUMMARY_COPY_KEY = 'butvan-record-summary-copy';
const DEFAULT_SUMMARY_COPY = '持续积累八股文和面试题，把零散记忆变成可以表达的答案。';
const IS_MAC = navigator.platform.toLowerCase().includes('mac');
const TAB_SHORTCUT_LABEL = IS_MAC ? 'Command + 左右方向键' : 'Ctrl + 左右方向键';

function formatDate(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; }
function displayTitle(entry: RecordEntry) { return entry.title || entry.contentText.split('\n')[0] || '无标题资料'; }
function mondayOf(date: Date) { const result = new Date(date); result.setDate(result.getDate() - ((result.getDay() + 6) % 7)); return result; }
function typeForTab(tab?: RecordTab): RecordType {
  if (tab?.systemKey === 'weekly_review') return 'weekly_review';
  if (tab?.systemKey === 'journal') return 'journal';
  if (tab?.systemKey === 'reading') return 'reading';
  if (tab?.systemKey === 'knowledge' || tab?.systemKey === 'interview') return 'learning';
  return 'quick';
}

/** 极简资料看板：全部内容通过统一 Tab 导航和统一写作编辑器管理。 */
export function RecordPage({ initialEntry, initialType = 'quick' }: { initialEntry?: RecordEntry | null; initialType?: RecordType }) {
  const { showMessage } = useMessage();
  const today = useMemo(() => new Date(), []);
  const todayKey = formatDate(today);
  const [records, setRecords] = useState<RecordEntry[]>([]);
  const [tabs, setTabs] = useState<RecordTab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string>('all');
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<EditorTarget | null>(() => initialEntry === undefined ? null : { entry: initialEntry ?? undefined, type: initialEntry?.type ?? initialType });
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadedQuery, setLoadedQuery] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const requestId = useRef(0);
  const [summaryCopy, setSummaryCopy] = useState(() => readPreference(SUMMARY_COPY_KEY) || DEFAULT_SUMMARY_COPY);
  const [summaryDraft, setSummaryDraft] = useState(summaryCopy);
  const [editingSummaryCopy, setEditingSummaryCopy] = useState(false);
  const [newTabName, setNewTabName] = useState('');
  const [isNewTabModalOpen, setIsNewTabModalOpen] = useState(false);
  const [creatingTab, setCreatingTab] = useState(false);
  const [draggedTabId, setDraggedTabId] = useState<string | null>(null);
  const [dragOverTabId, setDragOverTabId] = useState<string | null>(null);
  const [reorderingTabs, setReorderingTabs] = useState(false);
  const importInputRef = useRef<HTMLInputElement>(null);
  const newTabInputRef = useRef<HTMLInputElement>(null);
  const tabPointerDragRef = useRef<{ tabId: string; pointerId: number; startX: number; startY: number; dragging: boolean } | null>(null);
  const suppressTabClickRef = useRef(false);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [trashEntries, setTrashEntries] = useState<RecordEntry[] | null>(null);
  const [clearingTrash, setClearingTrash] = useState(false);
  const [confirmClearTrash, setConfirmClearTrash] = useState(false);
  const [pendingImportFile, setPendingImportFile] = useState<File | null>(null);
  const [importingBackup, setImportingBackup] = useState(false);
  const [deleteTabTarget, setDeleteTabTarget] = useState<RecordTab | null>(null);
  const [deletingTab, setDeletingTab] = useState(false);
  const range = useMemo(() => { const from = new Date(today); from.setFullYear(from.getFullYear() - 1); const to = new Date(today); to.setFullYear(to.getFullYear() + 1); return { from: formatDate(from), to: formatDate(to) }; }, [today]);

  const handleConfirmImport = async () => {
    if (!pendingImportFile) return;
    setImportingBackup(true);
    try {
      const count = await importRecordBackup(pendingImportFile);
      await load();
      showMessage('success', `已恢复 ${count} 条资料`);
      setPendingImportFile(null);
    } catch (reason) {
      showMessage('error', reason instanceof Error ? reason.message : '导入失败');
    } finally {
      setImportingBackup(false);
    }
  };

  const handleConfirmDeleteTab = async () => {
    if (!deleteTabTarget) return;
    setDeletingTab(true);
    try {
      await deleteRecordTab(deleteTabTarget.id);
      setActiveTabId('all');
      await load();
      showMessage('success', `已删除分类“${deleteTabTarget.name}”`);
      setDeleteTabTarget(null);
    } catch (reason) {
      showMessage('error', reason instanceof Error ? reason.message : '删除失败');
    } finally {
      setDeletingTab(false);
    }
  };

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true); setLoadError(null);
    try {
      const [recordItems, tabItems] = await Promise.all([fetchRecords(range.from, range.to, { query: query.trim() }), fetchRecordTabs()]);
      if (id === requestId.current) { setRecords(recordItems); setTabs(tabItems); setLoadedQuery(query.trim()); }
    } catch (reason) {
      if (id === requestId.current) setLoadError(reason instanceof Error ? reason.message : '资料加载失败');
    } finally { if (id === requestId.current) setLoading(false); }
  }, [query, range.from, range.to]);
  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 250 : 0);
    return () => { window.clearTimeout(timer); requestId.current += 1; };
  }, [load, query]);
  useEffect(() => {
    if (!isNewTabModalOpen) return;
    const frame = window.requestAnimationFrame(() => newTabInputRef.current?.focus());
    return () => window.cancelAnimationFrame(frame);
  }, [isNewTabModalOpen]);

  const canShowRecords = loadedQuery === query.trim();
  const activeTab = tabs.find((tab) => tab.id === activeTabId);
  const visibleRecords = activeTabId === 'all' ? records : records.filter((entry) => entry.tabId === activeTabId);
  const startOfWeek = formatDate(mondayOf(today));
  const weekRecords = records.filter((entry) => entry.recordDate >= startOfWeek && entry.recordDate <= todayKey);
  const weekReviews = records.filter((entry) => entry.type === 'weekly_review' && entry.recordDate >= startOfWeek && entry.recordDate <= todayKey);

  const save = async (input: SaveRecordInput) => {
    if (!input.tabId) throw new Error('请选择这篇资料所属的 Tab');
    setSaving(true);
    try {
      if (editing?.entry) await updateRecord(editing.entry.id, editing.entry.version, input); else await createRecord(input);
      setEditing(null); await load(); showMessage('success', '资料已保存');
    } catch (reason) { showMessage('error', reason instanceof Error ? reason.message : '保存失败'); throw reason; }
    finally { setSaving(false); }
  };

  const beginCreate = () => setEditing({ tabId: activeTab?.id, type: typeForTab(activeTab) });
  const remove = async (entry: RecordEntry) => {
    if (confirmDeleteId !== entry.id) { setConfirmDeleteId(entry.id); return; }
    setDeletingId(entry.id);
    try { await trashRecord(entry.id, entry.version); setConfirmDeleteId(null); await load(); showMessage('success', '资料已移入回收站'); }
    catch (reason) { showMessage('error', reason instanceof Error ? reason.message : '删除失败'); }
    finally { setDeletingId(null); }
  };

  const clearTrash = async () => {
    if (clearingTrash) return;
    if (!confirmClearTrash) { setConfirmClearTrash(true); return; }
    setClearingTrash(true);
    try {
      const count = await clearRecordTrash();
      setTrashEntries([]);
      showMessage('success', count ? `已永久删除 ${count} 条资料` : '回收站已经是空的');
    } catch (reason) {
      showMessage('error', reason instanceof Error ? reason.message : '清空回收站失败');
    } finally {
      setClearingTrash(false);
      setConfirmClearTrash(false);
    }
  };

  const closeNewTabModal = () => {
    if (creatingTab) return;
    setIsNewTabModalOpen(false);
    setNewTabName('');
  };

  const submitNewTab = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const name = newTabName.trim();
    if (!name || creatingTab) return;
    setCreatingTab(true);
    try {
      const tab = await createRecordTab(name);
      setIsNewTabModalOpen(false);
      setNewTabName('');
      await load();
      setActiveTabId(tab.id);
      showMessage('success', 'Tab 已创建');
    } catch (reason) {
      showMessage('error', reason instanceof Error ? reason.message : 'Tab 创建失败');
    } finally {
      setCreatingTab(false);
    }
  };

  const persistTabOrder = async (nextTabs: RecordTab[], previousTabs: RecordTab[]) => {
    setTabs(nextTabs);
    setReorderingTabs(true);
    try {
      setTabs(await reorderRecordTabs(nextTabs.map((tab) => tab.id)));
    } catch (reason) {
      setTabs(previousTabs);
      showMessage('error', reason instanceof Error ? reason.message : 'Tab 排序保存失败');
    } finally {
      setReorderingTabs(false);
    }
  };

  const moveTab = (tabId: string, targetIndex: number) => {
    if (reorderingTabs) return;
    const sourceIndex = tabs.findIndex((tab) => tab.id === tabId);
    if (sourceIndex < 0 || targetIndex < 0 || targetIndex >= tabs.length || sourceIndex === targetIndex) return;
    const nextTabs = [...tabs];
    const [movedTab] = nextTabs.splice(sourceIndex, 1);
    nextTabs.splice(targetIndex, 0, movedTab);
    void persistTabOrder(nextTabs, tabs);
  };

  const resetTabPointerDrag = () => {
    tabPointerDragRef.current = null;
    setDraggedTabId(null);
    setDragOverTabId(null);
  };

  const tabIdAtPoint = (clientX: number, clientY: number) => {
    const target = document.elementFromPoint(clientX, clientY) as HTMLElement | null;
    return target?.closest<HTMLElement>('[data-record-tab-id]')?.dataset.recordTabId ?? null;
  };

  const handleTabPointerDown = (event: ReactPointerEvent<HTMLButtonElement>, tabId: string) => {
    if (event.button !== 0 || reorderingTabs) return;
    tabPointerDragRef.current = {
      tabId,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      dragging: false,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const handleTabPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = tabPointerDragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    if (!drag.dragging && Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < 4) return;
    drag.dragging = true;
    setDraggedTabId(drag.tabId);
    setDragOverTabId(tabIdAtPoint(event.clientX, event.clientY));
    event.preventDefault();
  };

  const handleTabPointerUp = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = tabPointerDragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    if (drag.dragging) {
      const targetId = tabIdAtPoint(event.clientX, event.clientY);
      if (targetId) moveTab(drag.tabId, tabs.findIndex((tab) => tab.id === targetId));
      suppressTabClickRef.current = true;
      window.setTimeout(() => { suppressTabClickRef.current = false; }, 0);
    }
    resetTabPointerDrag();
  };

  const handleTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, tabId: string) => {
    const platformModifierPressed = IS_MAC ? event.metaKey && !event.ctrlKey : event.ctrlKey && !event.metaKey;
    if (!platformModifierPressed || event.altKey || event.shiftKey || (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight')) return;
    event.preventDefault();
    const currentIndex = tabs.findIndex((tab) => tab.id === tabId);
    moveTab(tabId, currentIndex + (event.key === 'ArrowLeft' ? -1 : 1));
  };

  if (editing && loadedQuery === null && tabs.length === 0) return <main className={styles.workspace}><div className={styles.editorLoading}><h1>准备资料编辑器</h1>
    {loading ? <Skeleton variant="document" label="正在读取资料分类…" /> : <div className={styles.loadError} role="alert"><span>{loadError}</span><Button onClick={() => void load()}>重新加载资料</Button><Button variant="ghost" onClick={() => setEditing(null)}>返回资料</Button></div>}
  </div></main>;

  if (editing) return <RecordEditor date={editing.entry?.recordDate ?? todayKey} entry={editing.entry}
    initialType={editing.entry?.type ?? editing.type} initialTabId={editing.entry?.tabId ?? editing.tabId ?? (editing.type === 'journal' ? tabs.find((tab) => tab.systemKey === 'journal')?.id : undefined)}
    tabs={tabs} saving={saving} onSave={save} onBack={() => setEditing(null)} />;

  return <main className={styles.workspace}>
    <TopBar title="资料" subtitle="个人资料库" icon={<FileTextIcon size={16} />} search={
      <div className={styles.search}><MagnifyingGlassIcon size={15} /><input value={query} onChange={(event) => { setLoading(true); setLoadError(null); setQuery(event.target.value); }} aria-label="搜索资料" placeholder="搜索标题或正文…" /></div>
    } actions={<>
        <TopBarAction iconOnly aria-label="导出备份" onClick={() => void exportRecordBackup()} title="导出备份"><DownloadSimpleIcon size={14} /></TopBarAction>
        <TopBarAction iconOnly aria-label="导入备份" title="导入备份" onClick={() => importInputRef.current?.click()}><UploadSimpleIcon size={14} /></TopBarAction><input ref={importInputRef} hidden type="file" aria-label="导入备份" accept=".zip,application/zip" onChange={(event) => {
          const file = event.target.files?.[0]; if (!file) return;
          setPendingImportFile(file);
          event.target.value = '';
        }} />
        <TopBarAction iconOnly aria-label="回收站" onClick={() => void fetchRecordTrash().then(setTrashEntries)} title="回收站"><TrashIcon size={14} /></TopBarAction>
        <TopBarAction variant="primary" onClick={beginCreate} icon={<PlusIcon size={15} weight="bold" />}>新增资料</TopBarAction>
    </>} />
    <div className={styles.dashboard}>
      <header className={styles.pageHeading}><div><span className={styles.eyebrow}>个人资料库</span><h1>资料</h1><p>捕捉想法，沉淀值得留下的内容。</p></div><span className={styles.todayLabel}>{todayKey.replaceAll('-', ' / ')}</span></header>
      <section className={styles.summary} aria-label="本周资料概况" aria-busy={loading}>
        {!canShowRecords ? <div className={styles.summaryLoading} role={loading ? 'status' : undefined} aria-label={loading ? '正在读取资料概况…' : undefined}>
          {loading ? <><Skeleton variant="text" decorative /><Skeleton variant="text" decorative /><Skeleton variant="text" decorative /></> : <span>资料概况暂不可用</span>}
        </div> : <>
        <div><strong>{weekRecords.length}</strong><span>本周新增资料</span></div>
        <div className={weekReviews.length ? styles.reviewComplete : undefined}><strong>{weekReviews.length ? <CheckIcon size={20} weight="bold" /> : '—'}</strong><span>{weekReviews.length ? `已完成 ${weekReviews.length} 次复盘` : '本周尚未复盘'}</span></div>
        {editingSummaryCopy ? <input className={styles.summaryCopyInput} value={summaryDraft} autoFocus maxLength={120}
          aria-label="学习提示文案" onChange={(event) => setSummaryDraft(event.target.value)} onBlur={() => {
            const nextCopy = summaryDraft.trim() || DEFAULT_SUMMARY_COPY;
            setSummaryCopy(nextCopy); setSummaryDraft(nextCopy); writePreference(SUMMARY_COPY_KEY, nextCopy); setEditingSummaryCopy(false);
          }} onKeyDown={(event) => { if (event.key === 'Enter') event.currentTarget.blur(); if (event.key === 'Escape') { setSummaryDraft(summaryCopy); setEditingSummaryCopy(false); } }} />
          : <p className={styles.summaryCopy} tabIndex={0} role="button" aria-label="学习提示文案，双击编辑" title="双击编辑" onDoubleClick={() => setEditingSummaryCopy(true)}
            onKeyDown={(event) => { if (event.key === 'Enter' || event.key === 'F2') setEditingSummaryCopy(true); }}>{summaryCopy}</p>}
        </>}
      </section>

      <section className={styles.library}>
        <div className={styles.libraryHeader}><div><h2>{activeTab?.name ?? '全部资料'}</h2><span>{canShowRecords ? `${visibleRecords.length} 篇` : loading ? '正在读取…' : '— 篇'}</span>{loading && canShowRecords && <span role="status">正在更新资料…</span>}</div>
          {activeTab && !activeTab.systemKey && <button className={styles.deleteTabButton} onClick={() => setDeleteTabTarget(activeTab)}>删除 Tab</button>}
        </div>
        <nav className={styles.tabs} aria-label="资料分类">
          <button aria-pressed={activeTabId === 'all'} className={activeTabId === 'all' ? styles.activeTab : ''} onClick={() => setActiveTabId('all')}>全部</button>
          {tabs.map((tab) => <button key={tab.id} aria-pressed={activeTabId === tab.id} data-record-tab-id={tab.id}
            className={`${styles.tabButton} ${activeTabId === tab.id ? styles.activeTab : ''} ${draggedTabId === tab.id ? styles.draggingTab : ''} ${dragOverTabId === tab.id && draggedTabId !== tab.id ? styles.dragTarget : ''}`}
            aria-label={`${tab.name}，可拖拽排序`} title={`拖拽排序；也可按 ${TAB_SHORTCUT_LABEL} 调整`}
            onClick={() => { if (!suppressTabClickRef.current) setActiveTabId(tab.id); }} onKeyDown={(event) => handleTabKeyDown(event, tab.id)}
            onPointerDown={(event) => handleTabPointerDown(event, tab.id)} onPointerMove={handleTabPointerMove}
            onPointerUp={handleTabPointerUp} onPointerCancel={resetTabPointerDrag}>{tab.name}</button>)}
          <button className={styles.addTab} onClick={() => setIsNewTabModalOpen(true)}><PlusIcon size={13} weight="bold" />新建分类</button>
        </nav>

        {loadError && <div className={styles.loadError} role="alert"><span>{loadError}{canShowRecords && '，当前显示上次读取的资料。'}</span><Button variant="outline" size="sm" onClick={() => void load()}>重新加载资料</Button></div>}
        <div key={activeTabId} className={styles.recordList} aria-busy={loading}>{loading && !canShowRecords ? <Skeleton variant="document" label="正在整理资料列表…" /> : !canShowRecords ? null : visibleRecords.length ? visibleRecords.map((entry) => <article key={entry.id} className={styles.recordRow}>
          <div className={styles.recordDate}><strong>{entry.recordDate.slice(8)}</strong><span>{entry.recordDate.slice(5, 7)}月</span></div>
          <div className={styles.recordContent}><div><button className={styles.recordOpen} onClick={() => setEditing({ entry, type: entry.type })}>{displayTitle(entry)}</button>{entry.pinned && <span>置顶</span>}</div><p>{entry.contentText || '暂无正文'}</p>
            <footer><span className={styles.typeBadge} data-type={entry.type}>{TYPE_LABELS[entry.type]}</span>{entry.tags.map((tag) => <span key={tag}>#{tag}</span>)}</footer></div>
          <div className={styles.rowActions}>
            <button className={entry.favorite ? styles.favorite : undefined} aria-pressed={entry.favorite} onClick={async (event) => { event.stopPropagation(); try { await updateRecordFlags(entry.id, entry.version, { favorite: !entry.favorite }); await load(); } catch (reason) { showMessage('error', reason instanceof Error ? reason.message : '收藏失败'); } }} aria-label={entry.favorite ? '取消收藏' : '收藏'}><HeartIcon size={14} weight={entry.favorite ? 'fill' : 'regular'} /></button>
            <button aria-label={confirmDeleteId === entry.id ? '确认删除资料' : '删除资料'} className={confirmDeleteId === entry.id ? styles.confirmDelete : ''} disabled={deletingId === entry.id} onClick={(event) => { event.stopPropagation(); void remove(entry); }}>{confirmDeleteId === entry.id ? (deletingId === entry.id ? '删除中…' : '确认删除') : <TrashIcon size={14} />}</button>
          </div>
        </article>) : <div className={styles.empty}><FileTextIcon size={20} /><strong>{query.trim() ? '没有找到匹配的资料' : '从第一篇记录开始'}</strong><span>{query.trim() ? '试试其他关键词，或清空搜索查看全部内容。' : '一个想法、一段心得，都值得留下。'}</span><Button size="sm" onClick={query.trim() ? () => setQuery('') : beginCreate}>{query.trim() ? '清空搜索' : '新增第一篇资料'}</Button></div>}</div>
      </section>
    </div>

    {trashEntries && <div className={styles.trashPage}><div className={styles.trashHeader}><div><h2>回收站</h2><span>{trashEntries.length} 条资料</span></div><div>{trashEntries.length > 0 && <button type="button" className={confirmClearTrash ? styles.confirmClear : ''} disabled={clearingTrash} onClick={() => void clearTrash()}>{clearingTrash ? '清空中…' : confirmClearTrash ? '确认清空' : '清空'}</button>}<button type="button" disabled={clearingTrash} onClick={() => { setConfirmClearTrash(false); setTrashEntries(null); }} aria-label="关闭回收站"><XIcon size={16} /></button></div></div>
      <div className={styles.trashList}>{trashEntries.length ? trashEntries.map((entry) => <article key={entry.id}><div><strong>{displayTitle(entry)}</strong><span>{entry.recordDate}</span></div><button onClick={async () => { await restoreRecord(entry.id, entry.version); setTrashEntries(await fetchRecordTrash()); await load(); }}><ArrowCounterClockwiseIcon size={14} />恢复</button></article>) : <div className={styles.empty}>回收站是空的</div>}</div></div>}
    <Modal open={isNewTabModalOpen} title="新建 Tab" onClose={closeNewTabModal} width={420} centered>
      <form className={styles.newTabDialog} onSubmit={submitNewTab}>
        <label htmlFor="new-record-tab">Tab 名称</label>
        <TextInput ref={newTabInputRef} id="new-record-tab" value={newTabName} onChange={(event) => setNewTabName(event.target.value)}
          placeholder="例如：项目复盘" maxLength={20} required disabled={creatingTab} />
        <span className={styles.inputHint}>最多 20 个字符</span>
        <div className={styles.dialogActions}>
          <Button type="button" variant="outline" onClick={closeNewTabModal} disabled={creatingTab}>取消</Button>
          <Button type="submit" variant="primary" disabled={!newTabName.trim() || creatingTab}>{creatingTab ? '创建中…' : '创建'}</Button>
        </div>
      </form>
    </Modal>

    <ConfirmDialog
      open={Boolean(pendingImportFile)}
      title="确认恢复资料备份"
      tone="warning"
      confirmText="确认覆盖导入"
      confirmVariant="primary"
      busy={importingBackup}
      description="导入将使用备份文件内容合并与替换当前资料库，建议提前导出备份以防丢失。确定要继续导入吗？"
      onClose={() => { if (!importingBackup) setPendingImportFile(null); }}
      onConfirm={() => void handleConfirmImport()}
    />

    <ConfirmDialog
      open={Boolean(deleteTabTarget)}
      title="确认删除分类"
      tone="danger"
      confirmText="删除分类"
      confirmVariant="danger"
      busy={deletingTab}
      description={`确定要删除 Tab“${deleteTabTarget?.name}”吗？分类下的全部资料仍会保留在“全部”中，不会被丢弃。`}
      onClose={() => { if (!deletingTab) setDeleteTabTarget(null); }}
      onConfirm={() => void handleConfirmDeleteTab()}
    />
  </main>;
}
