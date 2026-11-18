import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'

/**
 * ABE knowledge QA plugin for DeepSeek Harness.
 *
 * Layering: Harness (this plugin) -> HTTP -> Java service (retrieval + DeepSeek + citation check).
 * The plugin only validates params, forwards the request and renders the result.
 *
 * Env: ABE_RAG_BASE_URL (default http://127.0.0.1:8080)
 */

export const name = 'abe-rag'

export const inject = ['tools']

const BASE_URL = process.env.ABE_RAG_BASE_URL ?? 'http://127.0.0.1:8080'

interface Citation {
  ref: number
  docId: string
  title: string
  section: string
  source: string
  score: number
}

interface AskResponse {
  question: string
  answer: string
  refused: boolean
  reason?: string | null
  citations: Citation[]
  retrievedCount: number
  invalidRefs: number[]
  latencyMs: number
}

interface AskValue {
  answer: string
  refused: boolean
  latencyMs: number
  citations: Citation[]
}

async function postJson(url: string): Promise<unknown> {
  const res = await fetch(url, { method: 'POST' })
  const text = await res.text()
  if (!res.ok) {
    throw new Error(`ABE RAG 服务返回 ${res.status}: ${text.slice(0, 200)}`)
  }
  return text ? JSON.parse(text) : {}
}

function renderAnswer(value: AskValue): string {
  const lines: string[] = []
  lines.push(value.answer.trim())
  lines.push('')
  if (value.citations.length > 0) {
    lines.push('---')
    lines.push('引用来源')
    lines.push('')
    for (const c of value.citations) {
      const where = c.section ? `${c.title} · ${c.section}` : c.title
      lines.push(`[${c.ref}] ${where} — \`${c.source}\`（相似度 ${c.score.toFixed(2)}）`)
    }
  } else {
    lines.push('> 未产生引用：本次回答没有引用任何片段（检索为空或模型判定证据不足）')
  }
  lines.push('')
  lines.push(`<sub>耗时 ${(value.latencyMs / 1000).toFixed(2)}s</sub>`)
  return lines.join('\n')
}

export function apply(ctx: Context): void {
  ctx.tools.register(
    defineTool({
      name: 'abe_qa',
      description:
        '基于 ABE（属性基加密）知识库回答问题，答案附带可核查的引用来源；知识库未覆盖的问题会明确拒答。回答 ABE 相关概念、方案对比、实现细节时优先使用本工具。',
      parameters: {
        question: {
          type: 'string',
          required: true,
          description: '用户的问题，中文自然语言',
        },
        topK: {
          type: 'number',
          description: '召回片段数量，默认 3，有效范围 1-8（可选）',
        },
      },
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            answer: { type: 'string' },
            refused: { type: 'boolean' },
            latencyMs: { type: 'number' },
            citations: {
              type: 'array',
              items: {
                type: 'object',
                additionalProperties: false,
                properties: {
                  ref: { type: 'number' },
                  docId: { type: 'string' },
                  title: { type: 'string' },
                  section: { type: 'string' },
                  source: { type: 'string' },
                  score: { type: 'number' },
                },
              },
            },
          },
        },
        render: (_args, value) => [{ type: 'text', text: renderAnswer(value) }],
      },
      async execute(args) {
        const topK = Math.min(Math.max(args.topK ?? 3, 1), 8)
        const url = `${BASE_URL}/internal/rag/ask?q=${encodeURIComponent(args.question)}&topK=${topK}`
        const data = (await postJson(url)) as AskResponse
        return {
          answer: data.answer ?? '',
          refused: Boolean(data.refused),
          latencyMs: Number(data.latencyMs ?? 0),
          citations: (data.citations ?? []).map((c) => ({
            ref: Number(c.ref ?? 0),
            docId: String(c.docId ?? ''),
            title: String(c.title ?? ''),
            section: String(c.section ?? ''),
            source: String(c.source ?? ''),
            score: Number(c.score ?? 0),
          })),
        }
      },
    }),
  )

  ctx.tools.register(
    defineTool({
      name: 'abe_rag_health',
      description: '检查 ABE 知识库服务状态：集合名、embedding 模型、向量维度、已入库片段数。',
      parameters: {},
      output: {
        schema: { type: 'string' },
        render: (_args, value) => [{ type: 'text', text: value }],
      },
      async execute() {
        const res = await fetch(`${BASE_URL}/internal/rag/health`)
        if (!res.ok) {
          throw new Error(`ABE RAG 服务返回 ${res.status}`)
        }
        const data = (await res.json()) as Record<string, unknown>
        const lines = [
          `状态：${data.status ?? 'UNKNOWN'}`,
          `集合：${data.collection ?? '-'}`,
          `embedding：${data.embeddingModel ?? '-'}（${data.dimensions ?? '-'} 维）`,
          `片段数：${data.points ?? '-'}`,
        ]
        if (data.chatModel) {
          lines.push(`chat：${data.chatModel}`)
        }
        return lines.join('\n')
      },
    }),
  )
}
