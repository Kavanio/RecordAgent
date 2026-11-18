package butvan.agent.agents.tool.impl;

import butvan.agent.agents.tool.AgentToolModule;
import butvan.agent.agents.config.TavilyConfigData;
import butvan.agent.agents.config.TavilyProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class WebSearchTool implements AgentToolModule {

    /**
     * 接口地址
     */
    private static final String TAVILY_URL = "https://api.tavily.com/search";

    /**
     * 单条摘要最多保留的字符数
     */
    private static final int MAX_CONTENT_LENGTH = 500;

    private final TavilyProperties tavilyProperties;
    private final RestClient restClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造注入配置服务，并构建带超时的 HTTP 客户端。
     *
     * @param tavilyProperties 配置读取服务（Spring 自动注入）
     */
    public WebSearchTool(TavilyProperties tavilyProperties) {
        this.tavilyProperties = tavilyProperties;
        this.restClient = buildRestClient();
    }


    /**
     * 构建带超市限制的 HTTO 客户端，避免搜索请求长时间挂起拖住 Agent
     * @return
     */
    public RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000); // 建立连接最长 5s
        factory.setReadTimeout(15_000); // 读取响应最长 15s
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * 联网搜索入口
     * @param query
     * @param maxResults
     * @return
     */
    @Tool(name = "web_search", description = "搜索互联网获取最新信息当用户询问实时资讯，最新事件或模型训练数据之外的动态调用")
    public String search(
            @ToolParam(name = "query", description = "要搜索的自然语言关键词或问题") String query,
            @ToolParam(name = "max_results", description = "要返回结果条数上限（1-10），不传则使用配置默认值", required = false) Integer maxResults
    ) {
        // 每次调用都要重新读取配置
        TavilyConfigData config = tavilyProperties.load();

        if (!config.isReady()) {
            return "联网搜索未启用，请先在 ~/.butvan-agent/config.json 中配置 webSearch.enable=true 和 webSearch.apiKey。";
        }
        if (query == null || query.isBlank()) {
            return "Error：query 参数不能为空";
        }

        // 校验并收敛结果数量，防止模型传入过大的值
        int limit = (maxResults == null || maxResults < 1 || maxResults > 10) ? config.maxResults() : maxResults;

        try {
            // 组装 Taviily 请求题：只有搜索参数 Key 通过请求头传递
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("query",query);
            body.put("search_depth", config.searchDepth());
            body.put("max_results", limit);
            body.put("include_answer", true); // 让 tavily 额外生成一段综合回答

            // 发起请求
            String responseBody = request(config, body);

            return formatResults(query, responseBody);
        } catch (Exception e) {
            log.warn("Tavily 搜索失败：query={}",query, e);
            return "搜索失败：" + e.getMessage();
        }
    }

    /** 通过现有搜索连接检索图片候选，候选地址只供受控下载器使用。 */
    public List<ImageResult> findImages(String query, int limit) throws JsonProcessingException {
        TavilyConfigData config = tavilyProperties.load();
        if (!config.isReady()) {
            throw new IllegalArgumentException("联网搜索未启用，请先配置 Tavily。");
        }
        var body = Map.of(
                "query", query,
                "max_results", limit,
                "include_images", true,
                "include_image_descriptions", true
        );
        var results = new ArrayList<ImageResult>();
        for (JsonNode item : objectMapper.readTree(request(config, body)).path("images")) {
            String url = item.isTextual() ? item.asText() : item.path("url").asText("");
            if (!url.isBlank()) results.add(new ImageResult(url, item.path("description").asText("")));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private String request(TavilyConfigData config, Map<String, ?> body) {
        return restClient.post()
                .uri(TAVILY_URL)
                .headers(headers -> headers.setBearerAuth(config.apiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** 搜索返回的公开图片候选。 */
    public record ImageResult(String url, String description) { }

    /**
     * 把 Tavily 返回的 JSON 整理成模型容易理解阅读的纯文本
     * @param query
     * @param responseBody
     * @return
     */
    private String formatResults(String query, String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        StringBuilder sb = new StringBuilder();

        // include_answer=true 时 tavily 会给一段综合性的回答，优先展示
        if (root.hasNonNull("answer") && !root.get("answer").asText().isBlank()) {
            sb.append("综合回答：").append(root.get("answer").asText()).append("\n\n");
        }

        JsonNode results = root.path("results");
        sb.append("针对'").append(query).append("'的搜索结果（共").append(results.size()).append("）条：\n");
        for (int i = 0; i < results.size(); i++) {
            JsonNode item = results.get(i);
            // 逐条输出：序列号+标题+链接+截断后的摘要
            sb.append(i + 1).append(". ").append(item.path("title").asText("无标题")).append("\n");
            sb.append(" 链接：").append(item.path("url").asText("")).append("\n");
            sb.append(" 摘要：").append(truncate(item.path("content").asText(""), MAX_CONTENT_LENGTH)).append("\n\n");
        }

        return sb.toString();
    }

    /** 超过长度上限的文本截断并加省略号，防止上下文被撑爆 */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "……";
    }

}
