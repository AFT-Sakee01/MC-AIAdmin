#!/bin/bash
# AI 管理机器人：在 Deck（Ubuntu 24.04）上启用 Tailscale 远程管理接口（MCP）。
#
# 用法（在 Deck 上，mcadmin 用户）：
#   bash deck-remote-setup.sh status            查看 Tailscale、防火墙、模组远程接口的状态（只读）
#   bash deck-remote-setup.sh install           安装 Tailscale 并登录（会打印登录网址，需要服主本人用浏览器确认）
#   bash deck-remote-setup.sh enable            打开模组的 remote.enabled、放行 tailscale0 上的 25580、/aibot reload
#   bash deck-remote-setup.sh disable           关闭 remote.enabled、删掉防火墙规则、/aibot reload
#   bash deck-remote-setup.sh enable-funnel     公网方式（给装不了 Tailscale 的云端会话用）：模组只监听 127.0.0.1，
#                                               Tailscale Funnel 把 https://<本机>.ts.net/ 整站（/mcp 和 OAuth 的几个路径）转发进来，
#                                               并把 remote.public_base_url 设成这个地址（启用 OAuth，给 ChatGPT 连接器等用）；不开防火墙端口
#   bash deck-remote-setup.sh disable-funnel    关掉本脚本开的 443 转发（不动其他端口的 Funnel，例如 10000）和模组远程接口
#   bash deck-remote-setup.sh info [--show-token]  打印云电脑接入方法（默认不显示令牌；开着 Funnel 时打印公网地址和 OAuth 接入方法）
#
# 约束：
#   - 只改 config/aiadmin/config.json 里的 remote 一节，改前备份；其他内容（含 API key）原样保留，脚本不打印任何密钥。
#   - 防火墙只在 tailscale0 网卡上放行 25580（局域网、公网都不开）；模组自身还会校验来源网段和令牌。
#   - 每一步都可以重复执行（已完成的会跳过）。
#   - 服务器目录可用环境变量 AIADMIN_SERVER_DIR 覆盖（默认 TCN2），RCON 脚本用 ~/mc-rcon.py。
set -euo pipefail

SERVER_DIR="${AIADMIN_SERVER_DIR:-/srv/steamdeck-shared/minecraft-shared/servers/829f6448-1a59-4e31-8e7b-ad128fd158d5}"
CONF_DIR="$SERVER_DIR/config/aiadmin"
CONF="$CONF_DIR/config.json"
TOKEN_FILE="$CONF_DIR/remote-token.txt"
RCON="$HOME/mc-rcon.py"
PORT_DEFAULT=25580
SUDO="${AIADMIN_SUDO-sudo}"   # 测试时可设为空

say() { printf '%s\n' "$*"; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

remote_port() {
    $SUDO python3 - "$CONF" "$PORT_DEFAULT" <<'EOF'
import json, sys
try:
    c = json.load(open(sys.argv[1], encoding="utf-8-sig"))
    print(int(c.get("remote", {}).get("port", sys.argv[2])))
except Exception:
    print(sys.argv[2])
EOF
}

rcon() {
    if [ -f "$RCON" ]; then $SUDO python3 "$RCON" "$@" 2>&1 | sed 's/^/  /'; else say "  （没有 $RCON，跳过：请在游戏里或后台执行 $*）"; fi
}

# 把 remote.enabled 设成 $1（true/false），可选 $2 = bind（tailscale / 127.0.0.1），可选 $3 = public_base_url（"-" = 清空）。
# 只动 remote 一节，保留文件属主和权限，改前备份。
set_remote_enabled() {
    local value="$1" bind="${2:-}" base="${3:-}"
    [ -f "$CONF" ] || die "找不到 $CONF（模组还没在这个服务器上运行过？）"
    local bak="$CONF.bak-$(date +%Y%m%d-%H%M%S)-remote"
    $SUDO cp -p "$CONF" "$bak"
    if $SUDO python3 - "$CONF" "$value" "$bind" "$base" <<'EOF'
import json, os, sys, tempfile
path, value, bind, base = sys.argv[1], sys.argv[2] == "true", sys.argv[3], sys.argv[4]
with open(path, encoding="utf-8-sig") as f:
    c = json.load(f)
r = c.setdefault("remote", {})
r["enabled"] = value
if bind:
    r["bind"] = bind
r.setdefault("bind", "tailscale")
r.setdefault("port", 25580)
if base == "-":
    r["public_base_url"] = ""
elif base:
    r["public_base_url"] = base
st = os.stat(path)
fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".config.json.")
try:
    with os.fdopen(fd, "w", encoding="utf-8-sig") as f:
        json.dump(c, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.chmod(tmp, st.st_mode & 0o777)
    if hasattr(os, "chown"):  # 保留 crafty:mcshared 属主（Windows 上没有 chown，测试时跳过）
        os.chown(tmp, st.st_uid, st.st_gid)
    os.replace(tmp, path)
finally:
    if os.path.exists(tmp):
        os.remove(tmp)  # 失败时不留临时文件
print("remote.enabled =", value)
EOF
    then
        say "  备份：$bak"
    else
        die "修改 $CONF 失败（原文件没动，备份在 $bak）"
    fi
}

cmd_status() {
    say "== Tailscale =="
    if command -v tailscale >/dev/null 2>&1; then
        say "  已安装：$(tailscale version 2>/dev/null | head -1)"
        say "  服务：$(systemctl is-active tailscaled 2>/dev/null || true)"
        local ip; ip="$(tailscale ip -4 2>/dev/null | head -1 || true)"
        if [ -n "$ip" ]; then say "  本机地址：$ip"; else say "  还没登录（执行 install）"; fi
    else
        say "  未安装（执行 install）"
    fi
    say "== 防火墙（ufw）=="
    local port; port="$(remote_port)"
    if $SUDO ufw status 2>/dev/null | grep -q "$port/tcp on tailscale0"; then say "  已放行 tailscale0 上的 $port/tcp"; else say "  未放行 $port/tcp（执行 enable）"; fi
    say "== 模组远程接口 =="
    if [ -f "$CONF" ]; then
        $SUDO python3 - "$CONF" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8-sig")).get("remote", {})
print("  config.json: enabled =", r.get("enabled", False), "| bind =", r.get("bind", "tailscale"), "| port =", r.get("port", 25580))
EOF
    else
        say "  找不到 $CONF"
    fi
    rcon "aibot remote"
}

cmd_install() {
    if ! command -v tailscale >/dev/null 2>&1; then
        say "== 安装 Tailscale（官方安装脚本）=="
        curl -fsSL https://tailscale.com/install.sh | sh
    else
        say "Tailscale 已安装，跳过安装。"
    fi
    $SUDO systemctl enable --now tailscaled
    if [ -n "$(tailscale ip -4 2>/dev/null | head -1 || true)" ]; then
        say "已登录：$(tailscale ip -4 | head -1)"
    else
        say "== 登录 =="
        say "下面会打印一个网址，请服主本人用浏览器打开并用自己的 Tailscale 账号确认。"
        $SUDO tailscale up
        say "已登录：$(tailscale ip -4 | head -1)"
    fi
    say "下一步：bash $0 enable"
}

cmd_enable() {
    command -v tailscale >/dev/null 2>&1 || die "还没装 Tailscale，先执行 install"
    local ip; ip="$(tailscale ip -4 2>/dev/null | head -1 || true)"
    [ -n "$ip" ] || die "Tailscale 还没登录，先执行 install"
    local port; port="$(remote_port)"
    say "== 防火墙：只在 tailscale0 上放行 $port/tcp =="
    if $SUDO ufw status 2>/dev/null | grep -q "$port/tcp on tailscale0"; then
        say "  已有规则，跳过"
    else
        $SUDO ufw allow in on tailscale0 to any port "$port" proto tcp comment 'aiadmin remote MCP'
    fi
    say "== 打开模组远程接口 =="
    set_remote_enabled true
    rcon "aibot reload" "aibot remote"
    say "完成。云电脑接入方法：bash $0 info"
}

cmd_disable() {
    local port; port="$(remote_port)"
    say "== 关闭模组远程接口 =="
    set_remote_enabled false
    rcon "aibot reload" "aibot remote"
    say "== 删除防火墙规则 =="
    if $SUDO ufw status 2>/dev/null | grep -q "$port/tcp on tailscale0"; then
        $SUDO ufw delete allow in on tailscale0 to any port "$port" proto tcp
    else
        say "  没有这条规则，跳过"
    fi
}

ts_dns() {
    tailscale status --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true
}

# 本脚本开的 443 转发是否在（旧版只转发 /mcp，新版转发整站 /）。只认指向本机远程接口端口的 proxy，别的 Funnel（如 10000 的 SSH）不算。
# 读 `tailscale serve status --json`（结构稳定）而不是解析 `tailscale funnel status` 的文本（不同版本格式会变）：
#   {"Web": {"<dns>:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:25580"}}}}, "AllowFunnel": {"<dns>:443": true}}
# 输出 443 上某个路径的 proxy 目标（没开 Funnel 或没有这条就输出空）。
funnel_proxy() {
    tailscale serve status --json 2>/dev/null | python3 -c '
import json, sys
path = sys.argv[1]
try:
    j = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
allow = j.get("AllowFunnel") or {}
for hp, web in (j.get("Web") or {}).items():
    if hp.endswith(":443") and allow.get(hp):
        h = (web.get("Handlers") or {}).get(path) or {}
        if h.get("Proxy"):
            print(h["Proxy"])
            break
' "$1" 2>/dev/null || true
}

funnel_on() {
    local port; port="$(remote_port)"
    case "$(funnel_proxy /)$(funnel_proxy /mcp)" in
        *"127.0.0.1:$port"*) return 0 ;;
        *) return 1 ;;
    esac
}

funnel_old_mcp_path() {
    [ -n "$(funnel_proxy /mcp)" ]
}

# 公网方式：模组只监听本机，由 Funnel 把 https://<dns>/ 整站转发到 127.0.0.1:端口（模组自己只认 /mcp 和 OAuth 的几个路径，其余 404）。
# 安全边界变成"令牌 / OAuth + 认证失败封禁"：来源网段检查对 Funnel 无效（转发进来的都是 127.0.0.1），所以不放行任何防火墙端口。
# 只动 443 上的转发：不要用 tailscale funnel reset / serve reset（会把其他端口的 Funnel，例如 10000 的 SSH，一起清掉）。
cmd_enable_funnel() {
    command -v tailscale >/dev/null 2>&1 || die "还没装 Tailscale，先执行 install"
    [ -n "$(tailscale ip -4 2>/dev/null | head -1 || true)" ] || die "Tailscale 还没登录，先执行 install"
    local port; port="$(remote_port)"
    local dns; dns="$(ts_dns)"
    [ -n "$dns" ] || die "拿不到本机的 ts.net 域名（tailscale status --json 里的 Self.DNSName）"
    say "== 打开模组远程接口（只监听 127.0.0.1，对外地址 https://$dns）=="
    set_remote_enabled true 127.0.0.1 "https://$dns"
    rcon "aibot reload" "aibot remote"
    if $SUDO ufw status 2>/dev/null | grep -q "$port/tcp on tailscale0"; then
        say "== 删除 tailscale0 上的 $port 规则（Funnel 不需要）=="
        $SUDO ufw delete allow in on tailscale0 to any port "$port" proto tcp
    fi
    if funnel_old_mcp_path; then
        say "== 去掉旧版只转发 /mcp 的规则 =="
        $SUDO tailscale funnel --https=443 --set-path=/mcp off
    fi
    say "== Tailscale Funnel：https://$dns/ → 127.0.0.1:$port/ =="
    say "  （如果 tailnet 还没开 HTTPS 证书 / Funnel 权限，下面会打印一个开启网址，请服主本人打开确认后再执行一次本命令）"
    $SUDO tailscale funnel --bg --https=443 --set-path=/ "http://127.0.0.1:$port"
    tailscale funnel status 2>/dev/null | sed 's/^/  /'
    say "完成。云端接入方法：bash $0 info"
}

cmd_disable_funnel() {
    say "== 关闭本脚本开的 443 转发（其他端口的 Funnel 不动）=="
    if funnel_old_mcp_path; then $SUDO tailscale funnel --https=443 --set-path=/mcp off; fi
    if funnel_on; then $SUDO tailscale funnel --https=443 --set-path=/ off; else say "  没有开着，跳过"; fi
    say "== 关闭模组远程接口 =="
    set_remote_enabled false "" -
    rcon "aibot reload" "aibot remote"
}

cmd_info() {
    local ip; ip="$(tailscale ip -4 2>/dev/null | head -1 || true)"
    [ -n "$ip" ] || ip="<Deck 的 Tailscale 地址，tailscale ip -4>"
    local port; port="$(remote_port)"
    local token="<令牌：$TOKEN_FILE>"
    if [ "${1:-}" = "--show-token" ]; then
        $SUDO test -f "$TOKEN_FILE" || die "还没有令牌文件（先 enable，模组启动远程接口时生成）"
        token="$($SUDO cat "$TOKEN_FILE" | tr -d '\r\n')"
        say "注意：下面含访问令牌，只在云电脑上用，别贴到聊天或公开的地方。"
    fi
    if funnel_on; then
        local url="https://$(ts_dns)/mcp"
        say "Funnel 已开（公网可达，靠令牌 / OAuth 和认证失败封禁保护）。"
        say "1) 能设请求头的客户端（Claude Code 等）："
        say "  claude mcp add --transport http mc-admin $url --header \"Authorization: Bearer $token\""
        say "2) ChatGPT 连接器 / claude.ai 等（OAuth）：服务器地址填 $url，认证选 OAuth。"
        say "   跳到批准页后输入一次性批准码（10 分钟有效）：$SUDO python3 $RCON \"aibot remote approve\""
        say "   已授权的客户端：aibot remote clients；吊销：aibot remote revoke <client_id> 或 aibot remote revoke-all"
        say "检查连通：curl -s -o /dev/null -w '%{http_code}\\n' -X POST $url   （返回 401 = 通了、只是没带令牌）"
        return
    fi
    say "云电脑（已装 Tailscale 并登录同一个账号）上执行："
    say "  claude mcp add --transport http mc-admin http://$ip:$port/mcp --header \"Authorization: Bearer $token\""
    say "检查连通：curl -s -o /dev/null -w '%{http_code}\\n' -X POST http://$ip:$port/mcp   （返回 401 = 通了、只是没带令牌）"
}

case "${1:-status}" in
    status) cmd_status ;;
    install) cmd_install ;;
    enable) cmd_enable ;;
    disable) cmd_disable ;;
    enable-funnel) cmd_enable_funnel ;;
    disable-funnel) cmd_disable_funnel ;;
    info) shift; cmd_info "$@" ;;
    *) die "用法：$0 status|install|enable|disable|enable-funnel|disable-funnel|info [--show-token]" ;;
esac
