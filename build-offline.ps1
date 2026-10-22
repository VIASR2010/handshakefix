# Build HandshakeFix offline against the jars of an installed NeoForge 26.2 instance.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File build-offline.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File build-offline.ps1 -Instance "D:\Downloads\.minecraft\versions\26.2-Fabric_0.19.3"
#
# Requires JDK 25: NeoForge 26.2 classes are class file version 69.
param(
    [string]$Instance = 'D:\Downloads\.minecraft\versions\26.2-Fabric_0.19.3',
    [string]$Jdk = 'C:\Users\111\tools\jdk25\jdk-25.0.4.1+1',
    [string]$Out = 'build'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$javac = Join-Path $Jdk 'bin\javac.exe'
$jarTool = Join-Path $Jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

$mc = Split-Path -Parent (Split-Path -Parent $Instance)
$libraries = Join-Path $mc 'libraries'
$versionJson = Get-ChildItem $Instance -Filter '*.json' |
    Where-Object { $_.Name -notlike '*.bak*' } |
    Select-Object -First 1
if (-not $versionJson) { throw "no version json in $Instance" }

$json = Get-Content $versionJson.FullName -Raw | ConvertFrom-Json
$cp = New-Object System.Collections.Generic.List[string]

foreach ($lib in $json.libraries) {
    $name = $lib.name
    if (-not $name) { continue }
    $parts = $name -split ':'
    if ($parts.Count -lt 3) { continue }
    $group = $parts[0] -replace '\.', '\'
    $artifact = $parts[1]
    $ver = $parts[2]
    $classifier = if ($parts.Count -ge 4) { $parts[3] } else { '' }

    $file = if ($classifier) { "$artifact-$ver-$classifier.jar" } else { "$artifact-$ver.jar" }
    $path = Join-Path $libraries (Join-Path (Join-Path $group $artifact) (Join-Path $ver $file))
    if (Test-Path $path) { $cp.Add($path) }
}

if ($cp.Count -eq 0) { throw "resolved no jars from $($versionJson.FullName)" }

# The version json normally does not list the NeoForge jar nor the patched client jar.
$extraCandidates = @(
    'net\neoforged\neoforge\*\neoforge-*-universal.jar',
    'net\neoforged\minecraft-client-patched\*\minecraft-client-patched-*.jar'
)
foreach ($pattern in $extraCandidates) {
    $found = Get-ChildItem (Join-Path $libraries $pattern) -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($found) { $cp.Insert(0, $found.FullName) }
}

Write-Host "classpath entries: $($cp.Count)"

# write the resolved classpath so tools/Probe.java can reuse it
New-Item -ItemType Directory -Force -Path $Out | Out-Null
($cp -join ';') | Set-Content -NoNewline -Encoding ASCII (Join-Path $Out 'classpath.txt')

$classes = Join-Path $Out 'classes'
$libs = Join-Path $Out 'libs'
Remove-Item -Recurse -Force $classes, $libs -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libs | Out-Null

$sources = Get-ChildItem 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "sources: $($sources.Count)"

& $javac -encoding UTF-8 --release 21 -nowarn -d $classes -cp ($cp -join ';') @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Copy-Item 'src\main\resources\*' $classes -Recurse -Force

# FML's TOML parser rejects a UTF-8 BOM ("Invalid bare key"), and Windows PowerShell
# writes one by default with Set-Content -Encoding UTF8. Fail loudly instead of shipping it.
$tomlOut = Join-Path (Join-Path (Get-Location).Path $classes) 'META-INF\neoforge.mods.toml'
$tomlBytes = [System.IO.File]::ReadAllBytes($tomlOut)
if ($tomlBytes.Length -ge 3 -and $tomlBytes[0] -eq 0xEF -and $tomlBytes[1] -eq 0xBB -and $tomlBytes[2] -eq 0xBF) {
    throw "META-INF/neoforge.mods.toml starts with a UTF-8 BOM; FML will reject the mod file"
}
Write-Host "mods.toml: no BOM, $($tomlBytes.Length) bytes"

$jarPath = Join-Path $libs 'handshakefix-1.1.0.jar'
& $jarTool --create --file $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }

Write-Host "built: $((Resolve-Path $jarPath).Path)"
