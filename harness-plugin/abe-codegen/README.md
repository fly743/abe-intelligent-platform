# ABE 代码生成与验证插件

`harness-plugin-abe-codegen` 是独立的 DeepSeek Harness 插件，注册 `abe_codegen_verify`。工具接收明确的策略、用户属性和非敏感演示明文，通过 HTTP 调用 B 服务。Harness 可以协助整理描述，但信息缺失或策略有歧义时必须先澄清；Java 生成器本身不理解自然语言，也不支持任意语言、算法或功能。不要输入密钥、口令或敏感明文。Harness 本身及其对话记录的保密行为不由本插件控制。

策略支持 `AND`、`OR` 和括号；属性及数量限制见 [B 模块说明](../../module-b-codegen/README.md)。示例 `A AND B`，属性 `A, B`。响应中的 `generated.status` 和 `generated.files` 描述生成结果；`execution.compilation`、`execution.execution`、`execution.cleanup` 和 `execution.outcome` 分别描述后续状态。生成成功不代表编译或运行成功。工具结果会包含完整的 `AbeExample.java` 代码块和运行说明，即使编译或运行失败也会展示已生成源码；最终对话展示仍受 Harness 和模型输出行为影响。`POLICY_NOT_SATISFIED` 表示运行阶段确认属性不足；其他运行失败不会推断具体原因。

## 独立服务

需要 JDK 17、Maven、Docker Linux 引擎，以及当前进程对 Docker 和绑定目录的访问权限。在仓库根目录执行：

```powershell
./module-b-codegen/scripts/prepare-bsw.ps1
mvn -pl module-b-codegen package -DskipTests
docker pull eclipse-temurin@sha256:5465815640e10433422217335e15a523a3e57021a1f2ae0fc3849380d237979f
java -jar module-b-codegen/target/module-b-codegen-1.0-SNAPSHOT-exec.jar
```

默认监听 `127.0.0.1:8081`，相对路径 `module-b-codegen` 以服务进程工作目录为基准；从其他位置启动时，通过 `--abe.codegen.module-directory=D:/abe-intelligent-platform/module-b-codegen` 指定实际绝对路径。必须保留该目录下已编译的 `target/classes` 与 `.bsw-local/repository`，且 Docker 引擎可读取绑定路径。执行器不会自动拉取镜像。D 集成服务也装配 B 接口，使用 D 时将插件地址设为 `http://127.0.0.1:8080` 并显式配置模块目录。

接口 `POST /internal/codegen/verify` 接收 JSON，例如 `{"policy":"A AND B","attributes":["A","B"],"plaintext":"demo"}`。它没有认证，默认仅适合可信本机；不要将服务暴露到公网。明文不会写入生成源码或响应，但 HTTP、Harness 或其他中间层仍可能保存请求；仅使用非敏感演示数据。固定模板调用项目内 `BswCryptoService`，不是独立算法源码，也不是任意代码执行沙箱。容器创建超时仍有清理竞态。

## 安装插件

插件目录为 `harness-plugin/abe-codegen`，已包含打包产物。Harness 设置中选择该目录安装；按照仓库现有第三方插件方式挂载：

```powershell
./scripts/setup.ps1 -PackageName harness-plugin-abe-codegen -EntryId abe-codegen
```

重启 Harness 后调用 `abe_codegen_verify`。服务地址通过启动 Harness 前设置的 `ABE_CODEGEN_BASE_URL` 覆盖。插件的实际 UI 安装及加载尚需在目标 Harness 环境确认。

修改 TypeScript 后可在已安装依赖的 Harness 源码环境中构建（按实际路径调整）：

```powershell
$repo = 'D:\ABE-Project\deepseek-harness'
$env:NODE_PATH = "$repo\node_modules"
$esbuild = "$repo\node_modules\.pnpm\@esbuild+win32-x64@0.28.1\node_modules\@esbuild\win32-x64\esbuild.exe"
& $esbuild harness-plugin/abe-codegen/src/index.ts --bundle --format=esm --platform=node --target=es2022 "--tsconfig=$repo/tsconfig.json" --outfile=harness-plugin/abe-codegen/lib/index.js
```
