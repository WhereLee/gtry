param(
    [string]$Base = "http://127.0.0.1:8091",
    [string]$Tenant = "acme",
    [string]$Email = "agent@acme.test",
    [string]$Password = "agent123"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Invoke-Json([string]$Method, [string]$Uri, $Payload, $Headers) {
    $params = @{ Method = $Method; Uri = $Uri; UseBasicParsing = $true }
    if ($Headers) {
        $params.Headers = $Headers
    }
    if ($null -ne $Payload) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Compress))
    }
    $response = Invoke-WebRequest @params
    $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    return $text | ConvertFrom-Json
}

$login = Invoke-Json "Post" "$Base/api/auth/login" @{
    tenantSlug = $Tenant; email = $Email; password = $Password
} $null
$headers = @{ Authorization = "Bearer $($login.token)" }
Write-Host "logged in as $($login.user.email) tenant=$($login.user.tenantSlug)"

$search = Invoke-Json "Post" "$Base/api/kb/search" @{ query = "拆封的耳机能退吗"; topK = 3 } $headers
Write-Host "`nknowledge hits:"
$search.hits | ForEach-Object { "  {0:N3}  {1} > {2}" -f $_.score, $_.pageTitle, ($_.headingPath -split ' > ')[-1] }

$ticket = Invoke-Json "Post" "$Base/api/tickets" @{
    subject = "拆封的耳机能退吗"
    description = "上周买的耳机拆封了，还能退吗？运费谁承担？"
} $headers
Write-Host "`nticket created: $($ticket.id)"

Write-Host "`nAI suggestion (SSE):"
curl.exe -s -N --max-time 60 -X POST -H "Authorization: Bearer $($login.token)" `
    "$Base/api/tickets/$($ticket.id)/ai-suggest"

Write-Host "`nusage:"
Invoke-Json "Get" "$Base/api/usage/summary" $null $headers | ConvertTo-Json
