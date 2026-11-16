$ErrorActionPreference = 'Stop'
$commit = '0e71de17ed64729f2de811503a7958673691e77a'
$expected = 'E0298FF2F23073AC141E333C01C3063E9544ABAB66E921425647FFD7FE47978B'
$moduleDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = Join-Path $moduleDir '.bsw-local/setup'
$repository = Join-Path $moduleDir '.bsw-local/repository'
New-Item -ItemType Directory -Force -Path $work, $repository | Out-Null
$archive = Join-Path $work 'cpabe.zip'
Invoke-WebRequest -Uri "https://github.com/junwei-wang/cpabe/archive/$commit.zip" -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw 'BSW 源码校验失败' }
Expand-Archive -LiteralPath $archive -DestinationPath $work -Force
$source = Join-Path $work "cpabe-$commit/cpabe-api"
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $source 'src/main/java/co/junwei/bswabe') -Filter '*.java' | ForEach-Object FullName)
& javac --release 8 -encoding UTF-8 -cp (Join-Path $source 'lib/*') -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'BSW 编译失败' }

function Write-Artifact([string]$artifact, [string]$jarPath, [string]$dependencies) {
    $version = '0e71de17ed'
    $destination = Join-Path $repository "com/abe/vendor/$artifact/$version"
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath $jarPath -Destination (Join-Path $destination "$artifact-$version.jar") -Force
    $pom = "<project xmlns=`"http://maven.apache.org/POM/4.0.0`"><modelVersion>4.0.0</modelVersion><groupId>com.abe.vendor</groupId><artifactId>$artifact</artifactId><version>$version</version>$dependencies</project>"
    [IO.File]::WriteAllText((Join-Path $destination "$artifact-$version.pom"), $pom, [Text.UTF8Encoding]::new($false))
    foreach ($extension in @('jar', 'pom')) {
        $file = Join-Path $destination "$artifact-$version.$extension"
        [IO.File]::WriteAllText("$file.sha1", (Get-FileHash -LiteralPath $file -Algorithm SHA1).Hash.ToLowerInvariant(), [Text.Encoding]::ASCII)
    }
}
$coreJar = Join-Path $work 'bsw-core.jar'
& jar cf $coreJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'BSW 打包失败' }
Write-Artifact 'jpbc-api' (Join-Path $source 'lib/jpbc-api-1.2.1.jar') ''
Write-Artifact 'jpbc-plaf' (Join-Path $source 'lib/jpbc-plaf-1.2.1.jar') ''
$deps = '<dependencies><dependency><groupId>com.abe.vendor</groupId><artifactId>jpbc-api</artifactId><version>0e71de17ed</version></dependency><dependency><groupId>com.abe.vendor</groupId><artifactId>jpbc-plaf</artifactId><version>0e71de17ed</version></dependency></dependencies>'
Write-Artifact 'bsw-core' $coreJar $deps
Write-Host "BSW 工件已准备：$repository"
