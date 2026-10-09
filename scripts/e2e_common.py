"""端到端测试共用工具：断言计数、按条件等待、开发服务端的准备/启动/收尾、远程接口（MCP JSON-RPC）调用、日志与请求记录读取。

各 e2e*.py 都 import 本模块（e2e.py 只是其中一套，不再充当公共库）。e2e.py 仍把 prepare / run_server / RUN / OUT 等名字
留在自己的命名空间里，live_deepseek.py 经 e2e.xxx 复用。
端到端测试共用固定端口（假模型 18765、远程 25580、MC 25591、回调 18777）和同一个 run/server 目录：同一时间只能跑一套。
"""
import contextlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOD = ROOT / "mod"
RUN = MOD / "neoforge-1.21.1" / "run" / "server"
OUT = MOD / "neoforge-1.21.1" / "run" / "e2e"
CFG_DIR = RUN / "config" / "aiadmin"
CONFIG = CFG_DIR / "config.json"
STATE = RUN / "aiadmin"
FULL_LOG = RUN / "logs" / "aiadmin" / "full.jsonl"
PORT = 18765         # 假模型服务
REMOTE_PORT = 25580  # 远程管理接口
MCP_URL = f"http://127.0.0.1:{REMOTE_PORT}/mcp"
MODERN = "2026-07-28"  # 新版 MCP 协议版本（e2e_events 用）
GRADLEW = str(MOD / ("gradlew.bat" if os.name == "nt" else "gradlew"))

sys.path.insert(0, str(Path(__file__).parent))
import mock_llm  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Windows 控制台默认编码打不出中文

# —— 断言计数 ——
FAILS = []


def check(cond, what):
    print(("  PASS " if cond else "  FAIL ") + what)
    if not cond:
        FAILS.append(what)
    return bool(cond)


def finish():
    """打印汇总并退出：退出码 0 = 全部通过。"""
    print(f"\n结果：{'全部通过' if not FAILS else str(len(FAILS)) + ' 项失败'}")
    sys.exit(1 if FAILS else 0)


def wait_until(pred, timeout, interval=0.5, what=None):
    """轮询 pred() 直到返回真值或超时，返回最后一次 pred() 的结果（超时 = 假值，紧接着的 check 会失败）。
    超时且给了 what 时打印原因。pred 抛出的异常不吞：比如远程调用失败本身就是要暴露的问题，交给调用方的 try 记下。
    用 monotonic 计时：改系统时间、夏令时切换不影响超时。"""
    deadline = time.monotonic() + timeout
    while True:
        v = pred()
        if v or time.monotonic() >= deadline:
            break
        time.sleep(interval)
    if not v and what:
        print(f"  等待超时（{timeout} 秒）：{what}")
    return v


# —— 文件 ——
def read_text(path):
    """读 UTF-8 文本（坏字节替换）。文件不存在照样抛异常：缺文件本身说明出了问题，不能当成空内容放过。"""
    return Path(path).read_text(encoding="utf-8", errors="replace")


def server_log(name):
    """run/e2e 下某次开服的 Gradle 输出（含服务端日志）。"""
    return read_text(OUT / name)


def full_log():
    """模组的程序日志 logs/aiadmin/full.jsonl 全文。"""
    return read_text(FULL_LOG)


def full_events():
    """程序日志逐行解析；任何一行解析失败都抛异常（日志必须全部可解析）。"""
    return [json.loads(line) for line in full_log().splitlines()]


def read_jsonl(path, strict=False):
    """JSON Lines（假模型的请求记录等），跳过空行。
    strict=False：文件不存在返回 []、解析不了的行跳过——服务端还在跑、假模型可能正写到一半时也能读；
    strict=True：文件必须存在、每个非空行都必须能解析（e2e.py 原有语义：出错算断言阶段异常）。"""
    p = Path(path)
    if not strict and not p.exists():
        return []
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            out.append(json.loads(line))
        except ValueError:
            if strict:
                raise
    return out


def mock_requests(path, proto=None):
    """假模型收到的请求（宽松读取）；proto 给定时只留这一种（如 "openai-chat"）。"""
    return [r for r in read_jsonl(path) if proto is None or r.get("proto") == proto]


def remove(*paths):
    for p in paths:
        Path(p).unlink(missing_ok=True)


def edit_config(fn=None, **sections):
    """改 config.json：sections 整段替换（如 remote=remote_cfg()），fn(cfg) 做细粒度修改。返回改后的设置。"""
    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    cfg.update(sections)
    if fn:
        fn(cfg)
    CONFIG.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    return cfg


def remote_cfg(**extra):
    """config.json 的 remote 段：只监听本机、固定端口；extra 追加 cloud、public_base_url 等。"""
    return {"enabled": True, "bind": "127.0.0.1", "port": REMOTE_PORT, **extra}


def remote_token():
    return (CFG_DIR / "remote-token.txt").read_text(encoding="utf-8").strip()


class ChatFile:
    """-Daiadmin.devChatFile：服务端每秒读一次新追加的整行，"chat 玩家 内容" 按真实聊天处理（服务器运行中途说话）。"""

    def __init__(self, name):
        self.path = OUT / name
        self.path.write_text("", encoding="utf-8")
        self.prop = {"aiadmin.devChatFile": str(self.path)}

    def say(self, line):
        with open(self.path, "a", encoding="utf-8", newline="\n") as f:
            f.write(line + "\n")


@contextlib.contextmanager
def mock_server(req_file, fresh=True):
    """假模型服务（mock_llm），请求追加写进 req_file；fresh 时先删掉旧记录。退出 with 时关闭监听口。"""
    if fresh:
        remove(req_file)
    m = mock_llm.Mock(req_file).start(PORT)
    try:
        yield m
    finally:
        m.stop()


# —— 开发服务端准备 ——
def prepare():
    RUN.mkdir(parents=True, exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (RUN / "server.properties").write_text(
        "online-mode=false\nserver-port=25591\nlevel-type=minecraft\\:flat\nspawn-protection=0\nmotd=AIAdmin E2E\n"
        "rcon.password=should-be-hidden\nenable-rcon=false\n"
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:grass_block","height":1}]}\n',
        encoding="utf-8")
    for p in [CFG_DIR, RUN / "logs" / "aiadmin", STATE, OUT]:
        shutil.rmtree(p, ignore_errors=True)
    OUT.mkdir(parents=True, exist_ok=True)
    base = f"http://127.0.0.1:{PORT}"
    cfg = {
        "enabled": True, "bot_name": "AI管家", "active_provider": "e2e_openai",
        "providers": {
            "e2e_openai": {"type": "openai", "base_url": base + "/v1", "api_key": mock_llm.KEY, "model": "mock-gpt",
                           "price_input_per_million": 2.0, "price_output_per_million": 3.0, "currency": "CNY"},
            "e2e_claude": {"type": "anthropic", "base_url": base, "api_key": mock_llm.KEY, "model": "claude-opus-5-5", "max_tokens": 4000},
            "e2e_agy": {"type": "antigravity", "base_url": base, "api_key": mock_llm.KEY, "model": "antigravity-preview-09-2026"},
            "e2e_hang": {"type": "openai", "base_url": base + "/hang/v1", "api_key": mock_llm.KEY, "model": "hang", "timeout_seconds": 10},
            "e2e_ds": {"type": "openai", "base_url": base, "api_key": mock_llm.KEY, "model": "deepseek-chat", "balance_api": "deepseek",
                       "currency": "CNY"},
        },
        "patrol": {"enabled": False},
        "events": {"player_join": False, "player_death": False, "tps_low": False, "server_error": False},
        "chat": {"debounce_seconds": 1},
        "remote": remote_cfg(),
    }
    rules = {
        "permissions": {"chat_safety_off": {"ops": True}},
        "quota": {"provider_balance": {"e2e_ds": {"warn_below": 5, "stop_below": 1}}, "balance_check_minutes": 0},
        "schedule": {"startup_delay_seconds": 0},
    }
    CFG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    (CFG_DIR / "rules.json").write_text(json.dumps(rules, ensure_ascii=False, indent=2), encoding="utf-8")


# —— 开发服务端进程管理 ——
# gradlew.bat 会再拉起 Gradle 客户端和服务端 JVM：只结束最外层进程的话 JVM 成了孤儿，还占着 25580 端口，
# 下一套测试会把孤儿的 401 当成"新服务端已就绪"。所以：新进程组启动，超时/异常时按 PID 结束整棵进程树（绝不按进程名杀 java，
# 别的项目也在用 java）。
POPEN_GROUP = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}


def kill_tree(proc):
    if proc.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        else:
            import signal
            os.killpg(proc.pid, signal.SIGKILL)
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        pass


def wait_or_kill(proc, timeout):
    # 等服务端自己关服（devHaltAfterSeconds / devStopAfterRuns / 控制台 stop）；超时就结束整棵进程树，返回 -9
    try:
        return proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"服务端 {timeout} 秒没有退出，结束进程树 PID {proc.pid}")
        kill_tree(proc)
        return -9


def wait_listening(url, proc, timeout=300):
    # 等远程接口开始监听（有 HTTP 回应即算）；服务端进程中途退出（编译失败等）立即返回 False，不再干等 5 分钟
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        if proc.poll() is not None:
            print(f"服务端进程已退出（退出码 {proc.returncode}），没等到监听")
            return False
        try:
            urllib.request.urlopen(urllib.request.Request(url, method="POST", data=b"{}"), timeout=2)
            return True
        except urllib.error.HTTPError:
            return True
        except OSError:
            time.sleep(1)
    return False


class DevServer:
    """dev_server() 给出的句柄。退出 with 之后 rc 是 runServer 的退出码（-9 = 超时后结束了进程树）。"""

    def __init__(self, proc, log_path):
        self.proc = proc
        self.log_path = log_path
        self.url = MCP_URL
        self.rc = None

    def log(self):
        return read_text(self.log_path)

    def wait_log(self, cond, timeout=60):
        """等服务端日志满足条件：cond 是字符串（出现即可）、字符串列表（全部出现）或函数（参数是日志全文）。
        服务端进程提前退出就不再等。返回是否满足；不满足时打印原因（之后对应的 check 会失败）。"""
        needles = [cond] if isinstance(cond, str) else cond
        test = cond if callable(cond) else (lambda text: all(s in text for s in needles))
        wait_until(lambda: test(self.log()) or self.proc.poll() is not None, timeout)
        ok = test(self.log())
        if not ok:
            why = "服务端已退出" if self.proc.poll() is not None else f"{timeout} 秒内"
            print(f"  等待失败（{why}）：服务端日志里没等到 {'预期内容' if callable(cond) else needles}")
        return ok

    def wait_ready(self, timeout=300):
        """等远程接口开始监听（模组在 ServerStarting 时启动它，此时开服日志 Done 已打出），返回远程令牌。
        没等到就抛异常，由调用方的 try 记进 out["error"]（"全程无异常"那项会失败并带上原因）。"""
        if not wait_listening(self.url, self.proc, timeout):
            raise RuntimeError("远程接口没有开始监听")
        return remote_token()

    def stop(self):
        """经控制台发 stop（dev_server 的 init 脚本让 runServer 读 gradlew 的标准输入），正常关服，不必干等 devHaltAfterSeconds。
        写不进去（进程已退出、管道已关）就算了：devHaltAfterSeconds 和 wait_or_kill 兜底。"""
        if self.proc.poll() is not None or self.proc.stdin is None:
            return
        try:
            self.proc.stdin.write(b"stop\n")
            self.proc.stdin.flush()
        except OSError:
            pass


@contextlib.contextmanager
def dev_server(logname, props=None, commands=None, stop_when_done=False, halt_timeout=600):
    """gradlew runServer 启动开发服务端（新进程组），Gradle 输出写进 run/e2e/<logname>。
    props：-D 系统属性（devHaltAfterSeconds 等）；commands：开服后以控制台身份执行的指令（写进 UTF-8 文件再传路径：
    Windows 上经命令行传的中文会变成问号）。
    with 体正常结束：stop_when_done 时先经控制台 stop，然后等服务端自己关服，halt_timeout 秒不退就结束进程树；
    with 体抛出没接住的异常（含 Ctrl+C）：不再等，直接按 PID 结束整棵进程树。退出码记在 srv.rc。"""
    # MDG 的 runServer（JavaExec）默认给服务端空的标准输入，控制台 stop 发不进去：用临时 init 脚本把 gradlew 的标准输入转过去
    # （只影响这次 Gradle 调用，不改 build.gradle）。
    init = OUT / "e2e-stdin.init.gradle"
    init.write_text("allprojects {\n    tasks.withType(JavaExec).matching { it.name == 'runServer' }.configureEach { standardInput = System.in }\n}\n",
                    encoding="utf-8")
    args = [GRADLEW, ":neoforge-1.21.1:runServer", "--console=plain", "--init-script", str(init)]
    if commands:
        cmd_file = OUT / (logname + ".commands.txt")
        cmd_file.write_text("\n".join(commands), encoding="utf-8")
        args.append(f"-Daiadmin.devConsoleCommandsFile={cmd_file}")
    args += [f"-D{k}={v}" for k, v in (props or {}).items()]
    log = open(OUT / logname, "w", encoding="utf-8", errors="replace")
    try:
        proc = subprocess.Popen(args, cwd=MOD, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, **POPEN_GROUP)
    except BaseException:
        log.close()
        raise
    srv = DevServer(proc, OUT / logname)
    try:
        try:
            yield srv
        except BaseException:
            kill_tree(proc)
            srv.rc = proc.returncode
            raise
        if stop_when_done:
            srv.stop()
        try:
            srv.rc = wait_or_kill(proc, halt_timeout)
        except BaseException:  # 等关服时 Ctrl+C
            kill_tree(proc)
            raise
    finally:
        with contextlib.suppress(OSError):
            proc.stdin.close()
        log.close()


def run_server(commands, props=None, logname="server-gradle.log", timeout=900, until=None):
    """开服并以控制台身份执行 commands，等服务端自己关服（devStopAfterRuns / devHaltAfterSeconds），返回 runServer 退出码。
    until(日志全文) 为真时提前经控制台 stop——结果一出来就能关服的场景用，devHaltAfterSeconds 仍是兜底；
    要"等一段时间确认某事没发生"的场景不要传 until。"""
    t0 = time.monotonic()
    with dev_server(logname, props, commands, halt_timeout=timeout) as srv:
        if until and srv.wait_log(until, timeout):
            srv.stop()
    print(f"runServer 退出码 {srv.rc}，用时 {time.monotonic() - t0:.0f} 秒")
    return srv.rc


# —— HTTP 与远程接口（MCP JSON-RPC） ——
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def http(method, url, body=None, headers=None, timeout=30):
    """返回 (状态码, 响应头 dict(小写), 正文)。不跟随跳转；4xx/5xx 不抛异常，照样返回。"""
    data = body.encode("utf-8") if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read().decode("utf-8", "replace")


def rpc(url, token, method, params=None, timeout=60, modern=False, name=None):
    """一次 JSON-RPC 调用，返回解析后的回复；HTTP 错误照常抛异常。
    modern=True 按新版协议（MODERN）在 _meta 和请求头里带版本、方法名（name = Mcp-Name 头，可选）。"""
    params = dict(params or {})
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if modern:
        params["_meta"] = {"io.modelcontextprotocol/protocolVersion": MODERN,
                           "io.modelcontextprotocol/clientInfo": {"name": "e2e-events", "version": "1"},
                           "io.modelcontextprotocol/clientCapabilities": {}}
        headers["MCP-Protocol-Version"] = MODERN
        headers["Mcp-Method"] = method
        if name:
            headers["Mcp-Name"] = name
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def call(url, token, tool, args=None, timeout=60, parse=False):
    """tools/call，返回 (isError, 文本)。parse=True 时把文本按 JSON 解析，解析不了给 {"_text": 原文}（调用处可以照常 .get）。"""
    res = rpc(url, token, "tools/call", {"name": tool, "arguments": args or {}}, timeout)["result"]
    text = res["content"][0]["text"]
    if not parse:
        return res["isError"], text
    try:
        return res["isError"], json.loads(text)
    except ValueError:
        return res["isError"], {"_text": text}


def tool_names(url, token):
    return [t["name"] for t in rpc(url, token, "tools/list")["result"]["tools"]]
