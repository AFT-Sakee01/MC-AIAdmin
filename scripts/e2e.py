"""端到端测试：真实 NeoForge 1.21.1 开发服务端 + 假模型服务（mock_llm.py）。

用法（仓库根目录）：python scripts/e2e.py
流程：
  1. 启动假模型服务；
  2. 准备 mod/neoforge-1.21.1/run/server：同意 EULA、离线、超平坦世界；写入指向假服务的 config.json / rules.json；清掉上次的日志和状态；
  3. gradlew runServer，开服后以控制台身份依次执行三条 /aibot askvia（OpenAI、Claude、Antigravity 三种协议）和 /aibot quota，
     三次运行结束后自动关服；
  4. 断言：三种协议都走完 观察 → 指令（含被拒绝的 stop 和被拒绝的读密钥文件）→ 聊天 → 结束；
     游戏里真的执行了 time set day、发出了聊天；黑名单生效；提示词文件自动生成；余额低于提醒线发出提醒；程序日志可解析。
退出码 0 = 全部通过。
"""
import datetime
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
# 公共部分在 e2e_common。prepare / run_server / RUN / OUT 也要留在本模块命名空间里：live_deepseek.py 经 e2e.xxx 复用。
from e2e_common import (  # noqa: E402,F401
    CFG_DIR, CONFIG, OUT, REMOTE_PORT, RUN, STATE, check, dev_server, finish, full_events, full_log, http, mock_server, prepare,
    read_jsonl, run_server, server_log)

MAIN_COMMANDS = [
    "aibot askvia e2e_openai 端到端测试",
    "aibot askvia e2e_claude 端到端测试",
    "aibot askvia e2e_agy 端到端测试",
    "aibot quota",
    "chat Alex 今天挖到钻石了",               # 没叫机器人：mention 模式下不应唤醒
    "chat Steve @AI 你好，服务器怎么样",      # 叫了：应该用 send_chat 回复
    "chat op:Boss @AI 给我一组面包",          # OP 说话：rules 里对 OP 关闭了安全提示词，应带【已授权】
]
SUITE_START = None  # 第一次开服前的本地时间，重启阶段判断额度账本的时段有没有翻篇


def assertions():
    reqs = read_jsonl(OUT / "requests.jsonl", strict=True)
    for proto in ["openai", "anthropic", "antigravity"]:
        rs = [r for r in reqs if r.get("proto") == proto]
        print(f"[{proto}] {len(rs)} 次请求")
        check(len(rs) == 4, f"{proto}: 4 轮请求（观察、动作、聊天、结束）")
        check(all(r["auth_ok"] for r in rs), f"{proto}: 鉴权头正确")
        if len(rs) < 4:
            continue
        tools = set(rs[0]["tools"])
        check({"get_server_status", "get_player", "read_file", "run_command", "send_chat"} <= tools, f"{proto}: 提供了观察和动作工具")
        # OpenAI/Claude 每次请求带完整历史，结果是累计的；Antigravity 只带本轮的新结果。统一成"本轮新增"。
        def fresh(i):
            prev = len(rs[i - 1]["results"]) if proto != "antigravity" and i > 1 else 0
            return rs[i]["results"][prev:]
        obs = fresh(1)
        check(len(obs) == 7, f"{proto}: 7 个观察工具都返回了结果")
        if len(obs) == 7:
            check('"tps"' in obs[0] and '"dimensions"' in obs[0], f"{proto}: get_server_status 含 TPS 和维度")
            check('"gamerules"' in obs[2] and "doDaylightCycle" in obs[2], f"{proto}: get_world_info 含游戏规则")
            check("[已隐藏]" in obs[4] and "should-be-hidden" not in obs[4], f"{proto}: read_file 隐藏了 rcon.password")
            check('"aiadmin"' in obs[6], f"{proto}: list_mods 含本模组")
        act = fresh(2)
        if len(act) == 4:
            check('"allowed":true' in act[0] and '"success":true' in act[0], f"{proto}: time set day 执行成功")
            check('"allowed":false' in act[1] and "stop" in act[1], f"{proto}: execute … run stop 被黑名单拒绝")
            check("禁止访问" in act[2], f"{proto}: 读机器人设置文件被拒绝")
            check("路径写法不允许" in act[3] and "api_key" not in act[3], f"{proto}: config/aiadmin./ 段尾点绕过被拒绝")
        else:
            check(False, f"{proto}: 动作轮有 4 个结果（实际 {len(act)}）")
        check(fresh(3) == ["已发送"], f"{proto}: send_chat 已发送")
    an = [r for r in reqs if r.get("proto") == "anthropic"]
    if len(an) >= 2:
        check(an[1]["signatures"] == ["sig-e2e-0"], "anthropic: 思考块原样带回")
    ag = [r for r in reqs if r.get("proto") == "antigravity"]
    if len(ag) >= 2:
        check(ag[1]["prev"] is not None and ag[1]["environment"] == "env-e2e", "antigravity: 续接 previous_interaction_id 和环境")
        check("code_execution" not in ag[0]["tools"] and "google_search" not in ag[0]["tools"], "antigravity: 内置工具默认关闭")
    check(any(r.get("path") == "/user/balance" for r in reqs), "DeepSeek 余额接口被查询")

    lines = full_events()
    check(all(l.get("schema_version") == 1 and "correlation_id" in l for l in lines), f"程序日志 {len(lines)} 行全部可解析")
    ends = [l for l in lines if l["event"] == "run.end"]
    check(len(ends) == 4, "4 次运行正常结束（3 次管理员提问 + 1 次聊天）")
    check(sorted(l["data"]["input_tokens"] for l in ends) == [1000, 4000, 4000, 4000], "run.end 记录了真实 token 数（没被脱敏误伤）")
    check(sum(1 for l in lines if l["event"] == "tool.command_denied") == 3, "3 次 stop 被拒绝记入审计日志")
    check(any(l["event"] == "quota.alert" and "e2e_ds" in l["message"] for l in lines), "余额低于提醒线时发出提醒")
    check("e2e-test-key" not in full_log(), "日志里没有 API key")

    glog = server_log("server-gradle.log")
    check(glog.count("E2E 测试消息") >= 3, "三条聊天出现在服务端日志")
    check("Set the time to 1000" in glog, "游戏里真的执行了 time set day")
    check("E2E openai 完成" in glog and "E2E anthropic 完成" in glog and "E2E antigravity 完成" in glog, "三次回复回给了提问的控制台")
    check("今天" in glog and "e2e_ds：余额 3.00 CNY" in glog, "/aibot quota 报告了额度和余额")
    chats = [r for r in reqs if r.get("proto") == "openai-chat"]
    check(len(chats) == 2, f"聊天：叫了机器人的那句唤醒了一次运行（2 轮请求，实际 {len(chats)}）")
    if chats:
        up = chats[0]["user_prompt"]
        check("Steve：@AI 你好，服务器怎么样" in up, "聊天：提示词里是 Steve 的原话")
        check("Alex：今天挖到钻石了" in up and "- Alex" not in up, "聊天：Alex 那句只作为上下文、没有单独唤醒")
        check(chats[-1]["results"] == ["已发送"], "聊天：send_chat 已发送")
        check("Boss【已授权】：@AI 给我一组面包" in up and "Steve：@AI" in up and "没有【已授权】标记的玩家是普通玩家" in up,
              "聊天：OP 带【已授权】标记、普通玩家仍受安全限制（同一批合并）")
    check("[AI管家] 你好 Steve，服务器一切正常！" in glog, "聊天：回复出现在游戏聊天里")
    pd = CFG_DIR / "prompts"
    check((pd / "e2e_claude.system.md").exists() and (pd / "e2e_agy.custom.md").exists() and (pd / "compress.md").exists(),
          "提示词文件自动生成")
    check((STATE / "quota.json").exists() and (STATE / "memory.json").exists(), "额度账本和行动记录已保存")


def quota_key(period, t):
    """和 Java 端 Quota.key 同一格式（本地时区；周按 ISO 周，周数不补零）。"""
    if period == "day":
        return "day:" + t.date().isoformat()
    if period == "week":
        y, w, _ = t.isocalendar()
        return f"week:{y}-W{w}"
    return "month:" + t.strftime("%Y-%m")


def restart_phase():
    """第二次开服（不清理）：设置不重新生成、额度账本和行动记录接着用。"""
    print("\n[重启] 不清理状态再开服一次")
    cfg_before = CONFIG.read_text(encoding="utf-8")
    with mock_server(OUT / "requests-restart.jsonl"):
        rc = run_server(["aibot askvia e2e_openai 重启后测试"], {"aiadmin.devStopAfterRuns": 1}, "server-restart.log")
    check(rc == 0, "重启：runServer 正常退出")
    check(CONFIG.read_text(encoding="utf-8") == cfg_before, "重启：config.json 没被改写")
    reqs = read_jsonl(OUT / "requests-restart.jsonl", strict=True)
    check(len(reqs) == 4, f"重启：OpenAI 剧本走完 4 轮（实际 {len(reqs)}）")
    loads = [l for l in full_events() if l["event"] == "config.loaded"]
    check(len(loads) == 2 and loads[-1]["data"]["config_created"] is False and loads[-1]["data"]["rules_created"] is False,
          "重启：第二次开服读取已有设置（没有重新生成）")
    # 跨午夜误报：额度账本只保留当前时段的桶（Quota.save），午夜后"今天"的桶里只有重启这一次。
    # 取开跑时和现在时段键相同的第一个时段（天 → 周 → 月）断言累计值——两个时刻同一时段，中间所有运行都记在这一个桶里。
    now = datetime.datetime.now()
    period = next((p for p in ("day", "week", "month") if quota_key(p, SUITE_START) == quota_key(p, now)), None)
    q = json.loads((STATE / "quota.json").read_text(encoding="utf-8"))
    if period is None:  # 跨月且跨周的午夜：没有能比的桶，只能确认重启这次记上了
        print("  注意：本套测试跨过了午夜、周和月，额度桶都已翻篇，只检查重启这次有计入")
        b = q["buckets"].get(quota_key("day", now))
        check(b is not None and b["runs"] >= 1, f"重启：翻篇后的新桶记下了重启这次运行（实际 {b['runs'] if b else None}）")
        check(b is not None and b["tokens"] >= 4 * 1050, "重启：翻篇后的新桶记下了重启这次的 token")
    else:
        if period != "day":
            print(f"  注意：本套测试跨过了午夜，改用 {period} 桶断言累计值")
        b = q["buckets"].get(quota_key(period, now))
        check(b is not None and b["runs"] == 5, f"重启：今天的运行次数接着累计到 5（{period} 桶，实际 {b['runs'] if b else None}）")
        check(b is not None and b["tokens"] == 4 * 4 * 1050 + 2 * 520, "重启：token 累计正确（4 次 × 4 轮 × 1050 + 聊天 2 × 520）")
    mem = json.loads((STATE / "memory.json").read_text(encoding="utf-8"))
    check(len(mem["entries"]) == 5, "重启：行动记录接着累计到 5 条")
    # 第二次开服的第一个请求里应该带着上次开服的行动记录
    up = reqs[0].get("user_prompt", "") if reqs else ""
    check("你最近几次运行的记录" in up and "E2E antigravity 完成" in up and "指令 time set day" in up, "重启：提示词带着上次开服的行动记录")
    check("E2E openai 完成" in server_log("server-restart.log"), "重启：本次运行完成并回复")


def remote_phase():
    """远程管理接口（MCP）：用官方 MCP Python SDK 当客户端，连开发服务端上的 /mcp。"""
    print("\n[远程管理] 官方 MCP 客户端连接")
    import asyncio
    out = {}
    token_file = CFG_DIR / "remote-token.txt"
    ping = '{"jsonrpc":"2.0","id":1,"method":"ping"}'
    # 远程调用都做完就经控制台 stop；devHaltAfterSeconds=60 只是兜底
    with dev_server("server-remote.log", {"aiadmin.devHaltAfterSeconds": 60}, ["aibot remote"], stop_when_done=True) as srv:
        url = srv.url
        try:
            token = srv.wait_ready()
            out["bad_token"] = http("POST", url, ping, {"Authorization": "Bearer wrong-token-0000000000"}, timeout=5)[0]

            # 认证失败限流（模拟经 Funnel 进来：本机来源 + X-Forwarded-For）：同一真实来源 5 次错误后被封，别的来源不受影响
            def code(tok, xff):
                return http("POST", url, ping, {"Authorization": f"Bearer {tok}", "X-Forwarded-For": xff, "Content-Type": "application/json"},
                            timeout=5)[0]
            out["throttle"] = [code("wrong-token-0000000000", "203.0.113.50") for _ in range(6)] + [code(token, "203.0.113.50"), code(token, "198.51.100.20")]

            from mcp import ClientSession
            from mcp.client.streamable_http import streamablehttp_client

            async def go():
                async with streamablehttp_client(url, headers={"Authorization": f"Bearer {token}"}) as (rs, ws, _):
                    async with ClientSession(rs, ws) as s:
                        init = await s.initialize()
                        out["server"] = init.serverInfo.name
                        out["tools"] = [t.name for t in (await s.list_tools()).tools]
                        out["status"] = (await s.call_tool("get_server_status", {})).content[0].text
                        out["night"] = (await s.call_tool("run_command", {"command": "time set night"})).content[0].text
                        out["stop"] = (await s.call_tool("run_command", {"command": "stop"})).content[0].text
                        out["chat"] = (await s.call_tool("send_chat", {"message": "来自远程管理的问候"})).content[0].text
                        out["bot"] = (await s.call_tool("aibot_status", {})).content[0].text
                        out["secret"] = (await s.call_tool("read_file", {"path": "config/aiadmin/remote-token.txt"}))
            asyncio.run(go())
        except Exception as e:
            out["error"] = repr(e)
    rc = srv.rc
    check("error" not in out, f"远程：官方 MCP 客户端全程无异常 {out.get('error', '')}")
    check(out.get("bad_token") == 401, "远程：错误令牌返回 401")
    check(out.get("throttle") == [401, 401, 401, 401, 401, 429, 200, 200],
          f"远程：同一来源 5 次令牌错误后封禁（错误令牌 429），持有效令牌照常 200（同一出口上的别人封不掉服主），别的来源 200（实际 {out.get('throttle')}）")
    check(out.get("server") == "aiadmin-minecraft", "远程：initialize 握手成功")
    check({"get_server_status", "get_player", "read_file", "run_command", "send_chat", "aibot_status", "aibot_quota"} <= set(out.get("tools", [])),
          "远程：tools/list 列出观察、动作和机器人状态工具")
    check('"tps"' in out.get("status", ""), "远程：get_server_status 返回服务器概况")
    check('"success":true' in out.get("night", "") and "13000" in out.get("night", ""), "远程：run_command time set night 在游戏里执行成功")
    check('"allowed":false' in out.get("stop", ""), "远程：stop 同样被黑名单拒绝")
    check(out.get("chat") == "已发送", "远程：send_chat 已发送")
    check("状态：" in out.get("bot", ""), "远程：aibot_status 返回机器人状态")
    sec = out.get("secret")
    check(sec is not None and sec.isError and "禁止访问" in sec.content[0].text, "远程：读不到令牌文件（config/aiadmin 永远禁止）")
    glog = server_log("server-remote.log")
    check(f"远程管理：已监听 http://127.0.0.1:{REMOTE_PORT}/mcp" in glog, "远程：/aibot remote 显示监听地址")
    check("[AI管家] 来自远程管理的问候" in glog and "Set the time to 13000" in glog, "远程：聊天和指令真的发生在游戏里")
    full = full_log()
    check(full.count('"event":"remote.call"') == 6, "远程：6 次工具调用都写了审计日志")
    check(token_file.read_text(encoding="utf-8").strip() not in full, "远程：令牌没有出现在日志里")
    check(rc == 0, "远程：服务端正常关服")


def hang_phase():
    """中转站接受连接但不回复：超时 + 重试后报失败，服务端照常运行、正常关服。"""
    print("\n[卡死的接入点] 请求超时")
    t0 = time.monotonic()
    with mock_server(OUT / "requests-hang.jsonl"):
        rc = run_server(["aibot askvia e2e_hang 测试超时"], {"aiadmin.devStopAfterRuns": 1}, "server-hang.log")
    check(rc == 0, f"卡死：服务端正常关服（用时 {time.monotonic() - t0:.0f} 秒）")
    hangs = read_jsonl(OUT / "requests-hang.jsonl", strict=True)
    check(len(hangs) == 1, f"卡死：超时不重试，只尝试 1 次（实际 {len(hangs)}）")
    check("失败：e2e_hang：请求超时" in server_log("server-hang.log"), "卡死：回给管理员'请求超时'")
    check(any(l["event"] == "run.failed" for l in full_events()), "卡死：记录了 run.failed")


def broken_config_phase():
    """rules.json 写坏后开服：机器人保持停用、不覆盖用户文件、不调用模型。"""
    print("\n[坏设置] rules.json 写坏后开服")
    rf = CFG_DIR / "rules.json"
    good = rf.read_text(encoding="utf-8")
    bad = '{ "permissions": { "deny_roots": [ "stop", }'
    rf.write_text(bad, encoding="utf-8")
    try:
        with mock_server(OUT / "requests-broken.jsonl"):
            # 用默认设置里也存在的接入点名提问：锁定前这条会按默认规则真的发出去。
            # 负向断言（没有向模型发出任何请求）：故意不传 until 提前关服，开着 8 秒（devHaltAfterSeconds）确认确实没发
            rc = run_server(["aibot status", "aibot askvia deepseek 不应该执行"], {"aiadmin.devHaltAfterSeconds": 8}, "server-broken.log")
    finally:
        broken_after = rf.read_text(encoding="utf-8")
        rf.write_text(good, encoding="utf-8")
    check(rc == 0, "坏设置：runServer 正常退出")
    check(broken_after == bad, "坏设置：写坏的 rules.json 没被覆盖")
    glog = server_log("server-broken.log")
    check("机器人已锁定" in glog and "rules.json 格式错误" in glog, "坏设置：后台报出 rules.json 格式错误、机器人已锁定")
    check("已锁定：设置文件有误" in glog, "坏设置：/aibot status 显示已锁定")
    p = OUT / "requests-broken.jsonl"
    check(not p.exists() or not p.read_text(encoding="utf-8").strip(), "坏设置：没有向模型发出任何请求")
    check("拒绝：设置文件有误，机器人已锁定" in glog, "坏设置：管理员提问也被拒绝")


def main():
    global SUITE_START
    prepare()
    SUITE_START = datetime.datetime.now()
    with mock_server(OUT / "requests.jsonl"):
        rc = run_server(MAIN_COMMANDS, {"aiadmin.devStopAfterRuns": 4})
    check(rc == 0, "runServer 正常退出")
    try:
        assertions()
        restart_phase()
        remote_phase()
        hang_phase()
        broken_config_phase()
    except Exception as e:  # 断言阶段本身出错也算失败
        check(False, f"断言阶段异常：{e!r}")
    finish()


if __name__ == "__main__":
    main()
