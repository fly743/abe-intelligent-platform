import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'abe-codegen'
export const inject = ['tools']
const base = process.env.ABE_CODEGEN_BASE_URL ?? 'http://127.0.0.1:8081'

interface VerifyResult {
  generated: { status: string; stage: string; message: string; files: { name: string; content: string }[] }
  execution: { compilation: string; execution: string; cleanup: string; outcome: string; message: string }
}

function renderResult(result: VerifyResult): string {
  const { generated, execution } = result
  const lines = [
    '## 生成与验证结果',
    `- 代码生成：${generated.status}（${generated.stage}）；${generated.message}`,
    `- 编译：${execution.compilation}`,
    `- 运行：${execution.execution}`,
    `- 清理：${execution.cleanup}`,
    `- 结果：${execution.outcome}；${execution.message}`,
  ]
  const source = generated.files.find(file => file.name === 'AbeExample.java')
  if (source) {
    lines.push('', '## 生成的 Java 源码：AbeExample.java', '', '```java', source.content, '```')
    lines.push('', '该示例依赖项目内 BswCryptoService 和 BSW 工件，源码生成成功不代表编译或运行成功。')
  }
  const instructions = generated.files.find(file => file.name === 'README.md')
  if (instructions) lines.push('', '## 运行说明', '', instructions.content)
  return lines.join('\n')
}

export function apply(ctx: Context): void {
  ctx.tools.register(defineTool({
    name: 'abe_codegen_verify',
    description: '生成固定 Java ABE 示例并尝试 Docker 编译运行。工具返回的“生成的 Java 源码”是实际生成的完整代码：回答用户时必须逐字展示完整 Java 代码块，不要仅概述文件、删节或改写代码；同时说明生成、编译、运行和清理状态，即使验证失败也展示已生成源码。策略仅支持属性名、AND、OR 和括号；属性名区分大小写，须为 1 至 64 位 ASCII 字母、数字、下划线或连字符，首字符不能为连字符，不能使用保留字 AND 或 OR。不支持中文属性名、等号或引号。自然语言中的明确业务属性应映射为有意义的合法标识符，并在回答中说明映射，例如科室为急诊映射为 dept_emergency，角色为医生映射为 role_doctor；同时满足写为 dept_emergency AND role_doctor，用户属性使用同样的标识符。不得改变 AND/OR 关系或臆造用户属性；访问条件或当前用户属性有歧义、缺失时先询问。用户未指定演示明文时可用非敏感的 demo。生成器不理解自然语言。禁止传入密钥、口令或敏感明文。',
    parameters: {
      policy: { type: 'string', required: true, description: '明确的访问策略，仅使用合法 ASCII 属性名、AND、OR、括号，例如 dept_emergency AND role_doctor；不支持中文、等号或引号' },
      attributes: { type: 'array', required: true, items: { type: 'string' }, description: '明确的用户属性列表，使用与策略一致的 ASCII 标识符，例如 dept_emergency、role_doctor' },
      plaintext: { type: 'string', required: true, description: '非敏感演示明文，可为空字符串' },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute(args) {
      const response = await fetch(`${base}/internal/codegen/verify`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args),
      })
      if (!response.ok) throw new Error(`ABE 代码生成服务返回 HTTP ${response.status}`)
      return renderResult(await response.json() as VerifyResult)
    },
  }))
}
