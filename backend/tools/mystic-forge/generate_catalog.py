#!/usr/bin/env python3
"""Regenerates backend/src/main/resources/mystic-forge/catalog.json from captured GW2 Wiki data.

Offline (default, used by maintainers and CI): reads source/raw-recipes.json.gz (SMW rows for
[[Has recipe source::Mystic forge]]), source/page-templates.json.gz (comment-stripped {{recipe}} templates per
parent page) and source/verified-facts.json (promotion means) and writes the bundled catalog.
  python3 backend/tools/mystic-forge/generate_catalog.py            # write catalog.json
  python3 backend/tools/mystic-forge/generate_catalog.py --check    # fail if catalog.json is stale

Offline runs (default and --check) first require BOTH captured gzip files to match the hashes pinned in
verified-facts.json ("capture"); a mismatch aborts without writing or blessing anything.

Explicit live refresh (needs network, never used by tests or CI): --refresh re-captures the two source files from the
ArenaNet-hosted GW2 Wiki with finite timeouts, bounded retries and modest pagination. Every requested title must come
back exactly once (MediaWiki "normalized"/"redirects" mappings are applied explicitly); any HTTP/JSON failure, short
or malformed batch aborts BEFORE any file is replaced. Both new captures are fully built and validated in memory and
staged next to their targets first; the two final renames are sequential (each atomic), so this is NOT a multi-file
atomic commit. Refresh never regenerates the catalog and never edits verified-facts.json: it prints the new hashes and
a review step (re-verify the facts/means, update "capture", then run the generator).
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
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def fields(template):
    d = {}
    for m in re.finditer(r"^\|\s*([\w ]+?)\s*=\s*(.*)$", template, re.M):
        d.setdefault(m.group(1).lower(), m.group(2).strip())
    return d


ALIASES = {}  # verified Wiki redirect (lowercase) -> canonical page name (lowercase); see load_aliases()


def load_aliases(path):
    """Only the tracked, redirect-verified aliases; no singular/plural, fuzzy or other guessed mapping exists."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    aliases = data["verifiedAliases"]
    for k, v in aliases.items():
        if k != k.lower() or v != v.lower() or k == v or v in aliases:
            raise SystemExit("verified-aliases.json: entries must be lowercase, non-identity and not chained: %r" % k)
    return aliases


def canonical(name):
    return ALIASES.get(name, name)


def parse_ingredient(text):
    m = re.fullmatch(r"(\d+)\s+(.+)", text or "")
    # "Name|Display" piped links name the page before the pipe; only a verified redirect alias is mapped to its target.
    return (int(m.group(1)), canonical(m.group(2).split("|")[0].strip().lower())) if m else None


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
        slots[idx] = (qty, iid, canonical(name.strip().lower()))
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


def reviewed_fixed_output(recipe, facts):
    """A page-wide randomness warning can be cleared only for the exact reviewed recipe and revision."""
    fields = ("forgeId", "page", "revision", "outputItemId", "outputCount", "ingredients")
    return any(all(review.get(key) == recipe[key] for key in fields)
               for review in facts.get("fixedOutputReviews", []))


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
        elif info.get("randomMentioned") and not reviewed_fixed_output(rec, facts):
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
                       "(see mystic-forge/NOTICE.md); Guild Wars 2 game assets remain (c) ArenaNet.",
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


def map_batch(requested, response):
    """Maps every requested title to exactly one returned page with revision content, or raises ValueError.

    MediaWiki may report "normalized" (from -> to) and, when asked, "redirects" mappings; both are followed explicitly,
    one hop each. A title that is missing, returned twice, returned unrequested, or lacks revision content is a
    short/malformed batch and fails the whole refresh.
    """
    q = response.get("query") if isinstance(response, dict) else None
    if not isinstance(q, dict) or not isinstance(q.get("pages"), (dict, list)):
        raise ValueError("malformed batch: no query.pages")
    returned = q["pages"].values() if isinstance(q["pages"], dict) else q["pages"]
    by_title = {}
    for p in returned:
        if not isinstance(p, dict) or not isinstance(p.get("title"), str):
            raise ValueError("malformed batch: page without a title")
        if p["title"] in by_title:
            raise ValueError("malformed batch: duplicate page %r" % p["title"])
        by_title[p["title"]] = p
    hop = {}
    for key in ("normalized", "redirects"):
        for m in q.get(key, []):
            if not isinstance(m, dict) or not isinstance(m.get("from"), str) or not isinstance(m.get("to"), str):
                raise ValueError("malformed batch: bad %s entry" % key)
            if m["from"] in hop and hop[m["from"]] != m["to"]:
                raise ValueError("malformed batch: conflicting %s mapping for %r" % (key, m["from"]))
            hop[m["from"]] = m["to"]
    mapped, used = {}, set()
    for title in requested:
        resolved = title
        for _ in range(2):  # at most normalization then one redirect
            resolved = hop.get(resolved, resolved)
        page = by_title.get(resolved)
        if page is None or "missing" in page or "invalid" in page:
            raise ValueError("requested title %r was not returned (resolved %r)" % (title, resolved))
        revs = page.get("revisions")
        if not (isinstance(revs, list) and len(revs) == 1 and isinstance(revs[0].get("revid"), int)
                and isinstance(revs[0].get("slots", {}).get("main", {}).get("*"), str)):
            raise ValueError("requested title %r has no revision content" % title)
        if resolved in used:
            raise ValueError("two requested titles resolved to %r" % resolved)
        used.add(resolved)
        mapped[title] = (resolved, page)
    if used != set(by_title):
        raise ValueError("unrequested pages returned: %s" % sorted(set(by_title) - used))
    return mapped


def capture_pages(titles, fetch=fetch_json, pause=time.sleep):
    pages = {}
    for s in range(0, len(titles), PAGE):
        batch = titles[s:s + PAGE]
        j = fetch({"action": "query", "format": "json", "prop": "revisions", "rvprop": "ids|content",
                   "rvslots": "main", "titles": "|".join(batch), "redirects": 1})
        try:
            mapped = map_batch(batch, j)
        except ValueError as e:
            raise SystemExit("refresh aborted, nothing written: %s" % e)
        for title, (resolved, p) in mapped.items():
            rev = p["revisions"][0]
            text = rev["slots"]["main"]["*"]
            pages[title] = {"revision": rev["revid"], "templates": strip_templates(text),
                            "randomMentioned": bool(re.search(r"random", re.sub(r"<!--.*?-->", "", text, flags=re.S), re.I))}
            if resolved != title:
                pages[title]["resolvedTitle"] = resolved
        pause(1)
    if set(pages) != set(titles):
        raise SystemExit("refresh aborted, nothing written: page coverage is not exact")
    return pages


def collect_listing(fetch=fetch_json, pause=time.sleep):
    """Page through the SMW listing; any malformed, overlapping or non-advancing page aborts before anything is captured."""
    results, offset = {}, 0
    while True:
        j = fetch({"action": "ask", "format": "json", "query": SMW_QUERY + "|offset=%d" % offset})
        part = j.get("query", {}).get("results") if isinstance(j, dict) else None
        if not isinstance(part, dict) or not part:
            raise SystemExit("refresh aborted: empty/invalid listing page at offset %d" % offset)
        dup = sorted(set(results) & set(part))
        if dup:
            raise SystemExit("refresh aborted: listing pages overlap on %d row(s), e.g. %r" % (len(dup), dup[0]))
        results.update(part)
        if "query-continue-offset" not in j:
            return results
        nxt = j["query-continue-offset"]
        if isinstance(nxt, bool) or not isinstance(nxt, int) or nxt < 0 or nxt <= offset:
            raise SystemExit("refresh aborted: continuation offset %r does not advance from %d" % (nxt, offset))
        offset = nxt
        pause(1)


def refresh(fetch=fetch_json, pause=time.sleep):
    results = collect_listing(fetch, pause)
    titles = sorted({k.split("#")[0] for k in results})
    pages = capture_pages(titles, fetch, pause)
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    raw_bytes = gzip.compress(json.dumps({"query": {"results": results}}).encode(), mtime=0)
    tpl_bytes = gzip.compress(json.dumps(
        {"source": "https://wiki.guildwars2.com/", "license": "GW2 Wiki contributors, GFDL 1.3; ArenaNet game data retains its rights",
         "retrievedUtc": now, "pages": pages}).encode(), mtime=0)
    # Build and validate completely (dry run of the real generator) before ANY file is replaced.
    with open(os.path.join(SRC, "verified-facts.json"), encoding="utf-8") as f:
        facts = json.load(f)
    pages_meta["retrievedUtc"] = now
    build(json.loads(gzip.decompress(raw_bytes)), pages, facts, hashlib.sha256(raw_bytes).hexdigest(),
          hashlib.sha256(tpl_bytes).hexdigest())
    stage_and_replace([(os.path.join(SRC, "raw-recipes.json.gz"), raw_bytes),
                       (os.path.join(SRC, "page-templates.json.gz"), tpl_bytes)])
    print("refreshed %d rows / %d pages (catalog.json NOT regenerated, verified-facts.json NOT edited)" % (len(results), len(pages)))
    print("rawRecipesSha256     %s" % hashlib.sha256(raw_bytes).hexdigest())
    print("pageTemplatesSha256  %s" % hashlib.sha256(tpl_bytes).hexdigest())
    print("REVIEW STEP: re-verify the promotion means / vendor facts and capture counts in verified-facts.json against the\n"
          "new data, update its \"capture\" hashes deliberately, then run this tool without --refresh and commit the diff.")


def stage_and_replace(items):
    """Stage every file beside its target first; only then rename each. Sequential renames: not multi-file atomic."""
    staged = []
    try:
        for path, data in items:
            d = os.path.dirname(path)
            fd, tmp = tempfile.mkstemp(dir=d)
            with os.fdopen(fd, "wb") as f:
                f.write(data)
            staged.append((tmp, path))
        for tmp, path in staged:
            os.replace(tmp, path)
        staged = []
    finally:
        for tmp, _ in staged:
            if os.path.exists(tmp):
                os.unlink(tmp)


def verify_pins(facts):
    """Both captured gzip files must equal the hashes pinned in verified-facts.json; never bless fresh hashes."""
    cap = facts["capture"]
    for name, key in (("raw-recipes.json.gz", "rawRecipesSha256"), ("page-templates.json.gz", "pageTemplatesSha256")):
        actual = sha256(os.path.join(SRC, name))
        if actual != cap.get(key):
            sys.exit("%s hash %s does not match pinned %s (%s): stale/changed capture; review it and update "
                     "verified-facts.json deliberately" % (name, actual, key, cap.get(key)))


def compact_json(cat):
    """Header objects pretty-printed; one line per recipe (keeps ~2000 recipes from becoming ~38k lines)."""
    recipes = cat["recipes"]
    head = {k: v for k, v in cat.items() if k != "recipes"}
    body = json.dumps(head, indent=1, separators=(",", ": "))
    lines = ",\n".join(" " + json.dumps(r, separators=(",", ":")) for r in recipes)
    return (body[:-2] + ',\n "recipes": [\n' + lines + "\n ]\n}\n").encode()


pages_meta = {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--refresh", action="store_true", help="live re-capture of the source files (network); never regenerates")
    a = ap.parse_args()
    ALIASES.clear()
    ALIASES.update(load_aliases(os.path.join(SRC, "verified-aliases.json")))
    if a.refresh:
        refresh()
        return
    with open(os.path.join(SRC, "verified-facts.json"), encoding="utf-8") as f:
        facts = json.load(f)
    verify_pins(facts)
    raw = json.load(gzip.open(os.path.join(SRC, "raw-recipes.json.gz")))
    tpl = json.load(gzip.open(os.path.join(SRC, "page-templates.json.gz")))
    pages_meta["retrievedUtc"] = tpl["retrievedUtc"]
    cat, _ = build(raw, tpl["pages"], facts, facts["capture"]["rawRecipesSha256"], facts["capture"]["pageTemplatesSha256"])
    data = compact_json(cat)
    json.loads(data)  # the compact form must stay valid JSON
    if a.check:
        with open(OUT, "rb") as f:
            current = f.read()
        if current != data:
            sys.exit("catalog.json is stale; rerun without --check")
        print("catalog.json is current")
    else:
        atomic_write(OUT, data)
        print(json.dumps(cat["coverage"], indent=1))


if __name__ == "__main__":
    main()
