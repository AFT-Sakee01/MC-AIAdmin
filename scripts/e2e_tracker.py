"""玩家行为追踪的端到端测试：真实 NeoForge 开发服务端 + 假玩家 E2EBot（真的挖方块、打僵尸），经远程管理接口调用工具检查。

用法（仓库根目录）：python scripts/e2e_tracker.py
复用 e2e_common 的开发服务端准备（prepare 已让 remote 只监听 127.0.0.1）。
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import call, check, dev_server, finish, prepare, server_log, tool_names  # noqa: E402

FAKE_DONE = "假玩家 E2EBot 已挖 6 个方块"  # 假玩家脚本（开服时同步执行）最后一行日志


def main():
    prepare()
    out = {}
    # 工具都调完就经控制台 stop；devHaltAfterSeconds=45 只是兜底
    with dev_server("server-tracker.log", {"aiadmin.devFakePlayer": "true", "aiadmin.devHaltAfterSeconds": 45}, stop_when_done=True) as srv:
        url = srv.url
        try:
            token = srv.wait_ready()
            srv.wait_log(FAKE_DONE, timeout=60)  # 等假玩家脚本跑完（没等到时下面的计数断言会失败）
            out["tools"] = tool_names(url, token)
            out["overview"] = call(url, token, "get_players_overview", {})
            out["activity"] = call(url, token, "get_player_activity", {"name": "E2EBot", "minutes": 5})
            out["nobody"] = call(url, token, "get_player_activity", {"name": "Nobody"})
            out["filtered"] = call(url, token, "get_player_activity", {"name": "E2EBot", "minutes": 5, "event_kinds": "container"})
            out["offline"] = call(url, token, "get_offline_player", {"name": "E2EBot"})
            out["offline_nobody"] = call(url, token, "get_offline_player", {"name": "Nobody"})
            out["status"] = call(url, token, "get_server_status", {})
        except Exception as ex:
            out["error"] = repr(ex)
    rc = srv.rc

    print("玩家行为追踪：")
    check("error" not in out, f"全程无异常 {out.get('error', '')}")
    check({"get_players_overview", "get_player_activity", "get_offline_player"} <= set(out.get("tools", [])),
          "工具列表里有 get_players_overview、get_player_activity、get_offline_player")
    ov_err, ov = out.get("overview", (True, "{}"))
    check(not ov_err and '"online":[]' in ov.replace(" ", ""), "get_players_overview 可用（开发服没有真实玩家，online 为空）")
    act_err, act_text = out.get("activity", (True, "{}"))
    act = json.loads(act_text) if not act_err else {}
    counts = act.get("counts", {})
    check(counts.get("break", {}).get("stone") == 4 and counts.get("break", {}).get("iron_ore") == 2,
          f"挖方块事件被记录（stone 4、iron_ore 2）：{counts.get('break')}")
    check(counts.get("kill", {}).get("zombie") == 3, f"击杀事件被记录（zombie 3）：{counts.get('kill')}")
    check("战斗" in act.get("doing", ""), f"行为判断：{act.get('doing')}")
    check(act.get("now", {}).get("where", "").startswith("overworld"), f"有位置采样：{act.get('now')}")
    check(any(e.get("kind") == "join" for e in act.get("events", [])), "有进服事件")
    check(counts.get("use", {}).get("lever") == 1, f"右键拉杆记为交互（use.lever 1）：{counts.get('use')}")
    check(counts.get("interact_entity", {}).get("pig") == 1, f"右键猪记为实体交互：{counts.get('interact_entity')}")
    check(counts.get("take", {}).get("diamond") == 5 and counts.get("put", {}).get("dirt") == 7,
          f"容器存取计数（拿 diamond 5、放 dirt 7）：take={counts.get('take')} put={counts.get('put')}")
    evs = act.get("events", [])

    def ev(kind, *parts):
        return any(e.get("kind") == kind and all(x in e.get("detail", "") for x in parts) for e in evs)

    check(ev("use", "Lever [lever]"), "事件里有交互的方块名和 id：Lever [lever]")
    check(ev("interact_entity", "Pig [pig]"), "事件里有交互的实体名和 id：Pig [pig]")
    check(ev("container", "Chest [chest]", "拿走 diamond×5", "放入 dirt×7"), "事件里有箱子存取明细（拿走 diamond×5；放入 dirt×7）")
    check(ev("ore", "Diamond Ore [diamond_ore]") and any(e.get("kind") == "ore" and e.get("where", "").startswith("overworld") for e in evs),
          "挖到钻石矿带坐标")
    check(any(a.get("kind") == "break" for a in act.get("areas", [])), f"有挖方块的区域统计：{act.get('areas')}")
    f_err, f_text = out.get("filtered", (True, "{}"))
    f_evs = json.loads(f_text).get("events", []) if not f_err else []
    check(len(f_evs) == 1 and f_evs[0].get("kind") == "container", f"event_kinds 过滤只剩容器事件：{f_evs}")
    o_err, o_text = out.get("offline", (True, "{}"))
    off = json.loads(o_text) if not o_err else {}
    check(off.get("where", "").startswith("overworld") and any(i.get("id") == "minecraft:diamond" and i.get("count") == 5 for i in off.get("inventory", [])),
          f"离线玩家：位置和背包（diamond×5）：where={off.get('where')} inv={off.get('inventory')}")
    check("ip" not in off and not re.search(r"(?<![\d.])\d{1,3}(\.\d{1,3}){3}(?![\d.])", o_text), "离线玩家结果里没有 IP")
    on_err, on_text = out.get("offline_nobody", (False, ""))
    check("没找到这名玩家" in on_text, "离线查询不存在的玩家给出说明")
    nb_err, nb = out.get("nobody", (False, ""))
    check("没有这名玩家的记录" in nb, "查不存在的玩家给出说明")
    glog = server_log("server-tracker.log")
    check(FAKE_DONE in glog and "假玩家交互脚本失败" not in glog, "假玩家脚本执行了（含交互脚本）")
    check(rc == 0, "服务端正常关服")
    finish()


if __name__ == "__main__":
    main()
