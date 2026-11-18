# harness-plugin-abe-qa

ABE（属性基加密）知识库问答插件，为 DeepSeek Harness 提供两个工具：

| 工具 | 作用 |
| --- | --- |
| `abe_qa` | 基于 ABE 知识库回答问题，答案带 `[n]` 引用编号与可核查来源；证据不足时明确拒答 |
| `abe_rag_health` | 检查知识库服务状态：集合、embedding 模型、维度、已入库片段数 |

插件是**薄适配层**：只做参数校验、HTTP 转发与结果渲染，检索与生成在 Java 服务侧（Spring Boot + Qdrant + DeepSeek）。

## 前置条件

Java 服务需先启动（默认 `http://127.0.0.1:8080`）：

```powershell
curl.exe -sS http://localhost:8080/internal/rag/health
# {"collection":"abe_knowledge",...,"points":92,"status":"UP"}
```

服务地址不同则设置 `ABE_RAG_BASE_URL`。

## 安装（4 步）

```powershell
# 1 Harness 启动
cd <harness 目录>; pnpm dsh web

# 2 UI：设置 -> 插件 -> 添加插件 -> 填本目录绝对路径
#   例：D:/ABE-Program/harness-plugin/abe-qa
#   装完后若显示"已关闭"，无需手动开启（走第 3 步的 patch 层）

# 3 挂载到用户 patch 层（关键，不可跳过）
D:\ABE-Program\scripts\setup.ps1

# 4 重启并验证
Get-Process node -ErrorAction SilentlyContinue | Stop-Process -Force
pnpm dsh web
# 对话：调用 abe_rag_health
```

> **为什么必须第 3 步**：dsh 的 bundle 合成器只接受官方 bundle（`official-bundle-packages.ts` 的白名单），第三方包即使"安装成功"也不会写进合成的 `cordis.yml`，工具因此不可见。用户层 `cordis.patch.yml` 不受白名单限制，写进去一定生效。

## 返回结构（给前端 / 下游）

`abe_qa` 返回结构化对象，`render` 同时输出 Markdown：

```ts
interface AskValue {
  answer: string        // 含 [n] 编号的答案
  refused: boolean      // true 表示证据不足拒答
  latencyMs: number     // 服务端耗时
  citations: Citation[] // 答案实际引用的片段
}

interface Citation {
  ref: number           // 与答案中的 [n] 对应
  docId: string         // papers/bsw2007-cp-abe
  title: string
  section: string
  source: string        // papers/bsw2007-cp-abe.md
  score: number         // 0~1
}
```

前端建议：正文 `[n]` 渲染为可点击角标；`refused=true` 时不展示引用区；来源卡片显示 `title · section` + `source` + 相似度。

## 修改后重新打包

必须在 **harness 仓库内**构建（插件目录没有 `node_modules`，esbuild 解析不到 `@deepseek-ai/*`），且**必须带 tsconfig**（依赖里有 TS 装饰器，否则产物残留 `@Remote` 导致 `failed to import`）：

```powershell
$repo = "D:\projects\deepseek-harness"
$plug = "D:\ABE-Program\harness-plugin\abe-qa"
$esbuild = "$repo\node_modules\.pnpm\@esbuild+win32-x64@0.28.1\node_modules\@esbuild\win32-x64\esbuild.exe"

New-Item -ItemType Directory -Force -Path "$repo\.abe-build\src", "$plug\lib" | Out-Null
Copy-Item "$plug\src\index.ts" "$repo\.abe-build\src\index.ts" -Force
Set-Location "$repo\.abe-build"
& $esbuild "src/index.ts" --bundle --format=esm --platform=node --target=es2022 "--tsconfig=$repo\tsconfig.json" "--outfile=$plug\lib\index.js"
Set-Location $plug
Remove-Item "$repo\.abe-build" -Recurse -Force -ErrorAction SilentlyContinue
```

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 工具不存在（unknown tool） | 未写 patch 层，或 patch 层里的 `name` 与包名不一致 |
| 启动报 `did not activate` + `JsonSchemaError` | 输出 Schema 不合规：`object` 类型必须写 `additionalProperties: false`；可选参数不能写 `required: false` |
| 启动报 `failed to import` | 产物未打包依赖，或打包时漏了 `--tsconfig` |
| 工具调用报 500 | Java 服务未启动或内部报错；先 curl `/health` |
| 报 401 | Java 服务窗口未设置模型 key |
| 报 403 | `/ask` 被门控拦截，需 `abe.rag.dev-endpoints.enabled=true` |
