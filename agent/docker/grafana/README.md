# Workflow visualization

Grafana visualizes the agent workflow by reading the Python workflow service. It is development tooling, separate from the GW2 application and its containers. Stopping Grafana does not stop execution or discard the service's workflow state.

From the repository root, with Docker and the workflow service already running:

```powershell
.\agent\docker\grafana\start.ps1
```

Open [GW2 Workflow Overview](http://localhost:3000/d/gw2-workflow-overview/gw2-workflow-overview). Existing Grafana credentials and settings stay in the `gw2-workflow-grafana-data` Docker volume. A fresh volume uses Grafana's initial `admin` / `admin` login and prompts for a new password. Do not delete that volume to restart or recreate the container.

The image is pinned to the installed **Grafana 13.2.4** digest in `compose.yaml`; Infinity is pinned to **4.1.1**. The startup script prepares a local dashboard copy with the configured service URL, then creates or starts the container. Grafana provisions the saved dashboard and authenticated data source on every container start, including after a Docker restart. No manual dashboard import or Grafana API credentials are needed for setup.

The script uses the existing workflow service token file, falling back to its older location. Override a different installation with `-ServiceUrl http://HOST:8765 -ServiceTokenPath C:\path\token.txt`. That address must be reachable from the container. The token is mounted read-only as a Docker secret; its contents are not written into Git or the dashboard. Generated `.local/` files are ignored.

## Save UI edits

The checked-in dashboard includes the maintainer's current layout. Further UI edits are saved in Grafana's volume. To also keep them in Git and reproduce them in a fresh container, export before updating the provisioning files:

```powershell
.\agent\docker\grafana\export-dashboard.ps1
```

This prompts for Grafana credentials and updates `dashboards/workflow-overview.json`. Commit that file, then run `start.ps1` to refresh the startup copy. Grafana allows UI edits, but a changed provisioning file overwrites its database copy; UI saves do not automatically update Git. See [Grafana's provisioning behavior](https://grafana.com/docs/grafana/latest/administration/provisioning/#make-changes-to-a-provisioned-dashboard).

State labels and colors use `dashboard-state.jq`. After changing that projection, run `python agent/docker/grafana/build_dashboard.py`; it updates queries while preserving the saved layout. Run its state scenarios with `python -m unittest discover -s agent/docker/grafana -p test_dashboard_state.py` (requires the Python `jq` package).

The QA box distinguishes preparation (`qa`), CI diagnosis (`qa_review`) and test correction (`qa_repair`). The scrollable **Agent activity** feed replaces the separate stop-reason and recent-events panels. It shows emitted narration and CLI summaries, without tool commands, file output or structured result JSON. Newest messages appear first; a stopping error or wait reason stays at the top, including after a blocked story's active pointer is cleared. This is emitted progress, not a complete internal reasoning trace, and quiet periods do not prove that an agent is stuck.

Open `UI-*` interventions and `UD-*` decisions retain their separate list with story references and repository paths. An older service is explicitly labeled as needing an update. Deploy service changes in the agent worktree separately; provisioning Grafana does not upgrade or restart the workflow service.

The retry/correction counter identifies the current validation retry or story rework round: coding revisions, CI production fixes, QA test corrections and Claude process recovery are separate counts. Completed rework is not labeled as a retry of finalization; a waiting follow-up is marked pending. Repeated budget checks do not count as retries. Updated services expose configured limits and inner Planner/Evaluator/QA validation retries; older services show the available cycle counts without inventing a limit.

All panels share the Canvas panel's single `/cycle` query through Grafana's Dashboard data source. Each five-second refresh fetches one snapshot per open dashboard; panels filter its status, activity and request rows locally. This reads cached budget status and does not invoke agents. Successful status GETs log at DEBUG after the service is updated; failed requests and control actions remain visible at normal log levels.

The **Operator controls** canvas shows hold state, schedule mode and running agent processes, with one-click buttons: Pause, Resume, Start run, Abort (asks first), Schedule automatic/manual and Open workflow window. (Stat panels offer no actions, so these are canvas elements.) Each button is an Infinity action sent server-side by the data source with its bearer token, which is never in the dashboard: control commands as `POST /control` to the service, *Open workflow window* as `POST /launch` to the supervisor on port 8766 (`workflow_service\start-supervisor.cmd`, the only part that works while the service is down). `start.ps1` rewrites both addresses in the local copy and allows both hosts in the data source. `workflow_service\ctl.cmd` offers the same commands without Grafana.

See the [workflow service guide](../../../workflow_service/README.md) for execution and recovery.
