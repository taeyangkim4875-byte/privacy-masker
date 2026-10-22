# PII masker - local run (Windows PowerShell)
#   .\run.ps1            start MariaDB (Docker) + build UI + start app
#   .\run.ps1 -NoDb      use your own MariaDB (e.g. dev server), skip Docker
#   .\run.ps1 -SkipUi    reuse the last UI build (faster backend restarts)
param([switch]$NoDb, [switch]$SkipUi)
# Continue: Windows PowerShell 5.1 turns native stderr (java -version) into errors under "Stop"
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

function Fail($msg) { Write-Host "[x] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg) { Write-Host "[ok] $msg" -ForegroundColor Green }

# 1. Java 21+
$javaVer = (cmd /c "java -version 2>&1" | Select-Object -First 1) -as [string]
if (-not $javaVer -or $javaVer -notmatch 'version') { Fail "Java not found. Install JDK 21 or 25 (e.g. Eclipse Temurin) and reopen the terminal." }
if ($javaVer -match '"(\d+)') { if ([int]$Matches[1] -lt 21) { Fail "JDK 21+ required. Found: $javaVer" } }
Ok "Java: $javaVer"

# 2. Node (only when building UI)
if (-not $SkipUi) {
    $nodeVer = (cmd /c "node -v 2>nul")
    if (-not $nodeVer) { Fail "Node.js not found. Install Node 22 LTS, or run with -SkipUi after one successful build." }
    Ok "Node: $nodeVer"
}

# 3. MariaDB in Docker
if (-not $NoDb) {
    & docker version *> $null
    if ($LASTEXITCODE -ne 0) { Fail "Docker is not running. Start Docker Desktop, or run with -NoDb to use your own MariaDB." }
    & docker compose up -d mariadb
    if ($LASTEXITCODE -ne 0) { Fail "docker compose up failed" }
    Write-Host "waiting for MariaDB..." -NoNewline
    for ($i = 0; $i -lt 60; $i++) {
        $h = (cmd /c "docker inspect -f {{.State.Health.Status}} piimask-mariadb 2>nul")
        if ($h -eq "healthy") { break }
        Start-Sleep -Seconds 2; Write-Host "." -NoNewline
    }
    Write-Host ""
    if ($h -ne "healthy") { Fail "MariaDB did not become healthy. Check: docker logs piimask-mariadb" }
    Ok "MariaDB ready on 127.0.0.1:13306 (db=stt user=masker pw=masker-local)"
}

# 4. App
$port = if ($env:PIIMASK_PORT) { $env:PIIMASK_PORT } else { "8080" }
Write-Host ""
Write-Host "Starting app... open http://127.0.0.1:$port when you see 'Started PiiMaskApplication'" -ForegroundColor Cyan
Write-Host ""
$gradleArgs = @(":masking-app:bootRun")
if ($SkipUi) { $gradleArgs += "-PskipUi" }
& .\gradlew.bat @gradleArgs
