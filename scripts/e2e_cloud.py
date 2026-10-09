"""云端值守（云端优先、本地兜底）的端到端测试：真实 NeoForge 开发服务端 + 假模型服务（本地 API）+ 本脚本扮演云端 AI。

流程：
1. 云端先调用一次 wait_events（算在线）→ 玩家 @机器人 → 云端 wait_events 拿到这条聊天 → reply_chat / run_command_for（黑名单照样拒绝）→ complete_item；
   本地 API 没被调用，共享记忆里有"云端·玩家聊天"。
2. 云端在线但不取 → handoff 秒后交给本地 API。
3. 云端超过 online_timeout 不来 → 新聊天直接走本地。
用法（仓库根目录）：python scripts/e2e_cloud.py   （和其它 e2e 共用开发服务端，不能同时跑）
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import (  # noqa: E402
    CONFIG, OUT, ChatFile, call, check, dev_server, edit_config, finish, full_log, mock_requests, mock_server, prepare, remote_cfg,
    tool_names, wait_until)

HANDOFF = 5         # 秒：在线但没取，多久交给本地
ONLINE_TIMEOUT = 30  # 秒：多久没调用云端值守工具算离线（服务端下限 30）


def main():
    prepare()
    edit_config(remote=remote_cfg(cloud={"enabled": True, "handoff_seconds": HANDOFF, "online_timeout_seconds": ONLINE_TIMEOUT,
                                         "default_wait_seconds": 20}))
    # 服主扩展：宏和知识文档（改文件即生效）
    (CONFIG.parent / "macros.json").write_text(json.dumps({"macros": {"e2e_day": {
        "description": "把时间调成白天", "params": {"t": {"type": "choice", "values": ["day", "noon"], "default": "day"}},
        "commands": ["time set ${t}", "weather clear"]}}}, ensure_ascii=False), encoding="utf-8")
    (CONFIG.parent / "guides").mkdir(exist_ok=True)
    (CONFIG.parent / "guides" / "e2e.md").write_text("# 端到端测试指南\n\n活动时间是每周六晚上八点。\n", encoding="utf-8")
    chat = ChatFile("cloud-chat.txt")
    req_file = OUT / "requests.jsonl"

    def local_chats():
        return mock_requests(req_file, "openai-chat")

    def local_with(name, since=0):
        """本地 API 收到的、提示词里有 name 的聊天请求（since 之后）。"""
        return [r for r in local_chats()[since:] if name in r.get("user_prompt", "")]

    out = {}
    token = "<no-token>"
    # 流程走完就经控制台 stop；devHaltAfterSeconds=170 只是兜底
    with mock_server(req_file), \
            dev_server("server-cloud.log", {**chat.prop, "aiadmin.devHaltAfterSeconds": 170}, stop_when_done=True) as srv:
        url = srv.url
        try:
            token = srv.wait_ready()
            out["tools"] = tool_names(url, token)
            out["prompt"] = call(url, token, "get_bot_prompt", {})

            # 1) 在线：云端拿到聊天并处理
            out["first_wait"] = call(url, token, "wait_events", {"timeout_seconds": 1})
            chat.say("chat Steve @AI 帮我把天调成白天")
            t1 = time.monotonic()
            out["wait"] = call(url, token, "wait_events", {"timeout_seconds": 20}, timeout=40)
            out["wait_secs"] = time.monotonic() - t1
            items = json.loads(out["wait"][1]).get("items", [])
            out["items"] = items
            if items:
                iid = items[0]["item_id"]
                out["reply"] = call(url, token, "reply_chat", {"item_id": iid, "message": "好的，马上调成白天"})
                out["cmd"] = call(url, token, "run_command_for", {"item_id": iid, "command": "time set day"})
                out["deny"] = call(url, token, "run_command_for", {"item_id": iid, "command": "stop"})
                out["done"] = call(url, token, "complete_item", {"item_id": iid, "summary": "回复了 Steve，并把时间调成白天"})
            out["status_online"] = call(url, token, "aibot_status", {})
            # 负向断言（已取走的不会交给本地）：没有"事件"可等，只能固定等过 handoff（5 秒）再看本地 API 有没有被调用
            time.sleep(HANDOFF + 2)
            out["local_after_cloud"] = len(local_chats())

            # 2) 在线但不取：handoff 后本地接手（等本地 API 收到 Alex 这条，最多 handoff + 25 秒）
            chat.say("chat Alex @AI 你好呀")
            wait_until(lambda: local_with("Alex"), HANDOFF + 25, what="handoff 后本地 API 收到 Alex 的聊天")
            out["local_after_handoff"] = local_chats()
            out["late_wait"] = call(url, token, "wait_events", {"timeout_seconds": 0})

            # 3) 离线：超过 online_timeout（30 秒）不来，新聊天直接走本地。
            # 必须固定等：要验证的就是"这么久没联系就算离线"；中途查状态的远程调用本身可能被算成心跳，不能轮询
            time.sleep(ONLINE_TIMEOUT + 4)
            n_before = len(local_chats())
            chat.say("chat Bob @AI 在吗")
            wait_until(lambda: local_with("Bob", n_before), 30, what="离线后本地 API 收到 Bob 的聊天")
            out["local_offline"] = local_chats()[n_before:]
            out["memory"] = call(url, token, "memory_read", {"entries": 10})
            out["control"] = call(url, token, "bot_control", {"action": "chat_mode", "value": "mention"})
            out["power"] = call(url, token, "server_power", {"action": "status"})
            out["generic_list"] = call(url, token, "list_tools", {})
            out["generic_read"] = call(url, token, "call_read_tool", {"name": "get_server_status", "arguments": {}})
            out["generic_sneaky"] = call(url, token, "call_read_tool", {"name": "server_power", "arguments": {"action": "status"}})
            out["generic_action"] = call(url, token, "call_action_tool", {"name": "server_power", "arguments": {"action": "status"}})
            out["host"] = call(url, token, "host_info", {})
            out["macros"] = call(url, token, "list_macros", {})
            out["macro"] = call(url, token, "run_macro", {"name": "e2e_day"})
            out["guide"] = call(url, token, "read_guide", {"name": "e2e"})
            out["set"] = call(url, token, "set_setting", {"key": "patrol.interval_minutes", "value": "7", "reason": "端到端测试"})
            out["set_denied"] = call(url, token, "set_setting", {"key": "remote.enabled", "value": "false", "reason": "端到端测试"})
            # set_setting 之后服务端异步 reload（reloadSoon）：轮询 get_settings 直到看到新值。
            # 调用失败不吞——"远程接口没被重启打断"正是要验证的（远程设置没变时 McpServer.start 不重启）
            def settings_reloaded():
                out["get_after"] = call(url, token, "get_settings", {})
                return '"patrol.interval_minutes":7' in out["get_after"][1]
            wait_until(settings_reloaded, 20, what="get_settings 显示 patrol.interval_minutes=7")
            out["cfg_after"] = json.loads(CONFIG.read_text(encoding="utf-8"))
        except Exception as ex:
            out["error"] = repr(ex)
    rc = srv.rc

    print("云端值守：")
    check("error" not in out, f"全程无异常 {out.get('error', '')}")
    want = {"wait_events", "reply_chat", "run_command_for", "complete_item", "memory_read", "memory_note", "get_bot_prompt", "bot_control", "server_power"}
    check(want <= set(out.get("tools", [])), f"远程工具列表里有云端值守工具：缺 {want - set(out.get('tools', []))}")
    check("AIAdmin E2E" in out.get("prompt", (True, ""))[1], "get_bot_prompt 返回本地同款系统提示词（含服务器名）")
    items = out.get("items", [])
    check(len(items) == 1 and items[0].get("kind") == "chat" and "Steve：@AI 帮我把天调成白天" in items[0].get("instructions", ""),
          f"云端在线：wait_events 拿到玩家聊天（含原话）：{items}")
    check(out.get("wait_secs", 99) < 10, f"有消息时 wait_events 立即返回（{out.get('wait_secs', 0):.1f} 秒）")
    check(out.get("reply", (True, ""))[1] == "已发送", "reply_chat 发到游戏里")
    check('"allowed":true' in out.get("cmd", (True, ""))[1], "run_command_for 执行 time set day")
    check('"allowed":false' in out.get("deny", (True, ""))[1], "run_command_for 也受黑名单约束（stop 被拒绝）")
    check(out.get("done", (True, ""))[1] == "已记录", "complete_item 写进共享记忆")
    check("云端值守：在线" in out.get("status_online", (True, ""))[1], "aibot_status 显示云端在线")
    check(out.get("local_after_cloud", -1) == 0, f"云端处理的聊天没有调用本地 API（本地请求 {out.get('local_after_cloud')}）")
    lh = out.get("local_after_handoff", [])
    check(len(lh) >= 1 and "Alex" in lh[0].get("user_prompt", ""), "云端在线但没取：handoff 后本地 API 接手（Alex）")
    check(json.loads(out.get("late_wait", (True, "{}"))[1]).get("items") == [], "转本地的消息不会再给云端")
    lo = out.get("local_offline", [])
    check(len(lo) >= 1 and "Bob" in lo[0].get("user_prompt", ""), "云端超过 online_timeout 没来：新聊天直接走本地（Bob）")
    mem = out.get("memory", (True, ""))[1]
    check("云端·玩家聊天" in mem and "time set day" in mem and "回复了 Steve" in mem, "memory_read 里有云端处理记录和动作")
    gl = out.get("generic_list", (True, ""))[1]
    check("没有开启" in out.get("host", (True, ""))[1], "host_info 默认关")
    check("e2e_day" in out.get("macros", (True, ""))[1], "list_macros 读到 macros.json")
    check('"completed":true' in out.get("macro", (True, ""))[1], f"run_macro 在真实服务器上执行整串指令：{str(out.get('macro'))[:160]}")
    check("每周六晚上八点" in out.get("guide", (True, ""))[1], "read_guide 读到 guides/ 下的文档")
    check("15 → 7" in out.get("set", (True, ""))[1], f"set_setting 写入：{out.get('set')}")
    check(out.get("set_denied", (True, ""))[1].startswith("没有修改"), "remote.* 不能由云端修改")
    check('"patrol.interval_minutes":7' in out.get("get_after", (True, ""))[1], "改完自动 reload 生效，而且远程接口没被重启打断（这次调用照常成功）")
    check(out.get("cfg_after", {}).get("patrol", {}).get("interval_minutes") == 7 and out.get("cfg_after", {}).get("remote", {}).get("enabled") is True,
          "config.json 只改了允许的那一项")
    check('"name":"wait_events","access":"action"' in gl and '"name":"get_server_status","access":"read"' in gl, "list_tools 列出真实服务器的全部工具和读写类别")
    check(not out.get("generic_read", (True, ""))[0] and "tps" in out.get("generic_read", (True, ""))[1].lower(), f"call_read_tool 调到只读工具：{str(out.get('generic_read'))[:120]}")
    check(out.get("generic_sneaky", (False, ""))[0] and "call_action_tool" in out.get("generic_sneaky", (False, ""))[1], "call_read_tool 拒绝会改动服务器的工具")
    check("power_enabled" in out.get("generic_action", (True, ""))[1], "call_action_tool 走同一套检查（server_power 默认关）")
    check("power_enabled" in out.get("power", (True, ""))[1], f"server_power 默认关，只回提示不调宿主机程序：{out.get('power')}")
    check("提到关键词" in out.get("control", (True, ""))[1] or "mention" in out.get("control", (True, ""))[1], f"bot_control 切聊天模式：{out.get('control')}")
    full = full_log()
    check('"cloud.offer"' in full and '"cloud.take"' in full and '"cloud.done"' in full and '"cloud.handoff"' in full,
          "审计日志：交给云端、云端取走、完成、转本地都有记录")
    check(token not in full, "令牌没有出现在日志里")
    check(rc == 0, "服务端正常关服")
    finish()


if __name__ == "__main__":
    main()
