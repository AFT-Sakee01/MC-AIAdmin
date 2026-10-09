"""游戏资料与目标表的端到端测试：真实 NeoForge 开发服务端（原版数据）+ 假玩家 E2EGoal，经远程管理接口调用工具检查。

覆盖：item_search、recipe_for、recipe_uses、tag_members、item_sources、loot_table、craft_plan（整条合成链路）、
goal_status、set_player_goal；/goal 各子指令（假玩家执行，输出以 [E2E-GOAL] 写进日志）；目标判定与定时提示的频率限制；
默认 goals.json 自动生成；游戏资料快照的建立日志。
用法（仓库根目录）：python scripts/e2e_gamedata.py
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from e2e_common import CFG_DIR, STATE, call, check, dev_server, finish, prepare, remove, server_log, tool_names  # noqa: E402


def main():
    prepare()
    goals = CFG_DIR / "goals.json"
    remove(goals, STATE / "goals-progress.json")  # 验证 goals.json 自动生成；目标进度从零开始

    out = {}
    # 工具都调完就经控制台 stop；devHaltAfterSeconds=60 只是兜底
    with dev_server("server-gamedata.log", {"aiadmin.devGoalTest": "true", "aiadmin.devHaltAfterSeconds": 60}, stop_when_done=True) as srv:
        url = srv.url
        try:
            token = srv.wait_ready()
            # 假玩家 E2EGoal 的自测（ServerStarted 时同步执行）开头会清掉它的个人目标：等它跑完再用远程接口加目标，免得被清掉
            srv.wait_log("[E2E-GOAL] done", timeout=60)

            def q(tool, args):
                return call(url, token, tool, args, parse=True)
            out["tools"] = tool_names(url, token)
            out["search"] = q("item_search", {"query": "iron ingot"})
            out["search_zh"] = q("item_search", {"query": "铁锭"})
            out["recipe_for"] = q("recipe_for", {"item": "iron_pickaxe"})
            out["recipe_ingot"] = q("recipe_for", {"item": "minecraft:iron_ingot", "limit": 30})
            out["recipe_bad"] = q("recipe_for", {"item": "iron"})
            out["uses"] = q("recipe_uses", {"item": "minecraft:stick", "limit": 5})
            out["tag"] = q("tag_members", {"tag": "#minecraft:planks"})
            out["item_tags"] = q("tag_members", {"item": "minecraft:oak_log"})
            out["sources"] = q("item_sources", {"item": "minecraft:raw_iron"})
            out["sources_ingot"] = q("item_sources", {"item": "minecraft:iron_ingot", "limit": 40})
            out["loot_zombie"] = q("loot_table", {"table": "zombie"})
            out["loot_chest"] = q("loot_table", {"table": "chests/simple_dungeon"})
            out["plan"] = q("craft_plan", {"item": "minecraft:iron_pickaxe"})
            out["plan_beacon"] = q("craft_plan", {"item": "minecraft:beacon"})
            out["plan_offline"] = q("craft_plan", {"item": "minecraft:torch", "player": "Nobody"})
            out["plan_rocket"] = q("craft_plan", {"item": "minecraft:firework_rocket"})
            out["plan_leather"] = q("craft_plan", {"item": "minecraft:leather", "count": 4})
            out["loot_fishing"] = q("loot_table", {"table": "gameplay/fishing"})
            out["sources_cod"] = q("item_sources", {"item": "minecraft:cod"})
            out["recipe_arrow"] = q("recipe_for", {"item": "minecraft:tipped_arrow", "potion": "poison"})
            out["recipe_swift"] = q("recipe_for", {"item": "minecraft:potion", "potion": "swiftness"})
            out["set_goal"] = q("set_player_goal", {"player": "E2EGoal", "action": "add", "item": "minecraft:diamond_pickaxe", "note": "e2e"})
            out["goal_status"] = q("goal_status", {"player": "E2EGoal"})
            out["set_remove"] = q("set_player_goal", {"player": "E2EGoal", "action": "remove", "item": "minecraft:diamond_pickaxe"})
        except Exception as ex:
            out["error"] = repr(ex)
    rc = srv.rc

    print("游戏资料与目标表：")
    check("error" not in out, f"全程无异常 {out.get('error', '')}")
    want = {"item_search", "recipe_for", "recipe_uses", "tag_members", "item_sources", "loot_table", "craft_plan", "goal_status", "set_player_goal"}
    check(want <= set(out.get("tools", [])), f"工具列表里有全部新工具：缺 {want - set(out.get('tools', []))}")

    _, s = out.get("search", (True, {}))
    check(any(i.get("id") == "minecraft:iron_ingot" and i.get("recipes", 0) >= 3 for i in s.get("items", [])), f"item_search 找到铁锭且有配方数：{s}")
    _, sz = out.get("search_zh", (True, {}))
    check("note" in sz, "中文搜原版物品给出用英文名的提示")

    _, r = out.get("recipe_for", (True, {}))
    rec = (r.get("recipes") or [{}])[0]
    check(rec.get("station") == "工作台" and rec.get("shape") == ["XXX", " # ", " # "] and "#c:rods/wooden×2" in rec.get("in", [])
          and rec.get("key", {}).get("#") == "#c:rods/wooden", f"recipe_for 铁镐：工作台、摆法、木棍（c:rods/wooden 标签）×2：{rec}")
    check(any("Iron Ingot" in v for v in r.get("names", {}).values()), "recipe_for 附带物品名")
    _, ri = out.get("recipe_ingot", (True, {}))
    stations = {x.get("station") for x in ri.get("recipes", [])}
    check({"熔炉", "高炉", "工作台"} <= stations and ri.get("total", 0) >= 6, f"铁锭配方含熔炉/高炉/工作台：{stations} 共 {ri.get('total')}")
    _, rb = out.get("recipe_bad", (True, {}))
    check("error" in rb and len(rb.get("items", [])) > 1, "模糊物品名返回候选而不是乱猜")
    _, u = out.get("uses", (True, {}))
    check(u.get("total", 0) > 20 and len(u.get("recipes", [])) == 5 and "truncated" in u, f"recipe_uses 木棍：{u.get('total')} 条，截断到 5")

    _, tg = out.get("tag", (True, {}))
    check(tg.get("total", 0) >= 10 and any("minecraft:oak_planks" in x for x in tg.get("items", [])), f"tag_members #planks：{tg.get('total')}")
    _, it = out.get("item_tags", (True, {}))
    check("#minecraft:logs" in it.get("tags", []), f"物品所属标签：{it.get('tags')}")

    _, so = out.get("sources", (True, {}))
    blocks = " ".join(so.get("drops", {}).get("方块掉落", []))
    check("blocks/iron_ore" in blocks and "blocks/deepslate_iron_ore" in blocks and "时运加成" in blocks, f"粗铁来源：铁矿/深层铁矿，时运加成：{blocks[:200]}")
    _, si = out.get("sources_ingot", (True, {}))
    ent = " ".join(si.get("drops", {}).get("生物掉落", []))
    chest = si.get("drops", {}).get("宝箱/容器", [])
    check("entities/zombie" in ent and "需要玩家击杀" in ent, f"铁锭：僵尸掉落需要玩家击杀：{ent[:200]}")
    check(len(chest) >= 3, f"铁锭：多个宝箱能开出：{len(chest)}")

    _, lz = out.get("loot_zombie", (True, {}))
    check(lz.get("table") == "minecraft:entities/zombie" and any(x.startswith("minecraft:rotten_flesh ×0-2") for x in lz.get("drops", [])),
          f"loot_table zombie：腐肉 0-2：{lz.get('drops')}")
    _, lc = out.get("loot_chest", (True, {}))
    check(lc.get("kind") == "宝箱/容器" and any("minecraft:saddle" in x and "抽 1-3 次" in x for x in lc.get("drops", [])),
          f"地牢箱子：鞍、抽 1-3 次：{(lc.get('drops') or [''])[0]}")

    _, p = out.get("plan", (True, {}))
    recipes = [x.get("recipe") for x in p.get("steps", [])]
    miss = {m.get("item"): m.get("count") for m in p.get("missing", [])}
    check(recipes and recipes[-1] == "minecraft:iron_pickaxe", f"craft_plan 最后一步是铁镐：{recipes}")
    check(any("iron_ingot_from_smelting_raw_iron" in x or "iron_ingot_from_blasting_raw_iron" in x for x in recipes)
          and not any("iron_block" in x or "nugget" in x for x in recipes), f"铁锭走烧粗铁，不走铁块/铁粒：{recipes}")
    check(miss.get("minecraft:raw_iron") == 3 and "minecraft:raw_iron_block" not in miss, f"还缺粗铁 3（直接挖，不做粗铁块）：{miss}")
    raw = next((m for m in p.get("missing", []) if m.get("item") == "minecraft:raw_iron"), {})
    check(any("挖" in x for x in raw.get("get_from", [])), f"缺的原料给出去哪弄：{raw.get('get_from')}")
    _, pb = out.get("plan_beacon", (True, {}))
    pmiss = {m.get("item") for m in pb.get("missing", [])}
    check({"minecraft:nether_star", "minecraft:obsidian", "minecraft:sand"} <= pmiss
          and [x.get("recipe") for x in pb.get("steps", [])][-1] == "minecraft:beacon", f"信标：缺下界之星/黑曜石/沙子，最后一步是信标：{sorted(pmiss)}")
    _, pr = out.get("plan_rocket", (True, {}))
    rsteps = [x.get("recipe") for x in pr.get("steps", [])]
    check(rsteps and rsteps[-1] == "minecraft:firework_rocket_simple" and "minecraft:firework_rocket" not in rsteps
          and any(m.get("item") == "minecraft:gunpowder" for m in pr.get("missing", [])),
          f"烟花火箭走纸+火药的普通配方，不选读不到原料的特殊配方（不再显示\"已备齐\"）：{rsteps}")
    _, pl = out.get("plan_leather", (True, {}))
    check(not pl.get("steps") and any(m.get("item") == "minecraft:leather" for m in pl.get("missing", [])),
          f"皮革直接打牛，不拿兔子皮拼：{[x.get('recipe') for x in pl.get('steps', [])]}")
    _, po = out.get("plan_offline", (True, {}))
    check("不在线" in po.get("error", ""), "craft_plan 指定不在线玩家给出说明")

    # 钓鱼主表引用鱼/垃圾/宝藏子表：子表物品的概率乘上引用条目的选中概率（鳕鱼 = 鱼 85% × 子表内 60% ≈ 51%）
    _, lf = out.get("loot_fishing", (True, {}))
    fdrops = lf.get("drops", [])
    check(any("minecraft:cod" in x and "经子表" in x for x in fdrops), f"loot_table 钓鱼：鳕鱼经子表展开：{fdrops[:4]}")
    _, sc = out.get("sources_cod", (True, {}))
    fish = [x for v in sc.get("drops", {}).values() for x in v if "gameplay/fishing（经子表" in x]
    check(any("51" in x for x in fish), f"item_sources 鳕鱼：钓鱼约 51%（乘过子表权重）：{fish[:2]}")
    # 药水内容：药箭按药水区分（原版药箭是代码实现的特殊配方，按药水展开）；药水的酿造台配方
    _, ra = out.get("recipe_arrow", (True, {}))
    check(ra.get("recipes") and "poison" in str(ra.get("recipes")), f"recipe_for 剧毒药箭：{str(ra)[:240]}")
    _, rs = out.get("recipe_swift", (True, {}))
    check(rs.get("recipes") and "酿造" in str(rs.get("recipes")) and "swiftness" in str(rs.get("recipes")), f"recipe_for 迅捷药水：酿造台配方：{str(rs)[:240]}")
    _, sg = out.get("set_goal", (True, {}))
    check("added" in sg, f"set_player_goal 添加：{sg}")
    _, gs = out.get("goal_status", (True, {}))
    check(any("diamond_pickaxe" in x.get("item", "") for x in gs.get("personal_goals", [])) and len(gs.get("stages", [])) >= 12,
          f"goal_status：有个人目标和阶段表：{gs.get('current_stage')}")
    check(not any(s.get("id") == "colony" for s in gs.get("stages", [])), "开发服没装 minecolonies，殖民地支线被跳过")
    _, sr = out.get("set_remove", (True, {}))
    check("removed" in sr, f"set_player_goal 删除：{sr}")

    glog = server_log("server-gamedata.log")
    lines = [ln.split("[E2E-GOAL] ", 1)[1] for ln in glog.splitlines() if "[E2E-GOAL] " in ln]
    joined = "\n".join(lines)
    check("游戏资料已建立" in glog, "开服时建立了游戏资料快照")
    brew = re.search(r"酿造台 (\d+) 条", glog)
    check(brew is not None and int(brew.group(1)) > 0 and "酿造配方枚举失败" not in glog, f"开服时枚举出酿造台配方：{brew.group(0) if brew else '没有这行日志'}")
    check(goals.exists() and "已生成默认目标表" in glog, "自动生成 goals.json")
    check("已记下目标：Torch ×8" in joined, "/goal add 记下目标")
    check("Torch ×8  0/8（照明用）" in joined, f"/goal 列出个人目标和进度")
    check("当前阶段：木器时代" in joined, "/goal 显示当前阶段")
    check("目标：Iron Pickaxe ×1" in joined and "还缺：Raw Iron×2（挖 Iron Ore）" in joined, "/goal plan 铁镐：扣掉背包里的 1 个粗铁后还缺 2 个，去挖铁矿")
    check("工作台：Oak Log等×1 → Oak Planks ×4" in joined and "Iron Ingot×3 + Stick×2 → Iron Pickaxe" in joined, "/goal plan 从原木开始，标签原料写物品名")
    check("还缺：Coal×2（挖 Coal Ore" in joined, "/goal next 火把：煤直接挖煤矿，不去烧要精准采集的煤矿石")
    check("【个人目标】Torch ×8" in joined, "/goal next 优先个人目标")
    check("➜ 木器时代" in joined, "/goal stages 标出当前阶段")
    check("minecraft:iron_ingot  Iron Ingot" in joined, "/goal find 找到物品 ID")
    check("已删除：minecraft:torch ×8" in joined and "【阶段目标】木器时代" in joined, "/goal remove 后 next 回到阶段目标")
    check("hint: 【阶段目标】木器时代" in joined and "hint-again-empty: true" in joined, "定时提示：发一次，马上再查不重复")
    check("Exception" not in "\n".join(ln for ln in glog.splitlines() if "aiadmin" in ln.lower() and "ERROR" in ln), "没有 aiadmin 的错误日志")
    check(rc == 0, "服务端正常关服")
    finish()


if __name__ == "__main__":
    main()
