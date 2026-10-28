import React, { createContext, useContext, useState, useEffect, useRef } from 'react';
import type { ModelProvider, ModelItem, ModelProtocol, FullModelConfig, ProviderType } from '../types/model';
import { fetchFullModelConfig, saveFullModelConfig, testModelConnection } from '../services/api';

interface ModelInput {
  vendor: string;
  baseUrl: string;
  protocol?: ModelProtocol;
  apiKey: string;
  modelId: string;
  name: string;
  makeDefault?: boolean;
  supportsVision?: boolean;
}

type ListedModel = ModelItem & {
  providerName: string; providerType: string; baseUrl: string; apiKey: string; protocol?: ModelProtocol;
};

interface ModelContextType {
  providers: ModelProvider[];
  activeProviderId: string;
  activeModelId: string;
  temperature: number;
  maxTokens: number;
  selectActiveModel: (providerId: string, modelId: string) => Promise<void>;
  addModelItem: (params: ModelInput) => Promise<string>;
  updateModelItem: (oldTarget: { providerId: string; modelId: string }, params: ModelInput) => Promise<string>;
  deleteModelItem: (providerId: string, modelId: string) => Promise<void>;
  testConnectionByUrl: (baseUrl: string, apiKey: string, type: string, modelId: string, protocol?: ModelProtocol) => Promise<{ success: boolean; message: string }>;
  getActiveModel: () => ModelItem | undefined;
  getActiveProvider: () => ModelProvider | undefined;
  getAllModels: () => ListedModel[];
}

const ModelContext = createContext<ModelContextType | undefined>(undefined);
const EMPTY_CONFIG: FullModelConfig = {
  activeVendor: '', activeModel: '', vendor: '', name: '', apiKey: '', temperature: 0.7, stream: true, providers: [],
};

/** 只保留模型字段，避免把启动时读取的账户或渠道快照写回并覆盖其他模块的新配置。 */
function modelDocument(data: FullModelConfig): FullModelConfig {
  return { providers: data.providers || [], activeVendor: data.activeVendor || data.vendor || '',
    activeModel: data.activeModel || data.name || '', vendor: data.vendor || '', name: data.name || '',
    apiKey: data.apiKey || '', baseUrl: data.baseUrl, protocol: data.protocol,
    temperature: data.temperature ?? 0.7, stream: data.stream ?? true };
}

/** 后端 config.json 是权威配置；每次修改串行保存，成功后再更新界面，禁止挂载自动写回。 */
export const ModelProviderContext: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [config, setConfig] = useState<FullModelConfig>(EMPTY_CONFIG);
  const current = useRef(config);
  const loaded = useRef(false);
  const queue = useRef<Promise<unknown>>(Promise.resolve());

  const apply = (next: FullModelConfig) => {
    current.current = next;
    setConfig(next);
    localStorage.setItem('butvan_agent_providers_v2', JSON.stringify(next.providers));
    localStorage.setItem('butvan_agent_active_provider', next.activeVendor);
    localStorage.setItem('butvan_agent_active_model', next.activeModel);
  };

  useEffect(() => {
    let active = true;
    void fetchFullModelConfig().then(data => {
      if (!active || !data) return;
      apply(modelDocument(data));
      loaded.current = true;
    });
    return () => { active = false; };
  }, []);

  const persist = (change: (previous: FullModelConfig) => FullModelConfig): Promise<void> => {
    const operation = queue.current.then(async () => {
      if (!loaded.current) {
        const authoritative = await fetchFullModelConfig();
        if (!authoritative) throw new Error('尚未读取模型配置，请检查后端连接后重试');
        apply(modelDocument(authoritative));
        loaded.current = true;
      }
      const next = change(current.current);
      const provider = next.providers.find(p => p.id === next.activeVendor);
      const payload: FullModelConfig = {
        ...next, vendor: next.activeVendor, name: next.activeModel, apiKey: provider?.apiKey || '',
        baseUrl: provider?.baseUrl || '', protocol: provider?.protocol,
        providers: next.providers.map(p => ({ ...p, models: p.models.map(m => ({ ...m, modelName: m.id })) })),
      };
      const result = await saveFullModelConfig(payload);
      if (!result.success) throw new Error(result.message);
      apply(payload);
    });
    queue.current = operation.catch(() => undefined);
    return operation;
  };

  const selectActiveModel = async (providerId: string, modelId: string) => {
    await persist(previous => {
      if (!previous.providers.some(p => p.id === providerId && p.models.some(m => m.id === modelId))) {
        throw new Error('找不到指定模型，请重新选择');
      }
      return { ...previous, activeVendor: providerId, activeModel: modelId };
    });
  };

  const saveItem = async (params: ModelInput, old?: { providerId: string; modelId: string }) => {
    let savedId = '';
    await persist(previous => {
      // 每个自定义连接独立标识，允许同一模型 ID 对应不同网关与密钥。
      const oldProvider = previous.providers.find(p => p.id === old?.providerId);
      savedId = params.vendor === 'custom'
        ? (oldProvider?.type === 'custom' ? oldProvider.id : `custom-${crypto.randomUUID()}`)
        : params.vendor;
      const item: ModelItem = { id: params.modelId, name: params.name, providerId: savedId,
        supportsVision: params.supportsVision === true };
      let providers = previous.providers.map(p => ({ ...p,
        models: p.models.filter(m => !(p.id === old?.providerId && m.id === old.modelId)),
      }));
      const target = providers.find(p => p.id === savedId);
      const provider: ModelProvider = {
        ...(target || { id: savedId, name: params.vendor === 'custom' ? params.name : params.vendor.toUpperCase(), isEnabled: true }),
        type: params.vendor as ProviderType, baseUrl: params.baseUrl, protocol: params.protocol, apiKey: params.apiKey,
        models: [...(target?.models.filter(m => m.id !== item.id) || []), item],
      };
      providers = [...providers.filter(p => p.id !== savedId && p.models.length > 0), provider];
      const wasActive = old && previous.activeVendor === old.providerId && previous.activeModel === old.modelId;
      const activate = params.makeDefault || wasActive || !previous.activeVendor;
      return { ...previous, providers, activeVendor: activate ? savedId : previous.activeVendor,
        activeModel: activate ? item.id : previous.activeModel };
    });
    return savedId;
  };

  const deleteModelItem = async (providerId: string, modelId: string) => {
    await persist(previous => {
      const providers = previous.providers.map(p => ({ ...p,
        models: p.id === providerId ? p.models.filter(m => m.id !== modelId) : p.models,
      })).filter(p => p.models.length > 0);
      const wasActive = previous.activeVendor === providerId && previous.activeModel === modelId;
      return { ...previous, providers, activeVendor: wasActive ? providers[0]?.id || '' : previous.activeVendor,
        activeModel: wasActive ? providers[0]?.models[0]?.id || '' : previous.activeModel };
    });
  };

  const getActiveProvider = () => config.providers.find(p => p.id === config.activeVendor);
  const getAllModels = (): ListedModel[] => config.providers.flatMap(p => p.models.map(m => ({
    ...m, providerId: p.id, providerName: p.name, providerType: p.type,
    baseUrl: p.baseUrl || '', apiKey: p.apiKey, protocol: p.protocol,
  })));

  return <ModelContext.Provider value={{
    providers: config.providers, activeProviderId: config.activeVendor, activeModelId: config.activeModel,
    temperature: config.temperature ?? 0.7, maxTokens: 4096,
    selectActiveModel, addModelItem: params => saveItem(params), updateModelItem: (old, params) => saveItem(params, old),
    deleteModelItem, getActiveProvider, getActiveModel: () => getActiveProvider()?.models.find(m => m.id === config.activeModel),
    getAllModels, testConnectionByUrl: (baseUrl, apiKey, vendor, modelName, protocol) =>
      testModelConnection({ baseUrl, apiKey, vendor, modelName, protocol }),
  }}>{children}</ModelContext.Provider>;
};

export const useModel = () => {
  const context = useContext(ModelContext);
  if (!context) throw new Error('useModel must be used within a ModelProviderContext');
  return context;
};
