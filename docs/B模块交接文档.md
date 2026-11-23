# B 模块交接文档

更新时间：2026-11-23

## 一、模块定位与当前状态

B 模块负责解析属性与访问策略，生成 Java ABE 示例，并在 Docker 中编译、运行示例，返回各阶段状态和基础错误说明。目前已完成项目内可调用的主要功能闭环，是原型而非已完成系统集成的插件。生成的示例调用项目内 `BswCryptoService`，不能脱离项目依赖独立运行。

流程：策略、属性、明文 → 输入校验与源码生成 → Docker 编译运行 → 返回生成结果、编译和运行状态、清理状态及错误说明。

## 二、已完成内容

- 策略支持 `AND`、`OR`、括号及策略中重复出现的属性；运算符不区分大小写，属性区分大小写。用户属性列表不允许完全重复。完整规则见 [模块说明](../module-b-codegen/README.md)。
- 已接入 BSW 密码适配层，生成示例覆盖初始化、用户密钥生成、加密和解密。明文在运行阶段传入，不写入生成的源码。
- `AbeCodegenService.generateAndVerify` 串联代码生成与 Docker 执行，区分非法输入、环境不可用、编译失败、运行失败、策略不满足、超时和清理失败等结果。
- Docker 执行器仅运行固定模板，设置了网络、资源和运行时间等限制，并尝试清理容器。
- 已提供 `POST /internal/codegen/verify` 内部 HTTP 接口及独立 Harness 插件；插件负责接收模型整理的参数和展示结果，Java 接口不理解自然语言。
- 密钥与密文跨进程恢复、密钥口令保护已有原型，属于额外能力，不是原始任务完成的必要条件。

2026-11-16 在当前 Windows 环境实际执行了模块编译、Java 生成检查及 Docker 执行检查，均通过。覆盖普通策略、重复属性的两条授权路径、属性不足、非法策略、中文、空明文，以及受控的编译失败、运行失败和超时清理等代表性场景；并非全部异常场景或安全验收。

## 三、未完成内容和限制

- C 模块目前仍为骨架，尚未完成 C、D 与 B 的前端端到端联调。内部 HTTP 接口已存在，不等于已完成面向最终用户的界面及对外服务。
- 错误分析以基础分类和简短提示为主；未知运行失败不会推断未经证实的原因。
- Docker 容器创建过程中存在极端中断与清理竞态，执行器不能作为运行任意不可信代码的通用沙箱。
- 属性否定、数值比较等扩展语法未实现；原始要求未明确要求这些能力。
- 正式密钥管理、分发和轮换尚未完成；跨进程恢复与口令保护仍是原型。
- 上游使用旧式固定配对参数，jPBC 原始许可及分发义务仍待核查。功能验证不等于密码学安全审计或安全等级证明。

## 四、下一步工作

1. 与团队确认 C、D 模块需要的调用形式、输入输出和错误展示方式。
2. 接入统一入口，完成从用户输入到源码及执行结果展示的一次端到端演示。
3. 请团队成员在另一台机器按下述步骤准备环境并验证，记录系统、Docker 环境和遇到的问题。
4. 按集成需要完善错误提示，并在后续处理 Docker 创建中断和清理竞态；不必扩展为通用代码沙箱。
5. 交付或论文中说明依赖许可、固定参数和验证范围；正式使用密钥前另行设计密钥管理。

## 五、团队成员环境准备与运行

团队成员拉取代码后，需要安装 JDK 17 和 Maven，并启动 Docker 的 Linux 容器引擎，确保当前用户可以访问 Docker，且 Docker 可以挂载项目目录。

首次使用时，在项目根目录运行 `module-b-codegen/scripts/prepare-bsw.ps1` 准备本地 BSW 工件，再执行 `mvn -pl module-b-codegen clean compile` 编译模块。准备过程需要访问 GitHub，Maven 首次构建也可能需要下载依赖。

Docker 中需提前拉取固定的 JDK 镜像 `eclipse-temurin@sha256:5465815640e10433422217335e15a523a3e57021a1f2ae0fc3849380d237979f`；执行器不会自动拉取镜像。编译产物和本地 BSW 工件需要保留，因为容器运行时会挂载它们。

准备完成后，可在项目根目录运行 `module-b-codegen/scripts/check-java-generator.ps1` 和 `module-b-codegen/scripts/check-docker-executor.ps1` 验证。前者应显示 `Java generator checks passed`，后者应显示 `Docker executor checks passed` 和 `Docker failure checks passed`。如需验证跨进程恢复原型，可另外运行 `module-b-codegen/scripts/check-persistence.ps1`。

如果提示环境不可用，依次检查 Docker Linux 引擎、固定镜像、本地工件、编译产物及项目目录挂载权限。以上步骤是准备本地调用和验证环境，不会启动完整插件服务。
## 六、项目内调用

在能访问 B 模块及其依赖的 Java 项目中调用，模块路径应指向当前机器实际的 `module-b-codegen` 目录；以下相对路径要求进程从项目根目录启动。

```java
var service = new com.abe.codegen.sandbox.AbeCodegenService(
        java.nio.file.Path.of("module-b-codegen"));
var result = service.generateAndVerify(
        "A AND B", java.util.List.of("A", "B"),
        "示例明文".getBytes(java.nio.charset.StandardCharsets.UTF_8));

System.out.println(result.generated().status());
System.out.println(result.execution().compilation());
System.out.println(result.execution().execution());
System.out.println(result.execution().outcome());
System.out.println(result.execution().cleanup());
System.out.println(result.execution().message());
```

`generated` 包含生成源码及说明；`execution` 包含阶段状态、结果与提示。生成成功不代表编译和运行成功。调用方自行管理明文和敏感数据，不应将密钥或口令写入日志和 Git。跨进程团队演示的指纹核对与密钥保护流程见 [团队演示接入说明](B模块团队演示接入说明.md)。

更详细的实现与限制见 [B 模块说明](../module-b-codegen/README.md)、[代码生成方案](B模块Java代码生成方案.md)和 [受限执行记录](B模块受限执行方案.md)。[开发计划](B模块开发计划.md)保留了阶段性规划，部分待办描述早于本交接文档，应以当前代码及本交接状态为准。

## 七、C 模块 HTTP 接入约定

本节整理已有接口，不新增字段或接口。B 独立服务默认地址为 `http://127.0.0.1:8081`；D 集成入口扫描 B 控制器，默认端口为 `8080`，使用同一路径。服务启动与模块目录配置见 [B 插件说明](../harness-plugin/abe-codegen/README.md)。D 中的装配代码已存在，实际前端联调尚未完成。

### 请求与响应

请求为 `POST /internal/codegen/verify`，使用 `Content-Type: application/json`：

```json
{
  "policy": "role_doctor AND hospital_a",
  "attributes": ["role_doctor", "hospital_a"],
  "plaintext": "demo"
}
```

- `policy` 是已经明确的策略表达式，仅支持合法属性、AND、OR 和括号；不能直接传自然语言。
- `attributes` 是本次测试用户拥有的属性，不得根据策略自动补齐。属性列表须为 1 至 64 项，命名规则见 [B 模块说明](../module-b-codegen/README.md)。
- `plaintext` 是运行时使用的非敏感演示字符串，允许空字符串，转为 UTF-8 后最多 1 MiB；缺失或 `null` 会被判为非法输入。B HTTP 接口不会自动补充 `demo`，这是 Harness 层的默认选择。

下面是成功响应的结构示例；`content` 为说明性占位文本，不是完整源码或本轮抓取的接口响应。实际响应返回完整文件内容。

```json
{
  "generated": {
    "status": "SUCCESS",
    "stage": "GENERATION",
    "files": [
      { "name": "AbeExample.java", "content": "此处为完整的 Java 示例源码" },
      { "name": "README.md", "content": "此处为生成的运行说明" }
    ],
    "message": "源码已生成；尚未编译或运行"
  },
  "execution": {
    "compilation": "SUCCESS",
    "execution": "SUCCESS",
    "outcome": "SUCCESS",
    "cleanup": "SUCCESS",
    "message": "授权解密成功"
  }
}
```

`generated.message` 描述源码生成阶段，因此即使后续运行成功，该消息仍为“源码已生成；尚未编译或运行”。最终结果必须读取 `execution`，不能将生成消息当成整个请求的执行状态。

以下是其他典型结果的 `execution` 片段示例；它们仍与 `generated` 一同返回，不代表新的响应结构。

属性不足：将上述请求的 `attributes` 改为 `["role_doctor"]`，在环境可用且实际完成编译运行时预期为：

```json
{
  "compilation": "SUCCESS",
  "execution": "FAILED",
  "outcome": "POLICY_NOT_SATISFIED",
  "cleanup": "SUCCESS",
  "message": "用户属性不满足访问策略，请检查属性和策略表达式"
}
```

环境不可用：例如 Docker 前置检查失败，典型结果为：

```json
{
  "compilation": "NOT_RUN",
  "execution": "NOT_RUN",
  "outcome": "ENVIRONMENT_UNAVAILABLE",
  "cleanup": "NOT_NEEDED",
  "message": "Docker 不可用或当前进程无权访问引擎"
}
```

具体消息及清理状态取决于失败位置。环境异常时，只要生成成功，`generated.files` 仍包含已生成源码；非法策略导致生成失败时，文件列表为空。

### 数据来源与展示规则

| 展示内容 | 数据来源或读取规则 |
| --- | --- |
| 访问策略、用户属性 | 与本次响应对应的请求快照中的 `policy`、`attributes`；当前响应不回传这两个字段 |
| 中文属性映射 | Harness 工具参数 `attributeMapping`，由调用层保留；B 请求和响应不包含此字段 |
| Java 源码 | 在 `generated.files` 中按 `name === "AbeExample.java"` 查找 `content`，不依赖数组下标 |
| 运行说明 | 按 `name === "README.md"` 查找 `content` |
| 源码生成 | `generated.status`、`stage`、`message` |
| 编译、运行、清理 | `execution.compilation`、`execution.execution`、`execution.cleanup` |
| 最终结果、说明 | `execution.outcome`、`execution.message` |

当前 Harness 工具输出是用于对话展示的 Markdown 字符串。C 直接接入 B 时应解析原始 HTTP JSON，不应从模型回复或 Markdown 中反向提取源码和状态。C 若接入自然语言交互，需要调用层另外传递策略、用户属性和映射；本约定未新增该桥接接口。

### 状态含义

| `execution.outcome` | 展示含义 |
| --- | --- |
| `SUCCESS` | 授权解密成功，恢复内容与输入一致 |
| `POLICY_NOT_SATISFIED` | 属性不足，已拒绝访问；不是算法故障。运行字段当前为 `FAILED`，界面应结合 outcome 解释 |
| `INVALID_INPUT` | 策略、属性或明文输入非法，展示具体 message |
| `ENVIRONMENT_UNAVAILABLE` | Docker 或运行依赖不可用 |
| `COMPILATION_FAILED` | Java 编译失败 |
| `EXECUTION_FAILED` | 程序执行或输入输出失败，按已有 message 说明，不推断未知原因 |
| `TIMEOUT` | 执行超时 |
| `CLEANUP_FAILED` | 容器清理未确认；不能展示为完整成功 |
| `GENERATION_FAILED` | 枚举已定义，当前生成器校验失败使用 `INVALID_INPUT`，不作为已验证的独立失败流程 |

编译和运行阶段状态为 `NOT_RUN`、`SUCCESS`、`FAILED`、`TIMEOUT`；清理状态为 `NOT_NEEDED`、`SUCCESS`、`FAILED`。`NOT_RUN` 表示未执行，`NOT_NEEDED` 表示无需清理，不能都改写成“成功”。界面应按枚举判断，不匹配中文消息决定结果。

控制器正常返回的业务失败（例如非法输入或授权拒绝）同样可能是 HTTP 200，不能仅根据 HTTP 状态判断验证通过。网络失败、非 2xx 或响应无法解析时，显示请求/服务异常，不伪造 `execution` 或授权拒绝结果。

目前响应是执行结束后的汇总，没有实时事件流，也没有初始化、密钥生成、加密、解密各步骤的独立状态或耗时。可以解释算法流程，但不能把这些步骤标成已逐项采集的实时执行进度。

展示源码和消息时作为文本处理。不要把明文、主密钥、用户私钥或 AES 密钥加入响应、交接记录或持久化展示数据。并发请求时将响应与各自请求快照对应，避免展示最新输入与上一次结果的组合。本节是接口约定，尚不代表 C 已实现或完成联调。
