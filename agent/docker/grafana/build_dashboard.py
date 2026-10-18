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
               "budget_status", "budget_time", "request_notice", "retry_label"] + [s + "_label" for s in STAGES]
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
      | map(. + ($snapshot | (''' + (ROOT / 'dashboard-retry.jq').read_text(encoding='utf-8') + ''')) + {row_type: "status"})) +
      ($snapshot | (''' + (ROOT / 'dashboard-activity.jq').read_text(encoding='utf-8') + ''')) +
      [ ($snapshot.human_requests.open // [])[] |
        {row_type: "request", request_id: .id, request_kind: .kind,
         request_stories: (.story_ids | join(", ")), request_reason: .reason, request_path: .path} ] +
      [ ($snapshot.human_requests.errors // [])[] |
        {row_type: "request", request_id: "Read error", request_kind: "error",
         request_stories: "", request_reason: ., request_path: ""} ]'''
    table_fields = {
        5: [('activity_at', 'Time'), ('activity_role', 'Agent'), ('activity_kind', 'Kind'),
            ('activity_text', 'Message'), ('activity_story', 'Story')],
        12: [('request_id', 'Request'), ('request_kind', 'Type'), ('request_stories', 'Stories'),
             ('request_reason', 'Reason / decision needed'), ('request_path', 'Repository path')],
    }
    extra_columns = [{'selector': name, 'text': title, 'type': 'string'}
                     for name, title in [('row_type', 'row_type')] + table_fields[5] + table_fields[12]]
    target['columns'] = [column for column in target['columns']
                         if column['selector'] not in {c['selector'] for c in extra_columns}] + extra_columns
    shared = {'type': 'datasource', 'uid': '-- Dashboard --'}
    for identifier in (1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13):
        if identifier not in panels:
            continue
        panel = panels[identifier]
        row_type = 'activity' if identifier == 5 else 'request' if identifier == 12 else 'status'
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


def add_activity_feed(dashboard):
    """Replace the two old panels without changing the maintainer's remaining layout."""
    panels = {p['id']: p for p in dashboard['panels']}
    if 10 in panels:
        removed = panels[10]['gridPos']
        dashboard['panels'] = [p for p in dashboard['panels'] if p['id'] != 10]
        for panel in dashboard['panels']:
            if panel['gridPos']['y'] >= removed['y'] + removed['h']:
                panel['gridPos']['y'] -= removed['h']
        feed = panels[5]
        feed['title'] = 'Agent activity - latest first; stopping errors stay on top'
        feed['description'] = 'Agent narration and emitted summaries, without tool commands or file output. Not a full internal reasoning trace.'
        # Keep messages readable and color errors across the row.
        feed['fieldConfig']['overrides'] = [
            {'matcher': {'id': 'byName', 'options': 'Kind'}, 'properties': [
                {'id': 'custom.width', 'value': 100},
                {'id': 'custom.cellOptions', 'value': {'type': 'color-background', 'applyToRow': True}},
                {'id': 'mappings', 'value': [{'type': 'value', 'options': {
                    'ERROR': {'color': '#F2495C', 'text': 'ERROR'},
                    'WAITING': {'color': '#FFB357', 'text': 'WAITING'},
                    'MESSAGE': {'color': '#5794F2', 'text': 'MESSAGE'},
                    'SUMMARY': {'color': '#5794F2', 'text': 'SUMMARY'},
                    'SYSTEM': {'color': '#64748B', 'text': 'SYSTEM'}}}]}]},
            {'matcher': {'id': 'byName', 'options': 'Message'}, 'properties': [
                {'id': 'custom.cellOptions', 'value': {'type': 'auto', 'wrapText': True}}]},
            {'matcher': {'id': 'byName', 'options': 'Time'}, 'properties': [
                {'id': 'custom.width', 'value': 170}, {'id': 'unit', 'value': 'dateTimeAsLocal'}]},
            {'matcher': {'id': 'byName', 'options': 'Agent'}, 'properties': [
                {'id': 'custom.width', 'value': 180}]},
            {'matcher': {'id': 'byName', 'options': 'Story'}, 'properties': [
                {'id': 'custom.width', 'value': 150}]}]
        feed['options']['sortBy'] = []  # Preserve the pinned alert, then newest messages.
        feed['transformations'] = []
    return dashboard


def add_retry_panel(dashboard):
    for feed in (p for p in dashboard['panels'] if p['id'] == 5):
        for override in feed.get('fieldConfig', {}).get('overrides', []):
            if override.get('matcher', {}).get('options') == 'Kind':
                for prop in override['properties']:
                    if prop['id'] == 'mappings':
                        for mapping in prop['value']:
                            if mapping['type'] == 'value':
                                mapping['options'].setdefault('RETRY', {'color': '#FFB357', 'text': 'RETRY'})
    if any(p['id'] == 13 for p in dashboard['panels']):
        return dashboard
    panels = {p['id']: p for p in dashboard['panels']}
    top = panels[4]['gridPos']['y']
    for panel in dashboard['panels']:
        if panel['gridPos']['y'] >= top:
            panel['gridPos']['y'] += 2
    panel = copy.deepcopy(panels[3])
    panel.update(id=13, title='Current retry / correction', gridPos={'x': 0, 'y': top, 'w': 24, 'h': 2},
                 description='Current task validation retry or active story rework round. Budget polling does not consume retries. Limits are shown only when exposed by the service.')
    panel['options']['reduceOptions']['fields'] = '/^retry_label$/'
    panel['options']['text'] = {'valueSize': 20}
    panel['options']['colorMode'] = 'value'
    panel['fieldConfig'] = {'defaults': {'mappings': [
        {'type': 'regex', 'options': {'pattern': '^No retry', 'result': {'color': '#73BF69'}}},
        {'type': 'regex', 'options': {'pattern': 'retry|revision|fix|correction|Rework', 'result': {'color': '#FFB357'}}}
    ]}, 'overrides': []}
    dashboard['panels'].append(panel)
    return dashboard


if __name__ == "__main__":
    dashboard = json.loads(PATH.read_text(encoding="utf-8-sig"))
    PATH.write_text(json.dumps(share_queries(update_queries(add_retry_panel(add_activity_feed(add_status_panels(dashboard))))), indent=2) + "\n", encoding="utf-8")
