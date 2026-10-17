# Workflow monitor setup

Local Grafana OSS 13.2.4 installation with a read-only Canvas view of the agent workflow. It reads the Python service's status API and works with either the legacy n8n driver or the new Python runner. It does not run agents, replace the scheduler, or change pipeline state. See the [architecture overview](../agent/docs/ARCHITECTURE.md#execution-and-visibility) for how execution and monitoring fit together.

From the repository root:

```powershell
docker compose -f workflow_monitor/compose.yaml up -d
```

Open [Grafana](http://localhost:3000). The first login is `admin` / `admin`; Grafana asks for a new password. The server is bound to the local machine. The Compose file pins the downloaded image digest so later starts use the same image.

Open the [GW2 Workflow Overview](http://localhost:3000/d/gw2-workflow-overview/gw2-workflow-overview). It shows current activity, the active story, a Canvas of the cycle stages, and recent active-story events. It refreshes every five seconds. The Infinity data-source plugin is pinned in Compose and reads `GET /cycle`. Switching to the Python runner retains that endpoint, so the dashboard does not need a new data source.

Colors describe the current task and active-story history: blue is running, green is a completed stage, amber is waiting, red needs attention, and gray has no recorded result. A completed coding stage is not a correctness verdict. Conditional stages may not run for each story; the `plan` stage can invoke either Planner or Architect. The current API does not expose completed/blocked story histories once there is no active cycle, so this first view is not a project-wide history dashboard. A bridge connection error appears as a query error, not a new workflow failure.

Edit the Canvas directly in Grafana. The diagram's arrows only illustrate transitions and never execute them. The layout is maintained manually: adding or renaming a workflow stage can require a dashboard edit. This dashboard has no pipeline-control actions or budget panels; the service's budget and runner fields are available for future panels. Stopping Grafana does not stop the Python workflow. If the workflow service stops, Grafana loses its status source.

To configure a fresh Grafana instance after starting Compose, run this from the repository root:

```powershell
.\workflow_monitor\configure.ps1
```

The script prompts for your Grafana credentials. It reads the existing bridge token from `%USERPROFILE%/.gw2-claude-bridge/token.txt` and stores it in Grafana's encrypted data-source credentials; it does not put tokens in the dashboard JSON or Git. Parameters let you change the Grafana URL, bridge URL, or token file. Rerunning it preserves an existing dashboard; `-ReplaceDashboard` explicitly reimports the saved design and overwrites UI edits.

Dashboards, users, plugins, and settings are stored in the Docker volume `gw2-workflow-grafana-data`. Editing a dashboard in Grafana does not update this repository automatically. Export the dashboard JSON to `workflow_monitor/dashboards/workflow-overview.json` when its design is ready to retain in Git.

To stop Grafana while retaining its data:

```powershell
docker compose -f workflow_monitor/compose.yaml down
```

See the [system architecture](../agent/docs/ARCHITECTURE.md) and [workflow diagrams](../agent/docs/WORKFLOW_DIAGRAMS.md) for the process the view should represent. Installation follows Grafana's [Docker guide](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/).
