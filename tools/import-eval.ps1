param(
    [string]$Base = "http://127.0.0.1:8091",
    [string]$Tenant = "acme",
    [string]$Email = "admin@acme.test",
    [string]$Password = "admin123",
    [string]$Csv = "docs\真实盲测模板.csv",
    [switch]$Replace
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Invoke-Json([string]$Method, [string]$Uri, $Payload, $Headers) {
    $params = @{ Method = $Method; Uri = $Uri; UseBasicParsing = $true }
    if ($Headers) { $params.Headers = $Headers }
    if ($null -ne $Payload) {
        $params.ContentType = "application/json; charset=utf-8"
        # -InputObject keeps a single-element array serialized as [ ... ] instead of a bare object.
        $json = ConvertTo-Json -InputObject $Payload -Depth 5 -Compress
        $params.Body = [Text.Encoding]::UTF8.GetBytes($json)
    }
    $response = Invoke-WebRequest @params
    $text = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    return $text | ConvertFrom-Json
}

$rows = Import-Csv -LiteralPath $Csv -Encoding UTF8
$questions = @()
foreach ($row in $rows) {
    if ([string]::IsNullOrWhiteSpace($row.question) -or [string]::IsNullOrWhiteSpace($row.pageId)) {
        continue
    }
    $questions += @{ question = $row.question.Trim(); pageId = $row.pageId.Trim() }
}
if ($questions.Count -eq 0) {
    throw "CSV 中没有有效行"
}

$login = Invoke-Json "Post" "$Base/api/auth/login" @{
    tenantSlug = $Tenant; email = $Email; password = $Password
} $null
$headers = @{ Authorization = "Bearer $($login.token)" }

$replaceValue = if ($Replace) { "true" } else { "false" }
$result = Invoke-Json "Post" "$Base/api/wiki/questions/import?replace=$replaceValue" @($questions) $headers
$result | ConvertTo-Json -Compress
