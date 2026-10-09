"""玩家互动（1.0.0.21–22）的端到端测试：真实 NeoForge 开发服务端 + 假模型服务 + 远程接口。

开发服务端里的假玩家 E2ESocial（-Daiadmin.devSocialTest=true）走真实的指令和事件路径：
/msg（私聊脱敏）、/ai（私聊机器人）、/goal project add、放 100 个方块、造信标、捡钻石、驯服狼、死亡、回死亡点捡东西。
本脚本另经聊天文件模拟公屏聊天（频道标记、"别说了"），再经远程接口调用新工具，最后检查假模型收到的提示词。
用法（仓库根目录）：python scripts/e2e_social.py   （和其它 e2e 共用开发服务端，不能同时跑）
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import (  # noqa: E402
    CFG_DIR, OUT, STATE, ChatFile, call, check, dev_server, edit_config, finish, full_log, mock_requests, mock_server, prepare, remove,
    tool_names, wait_until)

# 事件批次（debounce 3 秒）送进假模型的提示词里应出现的内容：等这些都到了再关服
EVENT_MARKS = ["milestone：E2ESocial 全服第一个造出了", "捡到了", "驯服了", "工程「测试酒馆」范围内已经放了 100 个方块",
               "player_death：", "附近 32 格内没有别的玩家", "death_followup：E2ESocial 回到死亡地点"]
QUIET_WINDOW = 10  # 秒："别说了"之后至少观察这么久才关服，确认它没有唤醒模型（负向断言只能固定等）


def main():
    prepare()

    def social_events(cfg):
        cfg.setdefault("events", {})["debounce_seconds"] = 3
        cfg["events"]["player_death"] = True  # prepare 默认关了死亡事件；这里要验证死亡的前因后果和跟进
    edit_config(social_events)
    remove(*(STATE / f for f in ["player-prefs.json", "projects.json", "milestones.json", "build-ledger.json", "command-notes.json"]))
    guide = CFG_DIR / "server-guide.md"
    remove(guide)  # 验证自动生成
    chat = ChatFile("social-chat.txt")
    req_file = OUT / "requests.jsonl"

    def reqs_now():
        return mock_requests(req_file)

    def events_text(reqs):
        return "\n".join(r.get("user_prompt", "") for r in reqs if "发生了以下事件" in r.get("user_prompt", ""))

    def carl_run_done():
        """Carl 唤醒的那次运行两轮请求（send_chat、结束）都到了假模型。"""
        return len([r for r in mock_requests(req_file, "openai-chat") if "Carl：@AI 在吗" in r.get("user_prompt", "")]) >= 2

    def background_done():
        """事件批次的提示词都送到了，/ai 私聊那次运行也走到了回复（假玩家不在线，send_chat 报"玩家不在线"）。"""
        rs = reqs_now()
        private = any("【玩家私聊】" in r.get("user_prompt", "") and any("玩家不在线：E2ESocial" in x for x in r.get("results", [])) for r in rs)
        return private and all(m in events_text(rs) for m in EVENT_MARKS)

    def in_activity(text):
        """get_recent_activity 里已有这句（服务端读到了聊天文件里的这一行）。url/token 在下面开服后赋值。"""
        return text in json.dumps(call(url, token, "get_recent_activity", {"limit": 50}, parse=True)[1], ensure_ascii=False)

    out = {}
    token = "<no-token>"
    # 流程走完就经控制台 stop；devHaltAfterSeconds=100 只是兜底
    with mock_server(req_file), \
            dev_server("server-social.log", {**chat.prop, "aiadmin.devSocialTest": "true", "aiadmin.devHaltAfterSeconds": 100},
                       stop_when_done=True) as srv:
        url = srv.url
        try:
            token = srv.wait_ready()
            # 假玩家自测在 ServerStarted 时同步执行：等它跑完（成功或失败都有日志）
            srv.wait_log(lambda log: "[E2E-SOCIAL] done" in log or "[E2E-SOCIAL] 开发自测失败" in log, timeout=60)
            out["tools"] = tool_names(url, token)

            # 公屏聊天：频道标记；"别说了"
            # Alex 这句只是上下文：等它进了动态（服务端读到）再让 Carl @机器人，保证两句的先后
            chat.say("chat Alex 今天去挖矿")
            wait_until(lambda: in_activity("今天去挖矿"), 15, interval=0.3, what="服务端读到 Alex 的聊天")
            chat.say("chat Carl @AI 在吗")
            # Carl 唤醒的这次运行（send_chat + 结束两轮）跑完再说"别说了"，免得 Bob 那句并进 Carl 这一批
            wait_until(carl_run_done, 30, what="Carl 唤醒的运行跑完（2 轮请求）")
            chat.say("chat Bob @AI 别说了")
            t_quiet = time.monotonic()
            wait_until(lambda: call(url, token, "get_player_prefs", {"player": "Bob"}, parse=True)[1].get("quiet") is True, 15, interval=0.3,
                       what="Bob 进入安静状态")

            out["activity"] = call(url, token, "get_recent_activity", {"limit": 300}, parse=True)
            out["commands_tpa"] = call(url, token, "get_commands", {"name": "tpa"}, parse=True)
            out["commands_list"] = call(url, token, "get_commands", {"query": "goal"}, parse=True)
            out["commands_time"] = call(url, token, "get_commands", {"name": "time"}, parse=True)
            out["note_before"] = call(url, token, "note_command", {"command": "neoforge", "text": "neoforge tps 查看各维度 TPS", "verified": True}, parse=True)
            out["run_aibot"] = call(url, token, "run_command", {"command": "neoforge tps"}, parse=True)
            out["note_after"] = call(url, token, "note_command", {"command": "neoforge", "text": "neoforge tps 查看各维度 TPS，只读", "verified": True}, parse=True)
            out["commands_aibot"] = call(url, token, "get_commands", {"name": "neoforge"}, parse=True)
            out["projects"] = call(url, token, "list_projects", {}, parse=True)
            out["who"] = call(url, token, "who_built", {"x": 5, "z": 5, "dim": "minecraft:overworld"}, parse=True)
            out["death"] = call(url, token, "get_death_story", {"player": "E2ESocial"}, parse=True)
            out["prefs_bob"] = call(url, token, "get_player_prefs", {"player": "Bob"}, parse=True)
            out["set_pref"] = call(url, token, "set_player_pref", {"player": "Dave", "style": "serious", "avoid_add": "身高"}, parse=True)
            pid = (out["projects"][1].get("projects") or [{}])[0].get("id", "p1") if isinstance(out["projects"][1], dict) else "p1"
            out["set_project"] = call(url, token, "set_project", {"action": "note", "player": "E2ESocial", "id": pid, "text": "地基打好了"}, parse=True)
            # 等事件批次（debounce 3 秒）和 /ai 私聊的运行都送到假模型
            wait_until(background_done, 45, what="事件批次和私聊运行都送到假模型")
            # 负向断言（"别说了"不唤醒模型）：没有"事件"可等，从说出那句起固定观察 QUIET_WINDOW 秒（聊天 debounce 1 秒，
            # 运行可能排在事件批次后面）再关服
            time.sleep(max(0.0, QUIET_WINDOW - (time.monotonic() - t_quiet)))
        except Exception as ex:
            out["error"] = repr(ex)
    rc = srv.rc

    reqs = reqs_now()
    slog = srv.log()
    full = full_log()
    print("玩家互动：")
    check("error" not in out, f"全程无异常 {out.get('error', '')}")
    check("[E2E-SOCIAL] done" in slog and "[E2E-SOCIAL] 开发自测失败" not in slog, "开发自测跑完")
    want = {"get_player_prefs", "set_player_pref", "get_commands", "note_command", "get_death_story", "list_projects", "set_project", "who_built"}
    check(want <= set(out.get("tools", [])), f"工具列表里有新工具：缺 {want - set(out.get('tools', []))}")

    # 频道标记
    chat_reqs = [r for r in reqs if r.get("proto") == "openai-chat" and "【玩家聊天】" in r.get("user_prompt", "")]
    carl = [r for r in chat_reqs if "[@你] Carl：@AI 在吗" in r["user_prompt"]]
    check(bool(carl), f"公屏 @机器人 的话标 [@你]：{[r['user_prompt'][:200] for r in chat_reqs]}")
    check(bool(carl) and "[公屏] Alex：今天去挖矿" in carl[0]["user_prompt"], "上下文里玩家之间的话标 [公屏]")
    check(bool(carl) and "频道标记" in carl[0]["user_prompt"], "提示词解释了频道标记")

    # /ai 私聊
    priv = [r for r in reqs if "【玩家私聊】" in r.get("user_prompt", "")]
    check(bool(priv) and "[私聊你] E2ESocial：钻石一般在哪一层" in priv[0]["user_prompt"], "/ai 私聊进了提示词（[私聊你]）")
    forced = [r for r in priv if any("玩家不在线：E2ESocial" in x for x in r.get("results", []))]
    check(bool(forced), f"私聊回复不填 player 也只发给 E2ESocial（假玩家不在线所以报错）：{[r.get('results') for r in priv]}")

    # "别说了"
    check(not any("别说了" in r.get("user_prompt", "") and "Bob" in r.get("user_prompt", "") and "【玩家聊天】" in r.get("user_prompt", "") for r in reqs),
          "\"别说了\"不唤醒模型")
    prefs = json.loads((STATE / "player-prefs.json").read_text(encoding="utf-8")) if (STATE / "player-prefs.json").exists() else {}
    bob = [p for p in prefs.get("players", []) if p.get("name") == "Bob"]
    check(bool(bob) and bob[0].get("quietUntil", bob[0].get("quiet_until", 0)) > 0, f"Bob 进入安静状态：{bob}")
    _, pb = out.get("prefs_bob", (True, {}))
    check(isinstance(pb, dict) and pb.get("quiet") is True, f"get_player_prefs 显示 Bob 安静中：{pb}")
    _, sp = out.get("set_pref", (True, {}))
    check(isinstance(sp, dict) and sp.get("prefs", {}).get("style") == "serious" and "身高" in sp.get("prefs", {}).get("avoid_topics", []),
          f"set_player_pref（远程）记下 Dave 的偏好：{sp}")

    # 私聊脱敏
    _, act = out.get("activity", (True, []))
    text = json.dumps(act, ensure_ascii=False)
    check("私聊了 Steve（内容不记录）" in text, "动态里只记 E2ESocial 私聊了 Steve")
    check("秘密内容123" not in text and "秘密内容123" not in full, "私聊正文没有进动态和日志")
    check("钻石一般在哪一层" in text, "/ai 的话记成 ai_private（机器人自己的对话）")

    # 指令说明
    _, ct = out.get("commands_tpa", (True, {}))
    check(isinstance(ct, dict) and ct.get("who_can_use") == "所有玩家" and any("/tpa" in u for u in ct.get("usage", [])), f"get_commands name=tpa：{ct}")
    _, cl = out.get("commands_list", (True, {}))
    check(isinstance(cl, dict) and any(c.startswith("/goal") for c in cl.get("commands", [])), f"get_commands query=goal：{cl}")
    check(guide.exists() and "服务器指南" in guide.read_text(encoding="utf-8"), "server-guide.md 模板自动生成")
    _, tm = out.get("commands_time", (True, {}))
    check(isinstance(tm, dict) and "time query" in tm.get("vanilla_reference", {}).get("undo", "") and "20 tick" in tm.get("units", ""), f"原版指令带现成资料：{tm}")
    check("note" in (ct if isinstance(ct, dict) else {}) and "还没有资料" in ct.get("note", ""), "没有资料的模组指令提示先小范围试、再记笔记")
    _, nb = out.get("note_before", (True, {}))
    check(isinstance(nb, dict) and "未验证" in nb.get("result", ""), f"没执行过时 verified 不生效：{nb}")
    _, na = out.get("note_after", (True, {}))
    check(isinstance(na, dict) and "已验证" in na.get("result", ""), f"成功执行后记下的笔记是已验证：{na} / 执行结果 {out.get('run_aibot')}")
    _, ca = out.get("commands_aibot", (True, {}))
    check(isinstance(ca, dict) and any(n.get("verified") for n in ca.get("learned_notes", [])), f"get_commands 带上学到的笔记：{ca}")
    check((STATE / "command-notes.json").exists(), "笔记落盘 command-notes.json")

    # 工程、建筑账本
    _, pj = out.get("projects", (True, {}))
    proj = (pj.get("projects") or [{}])[0] if isinstance(pj, dict) else {}
    check(proj.get("title") == "测试酒馆" and proj.get("owner") == "E2ESocial" and proj.get("placed", 0) >= 100, f"/goal project add + 放方块计数：{proj}")
    _, sj = out.get("set_project", (True, {}))
    check(isinstance(sj, dict) and "已记下" in sj.get("result", ""), f"set_project note（远程）：{sj}")
    _, who = out.get("who", (True, {}))
    b0 = (who.get("builders") or [{}])[0] if isinstance(who, dict) else {}
    check(b0.get("player") == "E2ESocial" and b0.get("blocks", 0) >= 100 and len(who.get("projects_here", [])) == 1, f"who_built：{who}")

    # 死亡前因后果
    _, dt = out.get("death", (True, {}))
    d0 = (dt.get("deaths") or [{}])[0] if isinstance(dt, dict) else {}
    check("story" in d0 and "附近 32 格内没有别的玩家" in d0.get("story", "") and d0.get("retrieval") == "retrieved", f"get_death_story：{d0}")

    # 事件：有意义的行为、死亡和跟进
    ev = events_text(reqs)
    check("milestone：E2ESocial 全服第一个造出了" in ev and ("信标" in ev or "beacon" in ev), f"首次造出信标 → milestone 事件：{ev[:600]}")
    check("捡到了" in ev and ("钻石" in ev or "diamond" in ev), "首次捡到钻石 → milestone 事件")
    check("驯服了" in ev, "驯服狼 → milestone 事件")
    check("工程「测试酒馆」范围内已经放了 100 个方块" in ev, "工程放方块跨过 100 → milestone 事件")
    check("player_death：" in ev and "附近 32 格内没有别的玩家" in ev, "死亡事件附带前因后果")
    check("death_followup：E2ESocial 回到死亡地点" in ev, "回死亡点捡东西 → 跟进事件")
    check(token not in full, "令牌没有出现在日志里")
    check(rc == 0, "服务端正常关服")
    finish()


if __name__ == "__main__":
    main()
