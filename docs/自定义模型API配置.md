# 自定义模型 API 配置

## 使用方式

在首次引导或「设置 → 模型配置 → 添加模型」中，选择提供商「自定义 API」，填写：

- 请求格式：`OpenAI（Chat Completions）` 或 `Anthropic（Messages）`。
- API 基础地址，例如 `https://gateway.example.com` 或 `https://gateway.example.com/v1`；允许服务自带的路径前缀。
- Model ID：网关实际支持的模型标识，不是界面显示名称。
- API Key：使用密码框输入；无需鉴权的本地服务可填占位值。

地址只接受 HTTP(S)，不能包含 URL 用户名/密码、查询参数、片段，也不填写 `/chat/completions` 或 `/messages`。Anthropic SDK 会自行添加 `/v1/messages`，填写结尾 `/v1` 时后端会去重。OpenAI 使用 Chat Completions，不包含 Responses API。本功能不是任意 HTTP 方法、请求头或 JSON 模板编辑器。

可先点击「测试连接」再保存。测试由后端实际请求所选模型，可能产生少量计费；HTTP 404 不视为成功。测试不保存配置或改变当前模型；其用量以 `MODEL_CONNECTION_TEST` 单独追加到系统用量账本，并在 Token 用量设置中显示「模型连接测试」，不计入聊天轮次。远程后端模式下，模型地址由服务器访问，`localhost` 也表示服务器自身。

保存后可立即设为默认；已有 OpenAI、Anthropic、DeepSeek 也可以覆盖基础地址。多个自定义连接具有不同 ID，即使使用相同 Model ID，也不会相互覆盖网关、协议或密钥。

添加或编辑模型时可以标注「支持图片识别」。这是用户自己声明模型具备视觉输入能力，系统不猜测模型能力：标注后渠道才允许把图片交给该模型，未标注时一律按纯文本模型处理，直接拒绝图片而不是等供应商返回错误。标注只是能力声明，不改变文本请求，也不会自动给模型添加图片。内置预置模型默认不标注，需要用户按实际使用的模型自行开启。

## 持久化与运行契约

权威配置仍是 `~/.butvan-agent/config.json`。每个 Provider 保存 `id`、`type`、`baseUrl`、`protocol`、`apiKey` 与 `models`；自定义连接使用 `type=custom`，`protocol=openai|anthropic`。`activeVendor` 是 Provider ID，不是协议名。恢复时精确匹配 ID，再使用 Provider 的类型、地址、密钥构建模型，避免使用过期的顶层 API Key。`models[]` 中的每个模型额外保存 `supportsVision`（图片识别能力标注）；能力属于模型而不是连接，切换模型时随之变化，旧配置缺少该字段时按不支持处理。

配置写入前先校验；前端串行保存，后端成功后才更新列表和激活状态。初始化读取失败时不自动写回缓存。显式删除最后一个模型会清空模型列表及内存模型，其他模块的配置节点继续保留；其他模块调用默认合并保存时仍保护已有模型配置。

自定义模型复用 AgentScope 官方适配器，流式响应、工具调用与供应商用量解析不另造协议。流式调用沿用原聊天取消路径。真实网关必须实际兼容所选协议，网关是否支持特定模型、工具和推理参数仍由其能力决定。

已知依赖限制：AgentScope 2.0.0 的 Anthropic 流式解析器会过滤纯 usage 事件，因此该路径的供应商实际 Token 用量可能不可用；非流式请求可返回用量。此处保留不可用状态，不用本地估算补齐，修复需单独升级或替换依赖适配器。

## HTTP 接口

接口沿用现有模型配置 API 的鉴权与单用户边界；启用 API Token 时必须携带 `Authorization: Bearer <应用 Token>`。模型供应商 API Key 放在请求体，不等同于应用 Token。自定义地址意味着调用者可令后端访问指定 HTTP(S) 服务（包括本地服务），仅向可信用户开放这些接口；远程部署必须遵守根工程契约的 Token、CORS 与网络访问控制要求。

### 更新当前模型

`POST /agent/model` 保留原有 `vendor`、`modelName`、`apiKey` 字段，新增可选 `baseUrl`、`protocol`。`vendor=custom` 时基础地址和协议必填；该入口主要用于首次配置。

```json
{
  "vendor": "custom",
  "modelName": "your-model-id",
  "baseUrl": "https://gateway.example.com/v1",
  "protocol": "anthropic",
  "apiKey": "<用户输入的密钥>"
}
```

成功返回 `{ "code": 200, "data": "更新模型配置成功！" }`。

### 测试模型请求

`POST /agent/model/test-connection` 接受同一 DTO，非流式发送最小提示，输出上限 16 Token，等待上限 20 秒。成功或供应商失败均返回统一信封：

```json
{ "code": 200, "data": { "success": true, "message": "连接成功，模型请求已通过" } }
```

供应商拒绝、网络错误或超时返回 `data.success=false` 与稳定中文说明，不透传错误正文、地址或密钥。字段缺失、地址或协议非法返回 `code=400`；配置目录写入失败返回 `code=503`。业务 code 沿用现有统一响应，不保证与 HTTP 状态码一致。

### 全量保存与选择

`GET /agent/model/full-config` 读取完整配置；`POST /agent/model/full-config` 保持原有结构，Provider 新增 `baseUrl`、`protocol`，`activeVendor`/`vendor` 提交所选 Provider ID，`activeModel`/`name` 提交 Model ID。保存、编辑和切换均一次提交完整模型配置，避免旧配置与新激活项并发覆盖。

`GET /agent/model/config` 的选择器新增 `baseUrl`、`protocol`，返回实际供应商类型。`GET /agent/model/vendors` 新增 `custom`。旧内置供应商配置缺省地址时继续使用 SDK 默认地址。

## 验证

- `ModelServiceTest` 用本地 HTTP 服务验证两种协议的路径、鉴权、请求体、工具、流式与用量、配置恢复、错误脱敏及删除；不调用真实模型、不访问用户数据。
- `ModelConfigRoundTripTest` 验证其他模块节点保留与旧配置兼容。
- 前端 `pnpm test` 覆盖新增、编辑、选择、保存失败、读取失败、连接测试、首次引导及设置表单；`pnpm build` 检查类型和构建。
- 真实第三方网关需由用户填入凭据自行测试，不在自动化测试中使用真实密钥或产生外部请求。
