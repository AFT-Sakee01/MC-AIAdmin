package io.github.aftsakee.aiadmin.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * NeoForge 1.21.1 入口。只在专用服务器上工作（单人/局域网的内置服务器不启动机器人）。
 * 结构：core（纯 Java：协议、代理循环、调度、额度、规则）+ 本包（MC 适配：观察工具、指令执行、事件、/aibot）。
 */
@Mod("aiadmin")
public final class AiAdminMod {
    static final Logger LOG = LogUtils.getLogger();

    public AiAdminMod(IEventBus modBus, ModContainer container) {
        // 全部游戏事件的订阅表：每组（事件, 优先级, 收不收已取消的）只订一次，按固定顺序分发给
        // ServerHooks（调度层）→ TrackerHooks（玩家行为追踪）→ GoalHooks（目标表）→ SocialHooks（/ai）→ StoryHooks（前因后果、里程碑、工程）
        HookBus.register(NeoForge.EVENT_BUS);
        TpaCommands.init(); // 只挂开发自测监听（单独订阅 ServerStartedEvent，排在上面那组之后）；/tpa 指令在 ServerHooks.onRegisterCommands 注册
    }
}
