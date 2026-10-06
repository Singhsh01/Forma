# FORMA one-command start for Windows (PowerShell 5.1 or 7+).
#
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1          build once, serve app + API on http://localhost:8080
#   powershell -ExecutionPolicy Bypass -File scripts\start.ps1 -Dev     engine on :8080 plus the Vite dev server on :5173
#
# Needs Java 21+ and Node 20+. The engine is built with the Maven Wrapper; if Maven cannot
# download, it falls back to plain javac, which needs nothing beyond the JDK.
param([switch]$Dev, [int]$Port = $(if ($env:FORMA_PORT) { [int]$env:FORMA_PORT } else { 8080 }))
# native tools (java, javac, npm, mvnw) report through exit codes and write notes to stderr, which
# Windows PowerShell 5.1 would turn into terminating errors under "Stop"; exit codes are checked instead
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Say($m) { Write-Host "[forma] $m" -ForegroundColor Yellow }
function Die($m) { Write-Host "[forma] $m" -ForegroundColor Red; exit 1 }

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Die "Java 21 or newer is required (java not found). Install a JDK 21, e.g. https://adoptium.net" }
$jv = (& java -XshowSettings:properties -version 2>&1 | Select-String "java.specification.version").ToString().Split("=")[1].Trim()
if ([int]($jv.Split(".")[0]) -lt 21) { Die "Java 21 or newer is required (found $jv)." }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Die "Node.js 20 or newer is required (node not found). https://nodejs.org" }
$nv = [int](& node -p "process.versions.node.split('.')[0]")
if ($nv -lt 20) { Die "Node.js 20 or newer is required." }

# ---- engine
# (written to also run under PowerShell 7 on macOS/Linux, which is how it was tested)
$sep = [IO.Path]::PathSeparator
$tmp = [IO.Path]::GetTempPath()
$onWindows = [IO.Path]::DirectorySeparatorChar -eq '\'
$cp = $null
$log = Join-Path $tmp "forma-maven.log"
if ($env:FORMA_SKIP_MAVEN -ne "1") {
  $mvnw = if ($onWindows) { Join-Path $Root "mvnw.cmd" } else { Join-Path $Root "mvnw" }
  & $mvnw -q -B -DskipTests package *> $log
  if ($LASTEXITCODE -eq 0) { $cp = (Join-Path "server" "target/forma-server.jar") + $sep + (Join-Path "engine" "target/forma-engine.jar"); Say "engine built with Maven" }
  else { Say "Maven build failed or could not download (see $log); compiling with javac instead" }
}
if (-not $cp) {
  $out = Join-Path (Join-Path $Root "build") "classes"
  if (Test-Path $out) { Remove-Item -Recurse -Force $out -ErrorAction Stop }
  New-Item -ItemType Directory -Force $out -ErrorAction Stop | Out-Null
  $sources = Get-ChildItem -Recurse -Filter *.java -Path (Join-Path $Root "engine/src/main/java"), (Join-Path $Root "server/src/main/java") | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
  $argfile = Join-Path $tmp "forma-sources.txt"
  Set-Content -Path $argfile -Value $sources -Encoding ascii
  & javac --release 21 -encoding UTF-8 -d $out "@$argfile"
  if ($LASTEXITCODE -ne 0) { Die "javac failed" }
  $cp = $out
  Say "engine compiled with javac into $out"
}

# ---- web
Set-Location (Join-Path $Root "web")
if (-not (Test-Path node_modules)) { Say "installing web dependencies (npm ci)"; & npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Die "npm ci failed" } }

if ($Dev) {
  Set-Location $Root
  Say "starting the engine on http://localhost:$Port (API) and the dev server on http://localhost:5173"
  $engine = Start-Process java -ArgumentList "-cp", $cp, "studio.forma.server.FormaServer", "--port", $Port -PassThru -NoNewWindow
  try { Set-Location (Join-Path $Root "web"); & npm run dev -- --port 5173 }
  finally { if ($engine -and -not $engine.HasExited) { Stop-Process -Id $engine.Id } }
  exit 0
}

Say "building the web app"
& npm run build | Out-Null
if ($LASTEXITCODE -ne 0) { Die "web build failed" }
Set-Location $Root
Say "open http://localhost:$Port  (Ctrl+C to stop)"
& java -cp $cp studio.forma.server.FormaServer --port $Port --web (Join-Path "web" "dist")
