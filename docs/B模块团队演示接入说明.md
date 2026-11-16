# B 模块团队演示接入说明

本文面向可信团队成员共同维护演示系统的场景。当前 B 模块提供 Java API，没有面向最终用户的服务或交互式命令。主密钥、用户密钥和口令均不得提交到仓库；团队自行通过受控渠道共享演示密钥和口令，并管理文件目录权限。

## 初始化和密钥签发

1. 授权成员创建 `BswCryptoService`，调用 `exportPublicKey()` 保存公开参数。
2. 从安全输入渠道取得口令 `char[]`，调用 `exportProtectedMasterKey(password)`，使用 `BswProtectedKeyFiles.saveNew` 保存到受控目录。该方法不会覆盖已有文件；写入失败可能留下不完整文件，应检查后由维护人员处理。
3. 使用 `generateKey(attributes)` 按演示角色生成用户密钥，调用 `exportProtectedUserKey(key, password)` 保护后分别保存。主密钥持有者能签发任意属性的用户密钥；不同角色密钥用于功能演示，不代表团队管理员之间彼此隔离。
4. 在可信的初始化环境中调用 `BswCryptoService.publicKeyFingerprint(publicData)` 获取 32 字节摘要，并通过独立可信渠道向团队分发并核对。不要只把指纹与公开参数一起放在同一个可替换位置。
5. 口令使用完后由调用方执行 `Arrays.fill(password, '\0')`。不要把口令写进命令行参数、代码、配置或日志。程序无法保证 JVM、上游对象或操作系统中的所有副本都能被清除。

## 跨进程恢复和使用

1. 从可信渠道取得指纹 `trustedFingerprint`，读取公开参数字节，先调用 `BswCryptoService.verifyPublicKeyFingerprint(publicData, trustedFingerprint)`。此检查不自动执行；调用方必须确保指纹来自独立可信渠道，不能用刚读入文件自己计算的指纹当作可信依据。
2. 从受控目录通过 `BswProtectedKeyFiles.read` 读取主密钥文件，调用 `BswCryptoService.restoreProtected(publicData, protectedMaster, password)` 恢复签发方。仅加密或解密时，可在完成指纹检查后通过 `restore(publicData, null)` 创建不持有主密钥的实例。
3. 使用 `importProtectedUserKey(protectedUserKey, password)` 导入用户密钥；使用 `importCiphertext` 导入密文，再调用 `decrypt`。属性不满足时抛出 `PolicyNotSatisfiedException`。
4. 密文可以通过 `exportCiphertext` 导出；公开参数和密文文件的存取及大小限制由调用方负责。密钥文件读取有大小上限，密码认证后才进入密钥解析。

口令和密钥文件应通过受控渠道共享，建议分别传递。保护文件无法防止删除或回滚，也不能保护被入侵的运行进程。主密钥文件和口令需要受控备份：口令丢失无法恢复。若口令泄漏，应更换保护口令并重新保护文件；若主密钥本身泄漏，重新加密文件无法撤销泄漏，需另行规划新系统参数及迁移。

## 验证及已知限制

完成模块编译后，执行 `./module-b-codegen/scripts/check-persistence.ps1`。脚本使用测试口令，验证独立进程的受保护密钥读写及主要错误场景。测试口令只供验证，不能作为团队实际口令。

公开参数恢复仍缺少上游的 `f`，不提供依赖它的密钥委托；固定旧配对参数、jPBC 许可及上游恶意输入处理仍待核查。结构检查和功能测试均不构成密码学安全审计。
