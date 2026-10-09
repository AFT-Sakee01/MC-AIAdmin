"""MCP Events 推送（ChatGPT / dots 的 webhook 唤醒）端到端测试：真实 NeoForge 开发服务端 + 假模型服务（本地 API）+ 本脚本扮演云端客户端和它的回调接收端。

流程：
1. 新版协议（2026-07-28）server/discover 公布 events 能力；events/list 有 duty.item。
2. events/subscribe → 服务端先向回调地址发签名挑战，脚本回显 → 订阅建立；aibot_status 显示"在线（推送）"。
3. 玩家 @机器人 → 回调收到签名正确的 duty.item 推送（含 item_id、摘要）→ handoff 秒已过仍能 wait_events 取到（送达后放宽到 webhook_handoff）
   → reply_chat / complete_item；本地 API 没被调用。
4. events/unsubscribe → 再有聊天不再推送，handoff 后交本地。
用法（仓库根目录）：python scripts/e2e_events.py   （和其它 e2e 共用开发服务端，不能同时跑）
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import (  # noqa: E402
    CFG_DIR, MODERN, OUT, ChatFile, call, check, dev_server, edit_config, finish, full_log, mock_requests, mock_server, prepare, remote_cfg,
    remove, rpc, wait_until)

HOOK_PORT = 18777
HANDOFF = 5  # 秒：没推送的条目多久交给本地；推送送达的放宽到 webhook_handoff_seconds（25）


def sign(secret, msg_id, ts, body):
    key = base64.b64decode(secret[len("whsec_"):])
    mac = hmac.new(key, f"{msg_id}.{ts}.".encode("utf-8") + body, hashlib.sha256).digest()
    return "v1," + base64.b64encode(mac).decode("ascii")


class Receiver:
    """回调接收端：验证挑战原样回显，事件记录下来并回 200。用作 with 时退出即关闭监听口。"""

    def __init__(self):
        self.hits = []
        self.lock = threading.Lock()
        outer = self

        class H(BaseHTTPRequestHandler):
            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n)
                try:
                    j = json.loads(body.decode("utf-8"))
                except ValueError:
                    j = {}
                with outer.lock:
                    outer.hits.append({"path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}, "body": body, "json": j})
                if j.get("type") == "verification":
                    out = json.dumps({"challenge": j.get("challenge")}).encode("utf-8")
                else:
                    out = b"{}"
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *a):
                pass

        self.srv = ThreadingHTTPServer(("127.0.0.1", HOOK_PORT), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def snapshot(self):
        with self.lock:
            return list(self.hits)

    def wait(self, n, seconds):
        """等到至少收到 n 次回调或超时，返回当时收到的全部回调。"""
        wait_until(lambda: len(self.snapshot()) >= n, seconds, interval=0.2)
        return self.snapshot()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.srv.shutdown()
        self.srv.server_close()


def main():
    prepare()
    edit_config(remote=remote_cfg(cloud={"enabled": True, "handoff_seconds": HANDOFF, "webhook_handoff_seconds": 25,
                                         "online_timeout_seconds": 30, "default_wait_seconds": 10}))
    state_file = CFG_DIR / "mcp-events.json"
    remove(state_file)
    chat = ChatFile("events-chat.txt")
    req_file = OUT / "requests.jsonl"

    def local_chats():
        return mock_requests(req_file, "openai-chat")

    callback = f"http://127.0.0.1:{HOOK_PORT}/mcp-events/cb_e2e"
    secret = "whsec_" + base64.b64encode(os.urandom(32)).decode("ascii")
    out = {}
    token = "<no-token>"
    # 流程走完就经控制台 stop；devHaltAfterSeconds=120 只是兜底
    with mock_server(req_file), Receiver() as rx, \
            dev_server("server-events.log", {**chat.prop, "aiadmin.devWebhookInsecure": "true", "aiadmin.devHaltAfterSeconds": 120},
                       stop_when_done=True) as srv:
        url = srv.url
        try:
            token = srv.wait_ready()

            # 1) 新版协议：discover / events/list
            out["discover"] = rpc(url, token, "server/discover", {}, modern=True)
            out["list"] = rpc(url, token, "events/list", {}, modern=True)
            out["status_before"] = call(url, token, "aibot_status", {})

            # 2) 订阅：挑战 → 回显 → 建立
            sub_params = {"name": "duty.item", "arguments": {}, "delivery": {"mode": "webhook", "url": callback, "secret": secret}, "cursor": None}
            out["subscribe"] = rpc(url, token, "events/subscribe", sub_params, modern=True)
            out["verify_hits"] = rx.wait(1, 5)
            out["status_online"] = call(url, token, "aibot_status", {})
            out["state_after_sub"] = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else None

            # 3) 聊天 → 推送 → 取走处理
            chat.say("chat Steve @AI 帮我把天调成白天")
            t1 = time.monotonic()
            hits = rx.wait(2, 15)
            out["push_secs"] = time.monotonic() - t1
            out["event_hits"] = [h for h in hits if h["json"].get("name") == "duty.item"]
            # 要验证的就是"handoff 5 秒已过、因为推送已送达放宽到 25 秒所以还没转本地"：必须固定等过 handoff 再取
            time.sleep(max(0.0, HANDOFF + 2 - (time.monotonic() - t1)))
            out["wait"] = call(url, token, "wait_events", {"timeout_seconds": 0})
            items = json.loads(out["wait"][1]).get("items", [])
            out["items"] = items
            if items:
                iid = items[0]["item_id"]
                out["reply"] = call(url, token, "reply_chat", {"item_id": iid, "message": "好的，马上调成白天"})
                out["done"] = call(url, token, "complete_item", {"item_id": iid, "summary": "回复了 Steve"})
            # 负向断言（推送唤醒处理的聊天没有调用本地 API）：没有"事件"可等，固定再等 2 秒确认本地 API 确实没被叫
            time.sleep(2)
            out["local_after_cloud"] = len(local_chats())

            # 4) 退订：不再推送；心跳还在（30 秒内）所以仍进收件箱，handoff 后交本地
            out["unsubscribe"] = rpc(url, token, "events/unsubscribe",
                                     {"name": "duty.item", "arguments": {}, "delivery": {"mode": "webhook", "url": callback}}, modern=True)
            out["state_after_unsub"] = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else None
            n_hits = len(rx.snapshot())
            chat.say("chat Alex @AI 你好呀")
            # 等本地 API 接手 Alex 这条（handoff 后）。推送若有，会在放进收件箱时立刻发出、早于转本地，所以等到这里也就覆盖了"不再推送"的观察窗口
            wait_until(lambda: any("Alex" in r.get("user_prompt", "") for r in local_chats()), HANDOFF + 25,
                       what="退订后 handoff 交本地 API（Alex）")
            out["hits_after_unsub"] = len(rx.snapshot()) - n_hits
            out["local_after_handoff"] = local_chats()
            out["status_after"] = call(url, token, "aibot_status", {})
        except Exception as ex:
            out["error"] = repr(ex)
    rc = srv.rc

    print("MCP Events 推送：")
    check("error" not in out, f"全程无异常 {out.get('error', '')}")
    d = out.get("discover", {}).get("result", {})
    check(d.get("resultType") == "complete" and "events" in d.get("capabilities", {}) and d.get("supportedVersions") == [MODERN],
          f"server/discover：新版协议、公布 events 能力：{json.dumps(d.get('capabilities'), ensure_ascii=False)}")
    evs = out.get("list", {}).get("result", {}).get("events", [])
    check(len(evs) == 1 and evs[0].get("name") == "duty.item" and evs[0].get("delivery") == ["webhook"], f"events/list 有 duty.item（webhook）：{[e.get('name') for e in evs]}")
    check("离线" in out.get("status_before", (True, ""))[1], "订阅前：云端离线")
    sub = out.get("subscribe", {}).get("result", {})
    check(str(sub.get("id", "")).startswith("sub_") and sub.get("refreshBefore") and sub.get("cursor") is None, f"events/subscribe 返回订阅 id 与 refreshBefore：{sub}")
    vh = [h for h in out.get("verify_hits", []) if h["json"].get("type") == "verification"]
    ok_sig = bool(vh) and vh[0]["headers"].get("webhook-signature") == sign(secret, vh[0]["headers"].get("webhook-id", ""), vh[0]["headers"].get("webhook-timestamp", ""), vh[0]["body"])
    check(bool(vh) and vh[0]["path"] == "/mcp-events/cb_e2e" and vh[0]["headers"].get("x-mcp-subscription-id") == sub.get("id"), "订阅前收到验证挑战（路径、订阅 id 正确）")
    check(ok_sig, "验证挑战的 Standard Webhooks 签名正确")
    check("在线（推送）" in out.get("status_online", (True, ""))[1] and "推送订阅 1 个" in out.get("status_online", (True, ""))[1], "订阅后 aibot_status 显示在线（推送）")
    st = out.get("state_after_sub") or {}
    check(len(st.get("subscriptions", [])) == 1 and st["subscriptions"][0].get("secret") == secret, "mcp-events.json 保存了订阅（含签名密钥）")
    eh = out.get("event_hits", [])
    data = eh[0]["json"].get("data", {}) if eh else {}
    check(len(eh) == 1 and data.get("kind") == "chat" and "Steve：@AI 帮我把天调成白天" in data.get("summary", "") and data.get("item_id", "").startswith("c"),
          f"玩家聊天推送到回调（{out.get('push_secs', 99):.1f} 秒）：{json.dumps(data, ensure_ascii=False)[:160]}")
    if eh:
        h = eh[0]["headers"]
        check(h.get("webhook-signature") == sign(secret, h.get("webhook-id", ""), h.get("webhook-timestamp", ""), eh[0]["body"])
              and h.get("webhook-id") == eh[0]["json"].get("eventId") and h.get("x-mcp-subscription-id") == sub.get("id"), "推送的签名、webhook-id、订阅 id 正确")
        check("instructions" not in eh[0]["json"], "推送体只有数据摘要（完整说明由 wait_events 取）")
    items = out.get("items", [])
    check(len(items) == 1 and items[0].get("item_id") == data.get("item_id"), "handoff 秒已过、送达后放宽：wait_events 仍取到同一条消息")
    check(out.get("reply", (True, ""))[1] == "已发送" and out.get("done", (True, ""))[1] == "已记录", "reply_chat / complete_item 正常")
    check(out.get("local_after_cloud", -1) == 0, f"推送唤醒处理的聊天没有调用本地 API（本地请求 {out.get('local_after_cloud')}）")
    un = out.get("unsubscribe", {}).get("result", {"x": 1})
    check(un.get("resultType") == "complete" and not (set(un) - {"resultType", "_meta"}), f"events/unsubscribe 返回空结果（新版协议只带 resultType/_meta）：{un}")
    st2 = out.get("state_after_unsub") or {}
    check(st2.get("subscriptions") == [], "退订后 mcp-events.json 里没有订阅")
    check(out.get("hits_after_unsub", -1) == 0, "退订后新聊天不再推送")
    lh = out.get("local_after_handoff", [])
    check(len(lh) >= 1 and "Alex" in lh[-1].get("user_prompt", ""), "退订后在线但没取：handoff 后本地 API 接手（Alex）")
    check("（推送）" not in out.get("status_after", (True, ""))[1], "退订后状态不再显示推送在线")
    full = full_log()
    check('"remote.events_subscribe"' in full and '"remote.events_delivered"' in full and '"remote.events_unsubscribe"' in full, "审计日志：订阅、送达、退订都有记录")
    check(secret not in full and secret[6:] not in full, "签名密钥没有出现在日志里")
    check(token not in full, "令牌没有出现在日志里")
    check(rc == 0, "服务端正常关服")
    finish()


if __name__ == "__main__":
    main()
