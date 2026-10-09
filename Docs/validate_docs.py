#!/usr/bin/env python3
"""文档治理校验 Gate（sakees-coding-skills 唯一校验器）。

检查项:
1. 四个 JSONL(FEATURE_INDEX / INTERFACE_INDEX / Technical INDEX / CHANGELOG)逐行可解析
2. 键唯一性:feature_id / id 全文件唯一
3. FEATURE_INDEX 的 primary_files(status=removed 除外)与 Technical INDEX 的 doc_path / spec_path 必须存在
4. 两个索引的 status / added_version 非空
5. FEATURE_INDEX 的 entrypoints 符号在 primary_files 中的文本命中(未命中只警告)
6. CHANGELOG 的 change_type 是否在封闭词表内(默认警告;--strict 时算失败,历史越表条目按铁律不回改)

用法:
    python validate_docs.py --root <项目根>
    python validate_docs.py --root <项目根> --strict

退出码 0 = 全绿;1 = 有失败项。缺失的文件 SKIP 并提示(不代表通过)。仅用标准库,Python 3.7+。
"""

import argparse
import collections
import functools
import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

FILES = {
    "Docs/Indexes/FEATURE_INDEX.jsonl": "feature_id",
    "Docs/Interfaces/INTERFACE_INDEX.jsonl": "id",
    "Docs/Technical/INDEX.jsonl": "id",
    "Docs/Maintenance/CHANGELOG.jsonl": "id",
}

CHANGE_TYPES = {
    "feature", "fix", "behavior_change", "ui_change", "perf", "refactor",
    "documentation", "spec", "release", "deployment", "revert",
    "confirmed_issue", "correction",
}

SEMANTIC_INDEXES = (
    "Docs/Indexes/FEATURE_INDEX.jsonl",
    "Docs/Interfaces/INTERFACE_INDEX.jsonl",
)

# 必须存在的文件（缺了算失败）
REQUIRED = SEMANTIC_INDEXES + ("Docs/Maintenance/CHANGELOG.jsonl",)


def nonempty_text(value):
    return isinstance(value, str) and bool(value.strip())


def entrypoint_symbol(value):
    """Return a source identifier only for entrypoints that look like C# symbols."""
    if not isinstance(value, str):
        return ""
    candidate = value.strip()
    if not candidate or "://" in candidate or "--" in candidate:
        return ""
    candidate = candidate.split("(", 1)[0].strip()
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*", candidate):
        return ""
    return candidate.rsplit(".", 1)[-1]


@functools.lru_cache(maxsize=None)
def read_source(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def source_contains_symbol(path, symbol):
    text = read_source(path)
    return re.search(rf"(?<![A-Za-z0-9_]){re.escape(symbol)}(?![A-Za-z0-9_])", text) is not None


def iter_jsonl(path):
    with open(path, encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            if line.strip():
                yield lineno, line


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="项目根目录")
    parser.add_argument("--strict", action="store_true", help="change_type 越表也算失败")
    args = parser.parse_args()
    root = os.path.abspath(args.root)
    fail = False
    warn_count = 0
    parsed = {}

    for rel, key in FILES.items():
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            # 维护日志和两份索引是必备的：缺了就是失败（以前只打 SKIP、退出码仍是 0）；技术说明索引没有执行规格时可以不存在
            if rel in REQUIRED:
                print(f"FAIL {rel} 不存在")
                fail = True
            else:
                print(f"SKIP {rel} (不存在)")
            continue
        rows = []
        seen = collections.Counter()
        for lineno, line in iter_jsonl(path):
            try:
                obj = json.loads(line)
            except Exception as exc:  # noqa: BLE001
                print(f"FAIL {rel}:{lineno} JSON 解析失败: {exc}")
                fail = True
                continue
            rows.append(obj)
            value = obj.get(key, "")
            if value:
                seen[value] += 1
            else:
                print(f"FAIL {rel}:{lineno} 缺少 {key}")
                fail = True
            if obj.get("schema_version") != 1:
                print(f"FAIL {rel}:{lineno} schema_version 应为 1")
                fail = True
        duplicate = [item for item, count in seen.items() if count > 1]
        if duplicate:
            print(f"FAIL {rel} 重复 {key}: {duplicate}")
            fail = True
        parsed[rel] = rows

    for obj in parsed.get("Docs/Indexes/FEATURE_INDEX.jsonl", []):
        if obj.get("status") == "removed":
            continue
        for file_path in obj.get("primary_files", []) or []:
            if not os.path.exists(os.path.join(root, file_path)):
                print(f"FAIL FEATURE_INDEX {obj.get('feature_id')} 引用缺失文件: {file_path}")
                fail = True

    for rel in SEMANTIC_INDEXES:
        key = FILES[rel]
        for obj in parsed.get(rel, []):
            item_id = obj.get(key)
            for field in ("status", "added_version"):
                if not nonempty_text(obj.get(field)):
                    print(f"FAIL {rel} {item_id} 必填字段为空: {field}")
                    fail = True

    # FEATURE_INDEX explicitly maps entrypoints to primary_files. Missing symbols are warnings:
    # generated methods and partial-class moves can make a textual lookup conservative, while a
    # warning still exposes stale navigation metadata without blocking an otherwise valid release.
    for obj in parsed.get("Docs/Indexes/FEATURE_INDEX.jsonl", []):
        if obj.get("status") == "removed":
            continue
        source_paths = [
            os.path.join(root, value)
            for value in (obj.get("primary_files", []) or [])
            if isinstance(value, str) and os.path.isfile(os.path.join(root, value))
        ]
        if not source_paths:
            continue
        for entrypoint in obj.get("entrypoints", []) or []:
            symbol = entrypoint_symbol(entrypoint)
            if symbol and not any(source_contains_symbol(path, symbol) for path in source_paths):
                print(
                    f"WARN FEATURE_INDEX {obj.get('feature_id')} entrypoint "
                    f"未在 primary_files 命中: {entrypoint}"
                )
                warn_count += 1

    # INTERFACE_INDEX 的 location 是仓库内路径时必须存在（外部 API、资源目录这类写的不是路径，跳过）
    for obj in parsed.get("Docs/Interfaces/INTERFACE_INDEX.jsonl", []):
        loc = obj.get("location")
        if obj.get("status") != "removed" and isinstance(loc, str) and loc.split("/")[0] in ("mod", "scripts", "Docs") \
                and not os.path.exists(os.path.join(root, loc)):
            print(f"FAIL INTERFACE_INDEX {obj.get('id')} location 不存在: {loc}")
            fail = True

    # 推荐测试里点名的测试类要存在（--tests *XxxTest*）
    test_dir = os.path.join(root, "mod", "core", "src", "test", "java")
    known_tests = set()
    for dp, _, fns in os.walk(test_dir):
        known_tests.update(fn[:-5] for fn in fns if fn.endswith(".java"))
    for obj in parsed.get("Docs/Indexes/FEATURE_INDEX.jsonl", []):
        for cmd in obj.get("recommended_test_commands", []) or []:
            for name in re.findall(r"--tests\s+\*?(?:[\w.]*\.)?(\w+?)\*?(?:\s|$)", str(cmd)):
                if known_tests and not any(name in t for t in known_tests):  # --tests *Cloud* 是通配：有类名包含它就算
                    print(f"WARN FEATURE_INDEX {obj.get('feature_id')} 推荐测试不存在: {name}")
                    warn_count += 1

    # 版本一致：version.json 和维护日志最新的版本必须一样（版本升了却没记、或记了更高的版本，都是失败）
    vpath = os.path.join(root, "version.json")
    log_rows = parsed.get("Docs/Maintenance/CHANGELOG.jsonl", [])
    if os.path.exists(vpath) and log_rows:
        ver = json.load(open(vpath, encoding="utf-8")).get("version", "")

        def vt(s):
            try:
                return tuple(int(x) for x in str(s).split("."))
            except ValueError:
                return ()

        latest = max((str(o.get("version", "")) for o in log_rows), key=vt)
        if vt(latest) != vt(ver):
            print(f"FAIL 版本不一致：version.json 是 {ver}，CHANGELOG 最新是 {latest}")
            fail = True

    for obj in parsed.get("Docs/Technical/INDEX.jsonl", []):
        for key in ("doc_path", "spec_path"):
            path = obj.get(key)
            if path and not os.path.exists(os.path.join(root, path)):
                print(f"FAIL Technical INDEX {obj.get('id')} 引用缺失文档: {path}")
                fail = True

    for obj in parsed.get("Docs/Maintenance/CHANGELOG.jsonl", []):
        change_type = obj.get("change_type")
        if change_type and change_type not in CHANGE_TYPES:
            label = "FAIL" if args.strict else "WARN"
            print(f"{label} CHANGELOG {obj.get('id')} change_type 越表: {change_type}")
            fail = fail or args.strict
            warn_count += 0 if args.strict else 1

    if fail:
        print("RESULT: FAIL")
        return 1
    suffix = f"(含 {warn_count} 条警告)" if warn_count else ""
    print(f"RESULT: PASS {suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
