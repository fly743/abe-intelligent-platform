$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repo = Join-Path $root '.bsw-local/repository/com/abe/vendor'
$cp = @((Join-Path $root 'target/classes'), (Join-Path $repo 'bsw-core/0e71de17ed/bsw-core-0e71de17ed.jar'), (Join-Path $repo 'jpbc-api/0e71de17ed/jpbc-api-0e71de17ed.jar'), (Join-Path $repo 'jpbc-plaf/0e71de17ed/jpbc-plaf-0e71de17ed.jar'), (Join-Path $root 'target')) -join ';'
javac -encoding UTF-8 -cp $cp -d (Join-Path $root 'target') (Join-Path $root 'crypto-poc/JavaGeneratorCheck.java')
if ($LASTEXITCODE -ne 0) { throw '验证程序编译失败' }
java -cp $cp JavaGeneratorCheck
if ($LASTEXITCODE -ne 0) { throw '生成代码验证失败' }
