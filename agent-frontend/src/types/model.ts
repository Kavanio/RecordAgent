export type ModelProtocol = 'openai' | 'anthropic';

export type ProviderType = 'dashscope' | 'gemini' | 'deepseek' | 'openai' | 'anthropic' | 'ollama' | 'qwen' | 'zhipu' | 'custom';

export interface ModelItem {
  id: string;
  name: string;
  providerId: string;
  description?: string;
  supportsReasoning?: boolean;
  contextWindow?: number;
  maxTokens?: number;
}

export interface ModelProvider {
  id: string;
  name: string;
  type: ProviderType;
  baseUrl?: string;
  protocol?: ModelProtocol;
  apiKey: string;
  isEnabled: boolean;
  isOfficial?: boolean;
  models: ModelItem[];
}

export interface FullModelConfig {
  activeVendor: string;
  activeModel: string;
  vendor: string;
  name: string;
  apiKey: string;
  baseUrl?: string;
  protocol?: ModelProtocol;
  temperature?: number;
  stream?: boolean;
  providers: ModelProvider[];
}

export interface ModelConfigState {
  providers: ModelProvider[];
  activeProviderId: string;
  activeModelId: string;
  temperature: number;
  maxTokens: number;
}
