# Workflow monitor setup

Local Grafana OSS 13.2.4 installation for developing a Canvas view of the agent workflow. This setup is independent of n8n and the development pipeline. It does not run agents, replace the scheduler, or change pipeline state.

From the repository root:

```powershell
docker compose -f workflow_monitor/compose.yaml up -d
```

Open [Grafana](http://localhost:3000). The first login is `admin` / `admin`; Grafana asks for a new password. The server is bound to the local machine. The Compose file pins the downloaded image digest so later starts use the same image.

Canvas is included with Grafana. To start a view, create a dashboard, add a visualization, and select Canvas. Live workflow data has not been connected yet; this installation prepares the editor for designing that view together.

Dashboards, users, and settings are stored in the Docker volume `gw2-workflow-grafana-data`. Editing a dashboard in Grafana does not update this repository automatically. Export a dashboard's JSON when its design is ready to retain in Git.

To stop Grafana while retaining its data:

```powershell
docker compose -f workflow_monitor/compose.yaml down
```

See the [system architecture](../agent/docs/ARCHITECTURE.md) and [workflow diagrams](../agent/docs/WORKFLOW_DIAGRAMS.md) for the process the view should represent. Installation follows Grafana's [Docker guide](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/).
