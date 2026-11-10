# ABE-project

本仓库用于协作开发 ABE Intelligent Platform，按功能划分为知识问答、代码生成与验证、Web 展示、系统集成四个模块。

## 目录结构

```text
.
├── module-a-rag/                 # A：ABE 知识问答插件
│   ├── docs/
│   │   ├── papers/              # ABE 论文和参考资料
│   │   ├── notes/               # 知识笔记
│   │   └── faq/                 # 常见问题
│   ├── evaluation/              # RAG 评测数据与评测记录
│   └── README.md
├── module-b-codegen/             # B：ABE 代码生成与验证
│   ├── crypto-poc/              # CP-ABE 算法验证
│   ├── templates/               # Java 代码模板
│   ├── sandbox/                 # 代码运行沙箱相关内容
│   ├── tests/                   # 测试用例
│   └── README.md
├── module-c-web/                 # C：Web 前端展示
│   └── README.md
├── module-d-integration/         # D：系统集成与测试
│   └── README.md
├── docs/                         # 项目整体设计和接口文档
├── deploy/                       # 公共部署配置
├── .gitignore                    # 忽略本地配置、密钥和数据文件
└── README.md                     # 项目总说明
```

## 当前进度

目前仓库已经建立 A、B、C、D 四个模块的基础目录，以及项目级的 `docs/` 和 `deploy/` 目录。部分暂时为空的目录使用 `.gitkeep` 占位，以便 Git 保留目录结构。

以下内容尚未创建，后续可根据技术方案逐步补充：

- `module-a-rag`：论文和知识笔记、FAQ 内容、RAG 评测数据，以及具体插件或服务工程。
- `module-b-codegen`：CP-ABE 验证代码、Java 模板、沙箱配置和测试用例。
- `module-c-web`：前端工程及页面实现。
- `module-d-integration`：集成测试、插件装配和测试脚本。
- `docs/`：整体设计、接口契约等正式文档。
- `deploy/`：Docker Compose、数据库等部署配置。

以上目录当前是项目骨架，不代表对应模块的功能代码已经实现。后续可以在各模块内逐步补充具体工程结构和实现。

## 协作建议

团队成员可以按负责模块创建独立开发分支，在对应目录中开发，完成后通过 Pull Request 合并到 `main`。请勿将 API Key、数据库密码、`.env` 文件或本地数据库数据提交到仓库。
```
