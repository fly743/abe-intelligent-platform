<#
.SYNOPSIS
    Score probe for score-threshold calibration: positive (qrels) vs negative
    (unanswerable) queries.

.DESCRIPTION
    For every query it calls GET /internal/rag/search?q=..&topK=K and records
    the top-1 score and the K-th score (the "floor").

    Then it simulates what a given score-threshold would do, using the live
    scores (the running service is still using its configured threshold):

        posShort   = positive cases whose 5th score < thr -> returns fewer than K
        posEmpty   = positive cases whose 1st score < thr -> retrieval empty,
                     which makes /ask refuse immediately (a false refusal)
        negBlocked = negative cases whose 1st score < thr -> retrieval empty,
                     which is a correct refusal

    A useful threshold needs negBlocked high while posEmpty and posShort stay
    at 0. If the two score distributions overlap, no single threshold works and
    refusal must be decided by the model (system prompt), not by the threshold.

.PARAMETER Base
    Java service base URL, default http://localhost:8080

.PARAMETER TopK
    K used for probing, default 5

.PARAMETER Qrels
    Positive cases, default <repo>\module-a-rag\evaluation\qrels.json

.PARAMETER Negatives
    Negative cases, default <repo>\module-a-rag\evaluation\negative-cases.json

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\probe-scores.ps1
#>
param(
    [string]$Base = 'http://localhost:8080',
    [int]$TopK = 5,
    [string]$Qrels = '',
    [string]$Negatives = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Qrels) { $Qrels = Join-Path $projectRoot 'module-a-rag\evaluation\qrels.json' }
if (-not $Negatives) { $Negatives = Join-Path $projectRoot 'module-a-rag\evaluation\negative-cases.json' }
if (-not (Test-Path -LiteralPath $Qrels)) { Write-Host "qrels not found: $Qrels" -ForegroundColor Red; exit 1 }
if (-not (Test-Path -LiteralPath $Negatives)) { Write-Host "negatives not found: $Negatives" -ForegroundColor Red; exit 1 }

function Invoke-TopK {
    param([string]$query, [int]$k)
    $enc = [uri]::EscapeDataString($query)
    $tmp = Join-Path $env:TEMP ('probe-' + [guid]::NewGuid().ToString('N') + '.json')
    try {
        $code = & curl.exe -sS -m 60 -o $tmp -w '%{http_code}' "$Base/internal/rag/search?q=$enc&topK=$k" 2>$null
        if ($code -ne '200') { throw "HTTP $code" }
        $parsed = [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($null -eq $parsed) { return @() }
        return @($parsed)
    }
    finally {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    }
}

function Get-Stat {
    param($docs)
    $scores = @($docs | ForEach-Object { [double]$_.score })
    if ($scores.Count -eq 0) {
        return [pscustomobject]@{ N = 0; Top1 = 0.0; Floor = 0.0 }
    }
    return [pscustomobject]@{
        N     = $scores.Count
        Top1  = ($scores | Measure-Object -Maximum).Maximum
        Floor = ($scores | Measure-Object -Minimum).Minimum
    }
}

function Get-Percentile {
    param($values, [double]$p)
    $s = @($values | Sort-Object)
    if ($s.Count -eq 0) { return 0.0 }
    return $s[[int][math]::Floor(($s.Count - 1) * $p)]
}

$qdata = [System.IO.File]::ReadAllText($Qrels, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$cases = @($qdata.cases)
$ndata = [System.IO.File]::ReadAllText($Negatives, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$negs = @($ndata)

$pos = @()
foreach ($c in $cases) {
    $st = Get-Stat (Invoke-TopK ([string]$c.q) $TopK)
    $pos += [pscustomobject]@{ Id = [string]$c.id; N = $st.N; Top1 = $st.Top1; Floor = $st.Floor }
}

$neg = @()
for ($i = 0; $i -lt $negs.Count; $i++) {
    $st = Get-Stat (Invoke-TopK ([string]$negs[$i]) $TopK)
    $neg += [pscustomobject]@{ Id = ("N{0:D2}" -f ($i + 1)); N = $st.N; Top1 = $st.Top1; Floor = $st.Floor }
}

$posTop1 = @($pos | ForEach-Object { $_.Top1 })
$posFloor = @($pos | ForEach-Object { $_.Floor })
$negTop1 = @($neg | ForEach-Object { $_.Top1 })
$negFloor = @($neg | ForEach-Object { $_.Floor })

Write-Host ''
Write-Host '=== POSITIVE (qrels) ===' -ForegroundColor Cyan
Write-Host ("cases={0}  top1[min={1:N3} p50={2:N3} max={3:N3}]  floor[min={4:N3} p50={5:N3} max={6:N3}]" -f `
        $pos.Count, (Get-Percentile $posTop1 0), (Get-Percentile $posTop1 0.5), (Get-Percentile $posTop1 1), `
        (Get-Percentile $posFloor 0), (Get-Percentile $posFloor 0.5), (Get-Percentile $posFloor 1))
Write-Host ''
Write-Host '=== NEGATIVE (unanswerable) ===' -ForegroundColor Cyan
Write-Host ("cases={0}  top1[min={1:N3} p50={2:N3} p90={3:N3} max={4:N3}]  floor[min={5:N3} p50={6:N3}]" -f `
        $neg.Count, (Get-Percentile $negTop1 0), (Get-Percentile $negTop1 0.5), (Get-Percentile $negTop1 0.9), (Get-Percentile $negTop1 1), `
        (Get-Percentile $negFloor 0), (Get-Percentile $negFloor 0.5))
Write-Host ''
Write-Host '=== THRESHOLD SIMULATION ===' -ForegroundColor Cyan
Write-Host ("{0,6}  {1,10}  {2,10}  {3,12}" -f 'thr', 'posShort', 'posEmpty', 'negBlocked')
foreach ($t in 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70) {
    $ps = @($pos | Where-Object { $_.Floor -lt $t }).Count
    $pe = @($pos | Where-Object { $_.Top1 -lt $t }).Count
    $nb = @($neg | Where-Object { $_.Top1 -lt $t }).Count
    $color = if ($pe -gt 0) { 'Red' } elseif ($nb -gt 0) { 'Yellow' } else { 'Gray' }
    Write-Host ("{0,6:N2}  {1,10}  {2,10}  {3,12}" -f $t, $ps, $pe, $nb) -ForegroundColor $color
}
Write-Host ''
Write-Host 'Legend: posShort = positive case loses snippets; posEmpty = positive case would be refused (bad); negBlocked = negative case correctly refused (good).' -ForegroundColor DarkGray

$overlapLo = (Get-Percentile $negTop1 0.9)
$overlapHi = (Get-Percentile $posTop1 0)
Write-Host ''
if ($overlapLo -lt $overlapHi) {
    Write-Host ("SEPARABLE: negative p90 ({0:N3}) < positive min ({1:N3}); a threshold in [{0:N3}, {1:N3}] works." -f $overlapLo, $overlapHi) -ForegroundColor Green
}
else {
    Write-Host ("NOT SEPARABLE: negative p90 ({0:N3}) >= positive min ({1:N3}). Keep a permissive threshold and rely on the model prompt for refusal." -f $overlapLo, $overlapHi) -ForegroundColor Yellow
}
