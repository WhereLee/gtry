param(
    [string]$Base = "http://127.0.0.1:8091",
    [int]$Sample = 200,
    [switch]$Reseed
)

$ErrorActionPreference = "Stop"

function Invoke-Json([string]$Method, [string]$Uri, $Payload, $Headers) {
    $params = @{ Method = $Method; Uri = $Uri; UseBasicParsing = $true }
    if ($Headers) { $params.Headers = $Headers }
    if ($null -ne $Payload) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Compress))
    }
    $response = Invoke-WebRequest @params
    $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    return $text | ConvertFrom-Json
}

function Wait-Job([string]$Uri, $Headers, [int]$TimeoutSeconds = 900) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = Invoke-Json "Get" $Uri $null $Headers
        Write-Host ("  state={0} {1}/{2}" -f $status.state, $status.completed, $status.total)
        if ($status.state -in @("DONE", "FAILED")) { return $status }
        Start-Sleep -Seconds 5
    }
    throw "job timeout: $Uri"
}

$login = Invoke-Json "Post" "$Base/api/auth/login" @{
    tenantSlug = "platform"; email = "platform@helpdesk.test"; password = "platform123"
} $null
$headers = @{ Authorization = "Bearer $($login.token)" }

if ($Reseed) {
    Write-Host "seeding wiki..."
    Invoke-Json "Post" "$Base/api/admin/seed?force=true" $null $headers | Out-Null
    $seed = Wait-Job "$Base/api/admin/seed/status" $headers
    if ($seed.state -eq "FAILED") { throw $seed.message }
    $seed.result | ConvertTo-Json -Compress
}

Write-Host "running eval (sample=$Sample per tenant)..."
Invoke-Json "Post" "$Base/api/admin/eval?sample=$Sample" $null $headers | Out-Null
$eval = Wait-Job "$Base/api/admin/eval/status" $headers
if ($eval.state -eq "FAILED") { throw $eval.message }
$eval.result | ConvertTo-Json -Depth 8
