"""一条命令跑完交付前的检查（AGENTS.md 的 Gate）：
  1. Gradle：core 单测 + NeoForge 构建（含 jar）；
  2. 文档校验：Docs/validate_docs.py --strict（含 version.json 与维护日志最新版本一致）；
  3. 版本一致：version.json、构建出的 jar 文件名、jar 清单里的 Implementation-Version 一样；
  4. 可选 --e2e：依次跑全部端到端（共用开发服务端，约 6 分钟；不能和别的 e2e 同时跑）。
用法：python scripts/check_all.py [--e2e]
"""
import json
import os
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOD = ROOT / "mod"
E2E = ["e2e", "e2e_tracker", "e2e_tpa", "e2e_oauth", "e2e_persona", "e2e_gamedata", "e2e_cloud", "e2e_events", "e2e_social"]

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def step(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'} {name}{('：' + detail) if detail else ''}")
    return ok


def main():
    results = []
    version = json.loads((ROOT / "version.json").read_text(encoding="utf-8"))["version"]
    print(f"版本 {version}")
    gradlew = str(MOD / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    r = subprocess.run([gradlew, ":core:test", ":neoforge-1.21.1:build", "--console=plain", "-q"], cwd=MOD)
    results.append(step("Gradle 单测 + 构建", r.returncode == 0, f"退出码 {r.returncode}"))
    r = subprocess.run([sys.executable, "-I", str(ROOT / "Docs" / "validate_docs.py"), "--root", str(ROOT), "--strict"])
    results.append(step("文档校验", r.returncode == 0))
    jar = MOD / "neoforge-1.21.1" / "build" / "libs" / f"aiadmin-neoforge-1.21.1-{version}.jar"
    if jar.exists():
        with zipfile.ZipFile(jar) as z:
            manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
            toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8", "replace")
        results.append(step("jar 清单版本", f"Implementation-Version: {version}" in manifest))
        results.append(step("mods.toml 版本", f'version="{version}"' in toml.replace(" ", "")))
    else:
        results.append(step("jar 存在", False, str(jar)))
    if "--e2e" in sys.argv:
        for s in E2E:
            r = subprocess.run([sys.executable, "-X", "utf8", str(ROOT / "scripts" / f"{s}.py")], cwd=ROOT)
            results.append(step(s, r.returncode == 0, f"退出码 {r.returncode}"))
    ok = all(results)
    print(f"\n结果：{'全部通过' if ok else '有失败项'}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
