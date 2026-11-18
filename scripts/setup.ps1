param(
    [string]$PackageName = 'harness-plugin-abe-qa',
    [string]$EntryId = 'abe-rag',
    [string]$ProfileName = 'web'
)

# Adds the plugin entry to the dsh user patch layer.
# The bundle composer only merges official bundles, so third-party plugins must be
# declared here. Idempotent: running twice does not duplicate the entry.

$ErrorActionPreference = 'Stop'

$profilesRoot = Join-Path $env:USERPROFILE '.dsh/profiles'
if (-not (Test-Path $profilesRoot)) {
    throw "dsh profiles not found: $profilesRoot (start 'pnpm dsh web' once first)"
}

$profileDir = Join-Path $profilesRoot $ProfileName
if (-not (Test-Path $profileDir)) {
    $first = Get-ChildItem $profilesRoot -Directory | Select-Object -First 1
    if (-not $first) { throw "no dsh profile under $profilesRoot" }
    $profileDir = $first.FullName
    Write-Host "profile '$ProfileName' not found, using '$($first.Name)'"
}

$patch = Join-Path $profileDir 'cordis.patch.yml'
if (-not (Test-Path $patch)) {
    New-Item -ItemType File -Path $patch -Force | Out-Null
}

$content = Get-Content $patch -Raw
if ($content -and $content -match [regex]::Escape("id: $EntryId")) {
    Write-Host "entry '$EntryId' already present in $patch, nothing to do." -ForegroundColor Yellow
} else {
    $block = "`n# added by setup.ps1`n- insert:`n    - id: $EntryId`n      name: '$PackageName'`n"
    $merged = if ($content) { $content.TrimEnd() + "`n" + $block } else { $block.TrimStart() }
    Set-Content -Path $patch -Value $merged -Encoding UTF8
    Write-Host "added entry '$EntryId' -> '$PackageName' in $patch" -ForegroundColor Green
}

Write-Host ""
Write-Host "next steps:" -ForegroundColor Cyan
Write-Host "  1) make sure the plugin is installed in the UI (Settings -> Plugins -> Add plugin -> path to harness-plugin/abe-qa)"
Write-Host "  2) restart:  Get-Process node | Stop-Process -Force ; pnpm dsh web"
Write-Host "  3) verify in chat:  call abe_rag_health"
