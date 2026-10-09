package io.github.aftsakee.aiadmin.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.aftsakee.aiadmin.agent.AgentService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * /aibot 管理指令（权限等级见 rules.json permissions.admin_permission_level，后台控制台总是可以用）。
 * 代理的回复是异步的：运行结束后切回服务端主线程发给提问者；提问者已下线时只写后台日志。
 */
final class AdminCommand {
    private AdminCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("aibot")
                .requires(src -> ServerHooks.active() && src.hasPermission(ServerHooks.rules().permissions.adminPermissionLevel))
                .executes(c -> help(c.getSource()))
                .then(Commands.literal("ask").then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> ask(c, null))))
                .then(Commands.literal("askvia").then(Commands.argument("provider", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(ServerHooks.config().providers.keySet(), b))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(c -> ask(c, StringArgumentType.getString(c, "provider"))))))
                .then(Commands.literal("status").executes(c -> say(c.getSource(), svc().status())))
                .then(Commands.literal("quota").executes(c -> {
                    CommandSourceStack src = c.getSource();
                    say(src, "正在查询额度和余额…");
                    svc().quotaReport(text -> later(src, text));
                    return 1;
                }))
                .then(Commands.literal("patrol").executes(c -> say(c.getSource(), svc().patrolNow())))
                .then(Commands.literal("on").executes(c -> {
                    svc().setEnabled(true);
                    return say(c.getSource(), svc().enabled() ? "已开启" : "已开启，但 config.json 里 enabled=false（或设置文件有误），仍然停用");
                }))
                .then(Commands.literal("off").executes(c -> {
                    svc().setEnabled(false);
                    return say(c.getSource(), "已停用自动运行（巡检和事件）；管理员提问仍可用。重启后恢复");
                }))
                .then(Commands.literal("cancel").executes(c -> say(c.getSource(), svc().cancel() ? "已请求取消当前任务" : "当前没有任务")))
                .then(Commands.literal("resume").executes(c -> say(c.getSource(), svc().resume() ? "已解除连续失败暂停" : "当前没有失败暂停")))
                .then(Commands.literal("reload").executes(c -> {
                    String err = ServerHooks.reload(false);
                    return say(c.getSource(), err == null ? "已重新读取 config.json、rules.json（提示词文件每次运行都会重新读取）" : "读取失败，继续使用旧设置：" + err);
                }))
                .then(Commands.literal("chat")
                        .executes(c -> say(c.getSource(), "聊天模式：" + svc().chatMode() + "（off 不回应 / mention 提到关键词才回 / custom 关键词或自定义条件 / all 所有聊天都看）"))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("off", "mention", "custom", "all"), b))
                                .executes(c -> say(c.getSource(), svc().setChatMode(StringArgumentType.getString(c, "mode"))))))
                .then(Commands.literal("talk")
                        .executes(c -> say(c.getSource(), "话量：" + AgentService.talkLabel(svc().chattiness()) + "（quiet 安静 / normal 正常 / chatty 话痨），会记住；reset 回到设置文件"))
                        .then(Commands.argument("level", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("quiet", "normal", "chatty", "reset"), b))
                                .executes(c -> say(c.getSource(), svc().setChattiness(StringArgumentType.getString(c, "level"))))))
                .then(Commands.literal("mode")
                        .executes(c -> say(c.getSource(), svc().personaInfo()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    java.util.List<String> s = new java.util.ArrayList<>(svc().personaNames());
                                    s.add("reset");
                                    return SharedSuggestionProvider.suggest(s, b);
                                })
                                .executes(c -> say(c.getSource(), svc().setPersonaMode(StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("prank")
                        .executes(c -> say(c.getSource(), "恶作剧档位：" + svc().prankLevel() + "（chat_only 只动嘴 / harmless 无害整蛊 / rough 较狠），只在 /aibot mode prank 时生效"))
                        .then(Commands.argument("level", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("chat_only", "harmless", "rough", "reset"), b))
                                .executes(c -> say(c.getSource(), svc().setPrankLevel(StringArgumentType.getString(c, "level"))))))
                .then(Commands.literal("remote").executes(c -> {
                            var r = ServerHooks.remote();
                            return say(c.getSource(), r == null ? "远程管理：未初始化" : r.describe());
                        })
                        // OAuth 授权批准：批准批准页上显示确认号的那个请求。要求权限等级 4（控制台、RCON 都是 4）——
                        // 批准等于把服务器的管理权交给一个外部应用，3 级 OP 不该能做；代理的 run_command 在 CommandPolicy 里也被硬性禁止
                        .then(Commands.literal("approve").requires(src -> src.hasPermission(4))
                                .executes(c -> {
                                    var r = ServerHooks.remote();
                                    String list = r == null ? null : r.pendingRequests();
                                    return say(c.getSource(), list == null ? "OAuth 没有启用（需要 remote.enabled 和 remote.public_base_url）" : list);
                                })
                                .then(Commands.argument("confirm", StringArgumentType.word()).executes(c -> {
                                    var r = ServerHooks.remote();
                                    String msg = r == null ? null : r.approve(StringArgumentType.getString(c, "confirm"));
                                    return say(c.getSource(), msg == null ? "OAuth 没有启用（需要 remote.enabled 和 remote.public_base_url）" : msg);
                                })))
                        .then(Commands.literal("clients").executes(c -> {
                            var r = ServerHooks.remote();
                            return say(c.getSource(), r == null ? "远程管理：未初始化" : r.oauthClients());
                        }))
                        .then(Commands.literal("revoke").then(Commands.argument("client_id", StringArgumentType.string()).executes(c -> {
                            var r = ServerHooks.remote();
                            return say(c.getSource(), r == null ? "远程管理：未初始化" : r.revoke(StringArgumentType.getString(c, "client_id")));
                        })))
                        .then(Commands.literal("revoke-all").executes(c -> {
                            var r = ServerHooks.remote();
                            return say(c.getSource(), r == null ? "远程管理：未初始化" : r.revokeAll());
                        }))
                        .then(Commands.literal("events").executes(c -> {
                            var r = ServerHooks.remote();
                            String s = r == null ? null : r.eventSubscriptions();
                            return say(c.getSource(), s == null ? "远程管理没有开启" : s);
                        })))
                .then(Commands.literal("provider")
                        .executes(c -> say(c.getSource(), "当前接入点：" + svc().activeProvider() + "（可用：" + svc().providerNames() + "）"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(ServerHooks.config().providers.keySet(), b))
                                .executes(c -> say(c.getSource(), svc().setProvider(StringArgumentType.getString(c, "name")))))));
    }

    private static AgentService svc() {
        return ServerHooks.service();
    }

    private static int help(CommandSourceStack src) {
        return say(src, """
                /aibot ask <内容>  向 AI 管理员提问或下达任务
                /aibot askvia <接入点> <内容>  用指定接入点提问
                /aibot status | quota | patrol | cancel | resume
                /aibot on | off  开关自动运行（巡检、事件）
                /aibot provider [名字]  查看/临时切换接入点
                /aibot chat [off|mention|custom|all]  查看/临时切换聊天回复模式
                /aibot talk [quiet|normal|chatty|reset]  话量：安静 / 正常 / 话痨（会记住）
                /aibot mode [normal|helper|roast|prank|自定义|reset]  查看/切换预设模式（帮助、吐槽、恶作剧…，会记住）
                /aibot prank [chat_only|harmless|rough|reset]  恶作剧档位：只动嘴 / 无害整蛊 / 较狠
                /aibot remote  远程管理接口（Tailscale/MCP）状态
                /aibot remote approve [确认号] | clients | revoke <client_id> | revoke-all  OAuth 批准（需权限等级 4）、客户端列表、吊销
                /aibot remote events  云端的推送订阅（MCP Events）列表
                /aibot reload  重读 config.json 和 rules.json""");
    }

    private static int ask(CommandContext<CommandSourceStack> c, String provider) {
        CommandSourceStack src = c.getSource();
        String text = StringArgumentType.getString(c, "text");
        String who = src.getTextName();
        String ack = svc().ask(who, text, provider, reply -> later(src, reply));
        return say(src, ack);
    }

    /** 从代理线程回到主线程再发消息。 */
    private static void later(CommandSourceStack src, String text) {
        MinecraftServer server = src.getServer();
        AiAdminMod.LOG.info("[AI管理 → {}] {}", src.getTextName(), text);
        if (ServerHooks.stopping()) return; // 关服中：只留日志（见 McBridge.close 的原因）
        server.execute(() -> send(src, text)); // 提问的玩家已下线时发送是空操作，后台日志里已有记录
    }

    private static int say(CommandSourceStack src, String text) {
        send(src, text);
        return 1;
    }

    private static void send(CommandSourceStack src, String text) {
        String name = ServerHooks.config().botName;
        for (String line : text.split("\n")) {
            src.sendSystemMessage(McBridge.prefix(name, ChatFormatting.AQUA).append(Component.literal(line)));
        }
    }
}
