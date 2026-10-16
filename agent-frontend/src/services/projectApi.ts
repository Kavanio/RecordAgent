import type { Project, ProjectDto } from '../types/chat';
import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';

function mapProject(dto: ProjectDto): Project {
  return {
    id: dto.id,
    name: dto.name,
    path: dto.rootPath,
    createdAt: new Date(dto.importedAt).getTime() || Date.now(),
    availability: dto.availability,
  };
}

/** 获取当前设备已登记的本地项目。 */
export async function fetchProjects(): Promise<Project[]> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/projects`);
  const json: ApiResponse<ProjectDto[]> = await response.json();
  if (!response.ok || json.code !== 200 || !Array.isArray(json.data)) {
    throw new Error(json.message || '读取项目列表失败');
  }
  return json.data.map(mapProject);
}

/** 登记一个本地项目目录，不复制或修改目录内容。 */
export async function importProject(name: string, rootPath: string): Promise<Project> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/projects/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, rootPath }),
  });
  const json: ApiResponse<ProjectDto> = await response.json();
  if (!response.ok || json.code !== 200 || !json.data) {
    throw new Error(json.message || '导入项目失败');
  }
  return mapProject(json.data);
}

/** 从应用解除项目登记，磁盘目录不会被删除。 */
export async function removeProject(projectId: string): Promise<void> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/projects/${encodeURIComponent(projectId)}`, {
    method: 'DELETE',
  });
  const json: ApiResponse<null> = await response.json();
  if (!response.ok || json.code !== 200) {
    throw new Error(json.message || '移除项目失败');
  }
}
