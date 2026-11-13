# ABE 智能开发系统：知识检索模块技术文档

- 文档状态：当前实现与后续计划，供项目交接使用。
- 核对日期：2026-11-13。
- 对应代码：`module-a-rag`、`module-d-integration`；实际配置和 Qdrant 状态可能在之后变化。
- 技术方案以当前代码和团队后续决定为准，本文不是已完成全部功能的声明。

## 1. 项目目标与当前边界

项目按实施方案分为 ABE 知识问答、代码生成与验证、Web 展示、系统集成与测试四部分。当前重点是第一部分中的语料准备和知识检索。

当前已完成：

1. 五篇论文的资料卡、中文知识笔记、分类 QA，及一份 CP/KP/MA 对比说明。
2. 论文知识笔记和 QA 分别写入 Qdrant 不同集合；默认检索知识笔记集合。
3. Markdown 按标题及长度切分、向量化入库、相似度检索、内部调试接口和出处字段返回。
4. 面向密文与私钥属性关系问题的可关闭查询扩展，以及小样本检索前后对比。
5. 对真实 Embedding 测试增加显式启用条件，移除早期会污染测试的字符直方图假模型。

尚未完成：DeepSeek 或其他聊天模型的答案生成、回答引用校验及证据不足处理、结构化多来源引用、Harness/Cordis 插件适配、QA 与笔记联合检索、完整检索评测。当前检索返回证据片段，不直接生成最终答案。B 代码生成验证、C Web 展示和 D 完整系统集成仍需后续开发。

## 2. 技术栈与目录

| 组件 | 当前代码配置 |
| --- | --- |
| Java | 17，见根 `pom.xml` |
| Spring Boot | 3.5.16，见根 `pom.xml` |
| Spring AI | 1.1.8，见根 `pom.xml` |
| 文本 Embedding | 默认百炼 OpenAI 兼容接口、`text-embedding-v4`、1024 维；可通过配置覆盖 |
| 向量数据库 | Qdrant；`deploy/docker-compose.yml` 当前使用 `qdrant/qdrant:v1.13.6` |
| 应用入口 | `module-d-integration/src/main/java/com/abe/integration/AbeApplication.java` |
| 模块依赖 | D 模块依赖 A 模块，通过 Spring 扫描 `com.abe` 装配 |

主要目录：

```text
module-a-rag/
├── docs/papers/       5 篇论文中文知识笔记
├── docs/faq/          5 份分类 QA，文件名以 .qa.md 结尾
├── docs/notes/        CP/KP/MA 综合对比
├── evaluation/        评测题、评测记录和评测 JSON，不是入库语料
└── src/               切分、入库、检索、配置与测试
module-d-integration/  Spring Boot 服务入口及配置
deploy/                Qdrant Compose 配置
docs/                  项目说明、论文资料卡、语料与入库记录
```

`module-a-rag` 是 Maven 模块，不等于当前已实现完整 Harness 插件。项目内的内部 RAG 调试接口属于 Java 服务。

## 3. 初期论文与语料组织

| 资料 | 主要覆盖 | 本地笔记 |
| --- | --- | --- |
| Bethencourt、Sahai、Waters 2007 | CP-ABE 基本构造、访问树、抗合谋 | `module-a-rag/docs/papers/bsw2007-cp-abe.md` |
| Waters 2011 | CP-ABE、LSSS、选择性安全与假设 | `module-a-rag/docs/papers/waters2011-cp-abe.md` |
| Li 等 2013 | 医疗 PHR、个人域/公共域、撤销、紧急访问 | `module-a-rag/docs/papers/li2013-phr-abe.md` |
| Goyal 等 2006 | KP-ABE、访问树、选择性集合安全和委托 | `module-a-rag/docs/papers/gpsw2006-kp-abe.md` |
| Chase 2007 | 多授权机构、GID、可信中央机构及抗合谋 | `module-a-rag/docs/papers/chase2007-ma-abe.md` |

五份 QA 分别以 `.qa.md` 命名；综合对比位于 `module-a-rag/docs/notes/abe-scheme-comparison.md`。用户已确认初期以这五篇论文为范围。每篇的作者、出版信息和 PDF 页序在 `docs/` 下的对应资料卡中。

文献解释是项目知识语料，不代替论文原文、密码学审计或具体方案的安全证明；例如 Chase 2007 依赖可信中央机构，Li 2013 的部分证明和实验指向未取得的补充材料。

## 4. 代码执行流程

### 4.1 系统装配

`AbeApplication` 启动 Spring Boot，D 模块引入 A 模块。`RagVectorStoreConfiguration` 按 `abe.rag` 配置创建 Qdrant 客户端、OpenAI 兼容 Embedding 模型和 `QdrantVectorStore`。模型使用 `MetadataMode.EMBED`；配置类当前构建向量存储时固定调用 `initializeSchema(true)`，不能仅凭 `RagProperties.Qdrant.initializeSchema` 属性推断已生效。

Qdrant Compose 中 `6333` 为 REST 管理接口，`6334` 为 Java 客户端使用的 gRPC 端口。存储数据位于 Docker 命名卷 `qdrant_storage`；Compose 默认仅绑定本机回环地址。配置修改后已有容器需要重新创建才会生效。

### 4.2 摄取与入库

`CorpusIngestService.ingest(recreate)` 的流程：

```text
语料根目录
  → 递归筛选 .md/.txt 文件及 QA 开关
  → MarkdownCorpusSplitter 按标题和长度切分
  → 组装 Document 与来源元数据
  → 依据批量大小调用 Embedding
  → QdrantVectorStore 写入当前配置的集合
  → 返回入库报告
```

- 根目录由 `abe.rag.ingest.corpus-dir` 指定；D 模块配置默认是 `../module-a-rag/docs`，相对于**实际运行工作目录**解析。
- 扫描非点开头的 `.md` / `.txt` 文件。`include-qa=false` 时跳过以 `.qa.md` 结尾的文件；它是文件名约定，不是内容去重。
- 切分器提取 `title`、`type`、`sourceUrl`，用二级及以下标题作为章节；正文按字符数切块并保留重叠。
- 向量元数据包含 `docId`、`title`、`section`、`type`、相对路径 `source`、`chunkIndex`，有链接时保存 `sourceUrl`。
- 点 ID 根据文档 ID、片段索引和正文 SHA-256 构造。相同片段再次写入同一 ID 时不会因为随机 ID 追加重复点；但仍会重新执行 Embedding，可能产生费用。
- 内容、顺序或切分方式变化可能产生新 ID；普通入库不会自动清理旧点，可能出现旧、新片段并存。
- `recreate=true` 会尝试删除并重建**当前配置的整个集合**，不是只更新某个文档；执行前必须确认目标和数据影响。
- 文件处理中的异常会记录到 `failures`；报告中的计数并非完整的事务性保证。发生失败时应检查报告和实际集合，不能只看请求是否返回。

### 4.3 检索与上下文

`RagRetrievalService.retrieve(query, topK, docId)` 的流程：

1. 空白查询直接返回空列表。
2. 对特定密文、属性、密钥及策略关系问题，按配置补充 CP/KP 两个方向的中性术语；原问题保留。
3. 构建 Spring AI `SearchRequest`，应用 `topK`、相似度阈值；如果指定 `docId`，使用文档 ID 精确过滤。
4. 当前配置的 Qdrant 集合执行向量相似度检索，返回片段及分数。
5. 将正文、编号、标题、章节、路径、类型和单个来源链接转换成 `RetrievedSnippet`。

`buildContext()` 把命中结果整理为带编号的片段及来源文字，供以后答案生成使用。它目前不调用聊天模型，也不验证回答是否受证据支持。

### 4.4 配置项

以下是 D 模块 `application.yml` 当前值；`RagProperties` 中某些 Java 默认值可能不同，最终生效值取决于运行配置及环境变量。

| 配置项或环境变量 | 当前值或作用 |
| --- | --- |
| `ABE_RAG_EMBEDDING_API_KEY` | Embedding 密钥，环境变量读取；不要提交或记录其值 |
| `ABE_EMBEDDING_BASE_URL` | 默认 `https://dashscope.aliyuncs.com/compatible-mode` |
| `ABE_EMBEDDING_MODEL` | 默认 `text-embedding-v4` |
| `abe.rag.embedding.dimensions` | `1024` |
| `abe.rag.embedding.batch-size` | `10` |
| `QDRANT_HOST` | 默认 `localhost` |
| `QDRANT_GRPC_PORT` | 默认 `6334` |
| `QDRANT_COLLECTION` | 默认 `abe_knowledge`；设置 `abe_knowledge_qa` 可切换到 QA 集合 |
| `ABE_CORPUS_DIR` | 覆盖摄取目录；相对路径基于进程工作目录 |
| `abe.rag.ingest.chunk-max-chars` / `chunk-overlap-chars` | `800` / `120` |
| `ABE_RAG_INCLUDE_QA` | 默认 `false`，不扫描 `.qa.md` |
| `ABE_SERVER_BIND_ADDRESS` | 默认 `127.0.0.1`，服务仅监听本机 |
| `ABE_RAG_INGEST_ENABLED` | 默认 `false`，关闭入库接口 |
| `ABE_RAG_ALLOW_RECREATE` | 默认 `false`，关闭重建集合，且还需开启入库 |
| `abe.rag.retrieve.top-k` | `5`；`search` 和 `context` 可用请求参数覆盖 |
| `abe.rag.retrieve.score-threshold` | `0.15`，目前是配置值，不代表该分数以上一定相关 |
| `abe.rag.retrieve.relationship-query-expansion` | `true`；设为 `false` 可停用双向术语扩展 |
| `abe.rag.dev-endpoints.enabled` | `true`；设为 `false` 禁用整个 `/internal/rag` 控制器 |

服务一次只装配和查询一个配置集合；切换到 QA 不会自动混合两个集合或去重。

## 5. 内部 HTTP 接口

### 5.1 变更说明

以下四条路径在本轮工作之前已存在，**不是本轮新增的 HTTP 路径**。本轮扩展了 `search` 和 `context` 返回片段中的 `type`、`sourceUrl`，并让上下文文本包含非空来源链接；新增的是前文所述配置选项。它们是开发调试接口，不应作为已有的公开产品 API 或稳定版本化协议理解。

目前额外增加了入库和重建开关，默认拒绝入库。默认服务地址为 `http://localhost:8080`，控制器前缀 `/internal/rag`。设置 `abe.rag.dev-endpoints.enabled=false` 时整个控制器不注册；配置缺失时注解采用启用行为。

### 5.2 GET /internal/rag/health

用途：返回当前集合名、Embedding 配置、维度及 Qdrant 点数查询状态。

成功的响应结构示例（值仅示意）：

```json
{
  "collection": "abe_knowledge",
  "embeddingModel": "text-embedding-v4",
  "dimensions": 1024,
  "points": 92,
  "status": "UP"
}
```

当点数查询抛出运行时异常时，响应包含 `status: "DOWN"` 和 `error`。该方法检查的是集合点数查询结果；`UP` **不证明**远程 Embedding、DeepSeek 或完整问答链路可用，也不代表 HTTP 状态一定随内部状态变化。

### 5.3 POST /internal/rag/ingest

参数：`recreate`，可选，布尔值，默认 `false`。使用当前服务配置的语料目录与集合，不是按请求选择集合或目录。

默认入库开关关闭，调用返回 HTTP 403；开启 `ABE_RAG_INGEST_ENABLED=true` 才可普通导入。`recreate=true` 还需额外设置 `ABE_RAG_ALLOW_RECREATE=true`，否则返回 403。

返回 `IngestReport`：

```json
{
  "collection": "abe_knowledge",
  "corpusDir": "实际解析后的目录",
  "fileCount": 1,
  "chunkCount": 19,
  "pointCount": 92,
  "elapsedMs": 1000,
  "failures": []
}
```

示例中的点数仅说明字段结构。`pointCount` 是执行后**整个集合**的点数，不是新增数量。默认 `recreate=false` 也会向量化并写入；`recreate=true` 会影响整个当前集合。不要为了查看状态调用入库接口。

### 5.4 GET /internal/rag/search

| 参数 | 必填 | 行为 |
| --- | --- | --- |
| `q` | 是 | 用户问题；空白值返回空数组 |
| `topK` | 否 | 返回候选数；缺省或非正数使用配置值，D 模块当前为 5 |
| `docId` | 否 | 非空时按元数据 `docId` 精确过滤；不是文件名模糊搜索 |

响应为数组，每个对象含 `ref`、`docId`、`title`、`section`、`source`、`score`、`text`、`type`、`sourceUrl`。`source` 是语料相对路径，不一定等同 DOI；旧点缺少元数据时对应字符串为空。`score` 是相似度，不等同答案正确概率。

PowerShell 查询示例：

```powershell
$query = [uri]::EscapeDataString('什么是 KP-ABE？')
Invoke-RestMethod "http://localhost:8080/internal/rag/search?q=$query&topK=3"
```

该调用会对问题执行 Embedding，可能产生费用；示例不能代替服务鉴权或正式评测。

### 5.5 GET /internal/rag/context

参数：`q` 必填、`topK` 可选；当前没有 `docId` 参数。响应包含原始 `query`、`snippets` 和拼接好的 `context`。片段结构与 `search` 相同；`context` 是给未来回答模块使用的证据文本，不是答案。

### 5.6 接口使用限制

当前控制器以开发调试为用途，默认监听本机，入库和重建分别由开关控制。开关并非用户身份认证；代码没有实现按用户授权、请求体积限制、限流或按文档增量删除。`/ingest?recreate=true` 在两个开关开启时会删除整个集合；对外部署或共享服务之前仍需认证授权及网络隔离。不要把“internal”路径名称当成访问保护。

## 6. 当前 Qdrant 数据

2026-11-13 只读核对：

| 集合 | 内容 | 点数 | 默认查询 |
| --- | --- | ---: | --- |
| `abe_knowledge` | 五篇论文知识笔记及跨论文对比 | 92 | 是 |
| `abe_knowledge_qa` | 五篇分类 QA 与文献说明 | 135 | 否 |

这反映核对时的 Qdrant 状态，不是代码承诺或后续实时数量。旧假 Embedding、旧测试及复测集合已按用户要求删除。两个正式集合分别追加过真实 Embedding 生成的片段；评测集不在其中。原始 PDF 仍在用户提供的位置，不作为仓库语料扫描文件。

综合对比的 6 个片段在正文保留多个来源，但当前单一 `sourceUrl` 元数据无法表达多个来源；`sourceUrl` 可能为空。其他章节信息通常包含论文标识和 PDF 页序，但不要把“带链接”直接等同于该片段足以支持最终答案。

## 7. 测试和已知结果

### 7.1 测试文件

- `CorpusScopeAndCitationTest`：测试 QA 默认扫描范围及样本文档的切分元数据。
- `RagRetrievalServiceTest`：模拟 `VectorStore`，检查检索引用字段和旧元数据兼容。
- `RelationshipQueryExpansionTest`：检查双向术语扩展、关闭开关及空查询。
- `QdrantSmokeTest`、`RealEmbeddingSmokeTest`、`CorpusIngestTest`：真实服务相关测试，需 `-Dabe.integration.real=true` 和 `ABE_RAG_EMBEDDING_API_KEY` 才启用，可能调用收费服务；部分测试会写入测试集合。
- `RetrievalEvaluationTest`：真实检索评测采样，需要 `-Dabe.evaluation.real=true` 和 API Key；使用显式业务配置与真实模型类型断言，按题查询并在 `target/` 输出候选快照。

最近一次相关离线测试运行了 6 项、全部通过；最近真实检索评测使用 12 题开发集和 4 题补充集。更早测试曾因 `@Primary` 假 Embedding 被误判为真实调用；假模型已移除，历史结果已更正，不应作为真实效果证据。

### 7.2 第一轮检索评测

以人工指定相关章节判断命中，真实 Embedding、单集合检索、候选 10、阈值 0.15：

| 题集 | 查询扩展 | 前 1 条命中 | 前 3 条命中 |
| --- | --- | --- | --- |
| 开发集 12 题 | 关闭 | 10/12 | 11/12 |
| 开发集 12 题 | 开启 | 11/12 | 12/12 |
| 补充集 4 题 | 关闭 | 2/4 | 3/4 |
| 补充集 4 题 | 开启 | 3/4 | 4/4 |

这是命中指定章节的题数，不是生成答案正确率。开发集包含促成本次调整的问题；补充集的相关章节在查看结果后作过人工补充，故不是盲法独立评测。结果和复现参数见 `module-a-rag/evaluation/retrieval-improvement-20261113.md`，题集位于 `retrieval-dev.json`、`retrieval-holdout.json`。

## 8. 实际工作中的主要修改

| 文件或区域 | 变更及原因 |
| --- | --- |
| `docs/`、`module-a-rag/docs/` | 新增论文资料卡、笔记、QA、综合对比和语料说明；建立可追溯知识基础 |
| `module-a-rag/evaluation/` | 增加集中评测题、开发与补充数据及真实检索记录；评测文件不入库 |
| `RagProperties.java`、D 模块 `application.yml` | 增加 `include-qa` 和 `relationship-query-expansion` 控制 |
| `CorpusIngestService.java` | 根据 QA 开关筛选文件；保留原有切分、写入及 ID 机制 |
| `RetrievedSnippet.java`、`RagRetrievalService.java` | 返回文档类型、单个来源 URL，构建上下文时附来源链接；补充双向术语扩展 |
| `src/test/` | 移除字符直方图假模型，隔离并显式启用真实服务测试，新增范围、引用和查询扩展测试及检索采样 |
| 根 `README.md` | 更新项目状态与语料情况 |
| 旧 FAQ、评审副本和旧 Qdrant 测试集合 | 按用户要求清理；不代表删除正式语料或原论文 |

## 9. 下一步与优化顺序

1. **回答闭环**：按团队计划接入 DeepSeek 或选定的聊天模型；回答使用检索片段，支持证据不足时明确提示，并让引用编号对应实际片段。该能力当前不存在。
2. **检索评测**：扩充人工标注题目，固定独立测试集，检查 KP 定义、方案对比、反例、跨文献和无答案问题；测量相关章节命中、排序和来源正确性。不要仅依赖 16 道开发/补充问题。
3. **查询扩展评估**：当前规则只覆盖特定中文表述，可能改变其他问题排序；持续评估误触发、同义表达和语义变化，保留关闭能力。
4. **QA 检索与去重**：比较笔记、QA 及组合候选；目前没有自动混合或统一分数校准，不能直接假定两个集合的结果可直接合并。
5. **多来源引用**：设计章节级来源列表、兼容现有 `sourceUrl` 和旧向量元数据；在不必要时避免重复向量化。综合比较文档当前将多个来源保留在正文。
6. **语料更新与可靠性**：设计文档版本与增量清理，避免内容更新后旧点残留；完善摄取失败、批处理重试和状态报告；评估 Markdown 表格和公式的切分质量。
7. **部署与集成**：本机默认关闭入库和重建并限制监听地址；共享或对外部署仍需身份认证授权。确定 Harness 插件契约、前端及系统集成方案。

## 10. 运行与复现提示

团队首次部署与导入步骤详见 [团队本机部署与语料导入](团队本机部署与语料导入.md)。项目需 Java 17、Maven、可访问的 Qdrant 与 Embedding 服务。具体部署和团队环境配置应按实际环境核对；不要将密钥写入仓库。常用准备命令示例（在项目根目录）：

```powershell
docker compose -f deploy/docker-compose.yml up -d
mvn -pl module-d-integration -am install -DskipTests
mvn -pl module-d-integration spring-boot:run
```

运行入口和 Maven 依赖是否可解析取决于本机环境；切换工作目录后应检查 `ABE_CORPUS_DIR` 的实际路径。上例不调用 `/ingest`，不会主动重新写入知识库，但应用启动可能连接 Qdrant 并按当前配置初始化集合。检索和入库会调用真实 Embedding，可能产生费用。实时评测要显式开启对应开关，详细方法见评测报告。

Codex 的沙箱 Java `user.home` 可能与用户 Windows 账户不同。需要使用已有缓存时，可在当前命令中显式传入 `-Dmaven.repo.local` 指向本机 Maven 仓库；这是环境差异，不应因此修改项目依赖版本。
