import { readPreference, writePreference } from '../../services/desktopPreferences';
import type { SlashCommandDefinition, SlashCommandName } from './slashCommands.ts';

const STORAGE_KEY = 'butvan.slashCommandPreferences';
export const SLASH_COMMAND_PREFERENCES_EVENT = 'butvan:slash-command-preferences-changed';

export interface SlashCommandOverride {
  enabled?: boolean;
  description?: string;
  usage?: string;
}

export type SlashCommandPreferences = Partial<Record<SlashCommandName, SlashCommandOverride>>;

export interface SlashCommandState {
  command: SlashCommandDefinition;
  enabled: boolean;
  customized: boolean;
}

function sanitizeOverride(value: unknown): SlashCommandOverride | null {
  if (!value || typeof value !== 'object') return null;
  const candidate = value as SlashCommandOverride;
  const override: SlashCommandOverride = {};
  if (typeof candidate.enabled === 'boolean') override.enabled = candidate.enabled;
  if (typeof candidate.description === 'string' && candidate.description.trim()) {
    override.description = candidate.description.trim();
  }
  if (typeof candidate.usage === 'string' && candidate.usage.trim()) {
    override.usage = candidate.usage.trim();
  }
  return Object.keys(override).length > 0 ? override : null;
}

/** 读取本机命令偏好；损坏字段会被忽略并回退到内置 Registry。 */
export function getSlashCommandPreferences(): SlashCommandPreferences {
  if (typeof localStorage === 'undefined') return {};
  try {
    const stored = JSON.parse(readPreference(STORAGE_KEY) ?? '{}') as Record<string, unknown>;
    return Object.fromEntries(
      Object.entries(stored)
        .map(([name, value]) => [name, sanitizeOverride(value)] as const)
        .filter((entry): entry is readonly [string, SlashCommandOverride] => entry[1] !== null),
    ) as SlashCommandPreferences;
  } catch {
    return {};
  }
}

function saveSlashCommandPreferences(preferences: SlashCommandPreferences): SlashCommandPreferences {
  if (typeof localStorage !== 'undefined') {
    writePreference(STORAGE_KEY, JSON.stringify(preferences));
  }
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent<SlashCommandPreferences>(
      SLASH_COMMAND_PREFERENCES_EVENT,
      { detail: preferences },
    ));
  }
  return preferences;
}

export function setSlashCommandEnabled(name: SlashCommandName, enabled: boolean): SlashCommandPreferences {
  const current = getSlashCommandPreferences();
  return saveSlashCommandPreferences({
    ...current,
    [name]: { ...current[name], enabled },
  });
}

export function setSlashCommandDetails(
  name: SlashCommandName,
  details: Pick<SlashCommandDefinition, 'description' | 'usage'>,
): SlashCommandPreferences {
  const current = getSlashCommandPreferences();
  return saveSlashCommandPreferences({
    ...current,
    [name]: {
      ...current[name],
      description: details.description.trim(),
      usage: details.usage.trim(),
    },
  });
}

export function resetSlashCommandDetails(name: SlashCommandName): SlashCommandPreferences {
  const current = getSlashCommandPreferences();
  const enabled = current[name]?.enabled;
  const next = { ...current };
  if (enabled === undefined) delete next[name];
  else next[name] = { enabled };
  return saveSlashCommandPreferences(next);
}

/** 将内置定义与用户偏好合并，供设置页和运行时共享同一份生效状态。 */
export function applySlashCommandPreferences(
  commands: readonly SlashCommandDefinition[],
  preferences: SlashCommandPreferences,
): SlashCommandState[] {
  return commands.map((command) => {
    const override = preferences[command.name];
    return {
      command: {
        ...command,
        description: override?.description ?? command.description,
        usage: override?.usage ?? command.usage,
      },
      enabled: override?.enabled !== false,
      customized: Boolean(
        override
        && (override.description !== undefined || override.usage !== undefined),
      ),
    };
  });
}

export function subscribeSlashCommandPreferences(listener: () => void): () => void {
  if (typeof window === 'undefined') return () => undefined;
  const onPreferenceChange = () => listener();
  const onStorage = (event: StorageEvent) => {
    if (event.key === STORAGE_KEY) listener();
  };
  window.addEventListener(SLASH_COMMAND_PREFERENCES_EVENT, onPreferenceChange);
  window.addEventListener('storage', onStorage);
  return () => {
    window.removeEventListener(SLASH_COMMAND_PREFERENCES_EVENT, onPreferenceChange);
    window.removeEventListener('storage', onStorage);
  };
}
