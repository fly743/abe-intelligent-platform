$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$project = Split-Path -Parent $root
$classes = Join-Path $root 'target/classes'
$target = Join-Path $root 'target'
Push-Location $project
try {
    javac -encoding UTF-8 -cp $classes -d $target (Join-Path $root 'crypto-poc/DockerExecutorCheck.java') (Join-Path $root 'crypto-poc/DockerFailureCheck.java')
    if ($LASTEXITCODE -ne 0) { throw '执行器验证程序编译失败' }
    java -cp "$classes;$target" DockerExecutorCheck
    if ($LASTEXITCODE -ne 0) { throw '执行器验证失败' }
    java -cp "$classes;$target" com.abe.codegen.sandbox.DockerFailureCheck
    if ($LASTEXITCODE -ne 0) { throw '执行器失败场景验证失败' }
} finally { Pop-Location }
