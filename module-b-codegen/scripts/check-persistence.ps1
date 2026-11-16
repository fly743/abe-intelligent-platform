$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repo = Join-Path $root '.bsw-local/repository/com/abe/vendor'
$cp = @((Join-Path $root 'target/classes'), (Join-Path $root '.bsw-local/setup/bsw-core.jar'), (Join-Path $repo 'jpbc-api/0e71de17ed/jpbc-api-0e71de17ed.jar'), (Join-Path $repo 'jpbc-plaf/0e71de17ed/jpbc-plaf-0e71de17ed.jar'), (Join-Path $root 'target')) -join ';'
javac -encoding UTF-8 -cp $cp -d (Join-Path $root 'target') (Join-Path $root 'crypto-poc/BswPersistenceCheck.java') (Join-Path $root 'crypto-poc/BswProtectedKeyCheck.java')
if ($LASTEXITCODE -ne 0) { throw '验证程序编译失败' }
java -cp $cp BswPersistenceCheck
if ($LASTEXITCODE -ne 0) { throw '跨进程验证失败' }
java -cp $cp BswProtectedKeyCheck
if ($LASTEXITCODE -ne 0) { throw '口令保护密钥验证失败' }
