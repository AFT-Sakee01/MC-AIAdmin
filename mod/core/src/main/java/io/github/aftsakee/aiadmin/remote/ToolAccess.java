package io.github.aftsakee.aiadmin.remote;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 远程工具的读写分类：给 tools/list 的 readOnlyHint 标注、给通用入口 call_read_tool / call_action_tool 把关，
 * 也决定只读模式下哪些工具隐藏（{@link #hidden}）。
 * <p>
 * 边界：这里是"只读白名单"，不在名单里的工具（包括以后新加、忘了登记的）一律按"会改动"处理——
 * 只会多一次客户端确认，不会把写操作误放进只读入口；只读模式下自动隐藏。新增只读工具时把名字加进来。
 * 本类只做判定、不依赖设置和工具表（纯函数，线程安全）：设置、rules.json 和当前工具名由调用方（RemoteTools）传入。
 */
final class ToolAccess {
    /** 通用入口：工具表不变也能用到以后新加的工具（ChatGPT 连接器刷新不会重新拉取工具表）。 */
    static final String LIST = "list_tools";
    static final String CALL_READ = "call_read_tool";
    static final String CALL_ACTION = "call_action_tool";
    static final Set<String> GENERIC = Set.of(LIST, CALL_READ, CALL_ACTION);
    /** 远程接口自己提供的两个工具（不在 Tools 里）：机器人自身状态、额度。 */
    static final String STATUS = "aibot_status";
    static final String QUOTA = "aibot_quota";

    static final Set<String> READ_ONLY = Set.of(
            // 服务器与玩家信息
            "get_server_status", "get_world_info", "get_players_overview", "get_player", "get_offline_player", "get_player_activity",
            "get_recent_activity", "get_entities", "get_server_log", "get_access_lists", "list_players", "list_mods", "list_files", "read_file",
            "get_commands", "get_death_story", "get_player_prefs", "list_projects", "who_built", "goal_status",
            // 游戏资料
            "item_search", "item_sources", "recipe_for", "recipe_uses", "craft_plan", "loot_table", "tag_members",
            // 机器人自身
            "get_bot_prompt", "memory_read", STATUS, QUOTA,
            // 宿主机只读动作、设置、服主写的宏清单和知识文档
            "host_info", "get_settings", "list_macros", "list_guides", "read_guide",
            LIST, CALL_READ);

    private ToolAccess() {}

    static boolean readOnly(String tool) {
        return READ_ONLY.contains(tool);
    }

    /**
     * 远程看不到、也调不了的工具：rules.json 隐藏的（ruleHidden），加上只读模式（allowActions = false）下 offered 里所有不在只读白名单的，
     * 以及写操作通用入口 call_action_tool。
     * <p>安全边界：只读模式是"默认拒绝"——以前逐个列"哪些算动作"，1.0.0.27 新加的 run_macro、memory_note 就漏掉了
     * （宏在内部转调 run_command，只读令牌就能执行指令）。现在新工具不登记进只读白名单就自动按动作隐藏；
     * 处理器里还有一道兜底（ActionTools.requireRemoteActions）。offered = 当前提供的全部工具名（本地工具 + 云端值守工具；
     * aibot_status / aibot_quota / list_tools / call_read_tool 本来就是只读，不用传），只在只读模式才取，免得每次调用都遍历工具表。
     */
    static Set<String> hidden(Collection<String> ruleHidden, boolean allowActions, Supplier<? extends Iterable<String>> offered) {
        Set<String> h = new HashSet<>(ruleHidden);
        if (!allowActions) {
            for (String name : offered.get()) if (!readOnly(name)) h.add(name);
            h.add(CALL_ACTION);
        }
        return h;
    }
}
