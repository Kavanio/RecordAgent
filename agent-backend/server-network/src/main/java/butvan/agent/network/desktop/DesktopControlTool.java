package butvan.agent.network.desktop;

import butvan.agent.agents.tool.AgentToolModule;
import butvan.agent.network.desktop.DesktopControlService.ToolInvocationOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 {@code agent-desktop} CLI 的电脑桌面观察与操作工具集。
 *
 * <p>只读观察工具（status/apps/snapshot/find/inspect/wait/guide）纳入自动放行白名单；
 * 所有写操作工具（action/input/window）进入统一人工审批流程，且默认使用无头语义交互（headless）。</p>
 */
@Component
@RequiredArgsConstructor
public class DesktopControlTool implements AgentToolModule {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_WAIT_MS = 30_000;
    private static final int MAX_DEPTH = 20;

    private static final Set<String> VALID_SURFACES = Set.of(
            "window", "focused", "menu", "menubar", "sheet", "popover", "alert",
            "desktop", "taskbar", "system-tray", "quick-settings", "notification-center",
            "toolbar", "dock", "spotlight", "menu-bar-extras", "system-tray-overflow",
            "start-menu", "action-center"
    );

    private static final Set<String> VALID_GET_PROPERTIES = Set.of(
            "text", "value", "title", "bounds", "role", "states"
    );

    private static final Set<String> VALID_IS_PROPERTIES = Set.of(
            "visible", "enabled", "checked", "focused", "expanded", "selected"
    );

    private static final Set<String> VALID_WAIT_EVENTS = Set.of(
            "window-opened", "window-closed", "app-launched", "app-terminated",
            "focus-changed", "surface-appeared", "surface-dismissed"
    );

    private static final Set<String> VALID_WAIT_PREDICATES = Set.of(
            "exists", "enabled", "visible", "actionable", "value"
    );

    private static final Set<String> VALID_SCROLL_DIRECTIONS = Set.of("up", "down", "left", "right");

    private final DesktopControlService service;

    // ==================== 只读观察工具（自动放行） ====================

    @Tool(name = "desktop_status",
            description = "检查后端所在电脑的桌面操控环境状态、操作系统平台、无障碍权限及可选显示器信息。只读。",
            readOnly = true)
    public ToolResultBlock status(
            @ToolParam(name = "request", description = "可选参数；includeDisplays=true 时返回显示器列表") StatusRequest request,
            RuntimeContext context
    ) {
        boolean includeDisplays = request != null && Boolean.TRUE.equals(request.includeDisplays());
        List<String> args = includeDisplays ? List.of("list-displays") : List.of("status");
        return toResult(service.invokeForTool(sessionId(context), args, null, READ_TIMEOUT, false, false));
    }

    @Tool(name = "desktop_apps",
            description = "列出后端所在电脑正在运行的 GUI 应用或可见窗口（包含 windowId 和应用名）。只读。",
            readOnly = true)
    public ToolResultBlock apps(
            @ToolParam(name = "request", description = "view 为 apps 或 windows（默认 windows）；可选 app 按应用名过滤") AppsRequest request,
            RuntimeContext context
    ) {
        try {
            String view = request == null || isBlank(request.view())
                    ? "windows"
                    : request.view().strip().toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>();
            switch (view) {
                case "apps" -> args.add("list-apps");
                case "windows" -> args.add("list-windows");
                default -> throw new IllegalArgumentException("view 只能是 apps 或 windows");
            }
            if (request != null && !isBlank(request.app())) {
                args.add("--app");
                args.add(validatePlainValue(request.app(), "app"));
            }
            return toResult(service.invokeForTool(sessionId(context), args, null, READ_TIMEOUT, false, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    @Tool(name = "desktop_snapshot",
            description = "抓取指定应用、窗口或局部 @ref 的无障碍界面树（返回带 @ref 的结构化 JSON）。"
                    + "建议先传 skeleton=true 获取骨架，再用 rootRef 深入具体区域。只读。",
            readOnly = true)
    public ToolResultBlock snapshot(
            @ToolParam(name = "request", description = "可选 app、windowId、rootRef、surface、skeleton、interactiveOnly、compact、maxDepth、includeBounds") SnapshotRequest request,
            RuntimeContext context
    ) {
        try {
            List<String> args = new ArrayList<>();
            args.add("snapshot");
            if (request != null) {
                if (!isBlank(request.app())) {
                    args.add("--app");
                    args.add(validatePlainValue(request.app(), "app"));
                }
                if (!isBlank(request.windowId())) {
                    args.add("--window-id");
                    args.add(validatePlainValue(request.windowId(), "windowId"));
                }
                if (!isBlank(request.rootRef())) {
                    args.add("--root");
                    args.add(validateRef(request.rootRef(), "rootRef"));
                }
                if (!isBlank(request.surface())) {
                    String surface = request.surface().strip().toLowerCase(Locale.ROOT);
                    if (!VALID_SURFACES.contains(surface)) {
                        throw new IllegalArgumentException("不支持的 surface：" + surface);
                    }
                    args.add("--surface");
                    args.add(surface);
                }
                if (request.maxDepth() != null) {
                    int depth = request.maxDepth();
                    if (depth < 1 || depth > MAX_DEPTH) {
                        throw new IllegalArgumentException("maxDepth 必须在 1 到 " + MAX_DEPTH + " 之间");
                    }
                    args.add("--max-depth");
                    args.add(Integer.toString(depth));
                }
                if (Boolean.TRUE.equals(request.skeleton())) {
                    args.add("--skeleton");
                }
                if (Boolean.TRUE.equals(request.includeBounds())) {
                    args.add("--include-bounds");
                }
                boolean interactiveOnly = request.interactiveOnly() == null || request.interactiveOnly();
                boolean compact = request.compact() == null || request.compact();
                if (interactiveOnly) args.add("-i");
                if (compact) args.add("--compact");
            } else {
                args.add("-i");
                args.add("--compact");
            }
            return toResult(service.invokeForTool(sessionId(context), args, null, READ_TIMEOUT, false, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    @Tool(name = "desktop_find",
            description = "按角色（role）、名称（name）、文本（text）或值（value）在桌面无障碍树中搜索匹配元素并返回 @ref。只读。",
            readOnly = true)
    public ToolResultBlock find(
            @ToolParam(name = "request", description = "筛选条件：app、windowId、rootRef、role、name、text、value、exact") FindRequest request,
            RuntimeContext context
    ) {
        try {
            if (request == null) throw new IllegalArgumentException("find 请求参数不能为空");
            List<String> args = new ArrayList<>();
            args.add("find");
            if (!isBlank(request.app())) {
                args.add("--app");
                args.add(validatePlainValue(request.app(), "app"));
            }
            if (!isBlank(request.windowId())) {
                args.add("--window-id");
                args.add(validatePlainValue(request.windowId(), "windowId"));
            }
            if (!isBlank(request.rootRef())) {
                args.add("--root");
                args.add(validateRef(request.rootRef(), "rootRef"));
            }
            if (!isBlank(request.role())) {
                args.add("--role");
                args.add(validatePlainValue(request.role(), "role"));
            }
            if (!isBlank(request.name())) {
                args.add("--name");
                args.add(validateTextValue(request.name(), "name"));
            }
            if (!isBlank(request.text())) {
                args.add("--text");
                args.add(validateTextValue(request.text(), "text"));
            }
            if (!isBlank(request.value())) {
                args.add("--value");
                args.add(validateTextValue(request.value(), "value"));
            }
            if (Boolean.TRUE.equals(request.exact())) {
                args.add("--exact");
            }
            if (args.size() == 1) {
                throw new IllegalArgumentException("至少需要提供 role、name、text 或 value 之一");
            }
            return toResult(service.invokeForTool(sessionId(context), args, null, READ_TIMEOUT, false, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    @Tool(name = "desktop_inspect",
            description = "读取单个 @ref 元素的属性（get：text/value/title/bounds/role/states）或布尔状态（is：visible/enabled/checked/focused/expanded/selected）。只读。",
            readOnly = true)
    public ToolResultBlock inspect(
            @ToolParam(name = "request", description = "ref（必填）、mode（get 或 is，默认 get）、property") InspectRequest request,
            RuntimeContext context
    ) {
        try {
            if (request == null) throw new IllegalArgumentException("inspect 请求参数不能为空");
            String ref = validateRef(request.ref(), "ref");
            String mode = isBlank(request.mode()) ? "get" : request.mode().strip().toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>();
            switch (mode) {
                case "get" -> {
                    String property = isBlank(request.property()) ? "text" : request.property().strip().toLowerCase(Locale.ROOT);
                    if (!VALID_GET_PROPERTIES.contains(property)) {
                        throw new IllegalArgumentException("get 支持的 property 仅限：" + VALID_GET_PROPERTIES);
                    }
                    args.add("get");
                    args.add("--property");
                    args.add(property);
                    args.add(ref);
                }
                case "is" -> {
                    String property = isBlank(request.property()) ? "visible" : request.property().strip().toLowerCase(Locale.ROOT);
                    if (!VALID_IS_PROPERTIES.contains(property)) {
                        throw new IllegalArgumentException("is 支持的 property 仅限：" + VALID_IS_PROPERTIES);
                    }
                    args.add("is");
                    args.add("--property");
                    args.add(property);
                    args.add(ref);
                }
                default -> throw new IllegalArgumentException("mode 只能是 get 或 is");
            }
            return toResult(service.invokeForTool(sessionId(context), args, null, READ_TIMEOUT, false, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    @Tool(name = "desktop_wait",
            description = "等待界面元素、文本、窗口、桌面生命周期事件或短暂延时就绪（单次最多 30000ms），替代盲目重试。只读。",
            readOnly = true)
    public ToolResultBlock waitCondition(
            @ToolParam(name = "request", description = "可选 elementRef、predicate、value、text、windowTitle、event、app、timeoutMs、delayMs") WaitRequest request,
            RuntimeContext context
    ) {
        try {
            if (request == null) throw new IllegalArgumentException("wait 请求参数不能为空");
            List<String> args = new ArrayList<>();
            args.add("wait");

            int timeoutMs = request.timeoutMs() == null ? 10_000 : request.timeoutMs();
            if (timeoutMs < 100 || timeoutMs > MAX_WAIT_MS) {
                throw new IllegalArgumentException("timeoutMs 必须在 100 到 " + MAX_WAIT_MS + " 之间");
            }

            boolean hasCondition = false;
            if (!isBlank(request.elementRef())) {
                args.add("--element");
                args.add(validateRef(request.elementRef(), "elementRef"));
                hasCondition = true;
                if (!isBlank(request.predicate())) {
                    String predicate = request.predicate().strip().toLowerCase(Locale.ROOT);
                    if (!VALID_WAIT_PREDICATES.contains(predicate)) {
                        throw new IllegalArgumentException("predicate 仅支持：" + VALID_WAIT_PREDICATES);
                    }
                    args.add("--predicate");
                    args.add(predicate);
                }
                if (!isBlank(request.value())) {
                    args.add("--value");
                    args.add(validateTextValue(request.value(), "value"));
                }
            }
            if (!isBlank(request.windowTitle())) {
                args.add("--window");
                args.add(validateTextValue(request.windowTitle(), "windowTitle"));
                hasCondition = true;
            }
            if (!isBlank(request.text())) {
                args.add("--text");
                args.add(validateTextValue(request.text(), "text"));
                hasCondition = true;
            }
            if (!isBlank(request.event())) {
                String event = request.event().strip().toLowerCase(Locale.ROOT);
                if (!VALID_WAIT_EVENTS.contains(event)) {
                    throw new IllegalArgumentException("event 仅支持：" + VALID_WAIT_EVENTS);
                }
                args.add("--event");
                args.add(event);
                hasCondition = true;
            }
            if (!isBlank(request.app())) {
                args.add("--app");
                args.add(validatePlainValue(request.app(), "app"));
            }

            if (hasCondition) {
                args.add("--timeout");
                args.add(Integer.toString(timeoutMs));
            } else if (request.delayMs() != null) {
                int delay = request.delayMs();
                if (delay < 50 || delay > MAX_WAIT_MS) {
                    throw new IllegalArgumentException("delayMs 必须在 50 到 " + MAX_WAIT_MS + " 之间");
                }
                args.add(Integer.toString(delay));
                timeoutMs = delay;
            } else {
                throw new IllegalArgumentException("至少需要指定 elementRef、windowTitle、text、event 或 delayMs 之一");
            }

            Duration processTimeout = Duration.ofMillis(timeoutMs + 5_000L);
            return toResult(service.invokeForTool(sessionId(context), args, null, processTimeout, false, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    @Tool(name = "desktop_guide",
            description = "按需读取当前系统对应的 agent-desktop 内置操作指南（topic 为 desktop 或 platform），了解 macOS/Windows 平台差异与最佳实践。只读。",
            readOnly = true)
    public ToolResultBlock guide(
            @ToolParam(name = "request", description = "topic 为 desktop（核心循环）或 platform（当前 OS 差异），默认 platform") GuideRequest request,
            RuntimeContext context
    ) {
        try {
            String topic = request == null || isBlank(request.topic())
                    ? "platform"
                    : request.topic().strip().toLowerCase(Locale.ROOT);
            if (!"desktop".equals(topic) && !"platform".equals(topic)) {
                throw new IllegalArgumentException("topic 只能是 desktop 或 platform");
            }
            return toResult(service.invokeForTool(
                    sessionId(context), List.of("skills", "get", topic), null, READ_TIMEOUT, false, false));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    // ==================== 写操作工具（强制审批） ====================

    @Tool(name = "desktop_action",
            description = "对无障碍树中的 @ref 元素执行交互操作（click/double-click/right-click/focus/select/toggle/check/uncheck/expand/collapse/scroll/scroll-to）。"
                    + "默认无头语义执行；当需要激活输入框光标（如浏览器地址栏、网页搜索框）或目标控件不支持无头操作时传 headed=true。需要用户审批。")
    public ToolResultBlock action(
            @ToolParam(name = "request", description = "action、ref（必填）；select 传 value；scroll 传 direction 与 amount；可选 headed") ActionRequest request,
            RuntimeContext context
    ) {
        try {
            if (request == null) throw new IllegalArgumentException("action 请求参数不能为空");
            String action = required(request.action(), "action").toLowerCase(Locale.ROOT);
            String ref = validateRef(request.ref(), "ref");
            boolean headed = Boolean.TRUE.equals(request.headed());

            List<String> args = new ArrayList<>();
            if (headed || "double-click".equals(action)) {
                args.add("--headed");
            }
            switch (action) {
                case "click", "double-click", "right-click", "focus",
                        "toggle", "check", "uncheck", "expand", "collapse", "scroll-to" -> {
                    args.add(action);
                    args.add(ref);
                }
                case "select" -> {
                    args.add("select");
                    args.add(ref);
                    args.add(validateTextValue(required(request.value(), "value"), "value"));
                }
                case "scroll" -> {
                    String direction = isBlank(request.direction())
                            ? "down"
                            : request.direction().strip().toLowerCase(Locale.ROOT);
                    if (!VALID_SCROLL_DIRECTIONS.contains(direction)) {
                        throw new IllegalArgumentException("direction 仅支持 up、down、left、right");
                    }
                    int amount = request.amount() == null ? 3 : request.amount();
                    if (amount < 1 || amount > 50) {
                        throw new IllegalArgumentException("amount 必须在 1 到 50 之间");
                    }
                    args.add("scroll");
                    args.add("--direction");
                    args.add(direction);
                    args.add("--amount");
                    args.add(Integer.toString(amount));
                    args.add(ref);
                }
                default -> throw new IllegalArgumentException("不支持的桌面操作：" + action);
            }
            return toResult(service.invokeForTool(sessionId(context), args, null, WRITE_TIMEOUT, true, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    @Tool(name = "desktop_input",
            description = "向桌面元素输入文本、设值、清空或发送按键与快捷键（mode 为 type/set-value/clear/press）。"
                    + "press 与默认未显式设 headed=false 的 type 会启用物理焦点投递（--headed）以确保按键不丢失；"
                    + "注意：若要打开网页 URL，请优先调用 desktop_window 的 open-url 动作直接精确打开网址，避免浏览器地址栏历史记录自动补全（如把 chatgpt.com 补全为 chatgpt.com/images）；"
                    + "若必须在地址栏 type 网址，请在网址末尾加一个空格（如 'https://chatgpt.com/ '）以抑制浏览器自动补全后再 press return。"
                    + "macOS 快捷键用 cmd+l/cmd+t/cmd+c，Windows 用 ctrl+l/ctrl+t/ctrl+c。需要用户审批。")
    public ToolResultBlock input(
            @ToolParam(name = "request", description = "mode（必填）；type/set-value/clear 需 ref；type/set-value 需 text；press 需 combo 与可选 app/windowId；可选 headed") InputRequest request,
            RuntimeContext context
    ) {
        try {
            if (request == null) throw new IllegalArgumentException("input 请求参数不能为空");
            String mode = required(request.mode(), "mode").toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>();
            boolean defaultHeadedMode = "press".equals(mode) || "type".equals(mode);
            boolean useHeaded = request.headed() != null ? request.headed() : defaultHeadedMode;
            if (useHeaded) {
                args.add("--headed");
            }
            String stdinText = null;
            switch (mode) {
                case "type" -> {
                    String ref = validateRef(request.ref(), "ref");
                    if (request.text() == null) throw new IllegalArgumentException("type 模式必须提供 text");
                    args.add("type");
                    args.add("--stdin");
                    args.add(ref);
                    stdinText = preventBrowserOmniboxAutocomplete(request.text());
                }
                case "set-value" -> {
                    String ref = validateRef(request.ref(), "ref");
                    if (request.text() == null) throw new IllegalArgumentException("set-value 模式必须提供 text");
                    args.add("set-value");
                    args.add("--stdin");
                    args.add(ref);
                    stdinText = request.text();
                }
                case "clear" -> {
                    String ref = validateRef(request.ref(), "ref");
                    args.add("clear");
                    args.add(ref);
                }
                case "press" -> {
                    String combo = validatePlainValue(required(request.combo(), "combo"), "combo");
                    args.add("press");
                    if (!isBlank(request.app())) {
                        args.add("--app");
                        args.add(validatePlainValue(request.app(), "app"));
                    }
                    if (!isBlank(request.windowId())) {
                        args.add("--window-id");
                        args.add(validatePlainValue(request.windowId(), "windowId"));
                    }
                    args.add(combo);
                }
                default -> throw new IllegalArgumentException("mode 只能是 type、set-value、clear 或 press");
            }
            return toResult(service.invokeForTool(sessionId(context), args, stdinText, WRITE_TIMEOUT, true, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    @Tool(name = "desktop_window",
            description = "管理后端所在电脑的应用与窗口（launch/open-url/close-app/focus-window/minimize/maximize/restore/resize-window/move-window）。"
                    + "访问网页时优先使用 action=open-url（传 url 与可选浏览器 app 如 'Google Chrome'），可直接精确打开目标网址，避免在浏览器地址栏打字触发历史记录自动补全错误路径。"
                    + "close-app 仅优雅退出，不强杀进程。需要用户审批。")
    public ToolResultBlock window(
            @ToolParam(name = "request", description = "action（必填）及对应目标 app、url（open-url 必填，仅限 http/https）、windowId、title、width/height、x/y") WindowRequest request,
            RuntimeContext context
    ) {
        try {
            if (request == null) throw new IllegalArgumentException("window 请求参数不能为空");
            String action = required(request.action(), "action").toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>();
            switch (action) {
                case "launch" -> {
                    args.add("launch");
                    args.add("--activate");
                    args.add(validatePlainValue(required(request.app(), "app"), "app"));
                    if (!isBlank(request.url())) {
                        args.add("--arg");
                        args.add(validateHttpUrl(request.url()));
                    }
                }
                case "open-url" -> {
                    String url = validateHttpUrl(required(request.url(), "url"));
                    String app = isBlank(request.app()) ? "Google Chrome" : validatePlainValue(request.app(), "app");
                    return toResult(service.openUrlForTool(app, url));
                }
                case "close-app" -> {
                    args.add("close-app");
                    args.add(validatePlainValue(required(request.app(), "app"), "app"));
                }
                case "focus-window" -> {
                    args.add("focus-window");
                    boolean hasTarget = appendWindowTarget(args, request.app(), request.windowId());
                    if (!isBlank(request.title())) {
                        args.add("--title");
                        args.add(validateTextValue(request.title(), "title"));
                        hasTarget = true;
                    }
                    if (!hasTarget) {
                        throw new IllegalArgumentException("focus-window 至少需要 app、windowId 或 title 之一");
                    }
                }
                case "minimize", "maximize", "restore" -> {
                    args.add(action);
                    if (!appendWindowTarget(args, request.app(), request.windowId())) {
                        throw new IllegalArgumentException(action + " 至少需要 app 或 windowId 之一");
                    }
                }
                case "resize-window" -> {
                    if (request.width() == null || request.height() == null) {
                        throw new IllegalArgumentException("resize-window 必须提供 width 和 height");
                    }
                    if (request.width() < 100 || request.width() > 10_000
                            || request.height() < 100 || request.height() > 10_000) {
                        throw new IllegalArgumentException("窗口尺寸必须在 100 到 10000 像素之间");
                    }
                    args.add("resize-window");
                    if (!appendWindowTarget(args, request.app(), request.windowId())) {
                        throw new IllegalArgumentException("resize-window 至少需要 app 或 windowId 之一");
                    }
                    args.add("--width");
                    args.add(Integer.toString(request.width()));
                    args.add("--height");
                    args.add(Integer.toString(request.height()));
                }
                case "move-window" -> {
                    if (request.x() == null || request.y() == null) {
                        throw new IllegalArgumentException("move-window 必须提供 x 和 y");
                    }
                    if (request.x() < -10_000 || request.x() > 20_000
                            || request.y() < -10_000 || request.y() > 20_000) {
                        throw new IllegalArgumentException("窗口坐标超出合理范围");
                    }
                    args.add("move-window");
                    if (!appendWindowTarget(args, request.app(), request.windowId())) {
                        throw new IllegalArgumentException("move-window 至少需要 app 或 windowId 之一");
                    }
                    args.add("--x=" + request.x());
                    args.add("--y=" + request.y());
                }
                default -> throw new IllegalArgumentException("不支持的窗口操作：" + action);
            }
            return toResult(service.invokeForTool(sessionId(context), args, null, WRITE_TIMEOUT, true, true));
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        }
    }

    // ==================== 校验与辅助方法 ====================

    private static boolean appendWindowTarget(List<String> args, String app, String windowId) {
        boolean added = false;
        if (!isBlank(app)) {
            args.add("--app");
            args.add(validatePlainValue(app, "app"));
            added = true;
        }
        if (!isBlank(windowId)) {
            args.add("--window-id");
            args.add(validatePlainValue(windowId, "windowId"));
            added = true;
        }
        return added;
    }

    private static String sessionId(RuntimeContext context) {
        return context == null ? null : context.getSessionId();
    }

    private static ToolResultBlock toResult(ToolInvocationOutcome outcome) {
        return outcome.ok() ? ToolResultBlock.text(outcome.content()) : ToolResultBlock.error(outcome.content());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String required(String value, String fieldName) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("缺少必填参数：" + fieldName);
        }
        return value.strip();
    }

    private static String validateRef(String ref, String fieldName) {
        String value = required(ref, fieldName);
        if (!value.startsWith("@") || value.length() > 128 || containsControlChars(value)) {
            throw new IllegalArgumentException(fieldName + " 必须是合法的 @ref 标识（例如 @s1234abcd:e5）");
        }
        return value;
    }

    private static String validatePlainValue(String raw, String fieldName) {
        String value = required(raw, fieldName);
        if (value.startsWith("-") || value.length() > 256 || containsControlChars(value)) {
            throw new IllegalArgumentException("参数 " + fieldName + " 格式非法");
        }
        return value;
    }

    private static String validateTextValue(String raw, String fieldName) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("参数 " + fieldName + " 不能为空");
        }
        if (raw.length() > 512 || raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("参数 " + fieldName + " 长度超限或包含非法字符");
        }
        return raw;
    }

    static String validateHttpUrl(String rawUrl) {
        String value = required(rawUrl, "url");
        if (value.length() > 2048 || containsControlChars(value) || value.contains(" ")) {
            throw new IllegalArgumentException("参数 url 格式非法");
        }
        if (!value.contains("://") && value.matches("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?$")) {
            value = "https://" + value;
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || isBlank(uri.getHost())) {
                throw new IllegalArgumentException("url 仅支持合法的 http:// 或 https:// 网址");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("url 仅支持合法的 http:// 或 https:// 网址");
        }
    }

    /**
     * 当向输入框 type 纯域名或无子路径的 http(s) 根网址（例如 {@code https://chatgpt.com} 或 {@code chatgpt.com}）时，
     * 在末尾自动补一个空格（如 {@code https://chatgpt.com/ }），抑制 Chrome/Safari/Edge 地址栏的联机历史子路径自动补全
     * （例如自动高亮补全成 {@code https://chatgpt.com/images} 导致按回车进错页面）。
     */
    static String preventBrowserOmniboxAutocomplete(String text) {
        if (text == null) return null;
        String stripped = text.strip();
        if (stripped.matches("^(https?://)?[A-Za-z0-9.-]+\\.[A-Za-z]{2,}/?$")) {
            String withSlash = stripped.endsWith("/") ? stripped : stripped + "/";
            return withSlash + " ";
        }
        return text;
    }

    private static boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return true;
        }
        return false;
    }

    // ==================== Tool 请求参数 Record ====================

    public record StatusRequest(Boolean includeDisplays) {}

    public record AppsRequest(String view, String app) {}

    public record SnapshotRequest(
            String app,
            String windowId,
            String rootRef,
            String surface,
            Boolean skeleton,
            Boolean interactiveOnly,
            Boolean compact,
            Integer maxDepth,
            Boolean includeBounds
    ) {}

    public record FindRequest(
            String app,
            String windowId,
            String rootRef,
            String role,
            String name,
            String text,
            String value,
            Boolean exact
    ) {}

    public record InspectRequest(String ref, String mode, String property) {}

    public record WaitRequest(
            String elementRef,
            String predicate,
            String value,
            String text,
            String windowTitle,
            String event,
            String app,
            Integer timeoutMs,
            Integer delayMs
    ) {}

    public record GuideRequest(String topic) {}

    public record ActionRequest(
            String action,
            String ref,
            String value,
            String direction,
            Integer amount,
            Boolean headed
    ) {}

    public record InputRequest(
            String mode,
            String ref,
            String text,
            String combo,
            String app,
            String windowId,
            Boolean headed
    ) {}

    public record WindowRequest(
            String action,
            String app,
            String url,
            String windowId,
            String title,
            Integer width,
            Integer height,
            Integer x,
            Integer y
    ) {}
}
