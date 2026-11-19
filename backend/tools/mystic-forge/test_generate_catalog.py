#!/usr/bin/env python3
"""Offline checks for generate_catalog.py (no network):  python3 backend/tools/mystic-forge/test_generate_catalog.py"""
import os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate_catalog as g

FACTS = {"promotionMeans": {}, "source": "s", "revision": 1}
g.pages_meta["retrievedUtc"] = "t"


def row(slots, forge=1, out=500, qty=1):
    printouts = {"Has mystic forge id": [forge], "Has output game id": [out], "Has output quantity": [qty],
                 "Has ingredient with id": [
                     {"Has ingredient index": {"item": [i]}, "Has ingredient quantity": {"item": [q]},
                      "Has ingredient id": {"item": [iid]}, "Has ingredient name": {"item": [{"fulltext": n}]}}
                     for i, (q, iid, n) in enumerate(slots, 1)]}
    return {"printouts": printouts}


def template(names, quantity=1):
    lines = ["{{Recipe", "|source = mystic forge"] + ["|ingredient%d = %s" % (i, n) for i, n in enumerate(names, 1)]
    return "\n".join(lines + ["|quantity = %d" % quantity, "}}"])


SLOTS = [(1, 11, "Glob of Ectoplasm"), (1, 12, "Obsidian Shard"), (1, 13, "A"), (1, 14, "B")]


class Aliases(unittest.TestCase):
    def setUp(self):
        g.ALIASES.clear()
        g.ALIASES.update(g.load_aliases(os.path.join(g.SRC, "verified-aliases.json")))

    def build(self, rows, templates):
        raw = {"query": {"results": rows}}
        return g.build(raw, {"P": {"revision": 9, "templates": templates}}, FACTS, "a", "b")[0]

    def test_verified_redirect_alias_matches_its_source_template(self):
        tpl = template(["1 Globs of Ectoplasm", "1 Obsidian Shards", "1 A", "1 B"])
        self.assertEqual(self.build({"P": row(SLOTS)}, [tpl])["coverage"]["includedRecipes"], 1)

    def test_unverified_singular_or_fuzzy_wording_stays_unmatched(self):
        tpl = template(["1 Glob of Ectoplasmm", "1 Obsidian Shard", "1 A", "1 B"])
        cat = self.build({"P": row(SLOTS)}, [tpl])
        self.assertEqual(cat["coverage"]["includedRecipes"], 0)
        self.assertIn("no unique matching source template", cat["coverage"]["excludedByReason"])

    def test_alias_does_not_bypass_quantity_or_ambiguity_checks(self):
        wrong_qty = template(["2 Globs of Ectoplasm", "1 Obsidian Shard", "1 A", "1 B"])
        self.assertEqual(self.build({"P": row(SLOTS)}, [wrong_qty])["coverage"]["includedRecipes"], 0)
        a = template(["1 Globs of Ectoplasm", "1 Obsidian Shard", "1 A", "1 B"])
        b = template(["1 Glob of Ectoplasm", "1 Obsidian Shard", "1 A", "1 B"])  # alias and canonical both match
        cat = self.build({"P": row(SLOTS)}, [a, b])
        self.assertEqual(cat["coverage"]["includedRecipes"], 0)
        self.assertIn("ambiguous source templates", cat["coverage"]["excludedByReason"])

    def test_wrong_output_id_is_rejected(self):
        tpl = template(["1 Globs of Ectoplasm", "1 Obsidian Shard", "1 A", "1 B"]).replace("}}", "|output item id = 999\n}}")
        self.assertEqual(self.build({"P": row(SLOTS)}, [tpl])["coverage"]["includedRecipes"], 0)

    def test_alias_file_rejects_chains_and_identity(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write('{"verifiedAliases": {"a": "b", "b": "c"}}')
        with self.assertRaises(SystemExit):
            g.load_aliases(f.name)
        os.unlink(f.name)


def page(title, content="x", **extra):
    p = {"title": title, "revisions": [{"revid": 1, "slots": {"main": {"*": content}}}]}
    p.update(extra)
    return p


class Batches(unittest.TestCase):
    def test_exact_coverage_with_explicit_normalization_and_redirect(self):
        resp = {"query": {"normalized": [{"from": "foo bar", "to": "Foo bar"}],
                          "redirects": [{"from": "Old", "to": "New"}],
                          "pages": {"1": page("Foo bar"), "2": page("New")}}}
        mapped = g.map_batch(["foo bar", "Old"], resp)
        self.assertEqual(mapped["foo bar"][0], "Foo bar")
        self.assertEqual(mapped["Old"][0], "New")

    def test_short_batch_missing_duplicate_or_unrequested_pages_fail(self):
        for pages in ({"1": page("A")},                                   # short: B absent
                      {"1": page("A"), "2": {"title": "B", "missing": ""}},
                      {"1": page("A"), "2": page("B"), "3": page("C")},   # unrequested
                      {"1": page("A"), "2": {"title": "B", "revisions": []}}):
            with self.assertRaises(ValueError):
                g.map_batch(["A", "B"], {"query": {"pages": pages}})
        with self.assertRaises(ValueError):
            g.map_batch(["A"], {"nope": 1})

    def test_capture_pages_aborts_before_any_result_on_a_short_batch(self):
        short = lambda params: {"query": {"pages": {"1": page("A")}}}
        with self.assertRaises(SystemExit):
            g.capture_pages(["A", "B"], fetch=short, pause=lambda s: None)
        ok = lambda params: {"query": {"pages": {"1": page("A", "{{recipe|a=1}}"), "2": page("B")}}}
        pages = g.capture_pages(["A", "B"], fetch=ok, pause=lambda s: None)
        self.assertEqual(sorted(pages), ["A", "B"])


class Pins(unittest.TestCase):
    def test_pinned_hashes_match_the_committed_capture_and_a_mismatch_aborts(self):
        import json
        facts = json.load(open(os.path.join(g.SRC, "verified-facts.json")))
        g.verify_pins(facts)
        facts["capture"]["rawRecipesSha256"] = "0" * 64
        with self.assertRaises(SystemExit):
            g.verify_pins(facts)

    def test_failed_staging_leaves_existing_files_untouched(self):
        with tempfile.TemporaryDirectory() as d:
            a = os.path.join(d, "a"); open(a, "wb").write(b"old")
            with self.assertRaises(Exception):
                g.stage_and_replace([(a, b"new"), (os.path.join(d, "missing-dir", "b"), b"x")])
            self.assertEqual(open(a, "rb").read(), b"old")
            self.assertEqual(os.listdir(d), ["a"])


if __name__ == "__main__":
    unittest.main()
