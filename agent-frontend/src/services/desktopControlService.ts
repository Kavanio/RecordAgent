import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';

export interface DesktopControlStatus {
  enabled: boolean;
  cliPath: string;
  hostOs: string;
}

export interface DesktopControlCheckResult {
  available: boolean;
  version: string;
  platform: string;
  accessibilityState: string;
  screenRecordingState: string;
  message: string;
  suggestion: string;
}

export interface DesktopControlPermissionRequestResult {
  triggered: boolean;
  accessibilityState: string;
  guideState: string;
  message: string;
}

async function request<T>(path = '', init?: RequestInit, fallbackError = '电脑操控配置请求失败'): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/desktop-control${path}`, {
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
    ...init,
  });
  const json = await response.json() as ApiResponse<T>;
  if (!response.ok || json.code !== 200 || !json.data) {
    throw new Error(json.message || fallbackError);
  }
  return json.data;
}

/** 读取后端 config.json 中保存的电脑操控配置与主机平台。 */
export const fetchDesktopControlStatus = () =>
  request<DesktopControlStatus>('', undefined, '读取电脑操控配置失败');

/** 更新电脑操控启用开关与可选 CLI 路径；cliPath 省略时保留既有路径。 */
export const updateDesktopControlConfig = (payload: { enabled: boolean; cliPath?: string }) =>
  request<DesktopControlStatus>(
    '',
    { method: 'PUT', body: JSON.stringify(payload) },
    '保存电脑操控配置失败',
  );

/** 触发一次有界环境检测（agent-desktop status），核验安装与辅助功能权限。 */
export const checkDesktopControlEnvironment = () =>
  request<DesktopControlCheckResult>('/check', undefined, '检测电脑操控环境失败');

/** 在 macOS 后端宿主机上触发系统辅助功能权限弹窗与引导面板。 */
export const requestDesktopControlPermission = () =>
  request<DesktopControlPermissionRequestResult>(
    '/request-permission',
    { method: 'POST' },
    '拉起系统权限引导失败',
  );
