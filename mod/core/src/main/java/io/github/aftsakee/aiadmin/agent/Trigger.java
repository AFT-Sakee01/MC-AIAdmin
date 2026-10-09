package io.github.aftsakee.aiadmin.agent;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一次唤醒代理的原因。
 * @param prompt   管理员提问的内容；玩家聊天时是最近的聊天上下文
 * @param provider 指定接入点（null = 当前接入点）
 * @param reply    运行结束后把代理的总结文字回给谁（管理员提问时用；null = 只写日志）
 */
public record Trigger(Kind kind, String title, List<String> events, String prompt, String requester,
                      String provider, Consumer<String> reply) {
    /** REMOTE 不经过模型，只用来标记远程管理接口（MCP）发起的工具调用。 */
    public enum Kind { PATROL, EVENT, ADMIN, CHAT, REMOTE }

    public static Trigger patrol(boolean manual) {
        return new Trigger(Kind.PATROL, manual ? "管理员手动巡检" : "定时巡检", List.of(), null, null, null, null);
    }

    public static Trigger events(List<String> events) {
        return new Trigger(Kind.EVENT, "事件", List.copyOf(events), null, null, null, null);
    }

    public static Trigger admin(String requester, String prompt, String provider, Consumer<String> reply) {
        return new Trigger(Kind.ADMIN, "管理员提问", List.of(), prompt, requester, provider, reply);
    }

    /** messages：这次要回应的玩家消息（"[频道] 玩家：内容"）；context：最近的聊天记录（含机器人自己说的）。 */
    public static Trigger chat(List<String> messages, String context) {
        return new Trigger(Kind.CHAT, "玩家聊天", List.copyOf(messages), context, null, null, null);
    }

    /** 玩家用 /ai 私下找机器人：requester = 这名玩家，回复强制只私聊给他（见 ActionTools 的 send_chat）。 */
    public static Trigger privateChat(String player, List<String> messages, String context) {
        return new Trigger(Kind.CHAT, "玩家私聊", List.copyOf(messages), context, player, null, null);
    }

    // —— 聊天频道标记（写在每行消息开头，模型和代码都靠它区分"对机器人说的"和"玩家之间聊的"） ——
    /** 公屏消息，里面叫了机器人（关键词 / @）。 */
    public static final String TAG_MENTION = "[@你]";
    /** 公屏消息，没叫机器人（custom/all 模式下被条件唤醒，或作为同一段对话带上）——多半是玩家之间在聊。 */
    public static final String TAG_PUBLIC = "[公屏]";
    /** 玩家用 /ai 私聊机器人。 */
    public static final String TAG_PRIVATE = "[私聊你]";

    public boolean isPrivate() {
        return kind == Kind.CHAT && requester != null;
    }

    /** "[频道] 玩家【已授权】：内容" → 玩家名；不是聊天行（如"（触发原因：…）"）返回空串。 */
    public static String speaker(String line) {
        if (line == null || line.startsWith("（触发原因")) return "";
        String s = line;
        if (s.startsWith("[")) {
            int j = s.indexOf("] ");
            if (j > 0) s = s.substring(j + 2);
        }
        int i = s.indexOf('：');
        return (i < 0 ? s : s.substring(0, i)).replace("【已授权】", "").trim();
    }

    /** 这一轮里直接找机器人（@ 了机器人或私聊）的玩家，小写名。 */
    public java.util.Set<String> direct() {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (kind != Kind.CHAT) return s;
        for (String e : events) {
            if (e.startsWith(TAG_MENTION) || e.startsWith(TAG_PRIVATE)) s.add(speaker(e).toLowerCase(java.util.Locale.ROOT));
        }
        if (requester != null) s.add(requester.toLowerCase(java.util.Locale.ROOT));
        return s;
    }

    /** 这一轮聊天里说话的玩家（原样大小写，去重保序）。 */
    public List<String> speakers() {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        if (kind == Kind.CHAT) for (String e : events) {
            String n = speaker(e);
            if (!n.isEmpty()) s.add(n);
        }
        return List.copyOf(s);
    }

    public static Trigger remote(String client) {
        return new Trigger(Kind.REMOTE, "远程管理", List.of(), null, client, null, null);
    }

    public String label() {
        return switch (kind) {
            case PATROL -> title;
            case EVENT -> "事件×" + events.size();
            case ADMIN -> "管理员 " + requester;
            case CHAT -> requester != null ? "私聊 " + requester : "玩家聊天×" + events.size();
            case REMOTE -> "远程 " + requester;
        };
    }
}
