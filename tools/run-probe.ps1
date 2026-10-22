# Compile and run tools/Probe.java against the classpath produced by build-offline.ps1.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-probe.ps1
param(
    [string]$Jdk = 'C:\Users\111\tools\jdk25\jdk-25.0.4.1+1',
    [string]$Build = 'build'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$cpFile = Join-Path $root (Join-Path $Build 'classpath.txt')
if (-not (Test-Path $cpFile)) { throw "missing $cpFile - run build-offline.ps1 first" }

$javac = Join-Path $Jdk 'bin\javac.exe'
$java = Join-Path $Jdk 'bin\java.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

$cp = Get-Content $cpFile -Raw
$classes = Join-Path $root (Join-Path $Build 'classes')
$outDir = Join-Path $root 'tools\out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

& $javac -encoding UTF-8 -nowarn -d $outDir -cp "$cp;$classes" (Join-Path $root 'tools\Probe.java')
if ($LASTEXITCODE -ne 0) { throw "probe javac failed ($LASTEXITCODE)" }

& $java -cp "$cp;$classes;$outDir" Probe
if ($LASTEXITCODE -ne 0) { throw "probe failed ($LASTEXITCODE)" }
