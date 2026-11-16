# B - ABE 代码生成与验证

## 当前能力

已实现内部请求/结果模型、属性校验、策略解析、BSW 后缀策略转换、BSW + AES-GCM 密码适配、持久化原型和项目内 Java 示例生成。Docker 执行器和统一调用入口已有原型，并覆盖代表性失败场景；正式密钥管理及对外接口尚未实现。

## 构建准备

首次构建前在项目根目录运行 `./module-b-codegen/scripts/prepare-bsw.ps1`。脚本下载固定提交 `0e71de17ed64729f2de811503a7958673691e77a`，检查 SHA-256，编译上游 BSW 并在 `module-b-codegen/.bsw-local/repository` 生成本地 Maven 工件。随后运行 `mvn -pl module-b-codegen clean compile`。该目录已加入 `.gitignore`，普通 Maven clean 不会删除它。

上游 BSW 仓库标注 GPL-2.0；jPBC 1.2.1 原始发行许可和分发义务仍需核查。准备脚本只在本机获取工件，不将第三方 JAR 提交到仓库。

`BswCryptoService` 是实例级内存适配层：同一实例生成的用户密钥用于解密该实例的密文，允许空明文，最大明文为 1 MiB。其密钥和密文可通过字节接口跨进程恢复；加密数据和策略密文通过 AES-GCM 关联，属性不满足会抛出 `PolicyNotSatisfiedException`。当前同步访问并为每次解密恢复独立策略树，避免上游共享可变密文的并发问题。该代码尚未提供外部服务接口，固定参数和安全限制见选型记录。

## 策略与属性规则

- 策略支持属性、`AND`、`OR`、括号；运算符不区分大小写，属性区分大小写。括号优先，其次 `AND`，最后 `OR`。
- 属性为 1 至 64 位 ASCII 字母、数字、下划线或连字符，首字符不能是连字符；`AND`、`OR` 是保留字。用户属性数量为 1 至 64，不允许完全相同的属性重复。
- 策略最多 4096 个字符、32 层括号、64 个属性叶子。解析器、BSW 策略转换和密码适配支持同一属性在不同分支重复出现，已验证代表性场景。
- `BswPolicyConverter` 将策略树转为 BSW 的后缀门限表达式；属性先进行无歧义编码以避免上游将包含 `of` 的名称误判为运算符。当前密钥生成对属性使用相同编码。
- 策略转换和密码二进制预检还会检查策略树深度及节点数量。
- `PolicyException.getPosition()` 返回策略中的零基 UTF-16 偏移；属性列表错误使用 `-1`。异常信息中的位置为一基位置。

可使用 `PolicyParser.parse(policy)` 解析策略，再用 `BswPolicyConverter.convert(...)` 生成 BSW 后缀策略。

## 持久化原型

`BswCryptoService` 已提供版本化公开参数、主密钥、用户密钥和密文的字节导出与恢复接口，系统标识用于检查误配。密钥导出是未加密的敏感字节，正式存储需要调用方使用受保护的密钥管理系统；不要写入日志或 Git。格式、验证范围及限制见 [持久化设计](../docs/B模块持久化设计.md)。上游公开参数不包含 `f`，不支持依赖它的委托。结构检查不等于密码学有效性验证。


完成模块编译后，可运行 `./module-b-codegen/scripts/check-persistence.ps1` 复现跨进程持久化检查。检查使用进程管道传递验证密钥，不创建明文密钥文件。

## 团队演示的密钥文件保护

主密钥和用户密钥可通过 `exportProtectedMasterKey`、`exportProtectedUserKey` 使用口令保护，再用 `BswProtectedKeyFiles.saveNew` 写入受控目录；恢复分别使用 `restoreProtected`、`importProtectedUserKey`。调用方负责口令输入、清除口令数组和目录权限。不要提交或记录口令和密钥；旧的明文导出接口不能作为正式文件存储方式。格式、信任边界和验证范围见 [持久化设计](../docs/B模块持久化设计.md)。

团队初始化、可信指纹核对与跨进程恢复流程见 [团队演示接入说明](../docs/B模块团队演示接入说明.md)。

## Java 示例代码生成

`JavaExampleGenerator.generate(policy, attributes)` 接收策略字符串与用户属性，返回 `CodegenResult`。成功时 `stage` 为 `GENERATION`，文件列表包括 `AbeExample.java` 和运行说明；这只代表源码生成成功，不代表已编译或运行。明文在运行时输入。

生成的示例调用现有密码适配接口，在普通进程内初始化系统、生成用户密钥并加解密。从标准输入读取原文字节直至 EOF，保留换行，最多 1 MiB；不输出原文或密钥。它仅用于项目内演示，不是沙箱、独立分发包或持久化密钥操作工具。先准备固定 BSW 工件并编译模块，可运行 `./module-b-codegen/scripts/check-java-generator.ps1` 复现生成源码的编译和运行验证。

## 统一生成与验证入口

`AbeCodegenService` 接收 B 模块目录；调用 `generateAndVerify(policy, attributes, plaintext)` 后，返回的 `generated` 包含源码和运行说明，`execution` 包含编译、运行、清理状态与错误说明。明文以字节数组传入运行阶段，不写入源码或结果；调用方自行管理输入内容。示例：

```java
var service = new com.abe.codegen.sandbox.AbeCodegenService(java.nio.file.Path.of("module-b-codegen"));
var result = service.generateAndVerify("A AND B", java.util.List.of("A", "B"), new byte[0]);
System.out.println(result.generated().stage());
System.out.println(result.execution().outcome());
```

运行前需从项目根目录准备依赖、编译模块，并确保运行进程有 Docker Linux 引擎及固定镜像访问权限。当前验证只运行固定模板，执行器仍有创建超时清理竞态等限制，不能用于任意不可信源码。可用 `./module-b-codegen/scripts/check-docker-executor.ps1` 验证统一入口及代表性场景。
