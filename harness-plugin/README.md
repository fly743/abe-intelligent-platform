# dsh-plugin-abe-rag

ABE（属性基加密）知识库问答插件，为 DeepSeek Harness 提供两个工具：

| 工具 | 作用 |
| --- | --- |
| `abe_qa` | 基于 ABE 知识库回答问题，答案带 `[n]` 引用编号与可核查来源；证据不足时明确拒答 |
| `abe_rag_health` | 检查知识库服务状态：集合、embedding 模型、维度、已入库片段数 |

插件本身不含检索与生成逻辑，只做参数校验、HTTP 转发与结果呈现；业务在 Java 服务侧（Spring Boot + Qdrant + DeepSeek）。

## 前置条件

Java 服务需先启动，默认地址 `http://127.0.0.1:8080`：

```powershell
curl.exe -sS http://localhost:8080/internal/rag/health
```

若服务地址不同，设置环境变量 `ABE_RAG_BASE_URL`。

## 安装

### 方式 A：本地目录（开发调试）

Harness 界面 → 设置 → 插件 → 右上角 **添加插件** → 填入本目录绝对路径：

```
D:/projects/abe-harness-plugin
```

### 方式 B：GitHub（分发给队友）

先把本仓库推到 GitHub，然后在 **添加插件** 里填：

```
github:<owner>/abe-harness-plugin
```

### 方式 C：npm（公开发布）

```powershell
npm publish
```

之后 **添加插件** 里填包名：

```
dsh-plugin-abe-rag
```

### 方式 D：临时 overlay（不改动用户配置）

在 harness 仓库内执行：

```powershell
pnpm dsh web --patch D:/projects/abe-harness-plugin/cordis.yml
```

## 验证

1. 问 Agent：`调用 abe_rag_health` → 返回状态、集合、维度、片段数
2. 问：`CP-ABE 和 KP-ABE 有什么区别？` → 调用 `abe_qa`，答案带 `[1]`，末尾有"引用来源"列表
3. 问：`ABE 怎么和格基后量子密码结合？` → 返回拒答与原因

## 卸载

插件面板中禁用或移除即可；工具随插件生命周期出现/消失。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 插件不激活，报 `parameters.xxx.required must be true when present` | 可选参数要**省略** `required` 字段，不能写 `false` |
| 工具调用报 `ABE RAG 服务返回 500` | Java 服务未启动或内部报错，查看其窗口日志 |
| 报 401 | Java 服务未设置模型 key |
| 报 403 | `/ask` 被门控拦截，需 `abe.rag.dev-endpoints.enabled=true` |
| 与 profile 里手写条目 id 重复导致激活失败 | 二者只能保留一个：`id: abe-rag` |
