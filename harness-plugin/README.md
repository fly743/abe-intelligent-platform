# harness-plugin

DeepSeek Harness 业务插件集合。每个子目录是一个**可独立安装的 npm 包**。

## 目录结构

```
harness-plugin/
└─ abe-qa/                  ABE 知识库问答插件（harness-plugin-abe-qa）
   ├─ package.json          name / version / dsh.bundle.patch
   ├─ cordis.patch.yml      bundle 条目（提交用，name 填包名）
   ├─ cordis.yml            本地开发 overlay（name 填 lib/index.js 绝对路径）
   ├─ src/index.ts          插件源码（abe_qa / abe_rag_health）
   ├─ lib/index.js          esbuild 产物（自包含，需一并提交）
   └─ README.md             安装与排错说明
```

## 新增一个插件的步骤

1. 在 `harness-plugin/` 下建目录 `<插件名>/`
2. 复制 `abe-qa/package.json` 改三处：`name`（如 `harness-plugin-xxx`）、`version`、`description`
3. `cordis.patch.yml` 里把 `id` 与 `name` 换成新插件的
4. `src/index.ts` 用 `defineTool` 注册工具，`export const name / inject / apply`
5. 打包（必须带 tsconfig，否则 TS 装饰器残留会导致 `failed to import`）：

```powershell
$repo = "D:\projects\deepseek-harness"
$plug = "D:\ABE-Program\harness-plugin\<插件名>"
$esbuild = "$repo\node_modules\.pnpm\@esbuild+win32-x64@0.28.1\node_modules\@esbuild\win32-x64\esbuild.exe"
New-Item -ItemType Directory -Force -Path "$repo\.abe-build\src", "$plug\lib" | Out-Null
Copy-Item "$plug\src\index.ts" "$repo\.abe-build\src\index.ts" -Force
Set-Location "$repo\.abe-build"
& $esbuild "src/index.ts" --bundle --format=esm --platform=node --target=es2022 "--tsconfig=$repo\tsconfig.json" "--outfile=$plug\lib\index.js"
Set-Location $plug
Remove-Item "$repo\.abe-build" -Recurse -Force
```

6. 安装：Harness → 设置 → 插件 → 添加插件 → 填该子目录绝对路径
7. **必须**在用户 profile 里补一条（第三方 bundle 会被合成器跳过，详见下节）：

```
%USERPROFILE%\.dsh\profiles\web\cordis.patch.yml
- insert:
    - id: abe-rag
      name: 'harness-plugin-abe-qa'
```

8. 重启 Harness，用工具名验证（不要用 `cordis.yml` 里有无条目做判断）

## 两个必须知道的约束

1. **第三方 bundle 会被 bundle 合成器跳过**（只接受官方白名单），所以插件虽能"安装"进 `package.json` 的 `bundles`，条目不会写进 `cordis.yml`。必须用用户 patch 层挂载，`name` 填**包名**。
2. **YAML/JSON 配置文件一律纯 ASCII 注释**。中文注释在 PowerShell 写入时曾两次导致换行丢失、整份文件被当注释解析。

## 环境依赖

| 组件 | 位置 |
| --- | --- |
| Java RAG 服务 | `http://127.0.0.1:8080`（可用 `ABE_RAG_BASE_URL` 覆盖） |
| 必需接口 | `POST /internal/rag/ask`、`GET /internal/rag/health` |
