param(
    [string]$Base = "http://localhost:8080",
    [int]$TopK = 3,
    [switch]$ShowAnswer
)

# RAG ask smoke test. Questions live in rag-cases.json (UTF-8) to keep this file pure ASCII.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $scriptDir) { $scriptDir = "D:\ABE-Program\scripts" }
$caseFile = Join-Path $scriptDir "rag-cases.json"

if (-not (Test-Path $caseFile)) {
    Write-Host "cases file not found: $caseFile" -ForegroundColor Red
    exit 1
}

$cases = Get-Content $caseFile -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Host "loaded $($cases.Count) cases, base=$Base, topK=$TopK`n" -ForegroundColor Cyan

$rows = @()
foreach ($c in $cases) {
    $enc = [uri]::EscapeDataString($c.q)
    $r = $null
    try {
        $raw = curl.exe -sS -m 90 -X POST "$Base/internal/rag/ask?q=$enc&topK=$TopK"
        $r = $raw | ConvertFrom-Json
    } catch {
        $rows += [pscustomobject]@{
            No = $c.n; Kind = $c.k; Question = $c.q; Result = "ERR"
            Refused = ""; Cite = "request failed"; Invalid = ""; Ms = ""
        }
        continue
    }

    $citations = @($r.citations)
    $cite = ($citations | ForEach-Object { "$($_.docId)#$($_.section)" }) -join " | "
    $invalid = @($r.invalidRefs).Count

    $ok = $false
    if ($c.expect -eq "REFUSE") {
        $ok = [bool]$r.refused
    } else {
        $ok = (-not $r.refused) -and ($cite -like "*$($c.expect)*") -and ($invalid -eq 0)
    }

    $rows += [pscustomobject]@{
        No       = $c.n
        Kind     = $c.k
        Question = $c.q
        Result   = if ($ok) { "PASS" } else { "FAIL" }
        Refused  = $r.refused
        Cite     = if ($cite) { $cite } else { "-" }
        Invalid  = $invalid
        Ms       = $r.latencyMs
    }

    if ($ShowAnswer) {
        Write-Host "[$($c.n)] $($c.q)" -ForegroundColor DarkCyan
        Write-Host $r.answer
        Write-Host ""
    }
}

$rows | Format-Table No, Kind, Result, Refused, Invalid, Ms, Cite -AutoSize -Wrap

$pass = @($rows | Where-Object { $_.Result -eq "PASS" }).Count
$total = $rows.Count
$color = if ($pass -eq $total) { "Green" } elseif ($pass -ge [math]::Ceiling($total * 0.8)) { "Yellow" } else { "Red" }
Write-Host "`nPASS $pass / $total" -ForegroundColor $color

Write-Host "`n--- per-question detail ---" -ForegroundColor Cyan
$rows | Format-Table No, Question, Result, Cite -AutoSize -Wrap
