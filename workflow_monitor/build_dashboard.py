"""Embed the shared read-only state projection into the saved Grafana dashboard."""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PATH = ROOT / "dashboards/workflow-overview.json"
STAGES = "sync review plan select qa implement evaluate publish ci finalize".split()
COLORS = ["#475569", "#2563EB", "#22C55E", "#DC2626", "#B45309"]


def build(dashboard):
    dashboard = copy.deepcopy(dashboard)
    projection = (ROOT / "dashboard-state.jq").read_text(encoding="utf-8")
    panels = {p["id"]: p for p in dashboard["panels"]}
    target = copy.deepcopy(panels[1]["targets"][0])
    target["root_selector"] = projection
    strings = ["activity", "story", "saved_phase", "detail", "weekly", "session",
               "budget_status", "budget_time"] + [s + "_label" for s in STAGES]
    target["columns"] = [{"selector": s, "text": s, "type": "string"} for s in strings]
    target["columns"] += [{"selector": s, "text": s, "type": "number"}
                          for s in ["activity_color"] + STAGES]

    def stat(identifier, title, field, x, y, width, height=4):
        panel = copy.deepcopy(panels[1])
        panel.update(id=identifier, title=title, targets=[copy.deepcopy(target)],
                     gridPos={"x": x, "y": y, "w": width, "h": height})
        panel["options"]["reduceOptions"]["fields"] = "/^" + field + "$/"
        panel["options"]["text"] = {"valueSize": 23 if width > 8 else 18}
        panel["fieldConfig"] = {"defaults": {}, "overrides": []}
        if field in ("activity", "budget_status"):
            panel["options"]["colorMode"] = "value"
            panel["fieldConfig"] = {"defaults": {"mappings": [
                {"type": "regex", "options": {"pattern": pattern, "result": {"color": color}}}
                for pattern, color in [
                    ("RUNNING|ADVANCING", "#5794F2"),
                    ("ERROR", "#F2495C"),
                    ("WAITING|BLOCKED|STALE|UNKNOWN|CHECK|PAUSED", "#FFB357"),
                    ("^AVAILABLE$", "#73BF69")]]}, "overrides": []}
        return panel

    canvas = panels[4]
    canvas_target = copy.deepcopy(target)
    canvas["gridPos"].update(y=13, h=23)
    for element in canvas["options"]["root"]["elements"]:
        name = element["name"]
        if name in STAGES:
            element["config"]["text"] = {"mode": "field", "field": name + "_label"}
            element["config"]["size"] = 16
            element["border"]["width"] = 4
            # A separate field override gives completed stages a dark fill.
            fill = name + "_fill"
            element["background"]["color"] = {"field": fill, "fixed": "#18212F"}
            canvas_target["columns"].append({"selector": name, "text": fill, "type": "number"})
        elif name == "legend":
            element["config"]["text"]["fixed"] = "Filled blue: running | Filled amber: waiting | Green border: completed | Red: current error"
            element["config"]["size"] = 14
        elif name == "scope":
            element["config"]["text"]["fixed"] = "Earlier failures and revision requests stay in the event history; they do not mark an inactive step red."
        elif name == "retry-notes":
            element["config"]["text"]["fixed"] = "Evaluation can request another implementation attempt. Read the current status above for the reason execution is waiting."
    canvas["targets"] = [canvas_target]
    canvas["fieldConfig"] = {
        "defaults": {"color": {"mode": "thresholds"}, "mappings": [],
                     "thresholds": {"mode": "absolute", "steps": [
                         {"value": None if i == 0 else i, "color": color}
                         for i, color in enumerate(COLORS)]}},
        "overrides": [{"matcher": {"id": "byName", "options": s + "_fill"},
                       "properties": [{"id": "thresholds", "value": {
                           "mode": "absolute", "steps": [
                               {"value": None if i == 0 else i,
                                "color": "#18212F" if i in (0, 2) else color}
                               for i, color in enumerate(COLORS)]}}]} for s in STAGES]}
    # Canvas gets extra fill fields; stat panels need only the projection columns.
    history = panels[5]
    history["gridPos"].update(y=36)
    dashboard["panels"] = [
        stat(1, "Execution status", "activity", 0, 0, 12),
        stat(2, "Current story", "story", 12, 0, 6),
        stat(3, "Current / next step", "saved_phase", 18, 0, 6),
        stat(6, "Claude weekly allowance (cached reading)", "weekly", 0, 4, 12),
        stat(7, "Claude session usage (cached reading)", "session", 12, 4, 8),
        stat(8, "Claude start gate", "budget_status", 20, 4, 4),
        stat(9, "Budget reading / next allowance unlock", "budget_time", 0, 8, 24, 2),
        stat(10, "Current execution / stop reason", "detail", 0, 10, 24, 3),
        canvas, history]
    dashboard["version"] = 1
    dashboard.pop("id", None)
    return dashboard


if __name__ == "__main__":
    PATH.write_text(json.dumps(build(json.loads(PATH.read_text(encoding="utf-8-sig"))),
                               indent=2) + "\n", encoding="utf-8")
