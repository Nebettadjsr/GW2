. as $snapshot |
(.latest_event // {outcome: .control.outcome, reason: .control.reason, proceed: false}) as $event |
(.running_task.status == "running" or .running_task.status == "queued" or .control.active == true) as $running |
(($running | not) and $event.proceed == false and ($event.outcome // "") != "" and $event.outcome != "queue_empty") as $stopped |
(if $stopped then [ {
  row_type: "activity", activity_at: ($event.at // $event.updated_at // $snapshot.control.finished_at),
  activity_role: ("Workflow / " + ($event.step // "control")),
  activity_kind: (if ($event.outcome | test("fail|error|conflict|missing|orphan|loop_guard|unknown"; "i")) then "ERROR" else "WAITING" end),
  activity_story: ($event.story_id // $snapshot.last_cycle.story_id // $snapshot.active_cycle.story_id // "Between stories"),
  activity_text: ("Stopped (" + $event.outcome + "): " + ([$event.error, $event.reason, "No reason recorded."] | map(select(. != null and . != "")) | first))
} ] else [] end) +
[ (.activity // [] | reverse | .[:60])[] |
  select(($stopped and .task_id == $event.task_id and (.kind == "error" or .kind == "waiting")) | not) |
  {row_type: "activity", activity_at: .at, activity_role: .role,
   activity_kind: (.kind | ascii_upcase), activity_text: .text,
   activity_story: (.story_id // "Between stories")} ] +
(if .activity == null then [{row_type: "activity", activity_at: null, activity_role: "Workflow",
  activity_kind: "SYSTEM", activity_story: "", activity_text: "Activity feed available after the updated workflow service is deployed."}]
 else [] end)
