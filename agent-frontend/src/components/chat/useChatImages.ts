import { useLayoutEffect, useRef, useState } from 'react';
import type { ChatImageAttachment } from '../../types/chat';
import { validateChatImageFile } from '../../features/chat-images/chatImages';
import { readChatImageFile } from '../../services/chatImageFiles';
import { useMessage } from '../common/Message';

type Draft = { sessionId: string; images: ChatImageAttachment[]; pending: number; active: boolean };
const emptyDraft = (sessionId: string): Draft => ({ sessionId, images: [], pending: 0, active: true });

/** 会话隔离的图片草稿：串行追加避免连续粘贴覆盖；切会话、清空、卸载后丢弃迟到读取。 */
export function useChatImages(sessionId: string) {
  const { showMessage } = useMessage();
  const draft = useRef<Draft>(emptyDraft(sessionId));
  const queue = useRef<Promise<void>>(Promise.resolve());
  const [view, setView] = useState({ sessionId, images: [] as ChatImageAttachment[], reading: false });

  const publish = (scope: Draft) => {
    if (scope.active && draft.current === scope) {
      setView({ sessionId: scope.sessionId, images: scope.images, reading: scope.pending > 0 });
    }
  };
  const clear = () => {
    draft.current.active = false;
    draft.current = emptyDraft(sessionId);
    queue.current = Promise.resolve();
    publish(draft.current);
  };

  useLayoutEffect(() => {
    clear();
    return () => { draft.current.active = false; };
    // 草稿只随会话身份重置，普通重渲染不丢附件。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  const addFiles = (files: File[]): Promise<void> => {
    const scope = draft.current;
    scope.pending += 1;
    publish(scope);
    const operation = queue.current.then(async () => {
      for (const file of files) {
        if (!scope.active || draft.current !== scope) return;
        try {
          const type = validateChatImageFile(file, scope.images);
          const image = await readChatImageFile(file, type);
          if (!scope.active || draft.current !== scope) return;
          // 删除附件与读取可以交错；按最新草稿重新校验预算后追加。
          validateChatImageFile(file, scope.images);
          scope.images = [...scope.images, image];
          publish(scope);
        } catch (error) {
          if (scope.active && draft.current === scope) {
            showMessage('error', error instanceof Error ? error.message : '图片读取失败，请重试。');
          }
        }
      }
    }).finally(() => { scope.pending -= 1; publish(scope); });
    queue.current = operation;
    return operation;
  };

  const remove = (index: number) => {
    const scope = draft.current;
    scope.images = scope.images.filter((_, current) => current !== index);
    publish(scope);
  };

  return {
    images: view.sessionId === sessionId ? view.images : [],
    reading: view.sessionId === sessionId && view.reading,
    addFiles, remove, clear,
  };
}
