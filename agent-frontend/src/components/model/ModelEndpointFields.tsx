import type { ModelProtocol } from '../../types/model';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import { Select } from '../common/Select';

interface ModelEndpointFieldsProps {
  idPrefix: string;
  vendor: string;
  baseUrl: string;
  protocol: ModelProtocol;
  onBaseUrlChange: (value: string) => void;
  onProtocolChange: (value: ModelProtocol) => void;
}

/** 初始化与模型编辑共用的协议及基础地址输入，密钥始终通过独立字段传递。 */
export function ModelEndpointFields({ idPrefix, vendor, baseUrl, protocol, onBaseUrlChange, onProtocolChange }: ModelEndpointFieldsProps) {
  if (!['custom', 'openai', 'anthropic', 'deepseek'].includes(vendor)) return null;
  return <>
    {vendor === 'custom' && <FormField label="请求格式" htmlFor={`${idPrefix}-protocol`} required>
      <Select id={`${idPrefix}-protocol`} value={protocol}
        options={[{ value: 'openai', label: 'OpenAI（Chat Completions）' }, { value: 'anthropic', label: 'Anthropic（Messages）' }]}
        onChange={event => onProtocolChange(event.target.value as ModelProtocol)} fullWidth />
    </FormField>}
    <FormField label="API 基础地址" htmlFor={`${idPrefix}-base-url`} required={vendor === 'custom'}
      hint={vendor === 'custom'
        ? '填写服务根地址或以 /v1 结尾的地址，不含 /chat/completions、/messages；由后端访问该地址。'
        : '填写服务根地址或以 /v1 结尾的地址，不含 /chat/completions、/messages；留空使用官方地址。'}>
      <TextInput id={`${idPrefix}-base-url`} value={baseUrl} placeholder="https://api.example.com/v1"
        onChange={event => onBaseUrlChange(event.target.value)} />
    </FormField>
  </>;
}
