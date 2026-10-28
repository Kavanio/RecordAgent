/** 聊天校验失败可能返回 HTTP 200 的 Result JSON，而不是 SSE；优先展示服务端中文原因。 */
export async function assertChatStreamResponse(response: Response): Promise<void> {
  const json = response.headers?.get('content-type')?.includes('application/json');
  if (response.ok && !json) return;
  let message: string | undefined;
  if (json) {
    try {
      const body: unknown = await response.json();
      if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
        message = body.message;
      }
    } catch {
      // 代理可能返回损坏 JSON，只展示状态，不泄露原始响应。
    }
  }
  throw new Error(message || (response.status === 413
    ? '聊天请求过大，每轮图片总大小不能超过 12 MiB'
    : `聊天请求失败（HTTP ${response.status}）`));
}
