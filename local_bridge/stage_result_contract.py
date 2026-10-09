"""Shared n8n parent stage-result normalization contract."""

NORMALIZER_JS = '''function normalizeStageResult(value) {
  const result = value && typeof value === "object" ? value : {};
  const executions = ["RUN", "REUSED", "SKIPPED", "STOPPED"];
  const error = result.error === undefined || result.error === null || result.error === ""
    ? null
    : result.error;
  return {
    ...result,
    stage: typeof result.stage === "string" && result.stage ? result.stage : "unknown",
    execution: executions.includes(result.execution) ? result.execution : "STOPPED",
    status: result.status ?? "UNKNOWN",
    decision: result.decision ?? result.status ?? "UNKNOWN",
    story_id: result.story_id ?? "",
    task_id: result.task_id ?? "",
    commit_sha: result.commit_sha ?? "",
    error,
    reason: result.reason ?? "",
    outstanding_requirements: Array.isArray(result.outstanding_requirements)
      ? result.outstanding_requirements
      : [],
    provenance: result.provenance ?? "unknown",
    record_source: result.record_source ?? "unknown",
    submission_disposition: result.submission_disposition ?? "unknown"
  };
}'''

REQUIRED_FIELDS = (
    "stage", "execution", "status", "decision", "story_id", "task_id",
    "commit_sha", "error", "reason", "outstanding_requirements", "provenance",
    "record_source", "submission_disposition",
)
