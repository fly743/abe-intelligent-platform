import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'

/**
 * ABE 知识库插件（可分发版）。
 *
 * 分层：Harness（本插件）→ HTTP → Java 服务（向量检索 + DeepSeek 生成 + 引用校验）。
 * 插件只做参数校验、转发与结果呈现，业务逻辑全在 Java 侧，便于单独替换实现。
 *
 * 环境变量：
 *   ABE_RAG_BASE_URL   Java 服务地址，默认 http://127.0.0.1:8080
 */

export const name = 'abe-rag'

/** 依赖 Cordis 的工具注册能力 */
export const inject = ['tools']

const BASE_URL = process.env.ABE_RAG_BASE_URL ?? 'http://127.0.0.1:8080'

interface Citation {
  ref?: number
  docId?: string
  title?: string
  section?: string
  source?: string
  sourceUrl?: string
  score?: number
}

interface AskResponse {
  question?: string
  answer?: string
  refused?: boolean
  reason?: string
  citations?: Citation[]
  retrievedCount?: number
  invalidRefs?: number[]
  latencyMs?: number
}

const ERROR_HINT: Record<number, string> = {
  401: 'Java 服务未配置模型 key（在启动服务的窗口里设置环境变量）',
  403: '接口被门控拦截（/ask 需要 abe.rag.dev-endpoints.enabled=true）',
  500: 'Java 服务内部错误，查看服务窗口日志',
}

async function requestJson(path: string, init?: RequestInit): Promise<unknown> {
  const res = await fetch(`${BASE_URL}${path}`, init)
  const text = await res.text()
  if (!res.ok) {
    const hint = ERROR_HINT[res.status] ?? '未知错误'
    throw new Error(`ABE RAG 服务返回 ${res.status}（${hint}）：${text.slice(0, 300)}`)
  }
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`ABE RAG 服务返回非 JSON 内容：${text.slice(0, 300)}`)
  }
}

function formatCitations(citations: Citation[]): string {
  if (!citations.length) return '（无引用）'
  return citations
    .map((c) => {
      const ref = c.ref ? `[${c.ref}] ` : ''
      const where = [c.title, c.section].filter(Boolean).join(' · ')
      const src = c.source ? ` — \`${c.source}\`` : ''
      const score = typeof c.score === 'number' ? `（相似度 ${c.score.toFixed(2)}）` : ''
      return `- ${ref}${where || c.docId || '未命名片段'}${src}${score}`
    })
    .join('\n')
}

function formatAnswer(data: AskResponse): string {
  const answer = (data.answer ?? '').trim()
  if (data.refused) {
    return [`**拒答**：${answer || '根据现有资料无法确定。'}`, `> 原因：${data.reason ?? '证据不足'}`].join(
      '\n\n',
    )
  }
  const parts = [answer]
  parts.push(`---\n**引用来源**\n${formatCitations(data.citations ?? [])}`)
  const meta: string[] = []
  if (typeof data.retrievedCount === 'number') meta.push(`召回 ${data.retrievedCount} 条`)
  if (data.invalidRefs?.length) meta.push(`无效引用编号 ${data.invalidRefs.join(',')}`)
  if (typeof data.latencyMs === 'number') meta.push(`耗时 ${data.latencyMs}ms`)
  if (meta.length) parts.push(`> ${meta.join(' · ')}`)
  return parts.join('\n\n')
}

export function apply(ctx: Context): void {
  ctx.tools.register(
    defineTool({
      name: 'abe_qa',
      description:
        '基于 ABE（属性基加密）知识库回答问题。答案带 [n] 引用编号，随附可核查的来源（论文/文档出处）。' +
        '知识库未覆盖的问题会明确拒答，不编造。适用于 ABE 概念、方案对比、密码学实现细节等提问。',
      parameters: {
        question: {
          type: 'string',
          required: true,
          description: '用户的问题，使用中文自然语言',
        },
        topK: {
          type: 'number',
          description: '召回片段数量，默认 3，有效范围 1-8（可选）',
        },
      },
      output: {
        schema: { type: 'string' },
        render: (_args, value) => [{ type: 'text', text: String(value) }],
      },
      async execute(args) {
        const topK = Math.min(Math.max(args.topK ?? 3, 1), 8)
        const query = encodeURIComponent(args.question)
        const data = (await requestJson(`/internal/rag/ask?q=${query}&topK=${topK}`, {
          method: 'POST',
        })) as AskResponse
        return formatAnswer(data)
      },
    }),
  )

  ctx.tools.register(
    defineTool({
      name: 'abe_rag_health',
      description: '检查 ABE 知识库服务是否可用：返回集合名、embedding 模型、向量维度与已入库片段数。',
      parameters: {},
      output: {
        schema: { type: 'string' },
        render: (_args, value) => [{ type: 'text', text: String(value) }],
      },
      async execute() {
        const data = (await requestJson('/internal/rag/health')) as Record<string, unknown>
        return [
          `状态：${data.status ?? '未知'}`,
          `集合：${data.collection ?? '-'}`,
          `embedding：${data.embeddingModel ?? '-'}（${data.dimensions ?? '-'} 维）`,
          `片段数：${data.points ?? '-'}`,
        ].join('\n')
      },
    }),
  )
}
