param(
    [string]$ServiceUrl = 'http://172.29.240.1:8765',
    [string]$ServiceTokenPath,
    [ValidateRange(1, 65535)][int]$Port = 3000,
    [string]$ContainerName = 'gw2-grafana',
    [string]$DataVolume = 'gw2-workflow-grafana-data',
    [string]$ProjectName = 'gw2-workflow-monitor'
)

$ErrorActionPreference = 'Stop'
if (-not $ServiceTokenPath) {
    $ServiceTokenPath = Join-Path $env:USERPROFILE '.gw2-workflow-service/token.txt'
    if (-not (Test-Path -LiteralPath $ServiceTokenPath)) {
        $ServiceTokenPath = Join-Path $env:USERPROFILE '.gw2-claude-bridge/token.txt'
    }
}
$tokenPath = (Resolve-Path -LiteralPath $ServiceTokenPath -ErrorAction Stop).Path
if ([IO.File]::ReadAllText($tokenPath).Trim().Length -lt 32) {
    throw 'The workflow service token must contain at least 32 characters.'
}
$serviceUri = [Uri]$ServiceUrl
if (-not $serviceUri.IsAbsoluteUri -or $serviceUri.Scheme -notin @('http', 'https') -or
    $serviceUri.UserInfo -or $serviceUri.Query -or $serviceUri.Fragment -or
    $serviceUri.AbsolutePath -ne '/') {
    throw 'ServiceUrl must be an HTTP(S) origin, for example http://172.29.240.1:8765.'
}
$serviceRoot = $ServiceUrl.TrimEnd('/')
$dashboard = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dashboards/workflow-overview.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($panel in $dashboard.panels) {
    foreach ($target in $panel.targets) {
        if ($target.datasource.uid -eq 'gw2-workflow-state') {
            $target.url = $serviceRoot + '/cycle'
        }
    }
}
$dashboardDir = Join-Path $PSScriptRoot '.local/dashboards'
$null = New-Item -ItemType Directory -Force -Path $dashboardDir
[IO.File]::WriteAllText((Join-Path $dashboardDir 'workflow-overview.json'),
    ($dashboard | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))

# Only paths and connection settings go into Compose's environment, never the token.
$settings = @{
    GW2_WORKFLOW_TOKEN_FILE = $tokenPath
    GW2_WORKFLOW_SERVICE_URL = $serviceRoot
    GW2_GRAFANA_PORT = [string]$Port
    GW2_GRAFANA_CONTAINER_NAME = $ContainerName
    GW2_GRAFANA_VOLUME = $DataVolume
}
$previous = @{}
try {
    foreach ($key in $settings.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
    }
    & docker compose -f (Join-Path $PSScriptRoot 'compose.yaml') -p $ProjectName up -d --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit $LASTEXITCODE)." }
} finally {
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
}
Write-Output "Workflow dashboard: http://localhost:$Port/d/gw2-workflow-overview/gw2-workflow-overview"
