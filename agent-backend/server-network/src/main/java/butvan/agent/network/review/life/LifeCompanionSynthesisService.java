package butvan.agent.network.review.life;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.agents.model.ModelFactory;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.agents.review.ReviewConnectionIdentity;
import butvan.agent.network.review.life.LifeCompanionCollector.CollectedMaterial;
import butvan.agent.network.review.life.LifeCompanionModels.Cognition;
import butvan.agent.network.review.life.LifeCompanionModels.RhythmState;
import butvan.agent.network.review.life.LifeReviewDtos.HabitSuggestionView;
import butvan.agent.network.review.life.LifeReviewModels.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统性四维认知提炼与自然口语主动问候生成器。
 *
 * <p>严格使用用户授权固化的 Provider ID、Model ID 与身份指纹创建独立模型调用，
 * 不复用或静默切换当前聊天模型；当模型配置不可用或处于离线测试环境时降级到本地有界归纳。</p>
 */
@Slf4j
@Component
public class LifeCompanionSynthesisService {
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(12);

    private final LocalConfigService localConfig;
    private final ObjectMapper mapper;

    public LifeCompanionSynthesisService(
            @Autowired(required = false) LocalConfigService localConfig,
            @Autowired(required = false) ObjectMapper mapper
    ) {
        this.localConfig = localConfig;
        this.mapper = mapper == null ? new ObjectMapper().findAndRegisterModules() : mapper;
    }

    public Cognition synthesizeCognition(Settings settings, Cognition current, CollectedMaterial material, Instant now) {
        Optional<Cognition> modelLearned = trySynthesizeWithFrozenModel(settings, current, material, now);
        if (modelLearned.isPresent()) {
            return modelLearned.get();
        }
        return synthesizeDeterministically(current, material, now);
    }

    public String composeOutreachMessage(Settings settings, Cognition cog, RhythmState rhythm, String hook, ZonedDateTime local) {
        Optional<Model> model = resolveFrozenModel(settings);
        if (model.isPresent()) {
            try {
                String prompt = "你是用户的私人智能体朋友。请基于以下对用户的了解，用自然、口语化、像微信/飞书上熟人朋友一样的语气，"
                        + "写一句简短的主动问候或关心（控制在 25 到 75 字之间，不要加任何标题或【提醒】前缀，只输出这一句话）：\n"
                        + "- 背景喜好：" + cog.backgroundAndPreferences() + "\n"
                        + "- 作息忙闲：" + cog.routineAndBusyHours() + "\n"
                        + "- 近期主线：" + cog.activeFocusAndBlockers() + "\n"
                        + "- 本次想聊的话头：" + hook + "\n"
                        + "- 当前时段：" + String.format("%02d:%02d", local.getHour(), local.getMinute()) + "\n"
                        + "- 是否刚过忙碌时段且上次未回：" + (rhythm.busyDeferred() || rhythm.unansweredCount() > 0);
                String text = invokeText(model.get(), "只输出一句自然温暖的朋友式口语问候，绝不带报告腔。", prompt, 0.5, 180);
                if (!text.isBlank()) {
                    return text;
                }
            } catch (RuntimeException e) {
                log.debug("主动问候模型生成降级：{}", e.getClass().getSimpleName());
            }
        }
        return fallbackOutreachMessage(cog, rhythm);
    }

    public static String naturalizeTemplateText(String raw) {
        if (raw == null) return "";
        return raw.replaceFirst("^【天气变化提醒】", "看了一眼外面的天气，")
                .replaceFirst("^【天气恢复提醒】", "跟你说一声，外面天气好转啦：")
                .replaceFirst("^【支出变化提醒】", "顺手帮你看了一眼今天的账单，")
                .replaceFirst("^【服务器状态提醒】", "刚帮你巡检服务器时发现个小状况：")
                .replaceFirst("^【服务器恢复提醒】", "好消息，刚才那台服务器已经恢复正常啦：")
                .replaceFirst("^【重要邮件提醒】", "刚帮你留意到一封重要邮件：")
                .strip();
    }

    private Optional<Cognition> trySynthesizeWithFrozenModel(
            Settings settings, Cognition current, CollectedMaterial material, Instant now
    ) {
        Optional<Model> model = resolveFrozenModel(settings);
        if (model.isEmpty()) return Optional.empty();
        try {
            String userPrompt = "请结合用户的既有四维认知与近期多源近况事实，完成一次增量认知整理。\n"
                    + "只输出严格合法 JSON 对象，字段如下：\n"
                    + "{\"backgroundAndPreferences\":\"基本背景与沟通偏好(60字内)\","
                    + "\"routineAndBusyHours\":\"作息规律与忙闲时段概括(60字内)\","
                    + "\"activeFocusAndBlockers\":\"近期主线大事、待办与卡点(80字内)\","
                    + "\"pendingHooksAndCuriosities\":\"上次未完话头与自然可聊的好奇点(80字内)\","
                    + "\"busyStartHour\":9,\"busyEndHour\":18}\n\n"
                    + "既有认知：\n"
                    + "- 背景偏好：" + current.backgroundAndPreferences() + "\n"
                    + "- 作息忙闲：" + current.routineAndBusyHours() + " (" + current.busyStartHour() + "-" + current.busyEndHour() + ")\n"
                    + "- 近期主线：" + current.activeFocusAndBlockers() + "\n"
                    + "- 未完话头：" + current.pendingHooksAndCuriosities() + "\n\n"
                    + "多源近期材料：\n"
                    + "- 个人画像：" + clip(material.profileText(), 220) + "\n"
                    + "- 近期对话：" + String.join("；", material.chats()) + "\n"
                    + "- 近期手记：" + String.join("；", material.journals()) + "\n"
                    + "- 活跃目标：" + String.join("；", material.activeGoals()) + "\n"
                    + "- 日历待办：" + String.join("；", material.todos()) + "\n"
                    + "- 学习打卡：" + String.join("；", material.studyLogs()) + "\n"
                    + "- 财务收支：" + String.join("；", material.financeSummary()) + "\n"
                    + "- 活跃时段统计：" + (material.habitSuggestion() == null ? "暂无" : material.habitSuggestion().note());

            String raw = invokeText(
                    model.get(),
                    "你是用户记忆整理助手，只输出严格 JSON，不输出 Markdown 代码块标记或多余说明。",
                    userPrompt,
                    0.2,
                    480);
            return parseCognitionJson(raw, current, now);
        } catch (RuntimeException e) {
            log.debug("陪伴认知模型提炼降级：{}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<Cognition> parseCognitionJson(String raw, Cognition current, Instant now) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String cleaned = raw.strip();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        try {
            JsonNode node = mapper.readTree(cleaned.substring(start, end + 1));
            String bg = pickText(node, "backgroundAndPreferences", current.backgroundAndPreferences());
            String routine = pickText(node, "routineAndBusyHours", current.routineAndBusyHours());
            String focus = pickText(node, "activeFocusAndBlockers", current.activeFocusAndBlockers());
            String hooks = pickText(node, "pendingHooksAndCuriosities", current.pendingHooksAndCuriosities());
            int busyStart = clampHour(node.path("busyStartHour").asInt(current.busyStartHour()), current.busyStartHour());
            int busyEnd = clampHour(node.path("busyEndHour").asInt(current.busyEndHour()), current.busyEndHour());
            return Optional.of(new Cognition(bg, routine, focus, hooks, busyStart, busyEnd, now));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String invokeText(Model model, String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        var messages = List.of(
                SystemMessage.builder().textContent(systemPrompt).build(),
                UserMessage.builder().textContent(userPrompt).build());
        GenerateOptions opts = GenerateOptions.builder()
                .stream(false)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .additionalBodyParam("thinking", Map.of("type", "disabled"))
                .build();
        var responses = model.stream(messages, List.of(), opts).collectList().block(MODEL_TIMEOUT);
        return responses == null ? "" : responses.stream()
                .filter(r -> r.getContent() != null)
                .flatMap(r -> r.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat)
                .replaceAll("(?s)<think>.*?</think>", "")
                .strip();
    }

    private Optional<Model> resolveFrozenModel(Settings settings) {
        if (localConfig == null || settings == null
                || settings.providerId() == null || settings.providerId().isBlank()
                || settings.modelName() == null || settings.modelName().isBlank()) {
            return Optional.empty();
        }
        try {
            var path = localConfig.getConfigPath();
            if (path == null || !Files.isRegularFile(path) || Files.size(path) > 2_000_000) {
                return Optional.empty();
            }
            var data = mapper.readValue(Files.readAllBytes(path), LocalConfigService.ModelConfigData.class);
            if (data == null || data.getProviders() == null) return Optional.empty();
            var provider = data.getProviders().stream()
                    .filter(p -> p != null && settings.providerId().equals(p.getId()))
                    .findFirst()
                    .orElse(null);
            if (provider == null || !Boolean.TRUE.equals(provider.getIsEnabled())
                    || provider.getApiKey() == null || provider.getApiKey().isBlank()
                    || provider.getModels() == null) {
                return Optional.empty();
            }
            boolean exists = provider.getModels().stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(m -> settings.modelName().equals(
                            m.getModelName() == null || m.getModelName().isBlank() ? m.getName() : m.getModelName()));
            if (!exists) return Optional.empty();
            if (settings.fingerprint() != null && !settings.fingerprint().isBlank()
                    && !settings.fingerprint().equals(ReviewConnectionIdentity.fingerprint(
                    provider.getId(), settings.modelName(), provider.getType(), provider.getProtocol(), provider.getBaseUrl()))) {
                return Optional.empty();
            }
            ModelSelector selector = new ModelSelector(provider.getType(), settings.modelName(), provider.getApiKey(),
                    0.3, false, provider.getBaseUrl(), provider.getProtocol());
            return Optional.ofNullable(ModelFactory.create(selector));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Cognition synthesizeDeterministically(Cognition current, CollectedMaterial m, Instant now) {
        String bg = current.backgroundAndPreferences();
        if (bg.isBlank() && m.profileText() != null && !m.profileText().isBlank()) {
            bg = m.profileText().lines()
                    .filter(l -> !l.startsWith("#") && !l.startsWith("<!--") && !l.isBlank())
                    .limit(3)
                    .reduce((a, b) -> a + "；" + b)
                    .orElse(clip(m.profileText(), 160));
        }
        if (bg.isBlank()) {
            bg = "偏好简洁直接、不打扰的交流方式；注重长期效率与个人成长记录。";
        }

        int busyStart = current.busyStartHour();
        int busyEnd = current.busyEndHour();
        String routine = current.routineAndBusyHours();
        HabitSuggestionView habitSug = m.habitSuggestion();
        if (habitSug != null && habitSug.available() && habitSug.suggestedHour() != null) {
            int activeHour = habitSug.suggestedHour();
            busyEnd = Math.max(17, Math.min(20, activeHour - 1));
            routine = "通常在 " + String.format("%02d:00—%02d:00", busyStart, busyEnd)
                    + " 集中忙正事，晚间 " + activeHour + ":00 前后互动最活跃。";
        } else if (routine.isBlank()) {
            routine = "工作日白天（约 09:00—18:00）通常在忙，傍晚至晚间较常互动。";
        }

        List<String> focusParts = new ArrayList<>();
        if (!m.activeGoals().isEmpty()) focusParts.add("当前目标：" + String.join("、", m.activeGoals()));
        if (!m.journals().isEmpty()) focusParts.add("近期记录：" + m.journals().getFirst());
        if (!m.todos().isEmpty()) focusParts.add("待办进度：" + m.todos().getFirst());
        if (!m.studyLogs().isEmpty()) focusParts.add("学习专注：" + m.studyLogs().getFirst());
        if (!m.financeSummary().isEmpty()) focusParts.add("财务近况：" + m.financeSummary().getFirst());
        String focus = !focusParts.isEmpty()
                ? String.join("；", focusParts)
                : (current.activeFocusAndBlockers().isBlank() && !m.chats().isEmpty()
                    ? "近期关注：" + m.chats().getFirst() : current.activeFocusAndBlockers());

        String hooks = current.pendingHooksAndCuriosities();
        if (!m.chats().isEmpty()) {
            hooks = "上次聊到：「" + m.chats().getFirst() + "」，可顺手跟进后续进展或询问最近忙得怎么样。";
        } else if (!m.journals().isEmpty()) {
            hooks = "近期手记提到：「" + m.journals().getFirst() + "」，可自然关心后续感受。";
        } else if (!m.todos().isEmpty()) {
            hooks = "近期日程待办提到：「" + m.todos().getFirst() + "」，可顺嘴了解推进节奏。";
        } else if (hooks.isBlank()) {
            hooks = "对你的日常节奏还在持续熟悉中，想顺嘴了解你最近最想推进的一件小事。";
        }

        return new Cognition(bg, routine, focus, hooks, busyStart, busyEnd, now);
    }

    private String fallbackOutreachMessage(Cognition cog, RhythmState rhythm) {
        if (rhythm.busyDeferred() || rhythm.unansweredCount() > 0) {
            if (!cog.activeFocusAndBlockers().isBlank()) {
                return "看你白天一直在忙就没催你，现在忙完歇下来没？" + cleanHookSnippet(cog.activeFocusAndBlockers()) + " 进展还顺吗？";
            }
            return "看你白天估计在忙正事，现在忙完歇口气没？有空随时跟我聊聊近况。";
        }
        if (!cog.pendingHooksAndCuriosities().isBlank()) {
            return "突然想起" + cleanHookSnippet(cog.pendingHooksAndCuriosities()) + "，这两天怎么样啦？有空跟我说说。";
        }
        if (!cog.activeFocusAndBlockers().isBlank()) {
            return "这两天看你在推进「" + cleanHookSnippet(cog.activeFocusAndBlockers()) + "」，进展还顺利吗？累了记得歇会儿。";
        }
        return "今天忙得怎么样？刚顺手整理了下你的近况，有什么想吐槽或者随手记一笔的随时找我。";
    }

    private static String pickText(JsonNode node, String field, String fallback) {
        String val = node.path(field).asText("").strip();
        return val.isBlank() ? fallback : clip(val, 240);
    }

    private static int clampHour(int value, int fallback) {
        return value >= 0 && value <= 23 ? value : fallback;
    }

    private static String clip(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    private static String cleanHookSnippet(String raw) {
        if (raw == null || raw.isBlank()) return "上次聊到的事";
        String cleaned = raw.replaceFirst("^(上次聊到：|近期关注：|当前目标：|近期手记提到：|待办进度：)", "").strip();
        return cleaned.length() > 45 ? cleaned.substring(0, 45) + "..." : cleaned;
    }
}
