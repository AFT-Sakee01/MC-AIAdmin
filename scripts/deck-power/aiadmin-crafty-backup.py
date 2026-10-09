#!/usr/bin/env python3
"""aiadmin-power 的备份半边：用 Crafty 自己的 API 触发"AI 手动备份"那份备份配置（root 运行，只被 aiadmin-power 调用）。
   aiadmin-crafty-backup.py start <server_id> <backup_id>
为什么走 Crafty API：Crafty 的不停服备份会先 save-off / save-all 再打 zip、最后 save-on，排除目录和轮换都由它管；
自己打 zip 容易和 Crafty 的轮换混在一起。手动备份是单独一份配置（只留 2 份、单独的子目录），不会挤掉定时备份的 3 份。
登录凭据由 /opt/steamdeck-deploy/crafty-integrate.py 从 root 600 文件读，这里不打印。"""
import importlib.util
import re
import ssl
import sys

if len(sys.argv) != 4 or sys.argv[1] != "start":
    sys.exit("用法：aiadmin-crafty-backup.py start <server_id> <backup_id>")
sid, bid = sys.argv[2], sys.argv[3]
uuid = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
if not uuid.match(sid) or not uuid.match(bid):
    sys.exit("ID 格式不对")
spec = importlib.util.spec_from_file_location("crafty_api", "/opt/steamdeck-deploy/crafty-integrate.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)
api.CTX = ssl.create_default_context(cafile=str(api.APP / "app/config/web/certs/commander.cert.pem"))
api.CTX.check_hostname = False  # Crafty 自签证书，只连 127.0.0.1
api.TOKEN = api.api("POST", "auth/login", api.CREDS)["data"]["token"]
configs = api.api("GET", f"servers/{sid}/backups")
configs = configs.get("data", configs)
if bid not in configs:
    sys.exit("这个服务器没有这份备份配置")
api.api("POST", f"servers/{sid}/action/backup_server/{bid}", {})
print("ok")
