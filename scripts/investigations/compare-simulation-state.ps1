param([int]$Pairs = 5, [int]$Recipes = 13000)
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
        $modes = if ($pair % 2 -eq 1) { @('false', 'true') } else { @('true', 'false') }
        foreach ($reuse in $modes) {
            if ((git rev-parse HEAD).Trim() -ne $baselineCommit) { throw 'Source changed during paired measurement.' }
            $runName = "pair-$pair-reuse-$reuse"
            $outputPath = Join-Path $repoRoot "quality-reports/optimization-20261026/state-paired-$Recipes/$runName"
            New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
            Write-Output "START $runName $(Get-Date -Format o)"
            & .\mvnw.cmd -q test '-Dtest=MultiAccountCapacityIT' '-DfailIfNoSpecifiedTests=false' '-Dgw2.investigation=true' '-Dgw2.investigation.pool-size=8' "-Dgw2.investigation.reuse-state=$reuse" "-Dgw2.investigation.recipes=$Recipes" "-Dgw2.perf.output-dir=$outputPath" *> (Join-Path $outputPath 'maven.log')
            if ($LASTEXITCODE -ne 0) { throw "Measurement failed: $runName; inspect maven.log" }
            Write-Output "PASS $runName $(Get-Date -Format o)"
        }
    }
} finally {
    Pop-Location
}
