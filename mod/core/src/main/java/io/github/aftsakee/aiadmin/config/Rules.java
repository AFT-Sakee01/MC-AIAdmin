package io.github.aftsakee.aiadmin.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config/aiadmin/rules.json：限制规则，三个层面。第一次启动按默认值生成；改完用 /aibot reload 生效。
 * <ul>
 *   <li>permissions —— 权限：代理能用哪些工具、哪些指令被禁止、能不能说话/私聊、能看到哪些文件和隐私；谁能用 /aibot。</li>
 *   <li>quota —— 额度管理：每次运行的上限；按小时/天/周/月的次数、token、估算花费上限；接入点余额的提醒线和停用线。</li>
 *   <li>schedule —— 启停限制：什么时候允许自动运行（时段、星期、开服延迟、在线人数），连续失败自动暂停。</li>
 * </ul>
 */
public final class Rules {
    public int schemaVersion = 1;
    public Permissions permissions = new Permissions();
    public QuotaRules quota = new QuotaRules();
    public Schedule schedule = new Schedule();

    // ———————————————————— 权限 ————————————————————

    public static final class Permissions {
        /** /aibot 指令需要的权限等级（后台控制台总是可以用）。 */
        public int adminPermissionLevel = 3;
        /** 是否允许代理执行指令 / 公屏说话 / 私聊。关掉的工具不会提供给模型。 */
        public boolean allowCommands = true;
        public boolean allowChat = true;
        public boolean allowPrivateChat = true;
        /** 不提供给模型的工具名（例如 ["read_file", "list_files"]）。 */
        public List<String> disabledTools = new ArrayList<>();
        /** 代理执行指令时的权限等级（4 = 与后台控制台相同）。 */
        public int commandPermissionLevel = 4;
        /** 在线 OP 能看到灰色的"[AI管家: 执行了…]"提示。 */
        public boolean echoToOps = true;
        /** 禁止的根指令（不分大小写，自动去掉 minecraft: 前缀；execute … run 里嵌套的也查）。 */
        public List<String> denyRoots = new ArrayList<>(List.of(
                "stop", "op", "deop", "ban-ip", "pardon-ip", "save-off", "reload", "debug", "jfr", "perf",
                "publish", "transfer", "aibot"));
        /** 禁止的指令正则（对去掉前导 / 的整条指令做 find，不分大小写）。 */
        public List<String> denyPatterns = new ArrayList<>(List.of(
                "^whitelist\\s+off\\b",
                "^kill\\s+@e(\\s|$)",                  // 不带筛选条件的 kill @e（连玩家一起杀）
                "\\bkill\\s+@a(\\s|$)",                // 不带筛选条件的 kill @a（杀全部玩家；@a[name=X] 仍允许）
                "^gamerule\\s+(logAdminCommands|sendCommandFeedback|commandBlockOutput)\\b",
                "^neoforge\\s+generate\\b",
                "click_?event",                           // tellraw/书/告示牌的点击事件以点击者（可能是 OP）的权限执行指令
                "command_block",                          // 放置/召唤命令方块绕过黑名单
                "\\bCommand\\s*:"));                      // data merge 改命令方块里的指令
        /** 非空时变成白名单模式：只允许这些根指令（黑名单仍然生效）。 */
        public List<String> allowOnlyRoots = new ArrayList<>();
        /** 是否让代理看到玩家 IP 和 IP 封禁表。 */
        public boolean exposePlayerIps = false;
        /** read_file / list_files 禁止访问的路径（相对服务器目录的 glob）。本模组的设置目录（含密钥）始终禁止。 */
        public List<String> fileDeny = new ArrayList<>(List.of("**/*.jks", "**/*.pem", "**/*.key", "**/*.p12"));
        public int fileReadMaxBytes = 65536;

        /**
         * 对哪些人关闭聊天回复里的"安全提示词"（默认对所有人开启）。被授权的人在聊天里提的管理要求（给物品、传送、改时间天气等），
         * 机器人可以照做；但指令黑名单/白名单、每次运行的上限照样生效。四项可以组合：
         * everyone = 所有人；ops = OP（权限等级 ≥ 2）；normal_players = 非 OP；players = 指定玩家名（不分大小写）。
         * 注意：按名字授权依赖正版验证（online-mode=true），离线服任何人都能冒用名字。
         */
        public ChatSafetyOff chatSafetyOff = new ChatSafetyOff();

        /** 不提供给模型的工具（disabled_tools + 被开关关掉的动作工具）。 */
        public Set<String> hiddenTools() {
            Set<String> s = new LinkedHashSet<>(disabledTools);
            if (!allowCommands) s.add("run_command");
            if (!allowChat) s.add("send_chat");
            return s;
        }
    }

    public static final class ChatSafetyOff {
        public boolean everyone = false;
        public boolean ops = false;
        public boolean normalPlayers = false;
        public List<String> players = new ArrayList<>();

        /** 这名玩家说话时是否关闭安全提示词。 */
        public boolean appliesTo(String player, boolean isOp) {
            if (everyone || (isOp && ops) || (!isOp && normalPlayers)) return true;
            for (String p : players) if (p.equalsIgnoreCase(player)) return true;
            return false;
        }
    }

    // ———————————————————— 额度管理 ————————————————————

    /**
     * 按自然时段（小时/天/周/月，本地时区）限制运行次数、token、估算花费；任一项用完即视为该时段额度用完。
     * 花费 = token × 接入点配置的单价（config.json 的 price_*_per_million），是估算；按钱计费的接入点另有余额接口。
     */
    public static final class QuotaRules {
        public boolean enabled = true;
        public PerRun perRun = new PerRun();
        public List<Window> windows = new ArrayList<>(List.of(Window.of("hour", 20), Window.of("day", 200)));
        /** 管理员提问是否计入额度（计入才会被统计和受限）。 */
        public boolean countAdminRuns = true;
        /** 额度用完后是否连管理员提问也拒绝（false = 只暂停巡检和事件）。 */
        public boolean blockAdminWhenExhausted = false;
        /** 某项剩余不足这个百分比时提醒一次（每个时段每项只提醒一次）。 */
        public int warnAtPercent = 20;
        /** 提醒同时发给在线 OP（否则只写后台和日志）。 */
        public boolean alertInGame = true;
        /** 查询接入点余额的间隔（分钟，0 = 只在 /aibot quota 时查）。 */
        public int balanceCheckMinutes = 30;
        /** 接入点名 → 余额提醒线 / 停用线（单位是该服务商的货币；0 = 不检查）。 */
        public Map<String, BalanceRule> providerBalance = defaultBalance();
        /** 玩家聊天唤醒的上限，按群体/个人分别设置。 */
        public ChatLimits chatLimits = new ChatLimits();
    }

    /**
     * 聊天上限：defaults（所有人）、ops（OP，权限等级 ≥ 2）、normal_players（非 OP）、players（指定玩家名 → 设置）。
     * 优先级：指定玩家 > OP/普通玩家 > defaults；每一项没写（null）就继承下一级，最后继承 config.json 的 chat 设置。
     */
    public static final class ChatLimits {
        public ChatLimit defaults = new ChatLimit();
        public ChatLimit ops = new ChatLimit();
        public ChatLimit normalPlayers = new ChatLimit();
        public Map<String, ChatLimit> players = new LinkedHashMap<>();

        public record Effective(int maxPerHour, int maxPerDay, int keywordCooldownSeconds, int playerCooldownSeconds, boolean ignore) {}

        public Effective resolve(String player, boolean isOp, int baseHour, int baseKwCd, int basePlCd) {
            ChatLimit p = null;
            for (Map.Entry<String, ChatLimit> e : players.entrySet()) if (e.getKey().equalsIgnoreCase(player)) p = e.getValue();
            ChatLimit g = isOp ? ops : normalPlayers;
            ChatLimit[] chain = {p, g, defaults};
            return new Effective(pick(chain, l -> l.maxPerHour, baseHour), pick(chain, l -> l.maxPerDay, 0),
                    pick(chain, l -> l.keywordCooldownSeconds, baseKwCd), pick(chain, l -> l.playerCooldownSeconds, basePlCd),
                    pick(chain, l -> l.ignore, false));
        }

        private static <T> T pick(ChatLimit[] chain, java.util.function.Function<ChatLimit, T> f, T base) {
            for (ChatLimit l : chain) {
                if (l == null) continue;
                T v = f.apply(l);
                if (v != null) return v;
            }
            return base;
        }
    }

    /** 不写的项为 null（继承）。上限 0 = 不限。 */
    public static final class ChatLimit {
        /** 每小时最多唤醒几次。 */
        public Integer maxPerHour;
        /** 每天（滚动 24 小时）最多唤醒几次。 */
        public Integer maxPerDay;
        /** 关键词触发的冷却（秒）。 */
        public Integer keywordCooldownSeconds;
        /** all 模式下的冷却（秒）。 */
        public Integer playerCooldownSeconds;
        /** true = 机器人完全不理这个人/这群人的聊天。 */
        public Boolean ignore;
    }

    public static final class PerRun {
        /** 一次运行里最多来回几轮（模型回复 → 执行工具 → 再回复）。 */
        public int maxSteps = 12;
        public int maxCommands = 10;
        public int maxChat = 4;
        public int maxChatLength = 200;
    }

    public static final class Window {
        /** hour / day / week / month。 */
        public String period = "day";
        /** 0 = 这一项不限。 */
        public int maxRuns = 0;
        public long maxTokens = 0;
        public double maxCost = 0;

        static Window of(String period, int runs) {
            Window w = new Window();
            w.period = period;
            w.maxRuns = runs;
            return w;
        }
    }

    public static final class BalanceRule {
        /** 余额低于这个值时提醒（0 = 不提醒）。 */
        public double warnBelow = 0;
        /** 余额低于这个值时停用这个接入点（有备用接入点就自动改用备用；0 = 不停用）。 */
        public double stopBelow = 0;
    }

    private static Map<String, BalanceRule> defaultBalance() {
        Map<String, BalanceRule> m = new LinkedHashMap<>();
        BalanceRule ds = new BalanceRule();
        ds.warnBelow = 5;
        ds.stopBelow = 1;
        m.put("deepseek", ds);
        return m;
    }

    // ———————————————————— 启停限制 ————————————————————

    public static final class Schedule {
        /** 允许自动运行的时段，"HH:mm-HH:mm"，可跨午夜（如 "20:00-02:00"）；空 = 全天。 */
        public List<String> activeHours = new ArrayList<>();
        /** 允许自动运行的星期（1 = 周一 … 7 = 周日）；空 = 每天。 */
        public List<Integer> activeWeekdays = new ArrayList<>();
        /** 开服后多少秒内不自动运行（等服务器稳定）。 */
        public int startupDelaySeconds = 120;
        /** 在线人数少于这个数时不自动运行（0 = 不限；巡检另有 patrol.only_when_players_online）。 */
        public int minOnlinePlayers = 0;
        /** 连续失败这么多次后自动暂停（0 = 不暂停）。 */
        public int pauseAfterConsecutiveFailures = 3;
        public int failurePauseMinutes = 30;
        /** 管理员提问不受启停限制（时段、星期、开服延迟、人数、失败暂停）。 */
        public boolean adminBypass = true;
    }
}
