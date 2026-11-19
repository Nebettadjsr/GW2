#!/usr/bin/env python3
"""Regenerates backend/src/main/resources/mystic-forge/catalog.json from captured GW2 Wiki data.

Offline (default, used by maintainers and CI): reads source/raw-recipes.json.gz (SMW rows for
[[Has recipe source::Mystic forge]]), source/page-templates.json.gz (comment-stripped {{recipe}} templates per
parent page) and source/verified-facts.json (promotion means) and writes the bundled catalog.
  python3 backend/tools/mystic-forge/generate_catalog.py            # write catalog.json
  python3 backend/tools/mystic-forge/generate_catalog.py --check    # fail if catalog.json is stale

Explicit live refresh (needs network, never used by tests): --refresh re-captures the two source files from the
ArenaNet-hosted GW2 Wiki with finite timeouts, bounded retries and modest pagination. Any HTTP/JSON failure or a
short/partial listing aborts BEFORE anything is written; captured files and the catalog are replaced atomically.
Source data is untrusted: it is only parsed, never executed or followed.
"""
import argparse, gzip, hashlib, json, os, re, sys, tempfile, time, urllib.parse, urllib.request
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "source")
OUT = os.path.join(HERE, "..", "..", "src", "main", "resources", "mystic-forge", "catalog.json")
API = "https://wiki.guildwars2.com/api.php"
SMW_QUERY = ("[[Has recipe source::Mystic forge]]|?Has output quantity|?Has output game id|?Has ingredient with id"
             "|?Has mystic forge id|?Has canonical name|limit=50")
PAGE = 50
STONE = "philosopher's stone"
LOWER_DUSTS = {"pile of shimmering dust", "pile of radiant dust", "pile of luminous dust", "pile of incandescent dust"}
T6_DUST = "pile of crystalline dust"
SOURCES = {"mystic forge", "mystic"}
RANDOM_WORDS = re.compile(r"random|chance|average|\bor other\b", re.I)


def atomic_write(path, data: bytes):
    d = os.path.dirname(path)
    os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=d)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def fields(template):
    d = {}
    for m in re.finditer(r"^\|\s*([\w ]+?)\s*=\s*(.*)$", template, re.M):
        d.setdefault(m.group(1).lower(), m.group(2).strip())
    return d


def parse_ingredient(text):
    m = re.fullmatch(r"(\d+)\s+(.+)", text or "")
    # "Name|Display" piped links name the page before the pipe; plural/alias wording stays unmatched on purpose.
    return (int(m.group(1)), m.group(2).split("|")[0].strip().lower()) if m else None


def one_int(v):
    return v[0] if isinstance(v, list) and len(v) == 1 and type(v[0]) is int else None


def smw_slots(printouts):
    """{index: (qty, id, lowername)} or a reason string. Exactly the four unique indices 1..4 are required."""
    slots = {}
    for rec in printouts.get("Has ingredient with id", []):
        idx = one_int(rec.get("Has ingredient index", {}).get("item"))
        qty = one_int(rec.get("Has ingredient quantity", {}).get("item"))
        iid = one_int(rec.get("Has ingredient id", {}).get("item"))
        names = rec.get("Has ingredient name", {}).get("item", [])
        name = names[0].get("fulltext") if len(names) == 1 and isinstance(names[0], dict) else None
        if idx is None or qty is None or iid is None or not name:
            return "ingredient slot missing index/quantity/id/name"
        if idx in slots:
            return "duplicate ingredient slot index %d" % idx
        if qty <= 0 or iid <= 0:
            return "nonpositive ingredient quantity or id"
        slots[idx] = (qty, iid, name.strip().lower())
    if sorted(slots) != [1, 2, 3, 4]:
        return "ingredient slot indices are not exactly 1..4"
    return slots


def yield_category(f, seed_name, slots, qty, upper):
    """Verified-mean category of a random Promotion template, or None (no verified mean => unsupported)."""
    ing = [parse_ingredient(f.get("ingredient%d" % i)) for i in range(1, 5)]
    if any(i is None for i in ing):
        return None
    (n1, _), (n2, name2), (n3, name3), (n4, name4) = ing
    if n2 != 1 or name2 != seed_name or name4 != STONE or n3 != 5:
        return None
    if n1 == 250 and name3 == T6_DUST and qty == 10 and upper == 40 and n4 in (5, 10):
        return "commonTierFiveToSix"
    if n1 == 250 and name3 in LOWER_DUSTS and qty == 40 and 190 <= upper <= 200:
        return "commonLowerTiers"
    if n1 == 50 and name3 == T6_DUST and qty == 5 and upper == 12 and n4 == 5:
        return "fineTierFiveToSix"
    if n1 == 50 and name3 in LOWER_DUSTS and qty in (6, 7) and upper == 40:
        return "fineLowerTiers"
    return None


def build(raw, pages, facts, rawsha, tplsha):
    means = facts["promotionMeans"]
    excluded = Counter()
    exreasons = []
    candidates = []
    results = raw["query"]["results"]
    for key in sorted(results):
        pr = results[key]["printouts"]
        page = key.split("#")[0]

        def drop(reason):
            excluded[reason] += 1
            exreasons.append((key, reason))

        forge = one_int(pr.get("Has mystic forge id"))
        out = one_int(pr.get("Has output game id"))
        if forge is None or out is None or not pr.get("Has ingredient with id"):
            drop("source row lacks wiki forge id / output id / ingredient ids (older or custom page)")
            continue
        if forge <= 0 or out <= 0:
            drop("nonpositive forge or output id")
            continue
        qv = pr.get("Has output quantity")
        if not (isinstance(qv, list) and len(qv) == 1 and type(qv[0]) is int and qv[0] > 0):
            drop("output quantity is missing or not a positive integer (display-only/average row)")
            continue
        qty = qv[0]
        slots = smw_slots(pr)
        if isinstance(slots, str):
            drop(slots)
            continue
        info = pages.get(page)
        if info is None:
            drop("no source page templates captured")
            continue
        matches = []
        for tpl in info["templates"]:
            f = fields(tpl)
            if f.get("source", "").lower() not in SOURCES:
                continue
            ing = [parse_ingredient(f.get("ingredient%d" % i)) for i in range(1, 5)]
            if any(i is None for i in ing):
                continue
            if any(ing[i - 1] != (slots[i][0], slots[i][2]) for i in range(1, 5)):
                continue
            tq = f.get("quantity", "1")
            if not re.fullmatch(r"\d+", tq) or int(tq) != qty:
                continue
            if "output item id" in f and f["output item id"] != str(out):
                continue
            matches.append((tpl, f))
        if len(matches) != 1:
            drop("no unique matching source template" if not matches else "ambiguous source templates")
            continue
        tpl, f = matches[0]
        if RANDOM_WORDS.search(f.get("name", "")) or RANDOM_WORDS.search(tpl):
            drop("source template marks the output random/average/alternative")
            continue
        rec = {"forgeId": forge, "page": key, "revision": info["revision"], "outputItemId": out,
               "outputCount": qty, "ingredients": None, "_tpl": tpl}
        agg = {}
        for i in (1, 2, 3, 4):
            agg[slots[i][1]] = agg.get(slots[i][1], 0) + slots[i][0]
        rec["ingredients"] = [[k, v] for k, v in agg.items()]
        if "upper quantity" in f:
            up = f["upper quantity"]
            if not re.fullmatch(r"\d+", up) or int(up) <= qty:
                drop("random output with unparseable upper quantity")
                continue
            seed = key.split("#")[0].lower()
            cat = yield_category(f, seed, slots, qty, int(up))
            if cat is None or cat not in means:
                drop("random output without a verified mean for its category")
                continue
            rec["yield"] = {"category": cat, "mean": means[cat], "min": qty, "max": int(up)}
        elif info.get("randomMentioned"):
            drop("page mentions random outcomes and the template has no verified fixed output")
            continue
        candidates.append(rec)
    byid = defaultdict(list)
    for r in candidates:
        byid[r["forgeId"]].append(r)
    tplrows = defaultdict(list)
    for r in candidates:
        tplrows[(r["page"].split("#")[0], r["_tpl"])].append(r)
    recipes = []
    for r in candidates:
        if len(byid[r["forgeId"]]) > 1:
            excluded["duplicate wiki forge id"] += 1
            exreasons.append((r["page"], "duplicate wiki forge id"))
        elif len(tplrows[(r["page"].split("#")[0], r["_tpl"])]) > 1:
            excluded["one template matches several rows"] += 1
            exreasons.append((r["page"], "one template matches several rows"))
        else:
            del r["_tpl"]
            recipes.append(r)
    recipes.sort(key=lambda r: r["forgeId"])
    ey = sum(1 for r in recipes if "yield" in r)
    cats = Counter(r["yield"]["category"] for r in recipes if "yield" in r)
    cat = {
        "schemaVersion": 1,
        "source": {
            "name": "Guild Wars 2 Wiki, Semantic MediaWiki [[Has recipe source::Mystic forge]] + {{recipe}} templates",
            "url": "https://wiki.guildwars2.com/",
            "retrievedUtc": pages_meta["retrievedUtc"],
            "rawRecipesSha256": rawsha, "pageTemplatesSha256": tplsha,
            "promotionMeansSource": facts["source"], "promotionMeansRevision": facts["revision"],
            "license": "Text/data contributed to the GW2 Wiki: GNU Free Documentation License 1.3 "
                       "(see THIRD_PARTY_NOTICES.md); Guild Wars 2 game assets remain (c) ArenaNet.",
        },
        "coverage": {
            "sourceRows": len(results), "includedRecipes": len(recipes), "fixedOutput": len(recipes) - ey,
            "expectedYield": ey, "expectedYieldByCategory": dict(sorted(cats.items())),
            "excludedRows": len(results) - len(recipes),
            "excludedByReason": dict(sorted(excluded.items())),
            "rowsMissingNewFields": excluded["source row lacks wiki forge id / output id / ingredient ids (older or custom page)"],
            "note": "Coverage is the strictly verified subset only; it is not every possible Mystic Forge recipe.",
        },
        "recipes": recipes,
    }
    return cat, exreasons


def fetch_json(params, tries=3, timeout=30):
    url = API + "?" + urllib.parse.urlencode(params)
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "gw2-forge-catalog-tool/1"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
        except Exception as e:  # network/HTTP/JSON failures are retried, then abort the refresh
            last = e
            time.sleep(2 * (attempt + 1))
    raise SystemExit("refresh aborted, nothing written: %s" % last)


def strip_templates(text):
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    out, i = [], 0
    while True:
        m = re.compile(r"\{\{\s*recipe\b", re.I).search(text, i)
        if not m:
            return out
        depth, j = 0, m.start()
        while j < len(text) - 1:
            if text.startswith("{{", j):
                depth += 1; j += 2
            elif text.startswith("}}", j):
                depth -= 1; j += 2
                if depth == 0:
                    break
            else:
                j += 1
        if depth != 0:
            raise SystemExit("refresh aborted: unbalanced template")
        out.append(text[m.start():j]); i = j


def refresh():
    results, offset = {}, 0
    while True:
        j = fetch_json({"action": "ask", "format": "json", "query": SMW_QUERY + "|offset=%d" % offset})
        part = j.get("query", {}).get("results")
        if not isinstance(part, dict) or (not part and offset == 0):
            raise SystemExit("refresh aborted: empty/invalid listing")
        results.update(part)
        if "query-continue-offset" not in j:
            break
        offset = j["query-continue-offset"]
        time.sleep(1)
    titles = sorted({k.split("#")[0] for k in results})
    pages = {}
    for s in range(0, len(titles), PAGE):
        j = fetch_json({"action": "query", "format": "json", "prop": "revisions", "rvprop": "ids|content",
                        "rvslots": "main", "titles": "|".join(titles[s:s + PAGE]), "redirects": 0})
        for p in j.get("query", {}).get("pages", {}).values():
            revs = p.get("revisions")
            if not revs:
                continue
            text = revs[0]["slots"]["main"]["*"]
            pages[p["title"]] = {"revision": revs[0]["revid"],
                                 "templates": strip_templates(text),
                                 "randomMentioned": bool(re.search(r"random", re.sub(r"<!--.*?-->", "", text, flags=re.S), re.I))}
        time.sleep(1)
    if len(pages) < len(titles) * 0.9:
        raise SystemExit("refresh aborted: only %d of %d pages captured" % (len(pages), len(titles)))
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    atomic_write(os.path.join(SRC, "raw-recipes.json.gz"), gzip.compress(json.dumps({"query": {"results": results}}).encode(), mtime=0))
    atomic_write(os.path.join(SRC, "page-templates.json.gz"), gzip.compress(json.dumps(
        {"source": "https://wiki.guildwars2.com/", "license": "GW2 Wiki contributors, GFDL 1.3; ArenaNet game data retains its rights",
         "retrievedUtc": now, "pages": pages}).encode(), mtime=0))
    print("refreshed %d rows / %d pages; update verified-facts.json hashes, then regenerate" % (len(results), len(pages)))


pages_meta = {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--refresh", action="store_true", help="live re-capture of the source files (network)")
    a = ap.parse_args()
    if a.refresh:
        refresh()
    raw = json.load(gzip.open(os.path.join(SRC, "raw-recipes.json.gz")))
    tpl = json.load(gzip.open(os.path.join(SRC, "page-templates.json.gz")))
    facts = json.load(open(os.path.join(SRC, "verified-facts.json")))
    pages_meta["retrievedUtc"] = tpl["retrievedUtc"]
    cat, _ = build(raw, tpl["pages"], facts,
                   sha256(os.path.join(SRC, "raw-recipes.json.gz")), sha256(os.path.join(SRC, "page-templates.json.gz")))
    data = (json.dumps(cat, indent=1, sort_keys=False, separators=(",", ": ")) + "\n").encode()
    if a.check:
        if open(OUT, "rb").read() != data:
            sys.exit("catalog.json is stale; rerun without --check")
        print("catalog.json is current")
    else:
        atomic_write(OUT, data)
        print(json.dumps(cat["coverage"], indent=1))


if __name__ == "__main__":
    main()
