from pathlib import Path

from config import PRODUCT_OWNER_REQUESTS_DIR


# ============================================================
# Product Owner request inbox (agent/product-owner-requests/)
#
# Human-written, free-form Markdown files describing requested changes,
# new features, or product-level requirements. This module only lists
# and reads what is already on disk -- deciding whether a request is
# already covered, converting it into doc updates / stories / user
# decisions, and deleting it once fully processed are all planning-
# prompt responsibilities (see PLANNER_INSTRUCTIONS.md's "Product Owner
# Requests" section), never something this module decides on its own.
#
# README.md documents the format/lifecycle for the human and is never
# itself a request -- it is excluded everywhere below. No filename
# convention is enforced (unlike STORY-*.md/UD-*.md): a human may drop
# an unstructured note here, and the planner must still attempt to
# understand it rather than rejecting it for formatting.
# ============================================================

def list_request_files() -> list[Path]:
    if not PRODUCT_OWNER_REQUESTS_DIR.exists():
        return []

    return sorted(
        path
        for path in PRODUCT_OWNER_REQUESTS_DIR.glob("*.md")
        if path.name.lower() != "readme.md"
    )


def list_request_filenames() -> set[str]:
    return {
        path.name
        for path in list_request_files()
    }


def read_requests() -> list[dict]:
    return [
        {
            "file": path.name,
            "content": path.read_text(
                encoding="utf-8"
            ),
        }
        for path in list_request_files()
    ]
