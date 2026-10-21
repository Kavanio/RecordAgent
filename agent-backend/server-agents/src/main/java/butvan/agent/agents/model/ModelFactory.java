package butvan.agent.agents.model;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public sealed interface ModelFactory {

    Logger log = LoggerFactory.getLogger(ModelFactory.class);

    String vendor();

    Model createModel(ModelSelector modelSelector);

    record Gemini() implements ModelFactory {

        @Override
        public String vendor() {
            return "gemini";
        }

        @Override
        public Model createModel(ModelSelector modelSelector) {

            GenerateOptions options = GenerateOptions.builder()
                    .temperature(modelSelector.temperature())
                    .stream(modelSelector.stream())
                    .build();

            GeminiChatModel model = GeminiChatModel.builder()
                    .modelName(modelSelector.name())
                    .apiKey(modelSelector.apiKey())
                    .defaultOptions(options)
                    .build();
            return model;
        }
    }

    record OpenAi() implements ModelFactory {
        @Override
        public String vendor() {
            return "openai";
        }

        @Override
        public Model createModel(ModelSelector modelSelector) {
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(modelSelector.temperature())
                    .stream(modelSelector.stream())
                    .build();

            OpenAIChatModel model = OpenAIChatModel.builder()
                    .modelName(modelSelector.name())
                    .apiKey(modelSelector.apiKey())
                    .baseUrl(ModelEndpoint.normalize(modelSelector.baseUrl()))
                    .generateOptions(options)
                    .build();
            return model;
        }
    }

    record DashScope() implements ModelFactory {
        @Override
        public String vendor() {
            return "dashscope";
        }

        @Override
        public Model createModel(ModelSelector modelSelector) {
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(modelSelector.temperature())
                    .stream(modelSelector.stream())
                    .build();

            DashScopeChatModel model = DashScopeChatModel.builder()
                    .modelName(modelSelector.name())
                    .apiKey(modelSelector.apiKey())
                    .defaultOptions(options)
                    .build();
            return model;
        }
    }

    record DeepSeek() implements ModelFactory {

        @Override
        public String vendor() {
            return "deepseek";
        }

        @Override
        public Model createModel(ModelSelector modelSelector) {
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(modelSelector.temperature())
                    .stream(modelSelector.stream())
                    .build();

            OpenAIChatModel model = OpenAIChatModel.builder()
                    .modelName(modelSelector.name())
                    .apiKey(modelSelector.apiKey())
                    .baseUrl(modelSelector.baseUrl() == null || modelSelector.baseUrl().isBlank()
                            ? "https://api.deepseek.com" : ModelEndpoint.normalize(modelSelector.baseUrl()))
                    .formatter(new DeepSeekFormatter())
                    .generateOptions(options)
                    .build();
            return model;
        }
    }

    record Anthropic() implements ModelFactory {

        @Override
        public String vendor() {
            return "anthropic";
        }

        @Override
        public Model createModel(ModelSelector modelSelector) {
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(modelSelector.temperature())
                    .stream(modelSelector.stream())
                    .build();

            AnthropicChatModel model = AnthropicChatModel.builder()
                    .modelName(modelSelector.name())
                    .apiKey(modelSelector.apiKey())
                    .baseUrl(ModelEndpoint.anthropicBaseUrl(modelSelector.baseUrl()))
                    .stream(modelSelector.stream() == null || modelSelector.stream())
                    .defaultOptions(options)
                    .build();
            return model;
        }
    }

    /** 自定义供应商复用官方适配器，保留流式输出、工具调用与供应商用量解析。 */
    record Custom() implements ModelFactory {
        @Override
        public String vendor() { return "custom"; }

        @Override
        public Model createModel(ModelSelector selector) {
            if (ModelEndpoint.normalize(selector.baseUrl()) == null) {
                throw new IllegalArgumentException("自定义模型必须填写 API 基础地址");
            }
            return fromVendor(ModelEndpoint.protocol(selector.protocol())).createModel(selector);
        }
    }

    static ModelFactory fromVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            throw new IllegalArgumentException("Model vendor cannot be null or empty");
        }

        return switch (vendor.trim().toLowerCase()) {
            case "gemini" -> new Gemini();
            case "openai" -> new OpenAi();
            case "dashscope" -> new DashScope();
            case "deepseek" -> new DeepSeek();
            case "anthropic" -> new Anthropic();
            case "custom" -> new Custom();
            default -> throw new IllegalArgumentException("Unsupported model vendor: " + vendor);
        };
    }

    static Model create(ModelSelector modelSelector) {
        if (modelSelector == null) {
            throw new IllegalArgumentException("ModelSelector cannot be null");
        }

        Model model = fromVendor(modelSelector.vendor()).createModel(modelSelector);
        log.info("ChatModel successfully instantiated -> Vendor: [{}], Model: [{}]",
                modelSelector.vendor(), modelSelector.name());
        return model;
    }

}
