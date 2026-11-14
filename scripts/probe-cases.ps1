param(
    [string]$Base = "http://127.0.0.1:8080",
    [int]$TopK = 3
)

# Probe actual docId#section for each smoke case. Read-only (search only).
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $scriptDir) { $scriptDir = "D:\ABE-Program\scripts" }
$caseFile = Join-Path $scriptDir "rag-cases.json"

$cases = Get-Content $caseFile -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Host "probing $($cases.Count) cases against $Base`n" -ForegroundColor Cyan

$rows = @()
foreach ($c in $cases) {
    $enc = [uri]::EscapeDataString($c.q)
    $hits = @()
    try {
        $hits = curl.exe -sS -m 30 "$Base/internal/rag/search?q=$enc&topK=$TopK" | ConvertFrom-Json
    } catch {
        $hits = @()
    }
    $hits = @($hits)
    $top = $hits | Select-Object -First 1

    $rows += [pscustomobject]@{
        No       = $c.n
        Kind     = $c.k
        Expect   = $c.expect
        Hits     = $hits.Count
        Top1     = if ($top) { "$($top.docId)#$($top.section)" } else { "-" }
        Score    = if ($top) { "{0:N3}" -f $top.score } else { "" }
        Question = $c.q
    }
}

$rows | Format-Table No, Kind, Hits, Score, Top1, Expect -AutoSize -Wrap

Write-Host "`n--- 供改写 rag-cases.json 使用 ---" -ForegroundColor Cyan
foreach ($r in $rows) {
    Write-Host ("{0,-3} expect={1,-24} actual={2}" -f $r.No, $r.Expect, $r.Top1)
}
