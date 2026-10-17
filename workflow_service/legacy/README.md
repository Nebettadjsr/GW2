# Legacy driver compatibility and migration

The supported entry point is now `workflow_service/start-service.cmd`, which starts the
Python scheduling loop. This page retains operating details for installations still using
n8n. The live installation is not switched by renaming files or merging this branch.

## Migration

1. Disable the n8n Development Pipeline and Claude Budget schedules. Let the current task finish
   and stop remaining waiting executions; do not launch another agent during deployment.
2. Stop the old service. Back up its `tasks.json` and preserve the token and uncommitted story work.
3. Deploy this change to the dedicated `GW2-agent` worktree through the normal maintainer merge.
   The package is now `workflow_service/`; the previous launcher path no longer exists. Do not
   discard dirty story files or run a feature checkout against live state.
4. Start `workflow_service/start-service.cmd --now`. The launcher requires the new package in the
   agent worktree. Existing environment variables, credentials and state are accepted as described
   in [configuration](../README.md#configuration).
5. Verify the active story/phase, current task and budget through the service API. Then stop the
   n8n container, retaining its data volume for rollback.

The `control` state replaces the previous `runner` field. The new service reads either name and
continues the saved run; story phases, counters, plans and budget settings remain intact.

## Compatibility mode

If needed before migration completes, run the new service with the old external driver:

```powershell
$env:GW2_WORKFLOW_DRIVER = 'legacy'
.\workflow_service\start-service.cmd
```

In this mode, `POST /cycle/step` accepts a signed local n8n Wait callback URL and optional budget
settings. `POST /tasks` accepts a prompt, agent name and callback URL for read-only connection
tests. `GW2_N8N_CALLBACK_ORIGIN` defaults to `http://localhost:5678`. These endpoints are rejected
while automatic Python execution owns the workflow.

Restore the normal mode by removing `GW2_WORKFLOW_DRIVER` from the launcher environment. Stop
one driver before starting the other. The old n8n budget refresh must also be disabled in normal
mode to avoid duplicate usage probes or replacement settings.

The [archived workflow exports](../workflows/) retain their historical names. They can be imported
into n8n for recovery. Do not restore an old state backup over newer completed work merely to
switch drivers; inspect and reconcile the current records instead.
