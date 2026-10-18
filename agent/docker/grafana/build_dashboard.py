"""Update query logic in the saved dashboard without rebuilding its UI layout."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PATH = ROOT / "dashboards/workflow-overview.json"
STAGES = "sync review plan select qa implement evaluate publish ci finalize".split()


def update_queries(dashboard):
    projection = (ROOT / "dashboard-state.jq").read_text(encoding="utf-8")
    strings = ["activity", "story", "saved_phase", "detail", "weekly", "session",
               "budget_status", "budget_time"] + [s + "_label" for s in STAGES]
    for panel in dashboard["panels"]:
        for target in panel.get("targets", []):
            if (target.get("datasource", {}).get("uid") != "gw2-workflow-state"
                    or "activity_color" not in target.get("root_selector", "")):
                continue  # Keep the event history and unrelated queries intact.
            target["root_selector"] = projection
            target["columns"] = [{"selector": s, "text": s, "type": "string"} for s in strings]
            target["columns"] += [{"selector": s, "text": s, "type": "number"}
                                  for s in ["activity_color"] + STAGES]
            if panel["type"] == "canvas":
                target["columns"] += [{"selector": s, "text": s + "_fill", "type": "number"}
                                      for s in STAGES]
    return dashboard


if __name__ == "__main__":
    dashboard = json.loads(PATH.read_text(encoding="utf-8-sig"))
    PATH.write_text(json.dumps(update_queries(dashboard), indent=2) + "\n", encoding="utf-8")
