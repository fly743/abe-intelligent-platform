# Changelog

本插件的所有重要变更记录在此文件。`lib/index.js` 是 esbuild 产物，随 `src/index.ts` 同步发布。

## [0.1.2] - 2026-11

### Added
- `abe_qa` 返回结构化对象 `{answer, refused, latencyMs, citations[]}`，前端可直接消费，不需要解析 Markdown
- `render` 同时输出带引用来源的 Markdown 视图，保持对话内可读
- `scripts/setup.ps1`：幂等写入 dsh 用户 patch 层，支持 `-PackageName` / `-EntryId` / `-ProfileName`
- `.env.example`：补齐服务启动所需的 4 个环境变量

### Changed
- `abe_qa` 的 `topK` 收敛到 1–8（默认 3），超出范围自动截断
- README 重写：三步安装、patch 层兜底原理、返回契约、排错表

### Fixed
- 打包产物残留 TS 装饰器导致 `failed to import`：改为 `--bundle --target=es2022 --tsconfig=<harness>/tsconfig.json`
- PowerShell 5.1 下脚本与 JSON 的编码兼容问题

## [0.1.1]

### Changed
- 插件移入 `harness-plugin/abe-qa` 子目录，作为独立 bundle 分发
- 补充 `dsh.bundle.patch` 声明与 `cordis.patch.yml`

## [0.1.0]

### Added
- 首个可用版本：`abe_qa`（检索 + 问答 + 引用校验 + 拒答）、`abe_rag_health`（服务健康检查）
