"""远程接口 OAuth 的端到端测试：真实 NeoForge 开发服务端，按 ChatGPT 连接器的流程走一遍。

发现（受保护资源元数据 → 授权服务器元数据）→ 动态注册 → 授权页（显示确认号）→ 批准（后台 /aibot remote approve 的指令路径在开服时验证；
批准本身用固定令牌，因为开发服务端只能在开服时执行后台指令）
→ 授权码 + PKCE 换令牌 → 用 access token 调 MCP 工具 → 刷新令牌轮换 → 各种拒绝情况。

用法（仓库根目录）：python scripts/e2e_oauth.py
复用 e2e_common 的开发服务端准备（prepare）；和其它 e2e 共用开发服务端，不要同时跑。
"""
import base64
import hashlib
import json
import re
import secrets
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import CFG_DIR, REMOTE_PORT, check, dev_server, edit_config, finish, full_log, http, prepare, remote_cfg, remove  # noqa: E402

REDIRECT = "https://chatgpt.com/connector_platform_oauth_redirect"
APPROVE_OUT = ["没有待批的 OAuth 授权请求", "没有确认号为 ZZZZZZ 的待批请求"]  # 开服时两条 /aibot remote approve 的输出


def form(url, fields, headers=None):
    h = {"Content-Type": "application/x-www-form-urlencoded"}
    h.update(headers or {})
    return http("POST", url, urllib.parse.urlencode(fields), h)


def mcp(url, bearer, method, params=None):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}})
    return http("POST", url, body, {"Content-Type": "application/json", "Authorization": f"Bearer {bearer}"})


async def sdk_flow(url, base, token):
    """官方 MCP Python SDK 的 OAuth 客户端（自己做发现、动态注册、PKCE、换令牌）；浏览器批准这一步由脚本代替服主完成。"""
    from mcp import ClientSession
    from mcp.client.auth import OAuthClientProvider, TokenStorage
    from mcp.client.streamable_http import streamablehttp_client
    from mcp.shared.auth import OAuthClientMetadata

    class Mem(TokenStorage):
        def __init__(self):
            self.t = None
            self.c = None

        async def get_tokens(self):
            return self.t

        async def set_tokens(self, t):
            self.t = t

        async def get_client_info(self):
            return self.c

        async def set_client_info(self, c):
            self.c = c

    got = {}

    async def redirect_handler(auth_url):
        _, _, page = http("GET", auth_url)
        rid = re.search(r'name="request" value="([^"]+)"', page)
        _, h, _ = form(base + "/authorize", {"request": rid.group(1) if rid else "", "approval": token, "decision": "approve"}, {"Origin": base})
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(h.get("location", "")).query))
        got["code"], got["state"] = q.get("code", ""), q.get("state")

    async def callback_handler():
        return got["code"], got["state"]

    provider = OAuthClientProvider(
        server_url=url,
        client_metadata=OAuthClientMetadata(client_name="sdk-interop", redirect_uris=["http://localhost:3030/callback"],
                                            grant_types=["authorization_code", "refresh_token"], response_types=["code"],
                                            token_endpoint_auth_method="none"),
        storage=Mem(), redirect_handler=redirect_handler, callback_handler=callback_handler)
    async with streamablehttp_client(url, auth=provider) as (r, w, _):
        async with ClientSession(r, w) as session:
            await session.initialize()
            tools = await session.list_tools()
            res = await session.call_tool("get_server_status", {})
            return [t.name for t in tools.tools], res.isError


def main():
    prepare()
    base = f"http://127.0.0.1:{REMOTE_PORT}"
    edit_config(remote=remote_cfg(public_base_url=base))
    remove(CFG_DIR / "oauth-state.json")  # 每次从干净的 OAuth 状态开始

    out = {}
    secrets_seen = []
    # 流程走完就经控制台 stop；devHaltAfterSeconds=60 只是兜底
    with dev_server("server-oauth.log", {"aiadmin.devHaltAfterSeconds": 60},
                    ["aibot remote approve", "aibot remote approve ZZZZZZ", "aibot remote"], stop_when_done=True) as srv:
        url = srv.url
        try:
            token = srv.wait_ready()
            secrets_seen.append(token)
            # 开服指令在 ServerStarted 时执行，比远程接口开始监听稍晚：等它们的输出出现（没等到时 approve_cmd 为 False，下面那项失败）
            out["approve_cmd"] = srv.wait_log(APPROVE_OUT, timeout=30)

            # 1. 发现
            out["mcp_401"] = http("POST", url, "{}", {"Content-Type": "application/json"})
            out["prm"] = http("GET", base + "/.well-known/oauth-protected-resource")
            out["asm"] = http("GET", base + "/.well-known/oauth-authorization-server")
            out["root_404"] = http("GET", base + "/")
            # 2. 动态注册
            st, _, body = http("POST", base + "/register", json.dumps({"client_name": "ChatGPT", "redirect_uris": [REDIRECT],
                                                                       "token_endpoint_auth_method": "none"}), {"Content-Type": "application/json"})
            out["register"] = (st, body)
            cid = json.loads(body).get("client_id") if st == 201 else ""
            out["register_evil"] = http("POST", base + "/register", json.dumps({"redirect_uris": ["https://evil.example/cb"]}),
                                        {"Content-Type": "application/json"})
            # 3. 授权页
            verifier = secrets.token_urlsafe(48)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
            q = urllib.parse.urlencode({"response_type": "code", "client_id": cid, "redirect_uri": REDIRECT, "code_challenge": challenge,
                                        "code_challenge_method": "S256", "state": "st-42", "scope": "mcp", "resource": url})
            out["page"] = http("GET", base + "/authorize?" + q)
            rid = re.search(r'name="request" value="([^"]+)"', out["page"][2])
            rid = rid.group(1) if rid else ""
            out["confirm"] = re.search(r'class="code">([A-Z2-9]{6})<', out["page"][2]) is not None
            out["not_yet"] = form(base + "/authorize", {"request": rid, "decision": "approve"}, {"Origin": base})
            out["wrong"] = form(base + "/authorize", {"request": rid, "approval": "WRONG-TOKEN-1", "decision": "approve"}, {"Origin": base})
            out["approve"] = form(base + "/authorize", {"request": rid, "approval": token, "decision": "approve"}, {"Origin": base})
            loc = out["approve"][1].get("location", "")
            params = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(loc).query))
            out["redirect_params"] = params
            code = params.get("code", "")
            secrets_seen.append(code)
            # 4. 换令牌
            out["token"] = form(base + "/token", {"grant_type": "authorization_code", "code": code, "redirect_uri": REDIRECT,
                                                  "code_verifier": verifier, "client_id": cid})
            tok = json.loads(out["token"][2]) if out["token"][0] == 200 else {}
            at, rt = tok.get("access_token", ""), tok.get("refresh_token", "")
            secrets_seen += [at, rt]
            out["code_reuse"] = form(base + "/token", {"grant_type": "authorization_code", "code": code, "redirect_uri": REDIRECT,
                                                       "code_verifier": verifier, "client_id": cid})
            # 5. 用 access token 调工具
            out["tools"] = mcp(url, at, "tools/list")
            out["call"] = mcp(url, at, "tools/call", {"name": "get_server_status", "arguments": {}})
            # 6. 刷新轮换
            out["refresh"] = form(base + "/token", {"grant_type": "refresh_token", "refresh_token": rt, "client_id": cid})
            tok2 = json.loads(out["refresh"][2]) if out["refresh"][0] == 200 else {}
            secrets_seen += [tok2.get("access_token", ""), tok2.get("refresh_token", "")]
            out["call2"] = mcp(url, tok2.get("access_token", ""), "tools/list")
            out["static"] = mcp(url, token, "tools/list")
            try:
                import asyncio
                import mcp as _sdk  # noqa: F401
                out["sdk"] = asyncio.run(sdk_flow(url, base, token))
            except ImportError:
                out["sdk"] = "skip"
            except Exception as ex:  # SDK 流程失败单独记，不影响前面的结果
                out["sdk"] = "error: " + repr(ex)
        except Exception as ex:
            out["error"] = repr(ex)
    rc = srv.rc
    url = srv.url

    print("远程接口 OAuth：")
    check("error" not in out, f"全程无异常 {out.get('error', '')}")
    check(out.get("approve_cmd") is True, "后台 /aibot remote approve（列出待批请求、按确认号批准）指令可用")
    st, h, _ = out.get("mcp_401", (0, {}, ""))
    check(st == 401 and h.get("www-authenticate") == f'Bearer resource_metadata="{base}/.well-known/oauth-protected-resource"',
          f"未带令牌访问 /mcp：401 + resource_metadata（{h.get('www-authenticate')}）")
    prm = json.loads(out.get("prm", (0, {}, "{}"))[2] or "{}")
    check(prm.get("resource") == url and prm.get("authorization_servers") == [base], f"受保护资源元数据：{prm}")
    asm = json.loads(out.get("asm", (0, {}, "{}"))[2] or "{}")
    check(asm.get("token_endpoint") == base + "/token" and asm.get("code_challenge_methods_supported") == ["S256"], "授权服务器元数据（只支持 S256）")
    check(out.get("root_404", (0,))[0] == 404, "其他路径一律 404")
    check(out.get("register", (0, ""))[0] == 201, f"动态注册成功：{out.get('register', (0, ''))[1][:120]}")
    check(out.get("register_evil", (0,))[0] == 400, "回调主机不在白名单的注册被拒绝")
    st, h, body = out.get("page", (0, {}, ""))
    check(st == 200 and h.get("x-frame-options") == "DENY" and "frame-ancestors 'none'" in h.get("content-security-policy", ""),
          "授权页：禁止被嵌入（X-Frame-Options、CSP）")
    check("chatgpt.com" in body and out.get("confirm") is True, "授权页显示批准后跳转到的主机和确认号")
    nst, _, nbody = out.get("not_yet", (0, {}, ""))
    check(nst == 200 and "还没有在服务器后台执行" in nbody, "还没在后台批准就点继续：提示去后台执行，不跳转")
    check(out.get("wrong", (0,))[0] == 401 and "location" not in out.get("wrong", (0, {}))[1], "错误的令牌：不跳转")
    p = out.get("redirect_params", {})
    check(out.get("approve", (0,))[0] == 302 and p.get("code") and p.get("state") == "st-42" and p.get("iss") == base,
          f"批准后跳回客户端，带 code、state、iss：{ {k: (v if k != 'code' else '…') for k, v in p.items()} }")
    check(out.get("token", (0,))[0] == 200 and out["token"][1].get("cache-control") == "no-store", "授权码 + PKCE 换到令牌（no-store）")
    check(out.get("code_reuse", (0,))[0] == 400, "授权码不能用第二次")
    check(out.get("tools", (0,))[0] == 200 and "get_server_status" in out.get("tools", (0, {}, ""))[2], "用 OAuth access token 列出工具")
    call = out.get("call", (0, {}, "{}"))
    check(call[0] == 200 and '"isError":false' in call[2].replace(" ", ""), "用 OAuth access token 调用 get_server_status")
    check(out.get("refresh", (0,))[0] == 200 and out.get("call2", (0,))[0] == 200, "刷新令牌换到新的一对，新 access token 可用")
    check(out.get("static", (0,))[0] == 200, "固定令牌照常可用")
    sdk = out.get("sdk")
    if sdk == "skip":
        print("  SKIP 官方 MCP Python SDK 未安装（pip install mcp），跳过互通测试")
    else:
        check(isinstance(sdk, tuple) and "get_server_status" in sdk[0] and sdk[1] is False,
              f"官方 MCP Python SDK 的 OAuth 客户端：自动发现、注册、PKCE 换令牌后列出并调用工具（{str(sdk)[:160]}）")
    full = full_log()
    glog = srv.log()
    leaked = [s for s in secrets_seen if s and (s in full or s in glog)]
    check(not leaked, f"令牌、授权码不出现在审计日志和服务端日志里（泄露 {len(leaked)} 个）")
    check(any('"event":"remote.call"' in l and "oauth:" in l for l in full.splitlines()), "审计日志标明是哪个 OAuth 客户端在调用")
    check(rc == 0, "服务端正常关服")
    finish()


if __name__ == "__main__":
    main()
