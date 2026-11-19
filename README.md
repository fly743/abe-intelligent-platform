# ABE-project

本仓库用于协作开发 ABE Intelligent Platform，按项目实施任务分为知识问答、代码生成与验证、Web 展示、系统集成与测试四部分。任务推进顺序以 `docs/项目背景与任务记忆.md` 记录的项目实施方案图片为准；技术选型可按团队后续决定调整。

知识检索模块的现状、内部接口、配置与后续工作见 [技术文档](docs/ABE知识检索模块技术文档.md)。 团队成员首次配置与导入请参阅 [团队本机部署指南](docs/团队本机部署与语料导入.md)。

## 模块

- `module-a-rag/`：ABE 知识问答/RAG，已有语料、检索、问答和 Harness 插件。
- `module-b-codegen/`：ABE 代码生成与验证，已有策略解析、BSW 密码适配、Java 示例生成和 Docker 执行原型；已有统一调用与基础执行异常分类，尚未完成系统集成，构建准备见 [B 模块说明](module-b-codegen/README.md)。
- `module-c-web/`：Web 展示，当前为 Maven 模块骨架，功能待开发。
- `module-d-integration/`：系统入口与集成，包含 Spring Boot 应用、配置及对 A、B 模块的依赖。
- `deploy/`：当前包含 Qdrant Docker Compose 配置。
- `docs/项目背景与任务记忆.md`：项目背景、图片规定的实施顺序、报告分工参考和基于仓库证据的进度记录。

## 当前进度

A 已有检索、问答及 Harness 插件，见 [A 模块说明](module-a-rag/README.md)。B 已有固定 Java 示例生成、Docker 验证、内部 HTTP 接口及独立 Harness 插件，见 [B 插件说明](harness-plugin/abe-codegen/README.md)。C 仍为骨架，D 提供集成启动入口；完整系统集成与测试仍待完成。

## 协作约定

不要提交 API Key、数据库密码、`.env` 文件或本地数据库数据。技术选型和实现方案按团队最新决定更新。
