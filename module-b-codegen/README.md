# B - ABE 代码生成与验证

## 当前能力

已实现内部请求/结果模型、属性校验、策略解析、BSW 后缀策略转换以及内存中的 BSW + AES-GCM 密码适配。代码生成、跨进程密钥管理、沙箱和对外接口尚未实现。

## 构建准备

首次构建前在项目根目录运行 `./module-b-codegen/scripts/prepare-bsw.ps1`。脚本下载固定提交 `0e71de17ed64729f2de811503a7958673691e77a`，检查 SHA-256，编译上游 BSW 并在 `module-b-codegen/.bsw-local/repository` 生成本地 Maven 工件。随后运行 `mvn -pl module-b-codegen clean compile`。该目录已加入 `.gitignore`，普通 Maven clean 不会删除它。

上游 BSW 仓库标注 GPL-2.0；jPBC 1.2.1 原始发行许可和分发义务仍需核查。准备脚本只在本机获取工件，不将第三方 JAR 提交到仓库。

`BswCryptoService` 是实例级内存适配层：同一实例生成的用户密钥用于解密该实例的密文，允许空明文，最大明文为 1 MiB。其密钥和密文不能跨进程保存；加密数据和策略密文通过 AES-GCM 关联，属性不满足会抛出 `PolicyNotSatisfiedException`。当前同步访问并为每次解密恢复独立策略树，避免上游共享可变密文的并发问题。该代码尚未提供对外接口，固定参数和安全限制见选型记录。

## 策略与属性规则

- 策略支持属性、`AND`、`OR`、括号；运算符不区分大小写，属性区分大小写。括号优先，其次 `AND`，最后 `OR`。
- 属性为 1 至 64 位 ASCII 字母、数字、下划线或连字符，首字符不能是连字符；`AND`、`OR` 是保留字。用户属性数量为 1 至 64，不允许完全相同的属性重复。
- 策略最多 4096 个字符、32 层括号、64 个属性叶子。解析器和 BSW 策略转换器支持同一属性在不同分支重复出现；候选密码实现已通过相关场景验证，但尚未接入正式流程。
- `BswPolicyConverter` 将策略树转为 BSW 的后缀门限表达式；属性先进行无歧义编码以避免上游将包含 `of` 的名称误判为运算符。未来密钥生成必须对相同属性使用 `encodeAttribute`。
- 以上是解析阶段的上限；后续密码参数可能设置更低的属性和策略规模上限，须在密码适配阶段统一校验。
- `PolicyException.getPosition()` 返回策略中的零基 UTF-16 偏移；属性列表错误使用 `-1`。异常信息中的位置为一基位置。

入口示例：`CodegenRequest.from(policy, attributes, plaintext)` 构造内部请求；`BswPolicyConverter.convert(request.policy())` 生成后缀策略。请求中的明文暂不限定长度，对外接入时须设置与执行环境一致的限制。
