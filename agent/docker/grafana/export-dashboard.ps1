param(
    [PSCredential]$GrafanaCredential,
    [string]$GrafanaUrl = 'http://localhost:3000'
)

$ErrorActionPreference = 'Stop'
if (-not $GrafanaCredential) {
    $GrafanaCredential = Get-Credential -UserName admin -Message 'Local Grafana credentials'
}
$login = $GrafanaCredential.GetNetworkCredential()
$auth = $login.UserName + ':' + $login.Password
$headers = @{Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($auth))}
$response = Invoke-RestMethod -Uri ($GrafanaUrl.TrimEnd('/') + '/api/dashboards/uid/gw2-workflow-overview') -Headers $headers -TimeoutSec 30
$dashboard = $response.dashboard
if ($dashboard.uid -ne 'gw2-workflow-overview' -or -not $dashboard.panels) {
    throw 'Grafana did not return the expected workflow dashboard; the saved file was not changed.'
}
$dashboard.PSObject.Properties.Remove('id')
$dashboard.version = 1
$path = Join-Path $PSScriptRoot 'dashboards/workflow-overview.json'
[IO.File]::WriteAllText($path, ($dashboard | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
Write-Output 'Saved the current UI layout to agent/docker/grafana/dashboards/workflow-overview.json.'
