param(
    [PSCredential]$GrafanaCredential,
    [string]$GrafanaUrl = 'http://localhost:3000',
    [Alias('BridgeUrl')][string]$ServiceUrl = 'http://172.29.240.1:8765',
    [Alias('BridgeTokenPath')][string]$ServiceTokenPath,
    [switch]$ReplaceDashboard
)

$ErrorActionPreference = 'Stop'
if (-not $ServiceTokenPath) {
    $ServiceTokenPath = Join-Path $env:USERPROFILE '.gw2-workflow-service/token.txt'
    if (-not (Test-Path -LiteralPath $ServiceTokenPath)) {
        $ServiceTokenPath = Join-Path $env:USERPROFILE '.gw2-claude-bridge/token.txt'
    }
}
if (-not $GrafanaCredential) {
    $GrafanaCredential = Get-Credential -UserName admin -Message 'Local Grafana credentials'
}
$login = $GrafanaCredential.GetNetworkCredential()
$authText = $login.UserName + ':' + $login.Password
$apiHeaders = @{Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($authText))}
$apiRoot = $GrafanaUrl.TrimEnd('/')
$serviceRoot = $ServiceUrl.TrimEnd('/')

function Invoke-Grafana([string]$Method, [string]$Path, $Body) {
    $request = @{Uri = $apiRoot + $Path; Method = $Method; Headers = $apiHeaders; TimeoutSec = 30}
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = ConvertTo-Json -InputObject $Body -Depth 100
    }
    Invoke-RestMethod @request
}

function Find-GrafanaResource([string]$Path) {
    try { Invoke-Grafana GET $Path $null }
    catch {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
        return $null
    }
}

$source = @{
    name = 'GW2 Workflow State'
    uid = 'gw2-workflow-state'
    type = 'yesoreyeram-infinity-datasource'
    access = 'proxy'
    jsonData = @{
        auth_method = 'bearerToken'
        allowedHosts = @($serviceRoot)
        customHealthCheckEnabled = $true
        customHealthCheckUrl = $serviceRoot + '/health'
    }
    secureJsonData = @{bearerToken = [IO.File]::ReadAllText($ServiceTokenPath).Trim()}
}
if (Find-GrafanaResource '/api/datasources/uid/gw2-workflow-state') {
    $null = Invoke-Grafana PUT '/api/datasources/uid/gw2-workflow-state' $source
} else {
    $null = Invoke-Grafana POST '/api/datasources' $source
}
if (-not (Find-GrafanaResource '/api/folders/gw2-workflow')) {
    $null = Invoke-Grafana POST '/api/folders' @{uid = 'gw2-workflow'; title = 'GW2 workflow'}
}

$existing = Find-GrafanaResource '/api/dashboards/uid/gw2-workflow-overview'
if ($existing -and -not $ReplaceDashboard) {
    Write-Output 'Data source configured. Existing dashboard preserved; use -ReplaceDashboard to reimport the saved design.'
    return
}
$dashboardPath = Join-Path $PSScriptRoot 'dashboards/workflow-overview.json'
$dashboard = Get-Content -LiteralPath $dashboardPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($panel in $dashboard.panels) {
    foreach ($target in $panel.targets) { $target.url = $serviceRoot + '/cycle' }
}
$result = Invoke-Grafana POST '/api/dashboards/db' @{
    dashboard = $dashboard
    folderUid = 'gw2-workflow'
    overwrite = [bool]$ReplaceDashboard
    message = 'Configure read-only workflow Canvas'
}
Write-Output ('Dashboard ready: ' + $apiRoot + $result.url)
