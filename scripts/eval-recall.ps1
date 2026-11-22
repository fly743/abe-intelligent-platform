<#
.SYNOPSIS
    Recall@K / Hit@K evaluation for the ABE RAG retrieval endpoint.

.DESCRIPTION
    Reads module-a-rag\evaluation\qrels.json and calls
        GET <Base>/internal/rag/search?q=<query>&topK=<K>
    for every case, then compares the returned snippets with the qrels targets.

    A target counts as hit when a returned snippet satisfies BOTH:
        snippet.docId  == target.doc
        snippet.section contains target.section

    Metrics reported:
        Recall@K  = sum(hits) / sum(targets)     (micro, main number)
        MeanRecall@K = average per-case recall
        Hit@K     = share of cases with at least one target found
        Returned  = average number of snippets the endpoint actually returned

    Note: the endpoint applies abe.rag.retrieve.score-threshold (0.15 by
    default), so a query can return fewer than K snippets. Watch the
    "ret<K" counter: if it is large, the threshold is truncating results and
    the Recall@K number is being depressed by filtering, not by ranking.

.PARAMETER Base
    Java service base URL, default http://localhost:8080

.PARAMETER TopK
    K used for Recall@K, default 5

.PARAMETER Qrels
    qrels file, default <repo>\module-a-rag\evaluation\qrels.json

.PARAMETER Report
    Report path. Default: module-a-rag\evaluation\reports\recall-at-<K>-<timestamp>.json

.PARAMETER NoReport
    Print only, do not write a JSON report.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\eval-recall.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\eval-recall.ps1 -TopK 3 -NoReport
#>
param(
    [string]$Base = 'http://localhost:8080',
    [int]$TopK = 5,
    [string]$Qrels = '',
    [string]$Report = '',
    [switch]$NoReport
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Qrels) { $Qrels = Join-Path $projectRoot 'module-a-rag\evaluation\qrels.json' }
if (-not (Test-Path -LiteralPath $Qrels)) {
    Write-Host "qrels not found: $Qrels" -ForegroundColor Red
    exit 1
}
$Qrels = (Resolve-Path -LiteralPath $Qrels).Path

function Invoke-Search {
    param([string]$query)
    $enc = [uri]::EscapeDataString($query)
    $tmp = Join-Path $env:TEMP ('recall-' + [guid]::NewGuid().ToString('N') + '.json')
    try {
        $code = & curl.exe -sS -m 60 -o $tmp -w '%{http_code}' "$Base/internal/rag/search?q=$enc&topK=$TopK" 2>$null
        if ($code -ne '200') { throw "HTTP $code" }
        $json = [System.IO.File]::ReadAllText($tmp, [System.Text.Encoding]::UTF8)
        $parsed = $json | ConvertFrom-Json
        if ($null -eq $parsed) { return @() }
        return @($parsed)
    }
    finally {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    }
}

function Test-Hit {
    param($snippet, $target)
    if ($null -eq $snippet) { return $false }
    $docOk = ([string]$snippet.docId).Equals([string]$target.doc, [System.StringComparison]::OrdinalIgnoreCase)
    if (-not $docOk) { return $false }
    $sec = [string]$snippet.section
    if ([string]::IsNullOrEmpty($sec)) { return $false }
    return $sec.Contains([string]$target.section)
}

function Format-Ref {
    param($snippet)
    $t = [string]$snippet.docId + '#' + [string]$snippet.section
    if ($t.Length -gt 44) { $t = $t.Substring(0, 44) + '..' }
    return $t
}

# ---- health gate -----------------------------------------------------------
$tmpHealth = Join-Path $env:TEMP ('recall-health-' + [guid]::NewGuid().ToString('N') + '.json')
$code = & curl.exe -sS -m 10 -o $tmpHealth -w '%{http_code}' "$Base/internal/rag/health" 2>$null
if (Test-Path -LiteralPath $tmpHealth) {
    $healthRaw = [System.IO.File]::ReadAllText($tmpHealth, [System.Text.Encoding]::UTF8)
    Remove-Item -LiteralPath $tmpHealth -Force -ErrorAction SilentlyContinue
}
if ($code -ne '200') {
    Write-Host "health check failed (HTTP $code) at $Base" -ForegroundColor Red
    Write-Host 'Start the Java service first (module-d-integration spring-boot:run).'
    exit 1
}
$health = $healthRaw | ConvertFrom-Json
Write-Host ("health: collection={0} points={1} status={2}" -f $health.collection, $health.points, $health.status) -ForegroundColor Cyan
if ($health.points -eq 0) {
    Write-Host 'collection is empty: run POST /internal/rag/ingest?recreate=true first.' -ForegroundColor Yellow
}

# ---- evaluate --------------------------------------------------------------
$data = [System.IO.File]::ReadAllText($Qrels, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$cases = @($data.cases)
Write-Host ("qrels: {0} cases, topK={1}`n" -f $cases.Count, $TopK) -ForegroundColor Cyan

$rows = @()
$allTargets = 0
$allHits = 0
$emptyCount = 0
$shortCount = 0

foreach ($c in $cases) {
    $targets = @($c.relevant)
    $allTargets += $targets.Count

    $docs = @()
    $err = ''
    try {
        $docs = @(Invoke-Search $c.q)
    }
    catch {
        $err = $_.Exception.Message
    }

    if ($docs.Count -eq 0) { $emptyCount++ }
    if ($docs.Count -lt $TopK) { $shortCount++ }

    $hits = 0
    if (-not $err) {
        foreach ($t in $targets) {
            $found = $docs | Where-Object { Test-Hit $_ $t } | Select-Object -First 1
            if ($found) { $hits++ }
        }
    }
    $allHits += $hits

    $recall = if ($targets.Count -gt 0) { $hits / [double]$targets.Count } else { 0.0 }
    $top1 = if ($docs.Count -gt 0) { Format-Ref $docs[0] } else { '-' }
    $top1Score = if ($docs.Count -gt 0) { [double]$docs[0].score } else { 0.0 }

    $rows += [pscustomobject]@{
        Id     = [string]$c.id
        Type   = [string]$c.type
        Recall = $recall
        Hits   = $hits
        Rel    = $targets.Count
        Ret    = $docs.Count
        S1     = [math]::Round($top1Score, 3)
        Top1   = $top1
        Error  = $err
    }

    $color = 'Gray'
    if ($recall -ge 1.0) { $color = 'Green' }
    elseif ($recall -gt 0) { $color = 'Yellow' }
    else { $color = 'Red' }

    $line = "[{0}] {1,-8} recall={2,4:P0} hits={3}/{4} ret={5} s1={6,5:N3}  {7}" -f `
        $c.id, $c.type, $recall, $hits, $targets.Count, $docs.Count, $top1Score, $top1
    if ($err) { $line += "  ERR=" + $err }
    Write-Host $line -ForegroundColor $color

    if ($recall -lt 1.0 -and $docs.Count -gt 0) {
        $shown = 0
        foreach ($d in $docs) {
            if ($shown -ge 3) { break }
            Write-Host ("      -> {0,5:N3}  {1}" -f [double]$d.score, (Format-Ref $d)) -ForegroundColor DarkGray
            $shown++
        }
    }
}

# ---- summary ---------------------------------------------------------------
if ($rows.Count -eq 0) { Write-Host 'no cases' -ForegroundColor Red; exit 1 }

$micro = $allHits / [double]$allTargets
$mean = ($rows | Measure-Object -Property Recall -Average).Average
$hit5 = (@($rows | Where-Object { $_.Hits -gt 0 }).Count) / [double]$rows.Count
$avgRet = ($rows | Measure-Object -Property Ret -Average).Average
$errors = @($rows | Where-Object { $_.Error -ne '' }).Count

Write-Host ''
Write-Host '================ RECALL SUMMARY ================' -ForegroundColor Cyan
Write-Host ("cases           : {0}" -f $rows.Count)
Write-Host ("targets         : {0}" -f $allTargets)
Write-Host ("Recall@{0} (micro): {1:P2}   ({2}/{3})" -f $TopK, $micro, $allHits, $allTargets) -ForegroundColor $(if ($micro -ge 0.90) { 'Green' } else { 'Yellow' })
Write-Host ("Mean recall@{0}  : {1:P2}" -f $TopK, $mean)
Write-Host ("Hit@{0}          : {1:P2}" -f $TopK, $hit5)
Write-Host ("avg returned    : {0:N2} / {1}" -f $avgRet, $TopK)
Write-Host ("ret=0 cases     : {0}" -f $emptyCount)
Write-Host ("ret<K cases     : {0}   (score-threshold may be truncating)" -f $shortCount)
Write-Host ("request errors  : {0}" -f $errors)
$verdict = if ($micro -ge 0.90) { 'PASS  target Recall@' + $TopK + ' >= 0.90' } else { 'FAIL  target Recall@' + $TopK + ' >= 0.90' }
Write-Host $verdict -ForegroundColor $(if ($micro -ge 0.90) { 'Green' } else { 'Red' })
Write-Host '================================================' -ForegroundColor Cyan

# ---- report ----------------------------------------------------------------
if (-not $NoReport) {
    if (-not $Report) {
        $dir = Join-Path $projectRoot 'module-a-rag\evaluation\reports'
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $Report = Join-Path $dir ('recall-at-' + $TopK + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    }
    $payload = [pscustomobject]@{
        generatedAt = (Get-Date).ToString('s')
        base        = $Base
        topK        = $TopK
        qrels       = $Qrels
        summary     = [pscustomobject]@{
            cases           = $rows.Count
            targets         = $allTargets
            recallAtK       = [math]::Round($micro, 4)
            meanRecallAtK   = [math]::Round($mean, 4)
            hitAtK          = [math]::Round($hit5, 4)
            avgReturned     = [math]::Round($avgRet, 2)
            emptyResults    = $emptyCount
            shortResults    = $shortCount
            requestErrors   = $errors
        }
        cases       = $rows
    }
    $json = $payload | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($Report, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ("report: {0}" -f $Report) -ForegroundColor Cyan
}
