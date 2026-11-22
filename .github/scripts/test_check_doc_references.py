"""Offline checks for the documentation reference checker (uses a throwaway git repository)."""

import subprocess
import tempfile
import unittest
from pathlib import Path

import check_doc_references as refs


def make_repo(files: dict[str, str]) -> Path:
    root = Path(tempfile.mkdtemp())
    subprocess.run(["git", "init", "-q"], cwd=root, check=True)
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    subprocess.run(["git", "add", "-A"], cwd=root, check=True)
    return root


class DocReferenceTest(unittest.TestCase):
    def found(self, files):
        return [(f["doc"], f["reference"]) for f in refs.check(make_repo(files))]

    def test_existing_and_missing_paths(self):
        found = self.found({
            "backend/src/main/java/sync/TpSync.java": "class TpSync {}",
            ".github/workflows/ci.yml": "name: CI",
            "docs/A.md": "See `sync/TpSync.java`, `.github/workflows/ci.yml` and `sync/Gone.java`.\n",
        })
        self.assertEqual([("docs/A.md", "sync/Gone.java")], found)

    def test_relative_links_resolve_from_the_document(self):
        found = self.found({
            "docs/A.md": "[ok](B.md#section) [up](../README.md) [bad](Missing.md) [web](https://x.example/y)\n",
            "docs/B.md": "b", "README.md": "r",
        })
        self.assertEqual([("docs/A.md", "Missing.md")], found)

    def test_code_fences_archive_and_ignored_paths_are_skipped(self):
        found = self.found({
            "docs/A.md": "```\n`fake/Path.java`\n```\n`quality-reports/coverage-kpi.md` `/opt/gw2/x.yaml` `NoSlash.java`\n",
            "docs/archive/Old.md": "`gone/Gone.java` [x](nope.md)\n",
        })
        self.assertEqual([], found)

    def test_render_reports_counts_and_truncates(self):
        many = [{"doc": "d.md", "line": i, "kind": "path", "reference": f"p{i}.java"} for i in range(70)]
        text = refs.render(many, limit=60)
        self.assertIn("70 unresolved reference(s)", text)
        self.assertIn("10 more omitted", text)
        self.assertIn("No unresolved", refs.render([]))


if __name__ == "__main__":
    unittest.main()
