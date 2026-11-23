# C - Web 前端展示

当前模块只有 Maven 骨架，尚无前端页面。本文约定后续展示 B 模块结果时的数据来源与处理方式，不代表页面或端到端联调已经完成。

## 接入已有 B 接口

使用 `POST /internal/codegen/verify`，请求为 JSON，字段保持 `policy`、`attributes`、`plaintext`。B 独立服务默认端口为 `8081`；D 集成服务默认端口为 `8080`，装配同一路径。完整字段说明和成功、拒绝、环境异常示例见 [B 模块交接文档第七节](../docs/B模块交接文档.md#七c-模块-http-接入约定)。

该接口接收明确策略，不理解自然语言。自然语言需求需要先由 Harness 澄清和转换，不能直接作为 `policy`。当前 Harness 工具输出为 Markdown，前端不应解析聊天文字来还原结构化结果；自然语言结果如何传入 C 仍需后续接入，不在本轮新增接口。

## 最小展示内容

| 展示区域 | 数据来源 |
| --- | --- |
| 访问策略 | 本次请求快照的 `policy` |
| 测试用户属性 | 同一次请求的 `attributes` |
| 中文属性映射（如有） | 调用层保留的 Harness `attributeMapping`，B 响应不包含 |
| Java 代码 | `generated.files` 中名称为 `AbeExample.java` 的 `content` |
| 运行说明 | `generated.files` 中名称为 `README.md` 的 `content` |
| 生成状态及提示 | `generated.status`、`stage`、`message` |
| 编译、运行、清理 | `execution.compilation`、`execution.execution`、`execution.cleanup` |
| 最终结果及提示 | `execution.outcome`、`execution.message` |

按文件名查找源码和说明，不依赖文件顺序；不存在时展示“未生成对应文件”。源码和错误说明按文本显示，不作为 HTML 执行。请求快照只保留展示所需的策略、属性和可选映射，与各自响应对应；不要将演示明文或密钥加入结果持久化。

## 状态展示约定

- `generated.status=SUCCESS` 只表示源码已生成。即使后续失败，也展示已有源码；最终结果读取 `execution`。
- `execution.outcome=SUCCESS` 显示“授权解密成功”。
- `POLICY_NOT_SATISFIED` 显示“属性不满足策略，已拒绝访问”。运行字段当前为 `FAILED`，不能仅据此显示“算法故障”。
- `INVALID_INPUT`、`ENVIRONMENT_UNAVAILABLE`、`COMPILATION_FAILED`、`EXECUTION_FAILED`、`TIMEOUT`、`CLEANUP_FAILED` 分别显示输入非法、环境不可用、编译失败、执行失败、执行超时、清理未确认，并附已有 message。
- 阶段 `NOT_RUN` 显示“未执行”，清理 `NOT_NEEDED` 显示“无需清理”，不统一改成成功。按枚举判断状态，不依赖中文消息匹配。
- HTTP 200 不等于业务验证成功；网络失败、非 2xx 或响应解析失败单独显示请求/服务异常，不编造授权结果。
- 当前接口返回执行完成后的阶段汇总，不提供实时进度或初始化、密钥生成、加密、解密的独立状态。流程说明与实际采集的状态应明确区分。

本轮仅整理接入约定，不新增响应字段、前端框架或页面；前端部署、请求转发及实际联调留待 C 模块实施时确定。
