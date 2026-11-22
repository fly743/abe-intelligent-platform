# harness-plugin-abe-qa

ABE（属性基加密）知识库问答插件，为 DeepSeek Harness 提供两个工具：

| 工具 | 作用 |
| --- | --- |
| `abe_qa` | 基于 ABE 知识库回答问题，答案带 `[n]` 引用编号与可核查来源；证据不足时明确拒答 |
| `abe_rag_health` | 检查知识库服务状态：集合、embedding 模型、维度、已入库片段数 |

插件是**薄适配层**：只做参数校验、HTTP 转发与结果渲染。检索、生成、引用校验全部在 Java 服务侧（Spring Boot + Spring AI + Qdrant + DeepSeek）。

```
Harness ──(abe_qa)──> HTTP POST /internal/rag/ask ──> RagQaService
                                                       ├─ RagRetrievalService (Qdrant, topK)
                                                       ├─ DeepSeek chat (system prompt)
                                                       └─ citation check / refusal
```

## 前置条件

Java 服务必须先启动，默认 `http://127.0.0.1:8080`：

```powershell
curl.exe -sS http://localhost:8080/internal/rag/health
# {"collection":"abe_knowledge","embeddingModel":"text-embedding-v4",
#  "chatModel":"deepseek-chat","dimensions":1024,"points":92,"status":"UP"}
```

服务地址不同时，在启动 Harness 的窗口设置：

```powershell
$env:ABE_RAG_BASE_URL = "http://127.0.0.1:8080"
```

## 安装（3 步）

```powershell
# 1) 启动 Harness（首次启动会创建 ~/.dsh/profiles/<name>）
cd D:\projects\deepseek-harness
pnpm dsh web

# 2) 在 UI 安装插件
#    设置 → 插件 → 添加插件 → 填本目录的绝对路径
#    D:/ABE-Program/harness-plugin/abe-qa
#    装完显示“已关闭”是正常的，不要手动开，第 3 步负责挂载

# 3) 写入用户 patch 层，然后干净重启
D:\ABE-Program\scripts\setup.ps1

Get-Process node -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
Test-NetConnection 127.0.0.1 -Port 3080 -InformationLevel Quiet   # 必须是 False
cd D:\projects\deepseek-harness; pnpm dsh web
```

在新进程打开的新 token 页面里调用 `abe_rag_health`，能返回集合与片段数即安装成功。

### 为什么第 3 步不可跳过

dsh 的 bundle 合成器只接受官方白名单里的包（`official-bundle-packages.ts`）。第三方包即使"安装成功"，也不会被写进合成的 `cordis.yml`，工具因此不可见。用户 patch 层 `~/.dsh/profiles/web/cordis.patch.yml` 不受白名单限制：

```yaml
- insert:
    - id: abe-rag
      name: 'harness-plugin-abe-qa'   # 必须是包名，不能是文件路径
```

`scripts/setup.ps1` 幂等写入这段配置（重复执行不会重复插入），并校验 `~/.dsh/profiles` 是否存在。

> 判断是否装好的正确依据是**新进程 + 新 token 页面下工具能否调用**，不是 `cordis.yml` 的内容——它是合成预览，不含用户 patch 层。

## 工具契约

### `abe_qa`

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `question` | string | 是 | 中文自然语言问题 |
| `topK` | number | 否 | 召回片段数，范围 1–8，默认 **3** |

返回结构化对象（同时渲染为 Markdown）：

```ts
interface AskValue {
  answer: string        // 含 [n] 编号的答案
  refused: boolean      // true 表示证据不足
  latencyMs: number     // 服务端耗时
  citations: Citation[] // 答案中实际引用到的片段
}

interface Citation {
  ref: number           // 与答案中的 [n] 对应
  docId: string         // 如 papers/bsw2007-cp-abe
  title: string
  section: string       // 已去掉页码噪音，如“3. CP-ABE 与 KP-ABE 的区别”
  source: string        // 如 papers/bsw2007-cp-abe.md
  score: number         // 0~1，Qdrant 相似度
}
```

前端展示建议：

- 正文里的 `[n]` 渲染成可点击角标，点击跳到对应 citation；
- `refused=true` 时不展示引用区；
- 来源卡片显示 `title · section`、`source` 与相似度。

两种 `refused=true` 的情况要注意区分：

| 情况 | `citations` | 含义 |
| --- | --- | --- |
| 检索结果为空（低于 `score-threshold`） | 空数组 | 知识库完全没召回到相关内容 |
| 模型判定证据不足 | 可能非空 | 召回到了片段，但不足以支撑回答 |

实测参考（41 条评测集、topK=5）：Recall@5 = 92.45%（49/53），Hit@5 = 100%，P50 延迟约 1.5–1.9s。

### `abe_rag_health`

无参数，返回可读文本：状态、集合名、embedding 模型与维度、片段数、chat 模型。

注意：**健康检查不校验 API key**。key 缺失时 health 仍然 `UP`，要到真正调用 `/ask` 才会报 401。所以体检通过不代表能问答。

## 修改后重新打包

必须在 **harness 仓库内**构建：插件目录没有 `node_modules`，esbuild 解析不到 `@deepseek-ai/*`；并且必须带 `--tsconfig`，依赖里有 TS 装饰器，否则产物残留 `@Remote` 导致 `failed to import`。

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

打包后 `lib/index.js` 约 320KB（自包含，含 `@deepseek-ai/dsh-tools` 与 `cordis` 的运行时引用）。**必须重启 Harness** 才会加载新产物。

## 排错

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 工具不存在 / `unknown tool` | 未写 patch 层，或 `name` 写成了路径 | 跑 `scripts\setup.ps1`；确认 `name: 'harness-plugin-abe-qa'` |
| 启动报 `JsonSchemaError: parameters.topK.required must be true` | 可选参数写了 `required: false` | 省略 `required` 字段；`object` 必须写 `additionalProperties` |
| 启动报 `failed to import` | 产物没打包依赖，或漏了 `--tsconfig` | 用上面命令 `--bundle` + `--tsconfig` 重新打包 |
| 装了插件但工具看不见 | 第三方 bundle 被合成器跳过 | 见上文“为什么第 3 步不可跳过” |
| patch 层条目消失 / 整份 yml 变注释 | 中文或换行导致 YAML 解析失败 | patch 层只用纯 ASCII 注释，改完逐行打印核对 |
| 工具调用 500 | Java 服务未启动或内部异常 | `curl.exe -sS http://localhost:8080/internal/rag/health`；看服务窗口日志 |
| 调用报 401 | 服务窗口未设置 `ABE_RAG_EMBEDDING_API_KEY` / `ABE_RAG_CHAT_API_KEY` | 在启动 Java 的同一个窗口设置后重启 |
| 调用报 403 | `/ingest` 未开门控 | 入库时设 `ABE_RAG_INGEST_ENABLED=true`；`/ask` 本身不受此限制 |
| 页面 `Failed to fetch` | Harness 进程已退出，页面是旧 token | 重启后用日志里最新 URL |

## 版本

当前 `0.1.2`，变更见 [CHANGELOG.md](./CHANGELOG.md)。
