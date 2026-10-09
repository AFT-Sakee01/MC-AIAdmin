"""传送请求（/tpa、/tpah）的端到端测试：真实 NeoForge 开发服务端。

用法（仓库根目录）：python scripts/e2e_tpa.py
1. 开服并启用 -Daiadmin.devTpaTest：用 FakePlayer 走请求 → 接受/拒绝 → 传送，检查真实坐标（日志 "TPA-DEV PASS/FAIL"）；
   同时以控制台身份执行 /tpa auto，应提示需要玩家（证明指令已注册、控制台/机器人不能代替玩家发起）。
2. 把 config.json 的 tpa.enabled 改成 false 再开服：/tpa 应变成未知指令（整体关闭生效）。
和其它 e2e 共用开发服务端，不要同时跑。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import check, edit_config, finish, prepare, run_server, server_log  # noqa: E402

REFUSED = ("A player is required", "需要玩家")
UNKNOWN = ("Unknown or incomplete command", "未知或不完整的命令")


def main():
    prepare()
    print("[传送请求] 假玩家流程")
    # 自测和控制台指令都在开服时同步执行完：结果一出来就关服，devHaltAfterSeconds 只是兜底
    rc = run_server(["tpa auto"], {"aiadmin.devTpaTest": "true", "aiadmin.devHaltAfterSeconds": 15}, "server-tpa.log",
                    until=lambda log: "TPA-DEV DONE" in log and any(s in log for s in REFUSED))
    log = server_log("server-tpa.log")
    check(rc == 0, "服务端正常关服")
    passes = [l.split("TPA-DEV PASS ", 1)[1] for l in log.splitlines() if "TPA-DEV PASS " in l]
    fails = [l.split("TPA-DEV FAIL ", 1)[1] for l in log.splitlines() if "TPA-DEV FAIL " in l]
    for p in passes:
        check(True, p)
    for f in fails:
        check(False, f)
    check("TPA-DEV DONE" in log and len(passes) == 9 and not fails, f"自测 9 项全部通过（PASS {len(passes)}，FAIL {len(fails)}）")
    check(any(s in log for s in REFUSED), "控制台执行 /tpa auto 被拒（只有玩家能用）")

    print("[传送请求] 整体关闭")
    edit_config(tpa={"enabled": False})
    rc = run_server(["tpa auto", "tpah TpA"], {"aiadmin.devHaltAfterSeconds": 10}, "server-tpa-off.log",
                    until=lambda log: any(log.count(s) >= 2 for s in UNKNOWN))
    log = server_log("server-tpa-off.log")
    check(rc == 0, "服务端正常关服")
    check(any(log.count(s) >= 2 for s in UNKNOWN), "关闭后 /tpa、/tpah 都是未知指令")
    finish()


if __name__ == "__main__":
    main()
