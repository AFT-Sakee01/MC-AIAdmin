package io.github.aftsakee.aiadmin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.aftsakee.aiadmin.config.BotConfig;
import io.github.aftsakee.aiadmin.log.JsonlLog;
import io.github.aftsakee.aiadmin.util.Text;

import java.util.List;

/**
 * 云端值守工具（只经远程接口提供，本地代理看不到）：
 * <ul>
 *   <li>wait_events：长轮询收件箱（聊天/事件/巡检），同时是心跳；</li>
 *   <li>reply_chat / run_command_for：针对某条消息说话、执行指令——直接调用本地代理同一个 send_chat / run_command 处理器，
 *       上下文用那条消息的触发（聊天原文、【已授权】标记、预设模式、恶作剧档位、每次运行的指令/聊天上限），所以检查和本地完全一致；</li>
 *   <li>complete_item：处理完写进共享的行动记录；</li>
 *   <li>memory_read / memory_note：读写和本地共用的长期记忆；get_bot_prompt：本地同款系统提示词和当前模式；</li>
 *   <li>bot_control：开关自动运行、切聊天模式/预设模式/恶作剧档位、解除失败暂停、取消本地任务；</li>
 *   <li>host_info / server_power：宿主机接口的只读动作和会改动的动作（remote.cloud.power_enabled，默认关）。动作清单由宿主机程序提供，
 *       加动作不用改模组；把关也在那个程序里，见 {@link ServerPower}；</li>
 *   <li>get_settings / set_setting：按服主的白名单（remote.settable_settings）读改 config.json，见 SettingsEditor。</li>
 * </ul>
 * 边界：OAuth 批准、远程接口设置等不提供（CommandPolicy 也在代码层拒绝 aibot remote）。
 */
final class CloudTools {
    static final String WAIT = "wait_events";
    static final String REPLY = "reply_chat";
    static final String RUN_FOR = "run_command_for";
    static final String COMPLETE = "complete_item";
    static final String CONTROL = "bot_control";
    static final String POWER = "server_power";
    static final String HOST = "host_info";
    static final String SET = "set_setting";
    /** 会改变服务器或机器人的工具：remote.allow_actions=false 时隐藏。 */
    static final List<String> ACTIONS = List.of(REPLY, RUN_FOR, CONTROL, POWER, SET);

    private CloudTools() {}

    static Tools create(CloudRouter router, AgentService svc, Tools main) {
        Tools t = new Tools();
        t.add(Tools.schema(WAIT,
                        "云端值守：等待服务器上需要你处理的消息（玩家聊天、事件、定时巡检）。有消息立即返回，没有就等到 timeout_seconds 再返回空列表；"
                                + "请循环调用它来 24 小时值守（每次返回后处理完再调用）。每条消息有 item_id、类型和 instructions（和本地 AI 收到的触发说明一致，"
                                + "包括聊天原文、上下文、【已授权】标记和该怎么回应）。处理一条消息：说话用 reply_chat，执行指令用 run_command_for（都带 item_id，"
                                + "这样服主的黑名单、恶作剧档位、授权规则和本地一样生效），查询用普通的 get_ 工具，最后 complete_item 写一句总结。"
                                + "为这条消息调用其它会改动的工具（set_player_pref、run_macro、send_chat 等）时也在参数里加上 item_id，按这条消息的身份检查；"
                                + "查询时带上 item_id 也行（会刷新这条消息的活动时间）。"
                                + "取走后要在 2×handoff 秒内对它有动作（reply_chat、run_command_for、带 item_id 的调用或 complete_item），否则当作你已断线、交给本地 AI；"
                                + "放进收件箱后超时没被取走的也会交给本地 AI。服务器概况不随消息附带，需要时自己查。")
                        .integer("timeout_seconds", "最多等几秒（默认见 remote.cloud.default_wait_seconds，上限 max_wait_seconds；请小于你这边工具调用的超时）", false)
                        .integer("max_items", "一次最多取几条，默认 5，最多 20", false).build(),
                (a, ctx) -> waitEvents(router, a));
        t.add(Tools.schema(REPLY,
                        "回复某条收件箱消息：在游戏里发聊天（全服或私聊 player）。和本地 AI 的 send_chat 相同的长度/条数限制。")
                        .str("item_id", "wait_events 给的 item_id", true)
                        .str("message", "要发送的内容（纯文本）", true)
                        .str("player", "私聊对象（可选）", false).build(),
                (a, ctx) -> forItem(router, main, a, ActionTools.SEND_CHAT));
        t.add(Tools.schema(RUN_FOR,
                        "为处理某条收件箱消息执行一条后台指令。和本地 AI 的 run_command 走同一套检查：服主黑名单、恶作剧档位白名单（恶作剧模式下回复普通玩家时）、"
                                + "每次运行的指令上限；被拒绝时不要换写法绕过。和这条消息无关的管理操作用普通的 run_command。")
                        .str("item_id", "wait_events 给的 item_id", true)
                        .str("command", "完整指令（不带 /）", true).build(),
                (a, ctx) -> forItem(router, main, a, ActionTools.RUN_COMMAND));
        t.add(Tools.schema(COMPLETE,
                        "这条消息处理完了（包括决定不回应）：写一句总结，记进和本地 AI 共用的行动记录。")
                        .str("item_id", "wait_events 给的 item_id", true)
                        .str("summary", "一两句话：看到了什么、做了什么（没做什么也写）", true).build(),
                (a, ctx) -> complete(router, a));
        t.add(Tools.schema("memory_read",
                        "读机器人的长期记忆摘要和最近的行动记录（本地 AI 和云端共用同一份），了解之前处理过什么。")
                        .integer("entries", "最近几条，默认 20，最多 49", false).build(),
                (a, ctx) -> memoryRead(router, Tools.optInt(a, "entries", 20, 1, 49)));
        t.add(Tools.schema("memory_note",
                        "往共享记忆里记一条笔记（以后本地和云端都能看到），比如服主的新要求、要持续关注的玩家或问题。")
                        .str("text", "笔记内容", true).build(),
                (a, ctx) -> memoryNote(router, Tools.optStr(a, "text", "")));
        t.add(Tools.schema("get_bot_prompt",
                        "读机器人的系统提示词（服主写的服规、性格、补充规则）和当前预设模式说明。云端开始值守时先读一次，说话风格和规矩要和本地 AI 一致。")
                        .build(),
                (a, ctx) -> router.briefing());
        t.add(Tools.schema(CONTROL,
                        "管理机器人本身：enable / disable（开关自动运行）、chat_mode（off|mention|custom|all）、chattiness（quiet|normal|chatty|reset 话量）、persona_mode（模式名或 reset）、"
                                + "prank_level（chat_only|harmless|rough|reset）、resume（解除连续失败暂停）、cancel（取消本地正在跑的任务）。"
                                + "改动都是临时的（reload/重启后按设置文件），预设模式会记住。")
                        .strEnum("action", "要做什么", true, "enable", "disable", "chat_mode", "chattiness", "persona_mode", "prank_level", "resume", "cancel")
                        .str("value", "chat_mode / chattiness / persona_mode / prank_level 的值", false).build(),
                (a, ctx) -> control(router, svc, a));
        t.add(Tools.schema(HOST,
                        "宿主机的只读信息（服主要先开启 remote.cloud.power_enabled）。不带 action 或 action=actions 先看有哪些动作和参数；"
                                + "常用：status（运行中的服务器、现有备份、最近的启停/备份记录）、check（现在能不能重启/停服）、host（负载、内存、磁盘、温度、服务状态）、"
                                + "logs（看日志，args 如 \"mc 80\"、\"aiadmin 50\"、\"crafty\"）。动作清单以宿主机返回的为准，以后可能会多。")
                        .str("action", "动作名（默认 actions = 列出全部动作）", false)
                        .str("args", "参数，空格分隔（例如 logs 的 \"mc 80\"）", false).build(),
                (a, ctx) -> host(router, a, false));
        t.add(Tools.schema(POWER,
                        "宿主机上会改动服务器的动作（服主要先开启 remote.cloud.power_enabled），必须写 reason。动作清单用 host_info 查（access=action 的那些）；"
                                + "目前有 backup（不停服备份：有人在线也可以，可能短暂卡顿；2 小时最多 1 次；单独留最近 2 份，不影响每天 10:00/22:00 的定时备份）、"
                                + "restart（重启当前服务器：约 2~4 分钟，你的远程连接会断开，之后重连）、stop（停服：远程接口也会跟着关，只有服主能再开，非必要不要用）。"
                                + "restart / stop 只在没有玩家在线时执行；60 分钟最多 3 次、间隔至少 5 分钟；10:00、22:00 定时备份前后不执行。"
                                + "只在确实需要时用（服主要求、卡顿或报错且没人在线），先用 host_info 的 check 看能不能做；不要试探或重复调用，被拒绝时把原因告诉服主，不要换办法绕过。")
                        .str("action", "动作名，例如 backup / restart / stop", true)
                        .str("args", "参数，空格分隔（大多数动作不需要）", false)
                        .str("reason", "为什么要做（必填，记进行动记录和日志）", true).build(),
                (a, ctx) -> host(router, a, true));
        t.add(Tools.schema("get_settings",
                        "看机器人设置里服主允许云端修改的那些项（remote.settable_settings）的当前值，例如聊天模式、巡检间隔、各类事件开关、话量。")
                        .build(),
                (a, ctx) -> getSettings(router));
        t.add(Tools.schema(SET,
                        "改一项机器人设置（只能改 get_settings 列出的键），写进 config.json 并立即重新加载，是永久的（不像 bot_control 那样重启后还原）。"
                                + "值按原类型填：true/false、数字、文字，列表写 JSON 数组或逗号分隔。接入点、密钥、远程接口、恶作剧档位永远改不了。"
                                + "改之前先 get_settings 看原值，改完告诉服主改了什么；服主没要求时不要改。")
                        .str("key", "设置键，例如 patrol.interval_minutes", true)
                        .str("value", "新值", true)
                        .str("reason", "为什么改（必填，记进行动记录和日志）", true).build(),
                (a, ctx) -> setSetting(router, a));
        return t;
    }

    private static String waitEvents(CloudRouter router, JsonObject a) throws InterruptedException {
        BotConfig.Cloud cl = router.config().remote.cloud;
        JsonObject o = new JsonObject();
        if (!cl.enabled) {
            o.addProperty("error", "云端值守没有开启（config.json 的 remote.cloud.enabled），收件箱不会有消息；需要服主开启");
            return o.toString();
        }
        int wait = Tools.optInt(a, "timeout_seconds", cl.defaultWaitSeconds, 0, cl.maxWaitSeconds);
        int max = Tools.optInt(a, "max_items", 5, 1, 20);
        List<CloudInbox.Item> got = router.inbox().take(wait * 1000L, max, cl.maxWaiters);
        if (got == null) {
            o.addProperty("error", "已经有 " + cl.maxWaiters + " 个 wait_events 在等了（同一个值守循环里不要并发调用）");
            return o.toString();
        }
        JsonArray arr = new JsonArray();
        for (CloudInbox.Item it : got) {
            JsonObject x = new JsonObject();
            x.addProperty("item_id", it.id);
            x.addProperty("kind", switch (it.trigger.kind()) {
                case CHAT -> "chat";
                case EVENT -> "event";
                case PATROL -> "patrol";
                default -> it.trigger.kind().name().toLowerCase(java.util.Locale.ROOT);
            });
            x.addProperty("title", it.trigger.label());
            x.addProperty("created", Text.time(it.created));
            x.addProperty("instructions", it.instructions);
            // instructions 和本地 AI 用的是同一份文字（里面写的是 send_chat / run_command），这里说明云端对应的工具
            x.addProperty("how", "instructions 里说的 send_chat 请用 reply_chat，run_command 请用 run_command_for（都带 item_id=" + it.id
                    + "），查询用普通工具；处理完（包括决定不回应）调用 complete_item");
            arr.add(x);
            router.log().info("agent", "cloud.take", "云端取走：" + it.trigger.label(), "cloud-" + it.id, JsonlLog.data("item", it.id));
        }
        o.add("items", arr);
        if (arr.isEmpty()) o.addProperty("note", "这段时间没有新消息，请继续调用 wait_events");
        else o.addProperty("next", "逐条处理（reply_chat / run_command_for / 查询工具），每条最后 complete_item；处理完继续调用 wait_events");
        return o.toString();
    }

    /** 用这条消息的上下文调用本地代理同一个动作处理器。 */
    private static String forItem(CloudRouter router, Tools main, JsonObject a, String tool) throws Exception {
        String id = Tools.optStr(a, "item_id", "");
        CloudInbox.Item it = router.inbox().get(id);
        if (it == null) return "没有这条消息：" + id + "（item_id 只在本次开服内有效）";
        Tools.Tool t = main.get(tool);
        if (t == null) return "动作工具不可用：" + tool;
        // 状态检查和建立上下文是同一个原子操作（CloudRouter.context → CloudInbox.beginAction），不会在检查后被收回交本地、两边各回一次
        RunContext ctx = router.context(id);
        if (ctx == null) return "这条消息当前状态是 " + it.state() + "，不能再为它执行动作（已完成、已超时关闭或已交给本地 AI）";
        JsonObject args = a.deepCopy();
        args.remove("item_id");
        return t.handler().call(args, ctx);
    }

    private static String complete(CloudRouter router, JsonObject a) {
        String id = Tools.optStr(a, "item_id", "");
        CloudInbox.Item it = router.inbox().complete(id, Tools.optStr(a, "summary", ""));
        if (it == null) {
            CloudInbox.Item x = router.inbox().get(id);
            return x == null ? "没有这条消息：" + id : "这条消息当前状态是 " + x.state() + "，不需要再完成";
        }
        router.record(it, Tools.optStr(a, "summary", ""), false);
        return "已记录";
    }

    private static String memoryRead(CloudRouter router, int n) {
        JsonObject o = new JsonObject();
        o.addProperty("digest", router.memory().digest());
        JsonArray arr = new JsonArray();
        for (Memory.Entry e : router.memory().recent(n)) {
            JsonObject x = new JsonObject();
            x.addProperty("time", e.time());
            x.addProperty("trigger", e.trigger());
            if (e.provider() != null) x.addProperty("by", e.provider());
            x.addProperty("summary", e.summary());
            if (e.actions() != null && !e.actions().isEmpty()) x.addProperty("actions", Text.brief(String.join("；", e.actions()), 400));
            if (e.error() != null) x.addProperty("error", e.error());
            arr.add(x);
        }
        o.add("recent", arr);
        return o.toString();
    }

    private static String memoryNote(CloudRouter router, String text) {
        if (text.isBlank()) return "内容为空";
        router.memory().add(new Memory.Entry(Text.now(), "云端笔记", "cloud", Text.brief(text, 400), List.of(), null));
        return "已记下";
    }

    /** host_info（changes=false，只放行 access=read 的动作）和 server_power（changes=true，只放行 access=action、必须写理由）。 */
    private static String host(CloudRouter router, JsonObject a, boolean changes) throws Exception {
        if (!router.config().remote.cloud.powerEnabled) return "宿主机接口没有开启（config.json 的 remote.cloud.power_enabled），需要服主开启";
        String action = Tools.optStr(a, "action", "").trim();
        if (action.isEmpty()) action = changes ? "" : "actions";
        List<String> args = ServerPower.split(Tools.optStr(a, "args", ""));
        String reason = Text.brief(Tools.optStr(a, "reason", "").trim(), 200);
        if (action.isEmpty()) return "要写 action（用 host_info 查有哪些动作）";
        String bad = ServerPower.invalid(action, args);
        if (bad != null) return bad;
        ServerPower.Runner runner = router.serverPower();
        if (!action.equals("actions")) {
            ServerPower.Action found = null;
            List<ServerPower.Action> list;
            try {
                list = ServerPower.actions(runner);
            } catch (IllegalStateException e) {
                return "宿主机接口不可用：" + e.getMessage();
            }
            for (ServerPower.Action x : list) if (x.name().equals(action)) found = x;
            if (found == null) return "宿主机没有这个动作：" + action + "（host_info 不带 action 可以看全部）";
            if (!changes && !found.readOnly()) return action + " 会改动服务器，请用 server_power（要写 reason）";
            if (changes && found.readOnly()) return action + " 是只读动作，请用 host_info";
        } else if (changes) {
            return "动作清单请用 host_info 查";
        }
        String label = switch (action) {
            case "backup" -> "备份";
            case "restart" -> "重启";
            case "stop" -> "停服";
            default -> action;
        };
        if (changes && reason.isEmpty()) return label + "要写 reason（为什么要做）";
        // 模组这边先挡一次（服务端玩家表最准）；宿主机程序会用 RCON 再数一次，两边都要 0 人
        if (action.equals("restart") || action.equals("stop")) {
            int n = router.onlinePlayers();
            if (n > 0) {
                router.log().info("agent", "cloud.power", "云端" + label + "被拒绝：" + n + " 人在线", null, JsonlLog.data("action", action, "reason", reason));
                return "有 " + n + " 名玩家在线，不能" + label + "。等没人在线再做，或请服主处理";
            }
        }
        ServerPower.Result r;
        try {
            r = runner.run(action, args);
        } catch (Exception e) {
            // 审计约束：每次 server_power 调用都要有日志和记忆，异常（超时、中断、读输出失败）也不例外
            r = new ServerPower.Result(-1, "宿主机接口出错（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "），动作是否执行未知");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        String out = r.output().isBlank() ? "（没有输出）" : r.output();
        if (changes) {
            router.log().warn("agent", "cloud.power", "云端" + label + "（" + reason + "）→ 退出码 " + r.exit() + "：" + Text.brief(out, 300), null,
                    JsonlLog.data("action", action, "args", String.join(" ", args), "reason", reason, "exit", r.exit()));
            router.memory().add(new Memory.Entry(Text.now(), "云端：服务器" + label, "cloud",
                    label + "（理由：" + reason + "）→ " + (r.exit() == 0 ? "" : "没有执行：") + Text.brief(out, 300), List.of(),
                    r.exit() == 0 ? null : "exit " + r.exit()));
        } else {
            router.log().info("agent", "cloud.host", "云端查看宿主机：" + action + " " + String.join(" ", args) + " → 退出码 " + r.exit(), null, null);
        }
        // 宿主机日志（logs mc / aiadmin）里有玩家登录地址：和 get_server_log 同一条规则——服主没允许、或 hide_player_ips 开着就隐藏
        if (!router.rules().permissions.exposePlayerIps || router.config().remote.hidePlayerIps) out = Text.redactIps(out);
        return r.exit() == 0 ? out : out + "\n（没有执行，退出码 " + r.exit() + "）";
    }

    private static String getSettings(CloudRouter router) {
        List<String> allowed = router.config().remote.settableSettings;
        JsonObject o = new JsonObject();
        JsonObject cur = new JsonObject();
        for (var e : io.github.aftsakee.aiadmin.config.SettingsEditor.current(router.config(), allowed).entrySet()) cur.add(e.getKey(), e.getValue());
        o.add("settings", cur);
        if (cur.size() == 0) o.addProperty("note", "服主没有允许云端修改任何设置（remote.settable_settings 为空）");
        return o.toString();
    }

    private static String setSetting(CloudRouter router, JsonObject a) {
        String key = Tools.optStr(a, "key", "").trim();
        String value = Tools.optStr(a, "value", "");
        String reason = Text.brief(Tools.optStr(a, "reason", "").trim(), 200);
        if (reason.isEmpty()) return "要写 reason（为什么改）";
        io.github.aftsakee.aiadmin.config.SettingsEditor.Change ch;
        try {
            ch = io.github.aftsakee.aiadmin.config.SettingsEditor.set(router.configFile(), router.config().remote.settableSettings, key, value);
        } catch (IllegalArgumentException e) {
            return "没有修改：" + e.getMessage();
        } catch (io.github.aftsakee.aiadmin.config.ConfigStore.ConfigException | java.io.IOException e) {
            router.log().warn("agent", "cloud.setting_failed", "云端改设置失败：" + key + " → " + e.getMessage(), null, null);
            return "没有修改（改完的设置通不过检查或写文件失败，原文件没动）：" + e.getMessage();
        }
        String desc = key + "：" + ch.before() + " → " + ch.after();
        router.log().warn("agent", "cloud.setting", "云端改设置（" + reason + "）：" + desc, null, JsonlLog.data("key", key, "reason", reason));
        router.memory().add(new Memory.Entry(Text.now(), "云端：改设置", "cloud", desc + "（理由：" + reason + "）", List.of(), null));
        router.reloadSoon("set_setting " + key);
        return "已写入 config.json（备份在 config.json.bak-setting），1 秒后重新加载生效：" + desc;
    }

    /** 恶作剧档位的高低；reset = 回到设置文件的值（-1，允许）；不认识的值返回 99（当作"更高"拒绝，不能放行）。 */
    static int rank(String level) {
        return switch (level == null ? "" : level.toLowerCase(java.util.Locale.ROOT)) {
            case "chat_only" -> 0;
            case "harmless" -> 1;
            case "rough" -> 2;
            case "reset" -> -1;
            default -> 99;
        };
    }

    static String control(CloudRouter router, AgentService svc, JsonObject a) {
        // 先统一小写再比较：setPersonaMode / setPrankLevel 生效时会转小写，比较却区分大小写的话 "Prank"、"ROUGH" 就能绕过下面的限制
        String v = Tools.optStr(a, "value", "").trim().toLowerCase(java.util.Locale.ROOT);
        String r = switch (Tools.optStr(a, "action", "")) {
            case "enable" -> {
                svc.setEnabled(true);
                yield "自动运行已开启";
            }
            case "disable" -> {
                svc.setEnabled(false);
                yield "自动运行已关闭（巡检、事件、聊天都不再处理，包括交给云端的；管理员提问照常）";
            }
            case "chat_mode" -> svc.setChatMode(v);
            case "chattiness" -> svc.setChattiness(v);
            // 边界：云端（可能被提示注入）不能把恶作剧开得比服主设置文件更狠——切进恶作剧模式、调高档位都只能服主在后台做
            case "persona_mode" -> Personas.PRANK.equals(v) && !Personas.PRANK.equals(router.config().persona.mode)
                    ? "云端不能切到恶作剧模式（需要服主在后台 /aibot mode prank）" : svc.setPersonaMode(v);
            case "prank_level" -> rank(v) > rank(router.config().persona.prank.level)
                    ? "云端不能把恶作剧档位调得比设置文件（" + router.config().persona.prank.level + "）更高" : svc.setPrankLevel(v);
            case "resume" -> svc.resume() ? "已解除连续失败暂停" : "当前没有暂停";
            case "cancel" -> svc.cancel() ? "已请求取消本地任务" : "本地当前没有任务";
            default -> "不认识的 action";
        };
        router.log().info("agent", "cloud.control", "云端管理：" + Tools.optStr(a, "action", "") + " " + v + " → " + r, null, null);
        return r;
    }
}
