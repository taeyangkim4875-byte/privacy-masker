# privacy-masker - local run (Windows PowerShell 5.1+)
#   .\run.ps1             MariaDB(Docker if running, otherwise portable) + UI build + app
#   .\run.ps1 -Portable   always use portable MariaDB (no install, lives in .local\)
#   .\run.ps1 -NoDb       use your own MariaDB (e.g. dev server)
#   .\run.ps1 -SkipUi     reuse the last UI build
#   .\run.ps1 -ResetDb    delete the local portable DB data and reload the fake seed
param([switch]$NoDb, [switch]$SkipUi, [switch]$Portable, [switch]$ResetDb)
# Continue: Windows PowerShell 5.1 turns native stderr into errors under "Stop"
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"
Set-Location $PSScriptRoot

function Fail($msg) { Write-Host "[x] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg) { Write-Host "[ok] $msg" -ForegroundColor Green }
function Info($msg) { Write-Host "[..] $msg" -ForegroundColor Cyan }

# ---------------------------------------------------------------
# Local DB settings (local test only, bound to 127.0.0.1)
# ---------------------------------------------------------------
$DbPort = 13306
$RootPw = "root-local-only"
$AppUser = "masker"
$AppPw = "masker-local"

# Portable MariaDB (official Windows zip, verified by SHA-256)
$MdbVersion = "11.4.13"
$MdbZipName = "mariadb-$MdbVersion-winx64.zip"
$MdbUrl = "https://downloads.mariadb.org/rest-api/mariadb/$MdbVersion/$MdbZipName"
$MdbSha256 = "d62986d433eeebfde218560b276103831604a61e929e87f1a17f5aebd80257e2"
$LocalRoot = if ($env:PIIMASK_DB_HOME) { $env:PIIMASK_DB_HOME } else { Join-Path $PSScriptRoot ".local" }
$MdbHome = Join-Path $LocalRoot "mariadb-$MdbVersion-winx64"
$MdbData = Join-Path $LocalRoot "mariadb-data"
$MdbBin = Join-Path $MdbHome "bin"

# ---------------------------------------------------------------
# 1. Java 21+
# ---------------------------------------------------------------
$javaVer = (cmd /c "java -version 2>&1" | Select-Object -First 1) -as [string]
if (-not $javaVer -or $javaVer -notmatch 'version') { Fail "Java not found. Install JDK 21 or 25 (e.g. Eclipse Temurin) and reopen the terminal." }
if ($javaVer -match '"(\d+)') { if ([int]$Matches[1] -lt 21) { Fail "JDK 21+ required. Found: $javaVer" } }
Ok "Java: $javaVer"

# ---------------------------------------------------------------
# 2. Node (only when building UI)
# ---------------------------------------------------------------
if (-not $SkipUi) {
    $nodeVer = (cmd /c "node -v 2>nul")
    if (-not $nodeVer) { Fail "Node.js not found. Install Node 22 LTS, or run with -SkipUi after one successful build." }
    Ok "Node: $nodeVer"
}

# ---------------------------------------------------------------
# 3. Pick DB mode
# ---------------------------------------------------------------
$mode = "portable"
if ($NoDb) {
    $mode = "none"
} elseif (-not $Portable -and (Get-Command docker -ErrorAction SilentlyContinue)) {
    cmd /c "docker version >nul 2>nul"
    if ($LASTEXITCODE -eq 0) { $mode = "docker" } else { Info "Docker is installed but not running. Using portable MariaDB instead." }
}

$ClientArgs = @("--user=root", "--password=$RootPw", "--host=127.0.0.1", "--port=$DbPort")

# Feeds a .sql file byte-for-byte through stdin (no PowerShell re-encoding)
function Invoke-SqlFile([string]$database, [string]$sqlFile) {
    $client = Join-Path $MdbBin "mariadb.exe"
    $p = Start-Process -FilePath $client -ArgumentList ($ClientArgs + @("--default-character-set=utf8mb4", $database)) `
        -RedirectStandardInput $sqlFile -NoNewWindow -Wait -PassThru
    return $p.ExitCode
}

function Invoke-Sql([string]$sql) {
    $client = Join-Path $MdbBin "mariadb.exe"
    & $client @ClientArgs "--default-character-set=utf8mb4" -e $sql | Out-Host
    return $LASTEXITCODE
}

function Test-Ping {
    $admin = Join-Path $MdbBin "mariadb-admin.exe"
    & $admin @ClientArgs "--connect-timeout=2" ping 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Stop-LocalDb {
    $admin = Join-Path $MdbBin "mariadb-admin.exe"
    & $admin @ClientArgs shutdown 2>$null | Out-Null
}

$mdbProc = $null

if ($mode -eq "docker") {
    & docker compose up -d mariadb
    if ($LASTEXITCODE -ne 0) { Fail "docker compose up failed" }
    Write-Host "waiting for MariaDB (Docker)..." -NoNewline
    $h = ""
    for ($i = 0; $i -lt 60; $i++) {
        $h = (cmd /c "docker inspect -f {{.State.Health.Status}} piimask-mariadb 2>nul")
        if ($h -eq "healthy") { break }
        Start-Sleep -Seconds 2; Write-Host "." -NoNewline
    }
    Write-Host ""
    if ($h -ne "healthy") { Fail "MariaDB did not become healthy. Check: docker logs piimask-mariadb" }
}

if ($mode -eq "portable") {
    New-Item -ItemType Directory -Force -Path $LocalRoot | Out-Null

    # 3-1. download + verify + extract (first run only)
    if (-not (Test-Path (Join-Path $MdbBin "mariadbd.exe"))) {
        $zip = Join-Path $LocalRoot $MdbZipName
        if (-not (Test-Path $zip)) {
            Info "Downloading MariaDB $MdbVersion (about 80MB, first run only)..."
            & curl.exe -fL --retry 3 -o $zip $MdbUrl
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $zip)) {
                try { Invoke-WebRequest -Uri $MdbUrl -OutFile $zip -UseBasicParsing } catch { Fail "MariaDB download failed: $($_.Exception.Message)" }
            }
        }
        $hash = (Get-FileHash -Algorithm SHA256 -Path $zip).Hash.ToLower()
        if ($hash -ne $MdbSha256) {
            Remove-Item $zip -Force
            Fail "MariaDB zip checksum mismatch (got $hash). The file was removed; run again to re-download."
        }
        Ok "MariaDB zip verified (SHA-256)"
        Info "Extracting MariaDB..."
        & tar.exe -xf $zip -C $LocalRoot
        if (-not (Test-Path (Join-Path $MdbBin "mariadbd.exe"))) {
            Expand-Archive -Path $zip -DestinationPath $LocalRoot -Force
        }
        if (-not (Test-Path (Join-Path $MdbBin "mariadbd.exe"))) { Fail "Extract failed: $MdbBin\mariadbd.exe not found" }
        Remove-Item $zip -Force
        Ok "MariaDB extracted to $MdbHome"
    }

    # 3-2. reset data if asked
    if ($ResetDb -and (Test-Path $MdbData)) {
        if (Test-Ping) { Stop-LocalDb; Start-Sleep -Seconds 3 }
        Remove-Item $MdbData -Recurse -Force
        Ok "Local DB data removed"
    }

    # 3-3. initialize data dir (first run only)
    if (-not (Test-Path (Join-Path $MdbData "my.ini"))) {
        Info "Initializing local database..."
        $installDb = Join-Path $MdbBin "mariadb-install-db.exe"
        if (-not (Test-Path $installDb)) { $installDb = Join-Path $MdbBin "mysql_install_db.exe" }
        & $installDb "--datadir=$MdbData" "--port=$DbPort" "--password=$RootPw" | Out-Host
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $MdbData "my.ini"))) {
            Fail "mariadb-install-db failed. If the path has non-English characters, set PIIMASK_DB_HOME=C:\piimask-local and retry."
        }
    }

    # 3-4. start server (unless one is already answering on the port)
    if (Test-Ping) {
        Ok "Local MariaDB already running on 127.0.0.1:$DbPort"
    } else {
        $mysqld = Join-Path $MdbBin "mariadbd.exe"
        $iniArg = "`"--defaults-file=$(Join-Path $MdbData 'my.ini')`""
        $errArg = "`"--log-error=$(Join-Path $MdbData 'mariadb.err')`""
        $mdbProc = Start-Process -FilePath $mysqld -ArgumentList @($iniArg, "--bind-address=127.0.0.1", $errArg) -WindowStyle Hidden -PassThru
        Write-Host "waiting for MariaDB (portable)..." -NoNewline
        $up = $false
        for ($i = 0; $i -lt 45; $i++) {
            if (Test-Ping) { $up = $true; break }
            if ($mdbProc.HasExited) { break }
            Start-Sleep -Seconds 1; Write-Host "." -NoNewline
        }
        Write-Host ""
        if (-not $up) { Fail "MariaDB did not start. See $MdbData\mariadb.err" }
    }

    # 3-5. create db/user + load fake seed (first run only)
    $seededMark = Join-Path $MdbData ".seeded"
    if (-not (Test-Path $seededMark)) {
        Info "Creating database 'stt' and user '$AppUser', loading fake STT/TTS history..."
        $sql = "CREATE DATABASE IF NOT EXISTS stt CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; " +
               "CREATE USER IF NOT EXISTS '$AppUser'@'localhost' IDENTIFIED BY '$AppPw'; " +
               "CREATE USER IF NOT EXISTS '$AppUser'@'127.0.0.1' IDENTIFIED BY '$AppPw'; " +
               "GRANT ALL PRIVILEGES ON stt.* TO '$AppUser'@'localhost'; " +
               "GRANT ALL PRIVILEGES ON stt.* TO '$AppUser'@'127.0.0.1'; FLUSH PRIVILEGES;"
        if ((Invoke-Sql $sql) -ne 0) { Fail "Failed to create database/user" }
        foreach ($f in @("01-schema.sql", "02-seed.sql")) {
            $path = Join-Path $PSScriptRoot "docker\mariadb\init\$f"
            if ((Invoke-SqlFile "stt" $path) -ne 0) { Fail "Failed to load $f" }
        }
        Set-Content -Path $seededMark -Value (Get-Date -Format s)
        Ok "Seed loaded"
    }
}

if ($mode -ne "none") {
    Ok "MariaDB ready on 127.0.0.1:$DbPort  (db=stt  user=$AppUser  pw=$AppPw)  [$mode]"
}

# ---------------------------------------------------------------
# 4. App
# ---------------------------------------------------------------
$port = if ($env:PIIMASK_PORT) { $env:PIIMASK_PORT } else { "8080" }
Write-Host ""
Write-Host "Starting app... open http://127.0.0.1:$port when you see 'Started PiiMaskApplication'" -ForegroundColor Cyan
Write-Host "Stop with Ctrl+C." -ForegroundColor Cyan
Write-Host ""
$gradleArgs = @(":masking-app:bootRun")
if ($SkipUi) { $gradleArgs += "-PskipUi" }
try {
    & .\gradlew.bat @gradleArgs
} finally {
    if ($mode -eq "portable" -and $mdbProc -ne $null) {
        Info "Stopping local MariaDB..."
        Stop-LocalDb
        Start-Sleep -Seconds 2
        if (-not $mdbProc.HasExited) { Stop-Process -Id $mdbProc.Id -Force -ErrorAction SilentlyContinue }
    }
}
