package io.github.aftsakee.aiadmin.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.aftsakee.aiadmin.agent.ActionTools;
import io.github.aftsakee.aiadmin.agent.AgentService;
import io.github.aftsakee.aiadmin.agent.OwnerTools;
import io.github.aftsakee.aiadmin.agent.RunContext;
import io.github.aftsakee.aiadmin.agent.Tools;
import io.github.aftsakee.aiadmin.agent.Trigger;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.config.Rules;
import io.github.aftsakee.aiadmin.llm.Llm;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 远程工具网关：工具目录（tools/list、list_tools）、通用入口（call_read_tool / call_action_tool）、调用路径（隐藏、额度、item_id 身份、审计）。
 * <p>从 McpServer 拆出来（1.0.0.33）："能调哪些工具、怎么调"都在这里；HTTP 闸门在 {@link RemoteGate}，JSON-RPC 协议分发在 {@link McpProtocol}。
 * 只读模式的默认拒绝判定在 {@link ToolAccess#hidden}（这里只收集 rules.json 隐藏项、allow_actions 和当前工具名交给它）。
 * <p>远程上限：全局每分钟调用数（tools/call 和 events/subscribe、unsubscribe 合起来）和每小时指令数（run_command、run_command_for、
 * 宏等实际执行的条数），两者共用 {@link SlidingWindow}，窗口剪枝只有那一份。tools/call 超限回 isError 工具结果并写明多久后可重试；
 * events/* 超限由 McpProtocol 回 HTTP 429 + Retry-After（同一句说明，见 {@link #callLimitMessage}）。
 * <p>线程：被 MiniHttp 的多个处理线程同时调用；两个窗口各用自己的监视器，锁里只做内存操作，不在锁里调用工具。
 */
final class RemoteTools {
    private final Supplier<BotConfig> cfg;
    private final Supplier<Rules> rules;
    private final Tools tools;
    private final AgentService svc;
    private final JsonlLog log;
    private final SlidingWindow calls = new SlidingWindow(60_000L);
    private final SlidingWindow commands = new SlidingWindow(3600_000L);

    RemoteTools(Supplier<BotConfig> cfg, Supplier<Rules> rules, Tools tools, AgentService svc, JsonlLog log) {
        this.cfg = cfg;
        this.rules = rules;
        this.tools = tools;
        this.svc = svc;
        this.log = log;
    }

    /** 远程看不到、也调不了的工具（默认拒绝的判定见 {@link ToolAccess#hidden}）。 */
    Set<String> hidden() {
        return ToolAccess.hidden(rules.get().permissions.hiddenTools(), cfg.get().remote.allowActions, this::offeredNames);
    }

    /** 当前提供的全部工具名：本地工具 + 云端值守工具（不管 rules.json 隐藏没有）。 */
    private List<String> offeredNames() {
        List<String> names = new ArrayList<>();
        for (Llm.ToolSpec s : tools.specs(Set.of())) names.add(s.name());
        for (Llm.ToolSpec s : svc.cloudTools().specs(Set.of())) names.add(s.name());
        return names;
    }

    /** 远程接口自己实现的两个工具（不在 Tools 里，永远提供、只读）。 */
    private static List<Llm.ToolSpec> ownTools() {
        return List.of(new Llm.ToolSpec(ToolAccess.STATUS, "机器人自身状态：接入点、运行情况、启停/额度状态、聊天模式、远程接口。", emptySchema()),
                new Llm.ToolSpec(ToolAccess.QUOTA, "机器人的额度使用情况和各接入点余额（会实时查询余额）。", emptySchema()));
    }

    JsonObject toolsList(boolean modern) {
        JsonArray arr = new JsonArray();
        Set<String> h = hidden();
        for (Llm.ToolSpec s : tools.specs(h)) arr.add(toolJson(s.name(), s.description(), s.parameters()));
        for (Llm.ToolSpec s : svc.cloudTools().specs(h)) arr.add(toolJson(s.name(), s.description(), s.parameters())); // 云端值守工具（只经远程接口提供）
        for (Llm.ToolSpec s : ownTools()) arr.add(toolJson(s.name(), s.description(), s.parameters()));
        for (String g : List.of(ToolAccess.LIST, ToolAccess.CALL_READ, ToolAccess.CALL_ACTION)) {
            if (!h.contains(g)) arr.add(toolJson(g, genericDescription(g), genericSchema(g)));
        }
        JsonObject r = new JsonObject();
        r.add("tools", arr);
        if (modern) {
            // 新版协议要求列表结果带缓存提示：工具表按 rules.json 的隐藏项变化，所以只能客户端私有缓存
            r.addProperty("ttlMs", 60_000);
            r.addProperty("cacheScope", "private");
        }
        return r;
    }

    private static JsonObject toolJson(String name, String desc, JsonObject schema) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", desc);
        o.add("inputSchema", schema);
        // 客户端（ChatGPT 等）按 readOnlyHint 决定调用前要不要让用户确认；分类见 ToolAccess（没登记的按会改动处理）
        JsonObject ann = new JsonObject();
        ann.addProperty("readOnlyHint", ToolAccess.readOnly(name));
        ann.addProperty("openWorldHint", false);
        o.add("annotations", ann);
        return o;
    }

    private static String genericDescription(String g) {
        return switch (g) {
            case ToolAccess.LIST -> "列出服务器当前提供的全部工具（包括你的工具列表里还没有的新工具）和读写类别（read 只读 / action 会改动服务器）；"
                    + "带 name 查看某个工具的完整说明和参数。要用的工具不在你的列表里时，先用它查，再用 call_read_tool / call_action_tool 调用。";
            case ToolAccess.CALL_READ -> "调用一个只读工具（list_tools 里 access=read 的）：name = 工具名，arguments = 它的参数对象。"
                    + "主要用于你的工具列表里还没有的新工具；会改动服务器的工具这里会拒绝，请用 call_action_tool。";
            default -> "调用一个会改动服务器的工具（list_tools 里 access=action 的：执行指令、说话、云端值守、备份/重启等）：name = 工具名，"
                    + "arguments = 它的参数对象。服主的黑名单、额度上限、在线人数检查和审计都和直接调用完全一样；被拒绝时不要换写法绕过。";
        };
    }

    private static JsonObject genericSchema(String g) {
        JsonObject props = new JsonObject();
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", g.equals(ToolAccess.LIST) ? "（可选）要查看说明和参数的工具名；不填则列出全部工具" : "工具名（用 list_tools 查）");
        props.add("name", name);
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        if (!g.equals(ToolAccess.LIST)) {
            JsonObject args = new JsonObject();
            args.addProperty("type", "object");
            args.addProperty("description", "这个工具的参数（按 list_tools 给出的 input_schema 填）；没有参数就传 {}");
            props.add("arguments", args);
            JsonArray req = new JsonArray();
            req.add("name");
            s.add("required", req);
        }
        s.add("properties", props);
        return s;
    }

    /** list_tools：不带 name 列出全部（名字、类别、说明第一句），带 name 给完整说明和参数。被隐藏的工具不出现。 */
    private String listTools(JsonObject args) {
        Set<String> h = hidden();
        List<Llm.ToolSpec> all = new ArrayList<>(tools.specs(h));
        all.addAll(svc.cloudTools().specs(h));
        all.addAll(ownTools());
        String want = args.has("name") && args.get("name").isJsonPrimitive() ? args.get("name").getAsString().trim() : "";
        if (!want.isEmpty()) {
            for (Llm.ToolSpec t : all) {
                if (!t.name().equals(want)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("name", t.name());
                o.addProperty("access", ToolAccess.readOnly(t.name()) ? "read" : "action");
                o.addProperty("call_with", ToolAccess.readOnly(t.name()) ? ToolAccess.CALL_READ : ToolAccess.CALL_ACTION);
                o.addProperty("description", t.description());
                o.add("input_schema", t.parameters());
                return o.toString();
            }
            return "没有这个工具或已被服主禁用：" + want + "（不带 name 调用 list_tools 看全部）";
        }
        JsonArray arr = new JsonArray();
        for (Llm.ToolSpec t : all) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("access", ToolAccess.readOnly(t.name()) ? "read" : "action");
            String d = t.description();
            int cut = d.indexOf('。');
            o.addProperty("summary", Text.brief(cut > 0 ? d.substring(0, cut + 1) : d, 120));
            arr.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("tools", arr);
        r.addProperty("how", "用 list_tools 带 name 查参数，再用 call_read_tool（access=read）或 call_action_tool（access=action）调用：{\"name\": 工具名, \"arguments\": {...}}");
        return r.toString();
    }

    private static JsonObject emptySchema() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", new JsonObject());
        return s;
    }

    /**
     * 滑动窗口计数（每分钟调用数、每小时指令数共用；窗口剪枝只在这里）：记每次的时间戳，超出窗口的先丢掉再判断。
     * <p>线程：每个实例用自己的监视器，被多个处理线程同时调用；锁里只做内存操作。上限按调用时的设置传入（/aibot reload 改了立即生效）。
     * 边界：宏是"先看余量、执行后补记"，并发的宏可能让队列短暂超过上限——超出的条数照样按时间滑出，只是之后要多等一会儿，不会放宽上限。
     */
    private static final class SlidingWindow {
        private final long windowMs;
        private final ArrayDeque<Long> stamps = new ArrayDeque<>();

        SlidingWindow(long windowMs) {
            this.windowMs = windowMs;
        }

        /** 还有余量返回 0（take = true 时同时记一条；宏只先看余量，执行后按实际条数 {@link #add}）；没有余量返回最早一条滑出窗口还要等的毫秒数（≥ 1）。 */
        synchronized long tryTake(long now, int max, boolean take) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > windowMs) stamps.pollFirst();
            if (stamps.size() >= max) return stamps.isEmpty() ? windowMs : Math.max(1, stamps.peekFirst() + windowMs + 1 - now);
            if (take) stamps.addLast(now);
            return 0;
        }

        synchronized void add(long now, int n) {
            for (int i = 0; i < n; i++) stamps.addLast(now);
        }
    }

    /** 全局每分钟调用数（tools/call 和 events/subscribe、unsubscribe 合起来算）：返回 0 = 有余量、已记一次；否则 = 超限，至少还要等几秒（HTTP Retry-After 用）。 */
    long takeCallSlot() {
        long wait = calls.tryTake(System.currentTimeMillis(), cfg.get().remote.maxCallsPerMinute, true);
        return wait == 0 ? 0 : (wait + 999) / 1000;
    }

    /** 超过每分钟上限时给调用方看的说明：tools/call 的 isError 结果和 events/* 的 429 错误体共用这一句。 */
    String callLimitMessage(long waitSeconds) {
        return "调用太频繁（每分钟上限 " + cfg.get().remote.maxCallsPerMinute + "），请 " + waitSeconds + " 秒后再试";
    }

    private static String commandLimitMessage(int max, long waitMs) {
        long wait = waitMs < 120_000L ? (waitMs + 999) / 1000 : (waitMs + 59_999) / 60_000;
        return "本小时远程指令数已达上限 " + max + "（按最近一小时滑动计算，约 " + wait + (waitMs < 120_000L ? " 秒" : " 分钟") + "后可再执行）";
    }

    JsonObject callTool(JsonObject params, String client, String clientInfo) {
        String name = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
        BotConfig c = cfg.get();
        long now = System.currentTimeMillis();
        long callWait = takeCallSlot();
        if (callWait > 0) return toolResult(callLimitMessage(callWait), true);
        if (svc.locked()) return toolResult("机器人设置文件有误、已锁定，请先修好设置后 /aibot reload", true);
        String cid = "remote-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000000, 0x7fffffff));
        // 参数放进 data（JsonlLog 会按键名脱敏），message 只写工具名
        log.info("remote", "remote.call", name, cid,
                JsonlLog.data("tool", name, "client", client, "client_info", clientInfo, "args", Text.brief(args.toString(), 300)));
        if (name.equals(ToolAccess.LIST)) return toolResult(listTools(args), false);
        if (name.equals(ToolAccess.CALL_READ) || name.equals(ToolAccess.CALL_ACTION)) {
            // 通用入口：拆出真正的工具名和参数，走下面同一条路径（同样的隐藏、额度、审计）；整次只算一次调用频率
            if (hidden().contains(name)) return toolResult("没有这个工具或已被服主禁用：" + name, true);
            String inner = args.has("name") && args.get("name").isJsonPrimitive() ? args.get("name").getAsString().trim() : "";
            JsonObject innerArgs;
            try {
                innerArgs = innerArguments(args);
            } catch (RuntimeException e) {
                return toolResult("arguments 要是一个 JSON 对象：" + e.getMessage(), true);
            }
            if (inner.isEmpty()) return toolResult("缺少 name（工具名，用 list_tools 查）", true);
            if (ToolAccess.GENERIC.contains(inner)) return toolResult("不能通过通用入口再调用通用入口：" + inner, true);
            if (name.equals(ToolAccess.CALL_READ) && !ToolAccess.readOnly(inner)) {
                return toolResult(inner + " 会改动服务器（或不是登记过的只读工具），请改用 " + ToolAccess.CALL_ACTION, true);
            }
            log.info("remote", "remote.call_generic", name + " → " + inner, cid, JsonlLog.data("tool", inner, "via", name, "client", client));
            name = inner;
            args = innerArgs;
        }
        // 只有云端值守工具（wait_events、reply_chat…）算心跳：服主偶尔用连接器查一次状态，不该让接下来 2 分钟的聊天都等云端
        if (svc.cloudTools().get(name) != null) svc.cloudHeartbeat();
        if (name.equals(ToolAccess.STATUS)) return toolResult(svc.status(), false);
        if (name.equals(ToolAccess.QUOTA)) return toolResult(svc.quotaReportBlocking(30_000), false);
        Set<String> h = hidden();
        Tools.Tool t = h.contains(name) ? null : tools.get(name);
        if (t == null && !h.contains(name)) t = svc.cloudTools().get(name);
        if (t == null) return toolResult("没有这个工具或已被服主禁用：" + name, true);
        // 远程每小时指令上限：run_command 和云端的 run_command_for 合起来算，调用前各记 1 条；
        // 宏一次会执行多条指令：先只看本小时还有没有余量（具体用了几条在执行后按实际计入）
        boolean counted = name.equals(ActionTools.RUN_COMMAND) || name.equals(AgentService.CLOUD_RUN_FOR);
        if (counted || name.equals(OwnerTools.RUN_MACRO)) {
            long wait = commands.tryTake(now, c.remote.maxCommandsPerHour, counted);
            if (wait > 0) return toolResult(commandLimitMessage(c.remote.maxCommandsPerHour, wait), true);
        }
        // 云端代表某条收件箱消息调用其它工具（send_chat、set_player_pref、run_macro…）时带上 item_id：
        // 动作类按这条消息的身份执行（玩家聊天的限制——只能改本人偏好、宏的 from_player_chat、"别说了"——照样生效），
        // 不带的话这些调用都是"远程 = 服主"身份，玩家聊天里的提示注入就能借云端绕过这些限制；
        // 查询类只刷新这条消息的活动时间（身份仍是远程，IP 隐藏等不变）
        RunContext ctx = null;
        String itemId = svc.cloudTools().get(name) == null && args.has("item_id") && args.get("item_id").isJsonPrimitive()
                ? args.get("item_id").getAsString().trim() : "";
        if (!itemId.isEmpty()) {
            args = args.deepCopy();
            args.remove("item_id");
            if (ToolAccess.readOnly(name)) {
                svc.cloudTouch(itemId);
            } else {
                ctx = svc.cloudContext(itemId);
                if (ctx == null) return toolResult("item_id " + itemId + " 不是正在处理的消息（已完成、已超时关闭、已交给本地 AI 或不存在），不能代表它执行动作", true);
            }
        }
        if (ctx == null) ctx = new RunContext(cid, Trigger.remote(client), c, rules.get(), "remote");
        int before = ctx.commandsUsed(); // 同一条消息的上下文会被多次调用复用，只算这一次新增的
        try {
            return toolResult(Text.clip(t.handler().call(args, ctx), c.limits.toolResultMaxChars * 4), false);
        } catch (Exception e) {
            return toolResult(e.getClass().getSimpleName() + "：" + e.getMessage(), true);
        } finally {
            // run_command / run_command_for 调用前已各记 1 条；其他工具（宏等）按这次实际执行的指令数补记，保证每小时上限对所有途径都有效
            int used = ctx.commandsUsed() - before - (counted ? 1 : 0);
            if (used > 0) commands.add(System.currentTimeMillis(), used);
        }
    }

    /** 通用入口的 arguments：对象原样用；有的客户端会把它序列化成字符串，也接受（必须解析成对象）；没给就是空对象。 */
    private static JsonObject innerArguments(JsonObject args) {
        JsonElement a = args.get("arguments");
        if (a == null || a.isJsonNull()) return new JsonObject();
        if (a.isJsonObject()) return a.getAsJsonObject();
        if (a.isJsonPrimitive() && a.getAsJsonPrimitive().isString()) {
            String t = a.getAsString().trim();
            if (t.isEmpty()) return new JsonObject();
            JsonElement e = JsonParser.parseString(t);
            if (e.isJsonObject()) return e.getAsJsonObject();
        }
        throw new IllegalArgumentException("收到的是 " + Text.brief(a.toString(), 80));
    }

    static JsonObject toolResult(String text, boolean isError) {
        JsonObject r = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", text);
        content.add(t);
        r.add("content", content);
        r.addProperty("isError", isError);
        return r;
    }
}
