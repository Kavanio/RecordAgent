import { apiFetch, getApiBaseUrl } from './api';
import type { FileTreeNode } from '../types/team';

function isFileTreeNode(value: unknown): value is FileTreeNode {
  if (typeof value !== 'object' || value === null) return false;
  const node = value as Record<string, unknown>;
  return (
    typeof node.name === 'string' &&
    typeof node.path === 'string' &&
    (node.type === 'file' || node.type === 'dir') &&
    (node.children === undefined || Array.isArray(node.children))
  );
}

/** 读取项目根目录下的文件树（默认深度 3）。 */
export async function fetchProjectFileTree(projectId: string, depth = 3): Promise<FileTreeNode[]> {
  const query = new URLSearchParams({ projectId, depth: String(depth) });
  const response = await apiFetch(`${getApiBaseUrl()}/api/files/tree?${query.toString()}`);
  if (!response.ok) {
    throw new Error(`读取项目文件失败：HTTP ${response.status}`);
  }

  const json: unknown = await response.json();
  if (
    typeof json !== 'object' ||
    json === null ||
    !('code' in json) ||
    json.code !== 200 ||
    !('data' in json) ||
    !Array.isArray(json.data) ||
    !json.data.every(isFileTreeNode)
  ) {
    const message = typeof json === 'object' && json !== null && 'message' in json
      ? String(json.message)
      : '';
    throw new Error(message || '读取项目文件失败：响应格式不正确');
  }
  return json.data as FileTreeNode[];
}
