import { useCallback, useEffect, useState } from 'react';
import { Button } from '../common/Button';
import { LeverSwitch } from '../common/LeverSwitch';
import { useMessage } from '../common/Message';
import { TextInput } from '../common/TextInput';
import { SettingsGroup, SettingsRow } from './SettingsGroup';
import {
  checkDesktopControlEnvironment,
  fetchDesktopControlStatus,
  requestDesktopControlPermission,
  updateDesktopControlConfig,
  type DesktopControlCheckResult,
  type DesktopControlStatus,
} from '../../services/desktopControlService';
import styles from './DesktopControlSettingsSection.module.css';

function formatHostOs(hostOs?: string): string {
  switch (hostOs?.toLowerCase()) {
    case 'macos': return 'macOS';
    case 'windows': return 'Windows';
    case 'linux': return 'Linux';
    default: return hostOs || '未知系统';
  }
}

/** 电脑桌面操控（agent-desktop）设置分组，读写后端 config.json 中的 desktopControl 节点。 */
export function DesktopControlSettingsSection() {
  const [status, setStatus] = useState<DesktopControlStatus | null>(null);
  const [cliPathDraft, setCliPathDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [requestingPermission, setRequestingPermission] = useState(false);
  const [checkResult, setCheckResult] = useState<DesktopControlCheckResult | null>(null);
  const { showMessage } = useMessage();

  const loadConfig = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const data = await fetchDesktopControlStatus();
      setStatus(data);
      setCliPathDraft(data.cliPath ?? '');
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : '读取电脑操控配置失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadConfig();
  }, [loadConfig]);

  const handleToggle = async (nextEnabled: boolean) => {
    if (!status || saving) return;
    setSaving(true);
    try {
      const updated = await updateDesktopControlConfig({ enabled: nextEnabled });
      setStatus(updated);
      showMessage('success', nextEnabled ? '已开启电脑操控' : '已关闭电脑操控');
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '更新电脑操控开关失败');
    } finally {
      setSaving(false);
    }
  };

  const handleSaveCliPath = async () => {
    if (!status || saving) return;
    setSaving(true);
    try {
      const updated = await updateDesktopControlConfig({
        enabled: status.enabled,
        cliPath: cliPathDraft.trim(),
      });
      setStatus(updated);
      setCliPathDraft(updated.cliPath ?? '');
      setCheckResult(null);
      showMessage('success', 'CLI 路径已保存到 config.json');
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '保存 CLI 路径失败');
    } finally {
      setSaving(false);
    }
  };

  const handleCheck = async () => {
    if (checking) return;
    setChecking(true);
    try {
      const result = await checkDesktopControlEnvironment();
      setCheckResult(result);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '环境检测失败');
    } finally {
      setChecking(false);
    }
  };

  const handleRequestPermission = async () => {
    if (requestingPermission) return;
    setRequestingPermission(true);
    try {
      const result = await requestDesktopControlPermission();
      showMessage(result.triggered ? 'success' : 'error', result.message);
      if (result.accessibilityState === 'granted') {
        const refreshed = await checkDesktopControlEnvironment();
        setCheckResult(refreshed);
      }
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '拉起系统权限引导失败');
    } finally {
      setRequestingPermission(false);
    }
  };

  const pathDirty = status !== null && cliPathDraft.trim() !== (status.cliPath ?? '');
  const canRequestMacPermission = status?.hostOs?.toLowerCase() === 'macos'
    && Boolean(checkResult?.available)
    && checkResult?.accessibilityState !== 'granted';

  const badgeClass = !checkResult
    ? ''
    : checkResult.available && checkResult.accessibilityState === 'granted'
      ? styles.statusReady
      : checkResult.available
        ? styles.statusWarn
        : styles.statusError;

  const badgeText = !checkResult
    ? ''
    : checkResult.available && checkResult.accessibilityState === 'granted'
      ? '已就绪'
      : checkResult.available
        ? '需授予权限'
        : '未就绪';

  return (
    <SettingsGroup
      title="电脑操控"
      description="允许 Agent 通过 agent-desktop 查看并操作当前后端所在电脑的桌面应用。只读观察自动放行，所有点击、输入与窗口操作均需你审批确认。"
    >
      <SettingsRow
        label="启用电脑操控"
        description={
          loadError
            ? `读取配置失败：${loadError}`
            : loading
              ? '正在读取后端 config.json 中的电脑操控配置…'
              : `当前后端主机：${formatHostOs(status?.hostOs)}。开关保存在后端 ~/.butvan-agent/config.json，修改后立即生效。`
        }
        control={
          loadError ? (
            <Button variant="outline" onClick={() => void loadConfig()}>重试</Button>
          ) : (
            <LeverSwitch
              checked={status?.enabled ?? false}
              disabled={loading || saving || !status}
              onChange={(checked) => void handleToggle(checked)}
              label="启用电脑操控"
              showLabel={false}
            />
          )
        }
      />

      <SettingsRow
        label="运行环境检测"
        description="核验后端电脑是否已安装 agent-desktop 以及系统辅助功能权限状态。"
        control={
          <div className={styles.inlineActions}>
            {checkResult && (
              <span className={`${styles.statusBadge} ${badgeClass}`}>{badgeText}</span>
            )}
            {canRequestMacPermission && (
              <Button
                variant="primary"
                disabled={requestingPermission}
                onClick={() => void handleRequestPermission()}
              >
                {requestingPermission ? '拉起中…' : '去授权'}
              </Button>
            )}
            <Button variant="outline" disabled={loading || checking || Boolean(loadError)} onClick={() => void handleCheck()}>
              {checking ? '检测中…' : '检测环境'}
            </Button>
          </div>
        }
      />

      {checkResult && (
        <div className={styles.checkBanner} role="status">
          <div>{checkResult.message}</div>
          {checkResult.suggestion && (
            <p className={styles.checkSuggestion}>{checkResult.suggestion}</p>
          )}
        </div>
      )}

      <SettingsRow
        label="CLI 路径（可选）"
        labelFor="desktop-control-cli-path"
        description="留空时自动从后端系统 PATH 查找 agent-desktop；Windows 或自定义安装目录可填写完整可执行文件路径。"
        control={
          <div className={styles.pathControl}>
            <TextInput
              id="desktop-control-cli-path"
              className={styles.pathInput}
              value={cliPathDraft}
              disabled={loading || saving || !status}
              placeholder="默认自动查找 agent-desktop"
              onChange={(event) => setCliPathDraft(event.target.value)}
            />
            <Button
              variant="outline"
              disabled={loading || saving || !pathDirty}
              onClick={() => void handleSaveCliPath()}
            >
              保存路径
            </Button>
          </div>
        }
      />
    </SettingsGroup>
  );
}
