import { useCallback, useEffect, useState } from 'react';
import { Pencil } from 'lucide-react';
import {
  fetchDailyContextConfig,
  fetchQWeatherConsoleSummary,
  resolveDailyContextLocation,
  saveDailyContextConfig,
  type DailyContextConfig,
  type QWeatherConsoleSummary,
} from '../../services/dailyContextApi';
import { detectCurrentCoordinates, DeviceLocationError } from '../../services/deviceLocation';
import { openLocationPrivacySettings } from '../../services/systemSettings';
import type { ChatTopBarPreferences } from '../../types/preferences';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { LeverSwitch } from '../common/LeverSwitch';
import {
  DailyContextConfigModal,
  type DailyContextFormState,
  type DailyContextProvider,
} from './DailyContextConfigModal';
import { QWeatherConsolePanel } from './QWeatherConsolePanel';
import { SettingsPageLayout } from './SettingsPageLayout';
import styles from './DailyContextSettingsPage.module.css';

interface DailyContextSettingsPageProps {
  chatTopBar: ChatTopBarPreferences;
  onChatTopBarChange: (key: keyof ChatTopBarPreferences, visible: boolean) => void;
}

const EMPTY_FORM: DailyContextFormState = {
  qweatherApiHost: '', qweatherApiKey: '', locationName: '', latitude: '', longitude: '', tianApiKey: '',
};

const QWEATHER_CONSOLE_PROJECT_URL = 'https://console.qweather.com/project';

/** 天气与节假日供应商设置；后端只返回密钥是否存在，不回传密钥正文。 */
export function DailyContextSettingsPage({
  chatTopBar,
  onChatTopBarChange,
}: DailyContextSettingsPageProps) {
  const { showMessage } = useMessage();
  const [config, setConfig] = useState<DailyContextConfig | null>(null);
  const [form, setForm] = useState<DailyContextFormState>(EMPTY_FORM);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [locating, setLocating] = useState(false);
  const [editingProvider, setEditingProvider] = useState<DailyContextProvider | null>(null);
  const [consoleSummary, setConsoleSummary] = useState<QWeatherConsoleSummary | null>(null);
  const [consoleLoading, setConsoleLoading] = useState(false);

  const loadConsoleSummary = useCallback(async (refresh: boolean) => {
    setConsoleLoading(true);
    try {
      const nextSummary = await fetchQWeatherConsoleSummary(refresh);
      setConsoleSummary(nextSummary);
      const permissionErrors = [nextSummary.financeError, nextSummary.usageError]
        .filter((message, index, errors): message is string => Boolean(message) && errors.indexOf(message) === index);
      if (permissionErrors.length > 0) {
        showMessage('error', permissionErrors.join('；'), {
          action: {
            label: '打开凭据权限设置',
            onClick: () => { window.open(QWEATHER_CONSOLE_PROJECT_URL, '_blank', 'noopener,noreferrer'); },
          },
        });
      }
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '和风控制台数据读取失败');
    } finally {
      setConsoleLoading(false);
    }
  }, [showMessage]);

  useEffect(() => {
    fetchDailyContextConfig()
      .then((loaded) => {
        setConfig(loaded);
        setForm({
          ...EMPTY_FORM,
          qweatherApiHost: loaded.qweatherApiHost,
          locationName: loaded.locationName,
          latitude: loaded.latitude?.toString() ?? '',
          longitude: loaded.longitude?.toString() ?? '',
        });
        if (loaded.qweatherApiKeyConfigured && loaded.qweatherApiHost) {
          void loadConsoleSummary(false);
        }
      })
      .catch((cause) => showMessage('error', cause instanceof Error ? cause.message : '配置读取失败'))
      .finally(() => setLoading(false));
  }, [loadConsoleSummary, showMessage]);

  const update = (key: keyof DailyContextFormState, value: string) => setForm((current) => ({ ...current, [key]: value }));

  const save = async () => {
    if (!editingProvider) return;
    const latitude = optionalNumber(form.latitude);
    const longitude = optionalNumber(form.longitude);
    if (editingProvider === 'weather' && (latitude === undefined || longitude === undefined)) {
      showMessage('error', '经纬度必须填写有效数字。');
      return;
    }
    setSaving(true);
    try {
      const saved = await saveDailyContextConfig({
        qweatherApiHost: form.qweatherApiHost,
        qweatherApiKey: form.qweatherApiKey,
        clearQweatherApiKey: false,
        locationName: form.locationName,
        latitude: latitude ?? config?.latitude ?? null,
        longitude: longitude ?? config?.longitude ?? null,
        tianApiKey: form.tianApiKey,
        clearTianApiKey: false,
      });
      setConfig(saved);
      setForm((current) => ({ ...current, qweatherApiKey: '', tianApiKey: '' }));
      setEditingProvider(null);
      showMessage('success', '天气与节假日配置已保存到本机。');
      if (saved.qweatherApiKeyConfigured && saved.qweatherApiHost) {
        void loadConsoleSummary(true);
      }
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '配置保存失败');
    } finally {
      setSaving(false);
    }
  };

  const locate = async () => {
    setLocating(true);
    try {
      const coordinates = await detectCurrentCoordinates();
      setForm((current) => ({
        ...current,
        locationName: '当前位置',
        latitude: String(coordinates.latitude),
        longitude: String(coordinates.longitude),
      }));

      try {
        const resolved = await resolveDailyContextLocation(coordinates.latitude, coordinates.longitude);
        setForm((current) => ({ ...current, locationName: resolved.locationName }));
        showMessage('success', `已识别为${resolved.locationName}，定位精度约 ${coordinates.accuracy} 米；请保存配置。`);
      } catch (cause) {
        showMessage('error', cause instanceof Error
          ? `${cause.message}；经纬度已填入，可以直接保存。`
          : '地点名称识别失败；经纬度已填入，可以直接保存。');
      }
    } catch (cause) {
      if (cause instanceof DeviceLocationError && cause.reason === 'permission-denied') {
        try {
          await openLocationPrivacySettings();
          showMessage('error', '定位权限未开启，已打开系统定位设置。授权后返回此页面，再次点击“识别当前位置”。', {
            action: { label: '重新打开系统定位设置', onClick: () => void openLocationSettings() },
            duration: 0,
          });
        } catch (settingsCause) {
          showMessage('error', settingsCause instanceof Error ? settingsCause.message : '无法打开系统定位设置');
        }
        return;
      }
      showMessage('error', cause instanceof Error ? cause.message : '自动定位失败');
    } finally {
      setLocating(false);
    }
  };

  const openLocationSettings = async () => {
    try {
      await openLocationPrivacySettings();
      showMessage('info', '已重新打开系统定位设置。授权后返回此页面，再次点击“识别当前位置”。', {
        action: { label: '重新打开系统定位设置', onClick: () => void openLocationSettings() },
        duration: 0,
      });
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '无法打开系统定位设置');
    }
  };

  const weatherConfigured = Boolean(config?.qweatherApiKeyConfigured && config.qweatherApiHost.trim());
  const holidayConfigured = Boolean(config?.tianApiKeyConfigured);

  const openConfigModal = (provider: DailyContextProvider) => {
    setForm({
      ...EMPTY_FORM,
      qweatherApiHost: config?.qweatherApiHost ?? '',
      locationName: config?.locationName ?? '',
      latitude: config?.latitude?.toString() ?? '',
      longitude: config?.longitude?.toString() ?? '',
    });
    setEditingProvider(provider);
  };

  return (
    <SettingsPageLayout
      title="天气与节假日"
      description="配置聊天顶栏使用的外部数据源，密钥仅保存在本机。"
      loading={loading}
      loadingLabel="正在读取配置…"
    >
      {!loading && (
        <div className={styles.content}>
          {weatherConfigured && (
            <QWeatherConsolePanel
              summary={consoleSummary}
              loading={consoleLoading}
              onRefresh={() => void loadConsoleSummary(true)}
            />
          )}

          <div className={styles.providerList}>
            <ProviderRow
              headingId="weather-settings-title"
              title="和风天气"
              description="使用专属 API Host 和 API KEY 获取当前天气。"
              configured={weatherConfigured}
              enabled={chatTopBar.showWeather}
              enabledLabel="在聊天顶栏显示天气"
              onConfigure={() => openConfigModal('weather')}
              onEnabledChange={(checked) => onChatTopBarChange('showWeather', checked)}
            />

            <ProviderRow
              headingId="holiday-settings-title"
              title="天聚数行节假日"
              description="识别法定节假日、双休日和调休上班。"
              configured={holidayConfigured}
              enabled={chatTopBar.showHoliday}
              enabledLabel="在聊天顶栏显示节假日"
              onConfigure={() => openConfigModal('holiday')}
              onEnabledChange={(checked) => onChatTopBarChange('showHoliday', checked)}
            />
          </div>
        </div>
      )}
      <DailyContextConfigModal
        open={editingProvider !== null}
        provider={editingProvider}
        form={form}
        weatherConfigured={weatherConfigured}
        holidayConfigured={holidayConfigured}
        saving={saving}
        locating={locating}
        onClose={() => { if (!saving) setEditingProvider(null); }}
        onChange={update}
        onLocate={() => void locate()}
        onSubmit={() => void save()}
      />
    </SettingsPageLayout>
  );
}

function ProviderRow({
  headingId,
  title,
  description,
  configured,
  enabled,
  enabledLabel,
  onConfigure,
  onEnabledChange,
}: {
  headingId: string;
  title: string;
  description: string;
  configured: boolean;
  enabled: boolean;
  enabledLabel: string;
  onConfigure: () => void;
  onEnabledChange: (enabled: boolean) => void;
}) {
  return (
    <article className={styles.providerRow}>
      <div className={styles.providerCopy}>
        <h2 id={headingId}>{title}</h2>
        <p>{description}</p>
        <div className={styles.statusList} aria-label={`${title}状态`}>
          <StatusPill active={configured}>{configured ? '已配置' : '未配置'}</StatusPill>
          <StatusPill active={enabled} tone="blue">{enabled ? '已开启' : '未开启'}</StatusPill>
        </div>
      </div>
      <div className={styles.providerActions}>
        <Button
          type="button"
          variant="outline"
          size="sm"
          icon={<Pencil size={13} />}
          onClick={onConfigure}
        >
          {configured ? '编辑配置' : '去配置'}
        </Button>
        <LeverSwitch
          checked={enabled}
          onChange={onEnabledChange}
          label={enabledLabel}
          showLabel={false}
        />
      </div>
    </article>
  );
}

function StatusPill({ children, active, tone = 'green' }: { children: string; active: boolean; tone?: 'green' | 'blue' }) {
  return <span className={`${styles.statusPill} ${active ? styles.statusActive : styles.statusInactive} ${tone === 'blue' ? styles.statusBlue : ''}`}>{children}</span>;
}

function optionalNumber(value: string): number | null | undefined {
  if (!value.trim()) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}
