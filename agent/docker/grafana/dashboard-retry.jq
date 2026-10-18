(.active_cycle // {}) as $c | (.running_task // {}) as $r |
(.retry_limits // {}) as $limits |
($r.step // $c.phase // (if .sync_review != null then "review" else "idle" end)) as $phase |
($r.status == "running" or $r.status == "queued") as $busy |
($c.retry_count // 0) as $coding | ($c.ci_fix_attempts // 0) as $ci |
($c.qa_repair_attempts // 0) as $qa | ($c.failed_runs // 0) as $failed |
(($c.history // []) | map(select(.outcome == "RETRY" or .outcome == "ci_production_retry" or .outcome == "qa_tests_corrected")) | last | .outcome) as $route |
def count($n; $limit): ($n | tostring) + (if $limit != null then " / " + ($limit | tostring) else "" end);
def retry_row($text): {retry_label: (if $busy then $text else "Pending: " + $text end), retry_active: 1};
if $busy and $r.current_retry != null then
  retry_row($r.current_retry.stage + " — retry " + count($r.current_retry.number; $r.current_retry.limit))
elif ($phase == "qa_review" or $phase == "qa_repair") and ($c.qa_action.invalid_attempts // 0) > 0 then
  retry_row("QA response correction — retry " + count($c.qa_action.invalid_attempts; $limits.qa_response))
elif $phase == "qa_repair" and $qa > 0 then
  retry_row("QA test correction — round " + count($qa; $limits.qa))
elif $phase == "implement" and $failed > 0 then
  retry_row("Claude process recovery — retry " + count($failed; $limits.claude))
elif $phase == "review" and (.sync_review.attempts // 0) > 0 then
  (if $busy then (.sync_review.attempts - 1) else .sync_review.attempts end) as $retry |
  if $retry > 0 then retry_row("Documentation review — retry " + count($retry; $limits.sync_review))
  else {retry_label: "No retry reported", retry_active: 0} end
elif $phase == "evaluate" and $route == "qa_tests_corrected" and $qa > 0 then
  retry_row("Evaluation after QA correction — round " + count($qa; $limits.qa))
elif (["implement", "evaluate", "publish", "ci"] | index($phase)) != null and ($coding > 0 or $ci > 0) then
  (if $route == "ci_production_retry" or ($coding == 0 and $ci > 0) then
    "CI production fix " + count($ci; $limits.ci)
   elif $route == null and $coding > 0 and $ci > 0 then
    "Rework: coding revisions " + ($coding | tostring) + "; CI fixes " + ($ci | tostring)
   else "Implementation revision " + count($coding; $limits.coding) end) as $round |
  retry_row($phase + " — " + $round)
else {retry_label: "No retry reported", retry_active: 0} end
