<#
.SYNOPSIS
    Collect midterm inspection data for plugin A (abe-qa).

.DESCRIPTION
    Runs the reproducible checks and archives raw output under
    <repo>\docs\midterm-data\ so it can be screenshot / attached to the report:

        01-health.json   GET /internal/rag/health
        02-smoke.txt     scripts\rag-smoke.ps1   (17 QA cases)
        03-eval.txt      scripts\eval-recall.ps1 (41 qrels cases / 53 targets)
        04-probe.txt     scripts\probe-scores.ps1 (threshold calibration)

    Child scripts are launched as separate processes with stdout/stderr
    redirected to files. This is required because they use Write-Host, which
    does not travel through a PowerShell pipeline in 5.1 and would otherwise
    drop the summary line (e.g. "PASS 17 / 17").

    Steps can be run one at a time so a single call never exceeds a shell
    timeout: -Step health|smoke|eval|probe. Use -Step all to run everything.

    Screenshots are not produced by this script. Take them from the terminal
    window after each step, or from the Harness chat page after calling
    abe_rag_health / abe_qa.

.PARAMETER Base
    Java RAG service base URL, default http://localhost:8080

.PARAMETER OutDir
    Archive directory, default <repo>\docs\midterm-data

.PARAMETER Step
    health | smoke | eval | probe | all

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\collect-midterm.ps1 -Step smoke
#>
param(
    [string]$Base = 'http://localhost:8080',
    [string]$OutDir = '',
    [ValidateSet('health', 'smoke', 'eval', 'probe', 'all')]
    [string]$Step = 'all'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $repo 'docs\midterm-data' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Save-Text([string]$name, [string]$text) {
    [System.IO.File]::WriteAllText((Join-Path $OutDir $name), $text, $utf8)
}

function Assert-Service {
    $ok = Test-NetConnection 127.0.0.1 -Port 8080 -InformationLevel Quiet
    if (-not $ok) { throw 'RAG service is not listening on 8080; start module-d-integration first' }
}

# Run a child PowerShell script and capture everything, including Write-Host.
function Invoke-ChildScript([string]$scriptPath) {
    $tmpOut = Join-Path $env:TEMP ('collect-' + [guid]::NewGuid().ToString('N') + '.out')
    $tmpErr = $tmpOut + '.err'
    try {
        Start-Process -FilePath 'powershell.exe' `
            -ArgumentList @('-ExecutionPolicy', 'Bypass', '-File', $scriptPath) `
            -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr | Out-Null
        $text = ''
        if (Test-Path -LiteralPath $tmpOut) { $text = [System.IO.File]::ReadAllText($tmpOut, [System.Text.Encoding]::UTF8) }
        if (Test-Path -LiteralPath $tmpErr) {
            $err = [System.IO.File]::ReadAllText($tmpErr, [System.Text.Encoding]::UTF8)
            if ($err.Trim().Length -gt 0) { $text += "`n[stderr]`n" + $err }
        }
        return $text
    }
    finally {
        Remove-Item -LiteralPath $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue
    }
}

function Run-Step([string]$name) {
    switch ($name) {
        'health' {
            Assert-Service
            $resp = & curl.exe -sS -m 15 "$Base/internal/rag/health" 2>$null
            Save-Text '01-health.json' $resp
            Write-Output ("[health] {0}" -f $resp)
        }
        'smoke' {
            Assert-Service
            $p = Join-Path $repo 'scripts\rag-smoke.ps1'
            $txt = Invoke-ChildScript $p
            Save-Text '02-smoke.txt' $txt
            $m = [regex]::Match($txt, 'PASS\s+(\d+)\s*/\s*(\d+)')
            Write-Output ("[smoke] PASS {0}/{1} -> 02-smoke.txt" -f $m.Groups[1].Value, $m.Groups[2].Value)
        }
        'eval' {
            Assert-Service
            $p = Join-Path $repo 'scripts\eval-recall.ps1'
            $txt = Invoke-ChildScript $p
            Save-Text '03-eval.txt' $txt
            $m = [regex]::Match($txt, 'Recall@5 \(micro\):\s*([\d.]+)%\s*\((\d+)/(\d+)\)')
            Write-Output ("[eval] Recall@5 {0}% ({1}/{2}) -> 03-eval.txt" -f $m.Groups[1].Value, $m.Groups[2].Value, $m.Groups[3].Value)
            $latest = Get-ChildItem (Join-Path $repo 'module-a-rag\evaluation\reports') -Filter 'recall-at-5-*.json' -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($latest) {
                Copy-Item $latest.FullName (Join-Path $OutDir ('03-eval-report-' + $latest.Name)) -Force
                Write-Output ("[eval] report copied: {0}" -f $latest.Name)
            }
        }
        'probe' {
            Assert-Service
            $p = Join-Path $repo 'scripts\probe-scores.ps1'
            $txt = Invoke-ChildScript $p
            Save-Text '04-probe.txt' $txt
            $verdict = if ($txt -match 'NOT SEPARABLE') { 'NOT SEPARABLE' } elseif ($txt -match 'SEPARABLE') { 'SEPARABLE' } else { 'UNKNOWN' }
            Write-Output ("[probe] {0} -> 04-probe.txt" -f $verdict)
        }
    }
}

if ($Step -eq 'all') {
    foreach ($s in 'health', 'smoke', 'eval', 'probe') { Run-Step $s }
}
else {
    Run-Step $Step
}

Write-Output ("archive: {0}" -f $OutDir)
