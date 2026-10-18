"""Update query logic in the saved dashboard without rebuilding its UI layout."""
import json
import copy
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PATH = ROOT / "dashboards/workflow-overview.json"
STAGES = "sync review plan select qa implement evaluate publish ci finalize".split()


def update_queries(dashboard):
    projection = (ROOT / "dashboard-state.jq").read_text(encoding="utf-8")
    strings = ["activity", "story", "saved_phase", "detail", "weekly", "session",
               "budget_status", "budget_time", "request_notice"] + [s + "_label" for s in STAGES]
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


def add_status_panels(dashboard):
    """Add request visibility once, retaining existing panel designs and canvas placement."""
    panels = {p['id']: p for p in dashboard['panels']}
    installing = 11 not in panels
    if installing:
        top = panels[4]['gridPos']['y']
        for panel in dashboard['panels']:
            if panel['gridPos']['y'] >= top:
                panel['gridPos']['y'] += 3
        notice = copy.deepcopy(panels[10])
        notice.update(id=11, title='Open human requests (all stories)',
                      gridPos={'x': 0, 'y': top, 'w': 24, 'h': 3})
        notice['options']['reduceOptions']['fields'] = '/^request_notice$/'
        notice['options']['text'] = {'valueSize': 18}
        dashboard['panels'].append(notice)
    if 12 not in panels:
        requests = copy.deepcopy(panels[5])
        bottom = max(p['gridPos']['y'] + p['gridPos']['h'] for p in dashboard['panels'])
        requests.update(id=12, title='Open decisions and interventions - repository paths',
                        gridPos={'x': 0, 'y': bottom, 'w': 24, 'h': 7})
        target = requests['targets'][0]
        target['root_selector'] = '''[ (.human_requests.open // [])[] |
          {id, kind, stories: (.story_ids | join(", ")), reason, path} ] +
          [ (.human_requests.errors // [])[] |
          {id: "Read error", kind: "error", stories: "", reason: ., path: ""} ]'''
        target['columns'] = [{'selector': name, 'text': title, 'type': 'string'} for name, title in
                             [('id', 'Request'), ('kind', 'Type'), ('stories', 'Stories'),
                              ('reason', 'Reason / decision needed'), ('path', 'Repository path')]]
        dashboard['panels'].append(requests)
    if installing:
        panels[2]['title'] = 'Story context'
        panels[5]['title'] = 'Recent events - current or last stopped story'
        panels[5]['targets'][0]['root_selector'] = '((.active_cycle // .last_cycle).history // []) | reverse | .[:30]'
        for element in panels[4]['options']['root']['elements']:
            if element['name'] == 'retry-notes':
                element['config']['text']['fixed'] = ('QA covers preparation, CI review and test correction. '
                    'Test corrections return to evaluation; production fixes return to implementation.')
    return dashboard


if __name__ == "__main__":
    dashboard = json.loads(PATH.read_text(encoding="utf-8-sig"))
    PATH.write_text(json.dumps(update_queries(add_status_panels(dashboard)), indent=2) + "\n", encoding="utf-8")
