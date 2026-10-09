"""端到端测试用的假模型服务：同时模拟 OpenAI Chat Completions、Claude Messages、Antigravity Interactions 和 DeepSeek 余额接口。

每种协议按"已经收到的工具结果数"推进同一套剧本：
  第 0 步：并行调用 7 个观察工具
  第 1 步：run_command time set day（应成功）、run_command execute run stop（应被黑名单拒绝）、read_file 机器人自己的设置（应被拒绝）
  第 2 步：send_chat
  第 3 步：给出最终文字
所有请求写进 requests.jsonl，供 e2e.py 断言。
"""
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = "e2e-test-key"
OBSERVE = [
    ("get_server_status", {}),
    ("list_players", {}),
    ("get_world_info", {}),
    ("get_entities", {"top_chunks": 3}),
    ("read_file", {"path": "server.properties", "max_lines": 50}),
    ("get_server_log", {"lines": 5}),
    ("list_mods", {}),
]
ACT = [
    ("run_command", {"command": "time set day"}),
    ("run_command", {"command": "execute as @a run stop"}),
    ("read_file", {"path": "config/aiadmin/config.json"}),
    ("read_file", {"path": "config/aiadmin./config.json"}),  # Windows 段尾点绕过（应被拒绝）
]


def script(n_results, proto):
    """返回 (要调用的工具列表, 最终文字)。"""
    if n_results == 0:
        return OBSERVE, None
    if n_results == len(OBSERVE):
        return ACT, None
    if n_results == len(OBSERVE) + len(ACT):
        return [("send_chat", {"message": f"E2E 测试消息（{proto}）"})], None
    return [], f"E2E {proto} 完成"


class Mock:
    def __init__(self, log_path):
        self.log_path = log_path
        self.lock = threading.Lock()
        self.agy = {}  # interaction id -> 已收到的结果数
        self.server = None

    def record(self, entry):
        with self.lock, open(self.log_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def start(self, port):
        mock = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, code, obj):
                b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def do_GET(self):
                mock.record({"method": "GET", "path": self.path, "auth": self.headers.get("Authorization")})
                if self.path == "/user/balance":
                    if self.headers.get("Authorization") != "Bearer " + KEY:
                        return self.reply(401, {"error": "bad key"})
                    return self.reply(200, {"is_available": True, "balance_infos": [
                        {"currency": "CNY", "total_balance": "3.00", "granted_balance": "0.00", "topped_up_balance": "3.00"}]})
                self.reply(404, {"error": "not found"})

            def do_POST(self):
                # 请求格式变了导致这里抛异常时：回 500 并把堆栈记进 requests.jsonl。以前直接断开连接，
                # Java 端当成网络错误重试，e2e 只报"请求轮数不对"，看不出真正原因
                try:
                    return self.handle_post()
                except Exception:  # noqa: BLE001
                    import traceback
                    mock.record({"proto": "mock_error", "path": self.path, "traceback": traceback.format_exc()})
                    try:
                        self.reply(500, {"error": "mock_llm 处理请求时出错，见 requests.jsonl 里的 mock_error"})
                    except OSError:
                        pass

            def handle_post(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8"))
                hdr = {k.lower(): v for k, v in self.headers.items()}
                if self.path.startswith("/hang/"):
                    # 模拟卡死的中转站：收下请求但一直不回
                    mock.record({"proto": "hang", "path": self.path, "t": time.time()})
                    time.sleep(40)
                    return
                if self.path == "/v1/chat/completions":
                    return self.openai(body, hdr)
                if self.path == "/v1/messages":
                    return self.anthropic(body, hdr)
                if self.path == "/v1beta/interactions":
                    return self.antigravity(body, hdr)
                self.reply(404, {"error": "not found"})

            def openai(self, body, hdr):
                ok = hdr.get("authorization") == "Bearer " + KEY
                results = [m for m in body["messages"] if m["role"] == "tool"]
                user_prompt = next((m["content"] for m in body["messages"] if m["role"] == "user"), "")
                if "【玩家聊天】" in user_prompt or "【玩家私聊】" in user_prompt:
                    # 聊天剧本：第一轮用 send_chat 回复，第二轮结束
                    mock.record({"proto": "openai-chat", "auth_ok": ok, "n_results": len(results), "user_prompt": user_prompt,
                                 "results": [m["content"] for m in results]})
                    if not results:
                        msg = {"role": "assistant", "content": "", "tool_calls": [{"id": "chat_0", "type": "function", "function": {
                            "name": "send_chat", "arguments": json.dumps({"message": "你好 Steve，服务器一切正常！"}, ensure_ascii=False)}}]}
                    else:
                        msg = {"role": "assistant", "content": "聊天已回复"}
                    return self.reply(200, {"choices": [{"message": msg}], "usage": {"prompt_tokens": 500, "completion_tokens": 20}})
                mock.record({"proto": "openai", "auth_ok": ok, "n_results": len(results), "tools": [t["function"]["name"] for t in body.get("tools", [])],
                             "results": [m["content"] for m in results], "messages": len(body["messages"]),
                             "user_prompt": next((m["content"] for m in body["messages"] if m["role"] == "user"), "")})
                if not ok:
                    return self.reply(401, {"error": "bad key"})
                calls, text = script(len(results), "openai")
                msg = {"role": "assistant", "content": text}
                if calls:
                    msg["tool_calls"] = [{"id": f"call_{len(results)}_{i}", "type": "function",
                                          "function": {"name": n, "arguments": json.dumps(a, ensure_ascii=False)}} for i, (n, a) in enumerate(calls)]
                self.reply(200, {"choices": [{"message": msg, "finish_reason": "tool_calls" if calls else "stop"}],
                                 "usage": {"prompt_tokens": 1000, "completion_tokens": 50}})

            def anthropic(self, body, hdr):
                ok = hdr.get("x-api-key") == KEY and hdr.get("anthropic-version") == "2023-06-01"
                results = [b for m in body["messages"] if m["role"] == "user" and isinstance(m["content"], list)
                           for b in m["content"] if b.get("type") == "tool_result"]
                sigs = [b.get("signature") for m in body["messages"] if m["role"] == "assistant" for b in m["content"] if b.get("type") == "thinking"]
                mock.record({"proto": "anthropic", "auth_ok": ok, "n_results": len(results), "tools": [t["name"] for t in body.get("tools", [])],
                             "results": [b["content"] for b in results], "signatures": sigs, "has_system": bool(body.get("system"))})
                if not ok:
                    return self.reply(401, {"type": "error"})
                calls, text = script(len(results), "anthropic")
                content = [{"type": "thinking", "thinking": "", "signature": f"sig-e2e-{len(results)}"}]
                if text:
                    content.append({"type": "text", "text": text})
                for i, (n, a) in enumerate(calls):
                    content.append({"type": "tool_use", "id": f"toolu_{len(results)}_{i}", "name": n, "input": a})
                self.reply(200, {"content": content, "stop_reason": "tool_use" if calls else "end_turn",
                                 "usage": {"input_tokens": 1000, "output_tokens": 50}})

            def antigravity(self, body, hdr):
                ok = hdr.get("x-goog-api-key") == KEY
                prev = body.get("previous_interaction_id")
                frs = [i for i in body.get("input", []) if isinstance(i, dict) and i.get("type") == "function_result"]
                with mock.lock:
                    n = (mock.agy.get(prev, 0) if prev else 0) + len(frs)
                mock.record({"proto": "antigravity", "auth_ok": ok, "n_results": n, "prev": prev, "environment": body.get("environment"),
                             "tools": [t.get("name") or t.get("type") for t in body.get("tools", [])],
                             "results": [f["result"][0]["text"] for f in frs], "agent": body.get("agent")})
                if not ok:
                    return self.reply(401, {"error": "bad key"})
                iid = "int-" + uuid.uuid4().hex[:8]
                with mock.lock:
                    mock.agy[iid] = n
                calls, text = script(n, "antigravity")
                if calls:
                    steps = [{"type": "function_call", "id": f"fc_{n}_{i}", "name": nm, "arguments": a} for i, (nm, a) in enumerate(calls)]
                    return self.reply(200, {"id": iid, "status": "requires_action", "environment_id": "env-e2e", "steps": steps,
                                            "usage": {"input_tokens": 1000, "output_tokens": 50}})
                self.reply(200, {"id": iid, "status": "completed", "output_text": text, "environment_id": "env-e2e",
                                 "usage": {"input_tokens": 1000, "output_tokens": 50}})

        # Windows 上 SO_REUSEADDR 允许重复绑定同一端口，旧监听口没关时新请求会被吞掉，所以关掉复用并在 stop() 里 server_close()
        ThreadingHTTPServer.allow_reuse_address = False
        self.server = ThreadingHTTPServer(("127.0.0.1", port), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
