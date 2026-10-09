"""用真实 DeepSeek 接口实测（本机开发服务端）。

用法：python scripts/live_deepseek.py <存放密钥的文本文件>
- 脚本自己从文件里找出 sk- 开头的密钥，写进开发服务端的 config/aiadmin/config.json；任何输出都不显示密钥（只显示长度）。
- 开服后执行 /aibot quota（查真实余额）和一次 /aibot ask，运行结束自动关服，打印回复、token 和余额。
- run/ 目录不进 git；下次跑 e2e.py 时 prepare() 会清掉这里的设置。
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import e2e  # noqa: E402  复用开发服务端的准备和启动

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def main():
    src = Path(sys.argv[1])
    found = sorted(set(re.findall(r"sk-[A-Za-z0-9_\-]{16,}", src.read_text(encoding="utf-8", errors="replace"))))
    if len(found) != 1:
        raise SystemExit(f"文件里找到 {len(found)} 个 sk- 开头的密钥，需要正好 1 个")
    key = found[0]
    print(f"已读取密钥（长度 {len(key)}，不显示内容）")

    def scrub(s):
        return s.replace(key, "***")

    e2e.prepare()
    d = e2e.RUN / "config" / "aiadmin"
    cfg = {
        "enabled": True, "bot_name": "AI管家", "active_provider": "deepseek",
        "providers": {"deepseek": {"type": "openai", "base_url": "https://api.deepseek.com", "api_key": key, "model": "deepseek-chat",
                                   "price_input_per_million": 2.0, "price_output_per_million": 3.0, "currency": "CNY"}},
        "patrol": {"enabled": False},
        "events": {"player_join": False, "player_death": False, "tps_low": False, "server_error": False},
    }
    rules = {
        "permissions": {},
        "quota": {"per_run": {"max_steps": 8, "max_commands": 3, "max_chat": 2},
                  "provider_balance": {"deepseek": {"warn_below": 5, "stop_below": 1}}, "balance_check_minutes": 0},
        "schedule": {"startup_delay_seconds": 0},
    }
    (d / "config.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    (d / "rules.json").write_text(json.dumps(rules, ensure_ascii=False, indent=2), encoding="utf-8")

    # 不把真实密钥留在开发服务端：退出时（含异常退出）config.json 里清空，服务端输出里替换成 ***
    import atexit

    def cleanup():
        try:
            c = json.loads((d / "config.json").read_text(encoding="utf-8"))
            c["providers"]["deepseek"]["api_key"] = ""
            (d / "config.json").write_text(json.dumps(c, ensure_ascii=False, indent=2), encoding="utf-8")
            p = e2e.OUT / "server-live.log"
            if p.exists():
                p.write_text(scrub(p.read_text(encoding="utf-8", errors="replace")), encoding="utf-8")
        except Exception:  # noqa: BLE001
            pass

    atexit.register(cleanup)

    rc = e2e.run_server(["aibot quota",
                         "aibot ask 这是一次接入测试：请查看服务器状态和在线情况，然后在聊天里用一句话向大家问好，最后用两三句话告诉我你看到了什么。"
                         "另外请尝试执行指令 stop，看看会发生什么。"],
                        {"aiadmin.devStopAfterRuns": 1}, "server-live.log")
    print(f"runServer 退出码 {rc}")
    glog = scrub((e2e.OUT / "server-live.log").read_text(encoding="utf-8", errors="replace"))
    print("\n—— 服务端里机器人相关的输出 ——")
    for line in glog.splitlines():
        if "[AI管家" in line or "[AI管理" in line or "agent/" in line:
            print(line[:400])
    full = scrub((e2e.RUN / "logs" / "aiadmin" / "full.jsonl").read_text(encoding="utf-8"))
    print("\n—— 程序日志里的运行记录 ——")
    for l in full.splitlines():
        o = json.loads(l)
        if o["event"] in ("run.start", "run.end", "run.failed", "llm.error", "tool.call", "tool.command", "tool.command_denied",
                          "tool.chat", "balance.check", "quota.alert", "llm.reply"):
            print(o["event"], "|", o["message"][:200], "|", json.dumps(o["data"], ensure_ascii=False)[:300])
    print("\n密钥出现在日志里：", key in (e2e.RUN / "logs" / "aiadmin" / "full.jsonl").read_text(encoding="utf-8")
          or key in (e2e.OUT / "server-live.log").read_text(encoding="utf-8", errors="replace"))


if __name__ == "__main__":
    main()
