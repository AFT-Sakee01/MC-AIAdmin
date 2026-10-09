"""预设模式（帮助/吐槽/恶作剧）的端到端测试：真实 NeoForge 开发服务端 + 本脚本自带的假 OpenAI 接口。

用法（仓库根目录）：python scripts/e2e_persona.py
复用 e2e_common 的开发服务端准备（prepare/run_server），和其它 e2e 共用 run/server，不要同时跑。
  阶段 1：/aibot mode roast、/aibot prank rough，然后管理员提问 → 系统提示词里有吐槽模式；模式文件自动生成；切换写进 aiadmin/persona.json。
  阶段 2（重启）：/aibot status 仍是吐槽模式（记住了）；切到恶作剧 + 无害档，模拟玩家聊天 → 模型试图执行 time set night（应被恶作剧白名单拒绝）
           和 playsound（应放行），系统提示词里有恶作剧模式和无害档的规则。
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import CFG_DIR, PORT, STATE, check, edit_config, finish, prepare, run_server, server_log  # noqa: E402

REQS = []


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8"))
        msgs = body.get("messages", [])
        system = next((m["content"] for m in msgs if m["role"] == "system"), "")
        user = next((m["content"] for m in msgs if m["role"] == "user"), "")
        results = [m["content"] for m in msgs if m["role"] == "tool"]
        REQS.append({"system": system, "user": user, "results": results})
        if "【玩家聊天】" in user and not results:
            calls = [("run_command", {"command": "time set night"}),
                     ("run_command", {"command": "playsound minecraft:entity.creeper.primed hostile @a"}),
                     ("send_chat", {"message": "嘿嘿，刚才的苦力怕声是我"})]
            msg = {"role": "assistant", "content": "", "tool_calls": [
                {"id": f"c{i}", "type": "function", "function": {"name": n, "arguments": json.dumps(a, ensure_ascii=False)}}
                for i, (n, a) in enumerate(calls)]}
        else:
            msg = {"role": "assistant", "content": "PERSONA-E2E 完成"}
        b = json.dumps({"choices": [{"message": msg, "finish_reason": "tool_calls" if msg.get("tool_calls") else "stop"}],
                        "usage": {"prompt_tokens": 100, "completion_tokens": 10}}, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)


def main():
    ThreadingHTTPServer.allow_reuse_address = False
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        prepare()
        edit_config(remote={"enabled": False})
        # 两次开服都靠 devStopAfterRuns=1：那一次运行结束就关服
        rc1 = run_server(["aibot mode", "aibot mode roast", "aibot prank rough", "aibot mode no_such_mode",
                          "aibot askvia e2e_openai 你好"], {"aiadmin.devStopAfterRuns": 1}, "server-persona1.log")
        n1 = len(REQS)
        rc2 = run_server(["aibot status", "aibot mode prank", "aibot prank harmless", "chat Steve @AI 整我一下"],
                         {"aiadmin.devStopAfterRuns": 1}, "server-persona2.log")
    finally:
        srv.shutdown()
        srv.server_close()

    print("预设模式：")
    log1 = server_log("server-persona1.log")
    log2 = server_log("server-persona2.log")
    check(rc1 == 0 and rc2 == 0, f"两次开服都正常关服（{rc1}, {rc2}）")
    modes = CFG_DIR / "prompts" / "modes"
    check(all((modes / f"{m}.md").exists() for m in ("helper", "roast", "prank")), "模式提示词文件自动生成（helper/roast/prank）")
    check("可用：normal（普通）、helper（帮助模式）、roast（吐槽模式）、prank（恶作剧模式）" in log1, "/aibot mode 列出可用模式")
    check("预设模式已改为 吐槽模式" in log1 and "恶作剧档位已改为 较狠" in log1, "切换模式和档位有回执")
    check("没有这个模式：no_such_mode" in log1, "不存在的模式给出说明")
    ask = REQS[0] if n1 >= 1 else {}
    check("当前预设模式：吐槽模式" in ask.get("system", "") and "毒舌" in ask.get("system", ""), "管理员提问的系统提示词带上吐槽模式")
    state = STATE / "persona.json"
    st = json.loads(state.read_text(encoding="utf-8")) if state.exists() else {}
    check(st.get("mode") == "prank" and st.get("prank_level") == "harmless", f"切换写进 aiadmin/persona.json：{st}")
    check("模式：吐槽模式" in log2, "重启后 /aibot status 仍是吐槽模式（记住了）")
    chat = [r for r in REQS[n1:] if "【玩家聊天】" in r["user"]]
    first = chat[0] if chat else {}
    check("当前预设模式：恶作剧模式" in first.get("system", "") and "当前恶作剧档位：无害整蛊" in first.get("system", ""),
          "聊天回复的系统提示词带上恶作剧模式和无害档规则")
    results = chat[1]["results"] if len(chat) >= 2 else []
    denied = [json.loads(r) for r in results if r.startswith("{") and '"allowed":false' in r.replace(" ", "")]
    allowed = [json.loads(r) for r in results if r.startswith("{") and '"allowed":true' in r.replace(" ", "")]
    check(len(denied) == 1 and "恶作剧模式" in denied[0].get("reason", ""), f"time set night 被恶作剧白名单拒绝：{denied}")
    check(len(allowed) == 1, f"playsound 放行：{allowed}")
    check("Set the time to 13000" not in log2, "游戏里没有真的改成晚上")
    check("[AI管家] 嘿嘿，刚才的苦力怕声是我" in log2, "恶作剧回复出现在游戏聊天里")
    finish()


if __name__ == "__main__":
    main()
