# Workflow visualization

Grafana provides the workflow visualization. It replaces the n8n execution view, which added technical overhead without showing the development process as intended. Execution stays in the Python workflow service.

From the repository root:

```powershell
docker compose -f workflow_monitor/compose.yaml up -d
.\workflow_monitor\configure.ps1
```

Open [GW2 Workflow Overview](http://localhost:3000/d/gw2-workflow-overview/gw2-workflow-overview). Configuration prompts for Grafana credentials and uses the service token. Keep the Docker volume to retain the setup.

See the [workflow service guide](../workflow_service/README.md) for operation and deployment.

Dashboard state labels and colors come from `dashboard-state.jq`. After editing it, run `python workflow_monitor/build_dashboard.py` and reimport with `configure.ps1 -ReplaceDashboard`. Check the state scenarios with `python -m unittest discover -s workflow_monitor -p test_dashboard_state.py` (requires the Python `jq` package).
