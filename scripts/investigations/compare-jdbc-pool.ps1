param([int]$Pairs = 5)
$ErrorActionPreference = 'Stop'
if ($env:DATABASE_URL -notmatch '^jdbc:postgresql://127\.0\.0\.1:\d+/investigation$' -or $env:DATABASE_USER -ne 'investigation') {
    throw 'Set explicit isolated investigation database environment before running.'
}
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$baselineCommit = (git -C $repoRoot rev-parse HEAD).Trim()
$env:JAVA_TOOL_OPTIONS = '-Xmx2g'
$env:PATH = 'C:\Windows\System32\WindowsPowerShell\v1.0;' + $env:PATH
Push-Location (Join-Path $repoRoot 'backend')
try {
    for ($pair = 1; $pair -le $Pairs; $pair++) {
        $modes = if ($pair % 2 -eq 1) { @(0, 8) } else { @(8, 0) }
        foreach ($poolSize in $modes) {
            if ((git rev-parse HEAD).Trim() -ne $baselineCommit) { throw 'Source changed during paired measurement.' }
            $runName = "pair-$pair-pool-$poolSize"
            $outputPath = Join-Path $repoRoot "quality-reports/optimization-20261026/paired/$runName"
            New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
            Write-Output "START $runName $(Get-Date -Format o)"
            & .\mvnw.cmd -q test '-Dtest=MultiAccountCapacityIT' '-DfailIfNoSpecifiedTests=false' '-Dgw2.investigation=true' "-Dgw2.investigation.pool-size=$poolSize" "-Dgw2.perf.output-dir=$outputPath" *> (Join-Path $outputPath 'maven.log')
            if ($LASTEXITCODE -ne 0) { throw "Measurement failed: $runName; inspect maven.log" }
            Write-Output "PASS $runName $(Get-Date -Format o)"
        }
    }
} finally {
    Pop-Location
}
