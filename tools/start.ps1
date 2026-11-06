param(
    [string]$Jar = "target\helpdesk-0.1.0.jar"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
New-Item -ItemType Directory -Force -Path (Join-Path $root "logs") | Out-Null

$localEnv = Join-Path $root ".env.local"
if (Test-Path $localEnv) {
    Get-Content $localEnv | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
        }
    }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $root "logs\app-$stamp.out.log"
$err = Join-Path $root "logs\app-$stamp.err.log"

Start-Process -FilePath "java" `
    -ArgumentList @("-jar", $Jar) `
    -RedirectStandardOutput $out `
    -RedirectStandardError $err `
    -WindowStyle Hidden

Write-Output $out
