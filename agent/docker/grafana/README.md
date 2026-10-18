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

The QA box distinguishes preparation (`qa`), CI diagnosis (`qa_review`) and test correction (`qa_repair`). The stop-reason panel and recent events retain the last stopped story even after its active pointer is cleared. Open `UI-*` interventions and `UD-*` decisions appear with their explicit story associations and repository paths; they are not all necessarily blocking the current story. A service that does not expose request details is labeled as needing an update, rather than reporting that no requests exist. Deploy service changes in the agent worktree separately; provisioning Grafana does not upgrade or restart the workflow service.

See the [workflow service guide](../../../workflow_service/README.md) for execution and recovery.
