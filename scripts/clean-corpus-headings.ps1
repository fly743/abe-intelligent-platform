<#
.SYNOPSIS
    Strip PDF page-number noise from Markdown section headings in the ABE corpus.

.DESCRIPTION
    Before:  ## 3. CP-ABE vs KP-ABE [BSW 2007, PDF page2]
    After:   ## 3. CP-ABE vs KP-ABE
             > source: BSW 2007, PDF page 2

    Default behaviour is a DRY RUN: cleaned copies are written to
    <Path>\.cleaned-preview and the originals are left untouched.
    Use -Apply to overwrite the original .md files in place.

    Output files are written as UTF-8 without BOM and keep the original
    line-ending style (CRLF / LF).

    This script is intentionally pure ASCII. Every Chinese character it needs
    ('chu chu :' prefix and the 'di / ye' page markers) is produced at runtime
    from \uXXXX escapes, so PowerShell 5.1 can never mis-decode this file even
    when it is saved as UTF-8 without BOM.

.PARAMETER Path
    Corpus root. Default: D:\ABE-Program\module-a-rag\docs

.PARAMETER Apply
    Write the changes back to the original files instead of the preview folder.

.PARAMETER IncludeFaq
    Also process docs\faq\*.qa.md (excluded by default: those are not ingested).

.PARAMETER Preview
    Name of the dry-run output folder, default: .cleaned-preview

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\clean-corpus-headings.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\clean-corpus-headings.ps1 -Apply
#>
[CmdletBinding()]
param(
    [string]$Path = 'D:\ABE-Program\module-a-rag\docs',
    [switch]$Apply,
    [switch]$IncludeFaq,
    [string]$Preview = '.cleaned-preview'
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# '> 出处：'  (U+51FA U+5904 U+FF1A)
$OUT_PREFIX = '> ' + [char]0x51FA + [char]0x5904 + [char]0xFF1A

# '> 出处' detection, for idempotency
$SOURCE_HEAD_RE = '^\s*>\s*' + [char]0x51FA + [char]0x5904

# heading whose trailing [...] contains 'PDF' and ends with U+9875 (ye)
$HEADING_RE = '^(?<hash>#{1,6}[ \t]+)(?<title>.+?)[ \t]*\[(?<tag>[^\[\]]*\bPDF\b[^\[\]]*\u9875[^\[\]]*)\][ \t]*$'

function Format-SourceTag {
    param([string]$tag)
    $t = $tag.Trim()
    $t = $t -replace '[ \t]+', ' '
    $t = $t -replace '\bPDF\b[ \t]*', 'PDF '
    $t = $t -replace '(\u7B2C)[ \t]*', '$1 '   # 'di' + space
    $t = $t -replace '[ \t]*(\u9875)', ' $1'   # space + 'ye'
    return $t
}

if (-not (Test-Path -LiteralPath $Path)) { throw "Corpus path not found: $Path" }
$root = (Resolve-Path -LiteralPath $Path).Path.TrimEnd('\')
$previewRoot = Join-Path $root $Preview

$files = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md' | Where-Object {
    ($_.FullName -notlike "*\$Preview\*") -and
    ($IncludeFaq -or $_.DirectoryName -notlike '*\faq')
} | Sort-Object FullName

if (-not $files) {
    Write-Warning "No .md files found under $root"
    return
}

$stats = New-Object System.Collections.Generic.List[object]

foreach ($file in $files) {
    $raw = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
    $nl = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = $raw -split '\r?\n'
    $out = New-Object System.Collections.Generic.List[string]

    $rewritten = 0
    $inserted = 0
    $changes = New-Object System.Collections.Generic.List[string]

    $i = 0
    while ($i -lt $lines.Count) {
        $line = $lines[$i]
        $m = [regex]::Match($line, $HEADING_RE)

        if (-not $m.Success) {
            $out.Add($line)
            $i++
            continue
        }

        $newHeading = $m.Groups['hash'].Value + $m.Groups['title'].Value.Trim()
        $tag = Format-SourceTag $m.Groups['tag'].Value
        $rewritten++
        $changes.Add('  - ' + $line.Trim() + '   =>   ' + $newHeading)
        $out.Add($newHeading)

        # look ahead past blank lines to see whether this section is already annotated
        $j = $i + 1
        while ($j -lt $lines.Count -and $lines[$j].Trim() -eq '') { $j++ }

        if ($j -lt $lines.Count -and $lines[$j] -match $SOURCE_HEAD_RE) {
            $out.Add('')
        }
        else {
            $out.Add('')
            $out.Add($OUT_PREFIX + $tag)
            $out.Add('')
            $inserted++
        }

        $i++
        while ($i -lt $lines.Count -and $lines[$i].Trim() -eq '') { $i++ }
    }

    $text = ($out -join $nl)
    if ($raw.EndsWith("`n") -and -not $text.EndsWith("`n")) { $text += $nl }

    $rel = $file.FullName.Substring($root.Length).TrimStart('\')
    if ($Apply) {
        $target = $file.FullName
    }
    else {
        $target = Join-Path $previewRoot $rel
        $dir = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    }

    [System.IO.File]::WriteAllText($target, $text, $utf8NoBom)

    Write-Output ("[{0}] headings rewritten={1}, source lines inserted={2}" -f $file.Name, $rewritten, $inserted)
    foreach ($c in $changes) { Write-Output $c }

    $stats.Add([pscustomobject]@{
            File      = $file.Name
            Rewritten = $rewritten
            Inserted  = $inserted
        })
}

$totalFiles = $stats.Count
$totalHeadings = ($stats | Measure-Object -Property Rewritten -Sum).Sum
$totalInserted = ($stats | Measure-Object -Property Inserted -Sum).Sum
$mode = if ($Apply) { 'APPLY  (originals overwritten)' } else { 'DRY-RUN (originals untouched)' }

Write-Output ''
Write-Output '================ SUMMARY ================'
Write-Output ("MODE         : {0}" -f $mode)
Write-Output ("FILES        : {0}" -f $totalFiles)
Write-Output ("HEADINGS     : {0} page markers removed" -f $totalHeadings)
Write-Output ("SOURCE LINES : {0} inserted" -f $totalInserted)
if (-not $Apply) {
    Write-Output ("PREVIEW DIR  : {0}" -f $previewRoot)
    Write-Output 'Next step    : diff the preview against originals, then rerun with -Apply'
}
Write-Output '========================================='
