import { useEffect, useState } from 'react';
import type { ChatImageAttachment } from '../../types/chat';
import { fetchMaterialImage } from '../../services/materialApi';
import { saveChatImage } from '../../services/saveChatImage';
import { Button } from '../common/Button';
import { Modal } from '../common/Modal';
import { useMessage } from '../common/Message';
import styles from './ChatImageGallery.module.css';

interface LoadedImage {
  src: string;
  blob?: Blob;
}

function safeSource(url?: string) {
  if (!url) return undefined;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' && !parsed.username && !parsed.password ? parsed.href : undefined;
  } catch {
    return undefined;
  }
}

function ChatImageTile({ image, label }: { image: ChatImageAttachment; label: string }) {
  const { showMessage } = useMessage();
  const { fileId, dataUrl } = image;
  const [loaded, setLoaded] = useState<LoadedImage>();
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [preview, setPreview] = useState(false);
  const [zoomed, setZoomed] = useState(false);
  const [saving, setSaving] = useState(false);
  const src = fileId ? loaded?.src : dataUrl;
  const source = safeSource(image.sourceUrl);

  useEffect(() => {
    if (!fileId) return;
    const controller = new AbortController();
    let url: string | undefined;
    void fetchMaterialImage(fileId, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return;
        url = URL.createObjectURL(blob);
        setLoaded({ src: url, blob });
      })
      .catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => {
      controller.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [fileId, retry]);

  async function save() {
    if (!src) return;
    setSaving(true);
    try {
      if (await saveChatImage(src, loaded?.blob)) showMessage('success', '图片已保存');
    } catch {
      showMessage('error', '图片保存失败，请重试');
    } finally {
      setSaving(false);
    }
  }

  if (failed) {
    return fileId ? (
      <Button size="sm" variant="ghost" onClick={() => { setFailed(false); setRetry((value) => value + 1); }}>
        图片无法打开，重试
      </Button>
    ) : <span className={styles.missingImage}>图片无法打开</span>;
  }
  if (!src) return <span className={styles.missingImage} role="status">正在加载图片…</span>;

  return (
    <>
      <button className={styles.imageButton} type="button" aria-label={`预览 ${label}`} onClick={() => setPreview(true)}>
        <img className={styles.image} src={src} alt={label} loading="lazy" onError={() => setFailed(true)} />
      </button>
      {preview && (
        <Modal open title={label} onClose={() => { setPreview(false); setZoomed(false); }} width={900} centered
          footer={(
            <div className={styles.previewActions}>
              {source && <a href={source} target="_blank" rel="noreferrer noopener">打开原图</a>}
              <Button size="sm" variant="ghost" onClick={() => setZoomed((value) => !value)}>{zoomed ? '适应窗口' : '原始尺寸'}</Button>
              <Button size="sm" disabled={saving} onClick={() => void save()}>保存图片</Button>
            </div>
          )}
        >
          <div className={styles.previewWrap}>
            <img className={`${styles.previewImage} ${zoomed ? styles.fullZoom : styles.normalZoom}`}
              src={src} alt={label} onClick={() => setZoomed((value) => !value)} />
          </div>
        </Modal>
      )}
    </>
  );
}

/** 用户和助手消息共用图片预览、放大和保存。 */
export function ChatImageGallery({ images }: { images?: ChatImageAttachment[] }) {
  if (!images?.length) return null;
  return (
    <div className={styles.images}>
      {images.map((image, index) => (
        <ChatImageTile key={image.fileId ?? index} image={image} label={image.name || `图片 ${index + 1}`} />
      ))}
    </div>
  );
}
