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


def share_queries(dashboard):
    """Fetch one snapshot; Dashboard datasource consumers select their own rows locally."""
    panels = {p['id']: p for p in dashboard['panels']}
    source = panels[4]
    target = source['targets'][0]
    projection = (ROOT / 'dashboard-state.jq').read_text(encoding='utf-8')
    target['root_selector'] = '. as $snapshot | (' + projection + '''
      | map(. + {row_type: "status"})) +
      [ (($snapshot.active_cycle // $snapshot.last_cycle).history // [] | reverse | .[:30])[] |
        {row_type: "event", event_at: .at, event_step: .step, event_outcome: .outcome} ] +
      [ ($snapshot.human_requests.open // [])[] |
        {row_type: "request", request_id: .id, request_kind: .kind,
         request_stories: (.story_ids | join(", ")), request_reason: .reason, request_path: .path} ] +
      [ ($snapshot.human_requests.errors // [])[] |
        {row_type: "request", request_id: "Read error", request_kind: "error",
         request_stories: "", request_reason: ., request_path: ""} ]'''
    table_fields = {
        5: [('event_at', 'Time'), ('event_step', 'Stage'), ('event_outcome', 'Outcome')],
        12: [('request_id', 'Request'), ('request_kind', 'Type'), ('request_stories', 'Stories'),
             ('request_reason', 'Reason / decision needed'), ('request_path', 'Repository path')],
    }
    extra_columns = [{'selector': name, 'text': title, 'type': 'string'}
                     for name, title in [('row_type', 'row_type')] + table_fields[5] + table_fields[12]]
    target['columns'] = [column for column in target['columns']
                         if column['selector'] not in {c['selector'] for c in extra_columns}] + extra_columns
    shared = {'type': 'datasource', 'uid': '-- Dashboard --'}
    for identifier in (1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12):
        panel = panels[identifier]
        row_type = 'event' if identifier == 5 else 'request' if identifier == 12 else 'status'
        transforms = [{'id': 'filterByValue', 'options': {
            'type': 'include', 'match': 'all', 'filters': [
                {'fieldName': 'row_type', 'config': {'id': 'equal', 'options': {'value': row_type}}}]}}]
        if identifier in table_fields:
            transforms.append({'id': 'filterFieldsByName', 'options': {
                'include': {'names': [title for _, title in table_fields[identifier]]}}})
        panel['transformations'] = transforms + [t for t in panel.get('transformations', []) if t not in transforms]
        if identifier != 4:
            panel['datasource'] = copy.deepcopy(shared)
            panel['targets'] = [{'datasource': copy.deepcopy(shared), 'panelId': 4,
                                 'refId': 'A', 'withTransforms': False}]
    return dashboard


if __name__ == "__main__":
    dashboard = json.loads(PATH.read_text(encoding="utf-8-sig"))
    PATH.write_text(json.dumps(share_queries(update_queries(add_status_panels(dashboard))), indent=2) + "\n", encoding="utf-8")
