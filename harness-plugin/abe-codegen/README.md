# ABE 代码生成与验证插件

`harness-plugin-abe-codegen` 是独立的 DeepSeek Harness 插件，注册 `abe_codegen_verify`。工具接收明确的策略、用户属性和非敏感演示明文，通过 HTTP 调用 B 服务。Harness 可以协助整理描述，但信息缺失或策略有歧义时必须先澄清；Java 生成器本身不理解自然语言，也不支持任意语言、算法或功能。不要输入密钥、口令或敏感明文。Harness 本身及其对话记录的保密行为不由本插件控制。

策略支持 `AND`、`OR` 和括号；属性及数量限制见 [B 模块说明](../../module-b-codegen/README.md)。示例 `A AND B`，属性 `A, B`。响应中的 `generated.status` 和 `generated.files` 描述生成结果；`execution.compilation`、`execution.execution`、`execution.cleanup` 和 `execution.outcome` 分别描述后续状态。生成成功不代表编译或运行成功。工具结果会包含完整的 `AbeExample.java` 代码块和运行说明，即使编译或运行失败也会展示已生成源码；最终对话展示仍受 Harness 和模型输出行为影响。`POLICY_NOT_SATISFIED` 表示运行阶段确认属性不足；其他运行失败不会推断具体原因。

## 自然语言转换与确认

工具描述要求 Harness 先区分访问条件和当前测试用户属性，再确定逻辑分组、统一属性映射并调用工具。条件明确时直接执行；以下情况应先澄清：

- 只有访问条件，未说明测试用户属性。即使策略只有一种身份，也不能默认用它测试；应询问“这次用什么身份或属性的用户测试？”。仅说“生成代码”“跑一下”或“做个演示”不等于授权自动选择身份。用户明确要求任选身份或构造成功、失败用例时，才可以选取属性，并说明它们是模拟身份；用户已提供属性或已明确授权时不重复询问。
- AND/OR 的作用范围不明确。例如“A 医院的医生或管理员”需要确认医院限制是否也适用于管理员。
- 出现 NOT、排除、数值比较或任意门限等当前不支持的条件。不能直接改成普通属性声称实现原语义；若以 `age_over_18` 模拟比较，须先取得用户同意并解释需要外部签发，系统不计算年龄。
- 当前表述与之前已采用的属性映射冲突。后续对话应沿用既有映射，只修改用户指定的内容。

缺失测试身份的两轮检查（新建对话，不在输入中提示模型追问）：

1. 输入“医生或者管理员能看这份数据，帮我生成代码跑一下”。预期识别 OR，先询问测试用户身份，不调用 `abe_codegen_verify`。
2. 回复“就拿护士试一下”。预期保留原策略，以护士作为用户属性调用工具，返回 `POLICY_NOT_SATISFIED`；标识符可采用 `role_doctor`、`role_admin`、`role_nurse`，但必须保持映射一致。

这项修改强化了工具描述及 `attributes` 参数说明，未增加能够证明属性来自用户输入的程序级校验。实际追问效果需重启 DSH、确认加载新插件后，在新对话中复测。

明确的输入示例：

> 用户是医生并且属于 A 医院，或者用户是管理员，才可访问。本次用户是医生，属于 B 医院。

对应工具参数（明文未指定时使用 `demo`）：

```json
{
  "policy": "(role_doctor AND hospital_a) OR role_admin",
  "attributes": ["role_doctor", "hospital_b"],
  "plaintext": "demo",
  "attributeMapping": [
    { "label": "医生", "identifier": "role_doctor" },
    { "label": "A 医院", "identifier": "hospital_a" },
    { "label": "管理员", "identifier": "role_admin" },
    { "label": "B 医院", "identifier": "hospital_b" }
  ]
}
```

该用户属性不满足策略，实际执行预期为 `POLICY_NOT_SATISFIED`，不能为了成功而将 B 医院替换成 A 医院。此例说明目标交互，不代表已完成 Harness 实测。

`attributeMapping` 是新增的可选工具参数，每项包含 `label` 和 `identifier`。自然语言转换时应提供，纯标识符调用可省略，以兼容原有调用。提供时插件在请求 B 服务前检查：

- 映射为 1 至 128 项；名称去除首尾空白后为 1 至 128 个字符，不能包含换行或控制字符。
- 标识符符合 B 模块命名规则，名称和标识符分别唯一；已明确的同义表述应先统一成一个名称。
- 映射覆盖策略及用户属性中的全部标识符，也不包含本次未使用的映射。保留用户原有合法标识符和大小写，可用同名映射。

检查失败时返回具体说明，不请求 B 服务。策略中的属性仅按标识符词法提取以检查映射覆盖，完整策略语法及属性列表校验仍由 Java 负责。插件只向原有 HTTP 接口传递 `policy`、`attributes`、`plaintext`；映射保留在插件层，不改变 Java 响应模型。正常响应时，工具结果先展示本次策略、用户属性及所提供的映射，再展示生成和执行结果。

以上校验只能发现映射结构、命名和覆盖问题，不能判断模型是否忠实理解原文，也不能验证用户真实身份。澄清和跨轮映射沿用依赖模型遵循工具描述及对话上下文；插件没有强制确认状态机或持久化映射表。映射未提供时也不能据此认定输入不是自然语言转换而来。

建议人工检查：明确 AND、明确 OR、混合条件分组、含歧义的医院限制、缺失用户属性、属性不足拒绝、不支持的条件、后续只修改用户属性，以及映射冲突。实际 Harness 对话效果需在目标环境验证；本轮未新增或运行自动化测试。

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
