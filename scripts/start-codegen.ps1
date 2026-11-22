<#
.SYNOPSIS
    Start the B module codegen service on http://127.0.0.1:8081.

.DESCRIPTION
    Why this script exists:
    `mvn spring-boot:run` sets the working directory to module-b-codegen, while
    CodegenInternalController defaults `abe.codegen.module-directory` to the
    relative path "module-b-codegen". The two combine into
    module-b-codegen/module-b-codegen/target/classes, which does not exist, so
    every /internal/codegen/verify call returns
        outcome=ENVIRONMENT_UNAVAILABLE
        message=module or local dependencies not compiled

    This script passes an absolute module directory, so the preflight can find
    target/classes and .bsw-local/repository/com/abe/vendor.

    Prerequisites:
      1. module-b-codegen/scripts/prepare-bsw.ps1   (creates .bsw-local)
      2. mvn -pl module-b-codegen compile           (creates target/classes)
      3. Docker Linux engine + the fixed JDK image  (execution stage only)

.PARAMETER JavaHome
    JDK 17 home. Default: C:\Users\Jary\.jdks\ms-17.0.18

.PARAMETER Mvn
    Path to mvn.cmd. Default: auto-detect from PATH, then from the Maven
    wrapper dist under %USERPROFILE%\.m2\wrapper\dists.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\scripts\start-codegen.ps1
#>
param(
    [string]$JavaHome = 'C:\Users\Jary\.jdks\ms-17.0.18',
    [string]$Mvn = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not $Mvn) {
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { $Mvn = $cmd.Source }
    else {
        $found = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $Mvn = $found.FullName }
    }
}
if (-not $Mvn -or -not (Test-Path -LiteralPath $Mvn)) { throw 'mvn.cmd not found; pass -Mvn <path>' }
if (-not (Test-Path -LiteralPath $JavaHome)) { throw "JAVA_HOME not found: $JavaHome" }

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
Set-Location -LiteralPath $repo

$moduleDir = Join-Path $repo 'module-b-codegen'
if (-not (Test-Path -LiteralPath (Join-Path $moduleDir 'target\classes'))) {
    throw 'module-b-codegen is not compiled; run: mvn -pl module-b-codegen compile'
}
if (-not (Test-Path -LiteralPath (Join-Path $moduleDir '.bsw-local\repository'))) {
    throw 'BSW artifacts missing; run: module-b-codegen\scripts\prepare-bsw.ps1'
}

$unixModuleDir = $moduleDir.Replace('\', '/')
Write-Host "starting codegen service on 127.0.0.1:8081" -ForegroundColor Cyan
Write-Host "module directory: $unixModuleDir" -ForegroundColor DarkGray
& $Mvn -pl module-b-codegen spring-boot:run "-Dspring-boot.run.jvmArguments=-Dabe.codegen.module-directory=$unixModuleDir"
