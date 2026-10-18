. as $s | (.active_cycle // {}) as $c | (.running_task // {}) as $r |
(.control // {}) as $control | (.budget // {}) as $b | ($b.result // {}) as $usage |
($c.history // []) as $h | ($h[-1] // {}) as $last |
($r.status == "running" or $r.status == "queued") as $busy |
($control.outcome // $last.outcome // "") as $outcome |
($b.stale == false and $b.error == null and $b.result != null) as $fresh |
($outcome == "waiting_for_claude_budget" and ($fresh | not)) as $budget_unknown |
($outcome == "waiting_for_claude_budget" and $fresh and $b.claude_allowed == false) as $budget_wait |
($outcome | test("fail|error|conflict|loop_guard"; "i")) as $error |
(if $busy then $r.step else $c.phase end) as $focus |
(if $busy then "RUNNING"
 elif $control.active == true then "ADVANCING"
 elif $budget_unknown then "BUDGET CHECK NEEDED"
 elif $budget_wait then "WAITING FOR CLAUDE BUDGET"
 elif $outcome == "waiting_for_claude_budget" then "BUDGET AVAILABLE / AWAITING RUN"
 elif $error then "STOPPED / ERROR"
 elif ($outcome | test("NEEDS_USER|blocked|waiting"; "i")) then "WAITING / " + $outcome
 elif $c.phase then "PAUSED / AWAITING RUN"
 else "IDLE" end) as $activity |
(if $busy or $control.active == true then 1
 elif $error then 3
 elif $c.phase then 4 else 0 end) as $activity_color |
def stage($id):
  ($h | map(select(.step == $id)) | last // {}) as $event |
  if $focus == $id then $activity_color
  elif (["activated", "READY", "NO_TESTS_NEEDED", "implemented", "COMPLETE", "published", "PASSED", "done"] | index($event.outcome // "")) != null then 2
  else 0 end;
def stage_label($id; $name):
  if $focus == $id then $name + "\n" +
    (if $busy then "RUNNING" elif $control.active then "NEXT" elif $error then "STOPPED / ERROR"
     elif $budget_wait then "WAITING: BUDGET" elif $budget_unknown then "CHECK BUDGET" else "WAITING" end)
  elif stage($id) == 2 then $name + "\nCOMPLETED"
  elif ($h | any(.step == $id and .outcome == "RETRY")) then $name + "\nREVISION REQUESTED"
  else $name end;
def pct($value): if $value == null then "?" else ($value | tostring) + "%" end;
[{
  activity: $activity,
  activity_color: $activity_color,
  story: ($c.story_id // $r.story_id // "No active story"),
  saved_phase: ($focus // "None"),
  detail: (if $busy then "Executing " + ($r.step // "task")
    elif $budget_wait or $budget_unknown then ($usage.reason // $control.reason // "Budget reading unavailable")
    else ($control.reason // $last.outcome // "No active run") end),
  weekly: (pct($usage.weekly_used_percent) + " used / " + pct($usage.weekly_cap_percent) + " allowed"),
  session: (pct($usage.session_used_percent) + " used / " + pct($usage.session_limit_percent) + " start threshold"),
  budget_status: (if $b.error != null then "ERROR: " + ($b.error | tostring)
    elif $b.stale == true then "STALE / permission unknown"
    elif $fresh and $b.claude_allowed == true then "AVAILABLE"
    elif $fresh and $b.claude_allowed == false then "BLOCKED" else "UNKNOWN" end),
  budget_time: ("Checked: " + (($b.checked_at // "unknown") | sub("\\.[0-9]+"; "") | sub("T"; " ")) + " | Next share: " + (($usage.next_unlock // "unknown") | sub("T"; " ")) + " (service local time)"),
  sync: stage("sync"), sync_label: stage_label("sync"; "Sync maintainer"),
  review: stage("review"), review_label: stage_label("review"; "Review incoming docs"),
  plan: stage("plan"), plan_label: stage_label("plan"; "Plan / Architect"),
  select: stage("select"), select_label: stage_label("select"; "Select story"),
  qa: stage("qa"), qa_label: stage_label("qa"; "QA preparation"),
  implement: stage("implement"), implement_label: stage_label("implement"; "Implementation"),
  evaluate: stage("evaluate"), evaluate_label: stage_label("evaluate"; "Evaluation"),
  publish: stage("publish"), publish_label: stage_label("publish"; "Publish story"),
  ci: stage("ci"), ci_label: stage_label("ci"; "Application CI"),
  finalize: stage("finalize"), finalize_label: stage_label("finalize"; "Finalize story")
}]
