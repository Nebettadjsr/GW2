. as $s | (.control // {}) as $control |
(.latest_event // {outcome: $control.outcome, reason: $control.reason}) as $event |
(.active_cycle // (if .last_cycle.phase == "blocked" or $event.proceed == false then .last_cycle else null end) // {}) as $c |
(.running_task // {}) as $r | (.budget // {}) as $b | ($b.result // {}) as $usage |
($c.history // []) as $h | ($h[-1] // {}) as $last |
($r.status == "running" or $r.status == "queued") as $busy |
($event.outcome // $last.outcome // "") as $outcome |
($b.stale == false and $b.error == null and $b.result != null) as $fresh |
($outcome == "waiting_for_claude_budget" and ($fresh | not)) as $budget_unknown |
($outcome == "waiting_for_claude_budget" and $fresh and $b.claude_allowed == false) as $budget_wait |
($outcome | test("NEEDS_USER|blocked"; "i")) as $human_wait |
(($event.status == "failed") or (($event.proceed != true) and ($outcome | test("fail|error|conflict|loop_guard|missing|orphan|timeout|unknown"; "i")))) as $error |
($busy or $control.active == true) as $advancing |
(($advancing | not) and ($event.proceed == false or $error or $human_wait)) as $stopped |
(if $busy then $r.step elif $outcome == "queue_empty" then null
 elif $stopped and $outcome == "ci_still_failed" then "ci"
 elif $stopped then ($event.step // (if $s.latest_event != null then "control" else $c.phase end)) else $c.phase end) as $focus |
(if $busy then (if $r.status == "queued" then "QUEUED" elif $control.hold == true then "RUNNING / PAUSE REQUESTED" else "RUNNING" end)
 elif $control.hold == true then "PAUSED / HOLDING"
 elif $control.active == true then "ADVANCING"
 elif $event.status == "interrupted" then "INTERRUPTED / AWAITING RECOVERY"
 elif $budget_unknown then "BUDGET CHECK NEEDED"
 elif $budget_wait then "WAITING FOR CLAUDE BUDGET"
 elif $outcome == "waiting_for_claude_budget" then "BUDGET AVAILABLE / AWAITING RUN"
 elif $outcome == "queue_empty" then "IDLE / QUEUE EMPTY"
 elif ($outcome | test("capacity_exhausted"; "i")) then "WAITING FOR MODEL CAPACITY"
 elif $human_wait then "WAITING FOR HUMAN / " + $outcome
 elif $error then "STOPPED / ERROR"
 elif $stopped then "STOPPED / " + (if $outcome == "" then ($event.status // "awaiting run") else $outcome end)
 elif $c.phase then "PAUSED / AWAITING RUN"
 else "IDLE" end) as $activity |
(if $advancing then 1 elif $error then 3 elif $focus then 4 else 0 end) as $activity_color |
def group($id): if (["qa", "qa_review", "qa_repair"] | index($id)) != null then "qa" else $id end;
def completed($value): (["activated", "READY", "NO_TESTS_NEEDED", "implemented", "COMPLETE", "published", "PASSED", "done", "qa_tests_corrected", "ci_production_retry", "qa_test_repair_requested"] | index($value)) != null;
def stage($id):
  ($h | map(select(group(.step) == $id)) | last // {}) as $previous |
  if group($focus) == $id then $activity_color
  elif completed($previous.outcome // "") then 2 else 0 end;
def stage_label($id; $name):
  if group($focus) == $id then $name + "\n" +
    (if $busy then (if $r.status == "queued" then "QUEUED" else "RUNNING" end)
     elif $control.active then "NEXT" elif $human_wait then "WAITING: HUMAN"
     elif $error then "STOPPED / ERROR" elif $budget_wait then "WAITING: BUDGET"
     elif $budget_unknown then "CHECK BUDGET" elif $stopped then "STOPPED" else "WAITING" end)
  elif stage($id) == 2 then $name + "\nCOMPLETED"
  elif ($h | any(.step == $id and .outcome == "RETRY")) then $name + "\nREVISION REQUESTED"
  else $name end;
def pct($value): if $value == null then "?" else ($value | tostring) + "%" end;
(if group($focus) == "qa" then $focus else ($h | map(select(group(.step) == "qa")) | last | .step) end) as $qa_step |
[{
  activity: $activity, activity_color: $activity_color,
  story: ($c.story_id // $r.story_id // $event.story_id // "No active story"),
  saved_phase: ($focus // "None"),
  detail: (if $busy then "Executing " + ($r.step // "task")
    elif $budget_wait or $budget_unknown then ($usage.reason // $event.reason // "Budget reading unavailable")
    else ([$event.error, $event.reason, $s.last_task.reason, $last.outcome, "No active run"] | map(select(. != null and . != "")) | first) end),
  request_notice: (if $s.human_requests == null then "Human request details require the updated workflow service."
    else ((($s.human_requests.open // []) | map(.id + " [" + (.story_ids | join(", ")) + "]") | join(" | ")) as $requests |
      (if $requests == "" then "No open human requests." else $requests end) +
      (if ($s.human_requests.errors // [] | length) > 0 then " | Some request files could not be read; see request status below." else "" end)) end),
  control_state: ((if $control.hold == true then (if $busy then "PAUSE REQUESTED - finishing " + ($r.step // "step") else "PAUSED - holding" end)
      elif $control.active == true then "RUNNING" else "IDLE" end)
    + " | schedule: " + ($control.schedule // "auto")
    + (if (($s.agent_processes // []) | length) > 0 then " | agents: " + (($s.agent_processes | map(.role)) | join(", ")) else "" end)),
  weekly: (pct($usage.weekly_used_percent) + " used / " + pct($usage.weekly_cap_percent) + " allowed"),
  session: (pct($usage.session_used_percent) + " used / " + pct($usage.session_limit_percent) + " start threshold"),
  budget_status: (if $b.error != null then "ERROR: " + ($b.error | tostring)
    elif $b.stale == true then "STALE / permission unknown"
    elif $fresh and $b.claude_allowed == true then "AVAILABLE"
    elif $fresh and $b.claude_allowed == false then "BLOCKED" else "UNKNOWN" end),
  budget_time: ("Checked: " + ($b.checked_at // "unknown") + " | Next share: " + ($usage.next_unlock // "unknown") + " (service local time)"),
  sync: stage("sync"), sync_label: stage_label("sync"; "Checking Git for updates"),
  review: stage("review"), review_label: stage_label("review"; "Review incoming docs"),
  plan: stage("plan"), plan_label: stage_label("plan"; "Plan / Architect"),
  select: stage("select"), select_label: stage_label("select"; "Select story"),
  qa: stage("qa"), qa_label: stage_label("qa"; (if $qa_step == "qa_review" then "QA: CI review" elif $qa_step == "qa_repair" then "QA: test correction" else "QA: preparation" end)),
  implement: stage("implement"), implement_label: stage_label("implement"; "Implementation"),
  evaluate: stage("evaluate"), evaluate_label: stage_label("evaluate"; "Evaluation"),
  publish: stage("publish"), publish_label: stage_label("publish"; "Publish story"),
  ci: stage("ci"), ci_label: stage_label("ci"; "Application CI"),
  finalize: stage("finalize"), finalize_label: stage_label("finalize"; "Finalize story")
}]
