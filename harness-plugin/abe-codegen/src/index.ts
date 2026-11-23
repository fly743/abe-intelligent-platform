import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'abe-codegen'
export const inject = ['tools']
const base = process.env.ABE_CODEGEN_BASE_URL ?? 'http://127.0.0.1:8081'

interface VerifyResult {
  generated: { status: string; stage: string; message: string; files: { name: string; content: string }[] }
  execution: { compilation: string; execution: string; cleanup: string; outcome: string; message: string }
}

interface AttributeMapping {
  label: string
  identifier: string
}

interface VerifyInput {
  policy: string
  attributes: string[]
  attributeMapping?: AttributeMapping[]
}

// 只检查本次调用的映射结构；策略语法仍由 Java 校验，语义需由 Harness 澄清。
function validateAttributeMapping(input: VerifyInput): string | undefined {
  const mapping = input.attributeMapping
  if (mapping === undefined) return
  if (mapping.length === 0 || mapping.length > 128) return '提供属性映射时，条目数量须为 1 至 128。'
  const labels = new Set<string>()
  const identifiers = new Set<string>()
  const used = new Set([
    ...(input.policy.match(/[A-Za-z0-9_-]+/g) ?? []).filter(token => !/^(AND|OR)$/i.test(token)),
    ...input.attributes,
  ])
  for (const { label, identifier } of mapping) {
    const name = label.trim()
    if (!name || name.length > 128 || /[\u0000-\u001f\u007f]/.test(label)) {
      return '属性映射名称须为 1 至 128 个字符，不能包含换行或控制字符。'
    }
    if (!/^[A-Za-z0-9_][A-Za-z0-9_-]{0,63}$/.test(identifier) || /^(AND|OR)$/i.test(identifier)) {
      return '映射标识符须符合 B 模块属性规则，不能使用保留字 AND 或 OR。'
    }
    if (labels.has(name)) return '同一属性名称不能重复映射；请先统一名称与标识符。'
    if (identifiers.has(identifier)) return '不同属性名称不能共用同一个标识符；同义表述请先统一为一个名称。'
    if (!used.has(identifier)) return '映射包含本次策略和用户属性均未使用的标识符，请仅保留本次使用的映射。'
    labels.add(name)
    identifiers.add(identifier)
  }
  if ([...used].some(identifier => !identifiers.has(identifier))) {
    return '属性映射须覆盖策略和用户属性中的全部标识符；原有 ASCII 属性可以使用同名映射。'
  }
}

function renderInput(input: VerifyInput): string[] {
  const context = {
    policy: input.policy,
    attributes: input.attributes,
    ...(input.attributeMapping === undefined ? {} : {
      attributeMapping: input.attributeMapping.map(({ label, identifier }) => ({ label: label.trim(), identifier })),
    }),
  }
  return [
    '## 本次策略与用户属性', '', '```json', JSON.stringify(context, null, 2), '```',
    ...(input.attributeMapping === undefined ? ['未提供属性映射；以上为本次工具收到的标识符。'] : []),
    '',
  ]
}

function renderResult(result: VerifyResult, input: VerifyInput): string {
  const { generated, execution } = result
  const lines = [
    ...renderInput(input),
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
    description: [
      '调用前必须分别取得访问策略和本次测试用户属性。用户只说“谁可以访问”时，必须先询问“这次用什么身份或属性的用户测试？”，禁止从策略中自动挑选身份调用工具，即使策略只有一种身份也不例外。',
      '将明确的访问条件整理为固定 Java ABE 示例，并调用 B 服务进行 Docker 编译运行。Java 生成器不理解自然语言。按以下顺序处理：',
      '1. 从用户当前输入或已明确的对话上下文中，分别提取访问策略和本次测试用户属性。策略中的属性绝不等于测试用户拥有的属性；缺少任何一项时先询问，不调用本工具。',
      '2. 例如用户只说“医生或者管理员能看这份数据，帮我生成代码跑一下”，应回答“访问条件是医生或管理员。这次想用什么身份的用户测试？”，此时不得调用工具。用户随后说“就拿护士试一下”，才使用护士作为用户属性，保留医生 OR 管理员的策略并执行。',
      '3. 不得为了让解密成功而自动选择、补齐或修改用户属性。只有用户明确允许“任选一个测试用户”“随便挑个身份”，或明确要求构造成功、失败用例时，才可自行构造演示属性，并在回答中说明是模拟身份。仅说“生成代码”“跑一下”“做个演示”不等于授权自动选择测试身份。用户已明确提供测试属性或已明确授权构造时，不重复询问。',
      '4. 保持原意和 AND/OR 关系，混合条件显式使用括号。明确的“用户是医生并且属于 A 医院，或者用户是管理员”写为 (role_doctor AND hospital_a) OR role_admin；“A 医院的医生或管理员”存在作用范围歧义，应先询问医院限制是否也适用于管理员，收到答复后再调用。不得根据 Java 语法合法就认定语义正确。',
      '5. 仅支持正向属性、AND、OR 和括号；不支持 NOT、排除条件、数值比较或任意门限。遇到这些条件先说明限制并澄清，不得静默改写；如使用 age_over_18 等普通属性模拟比较，须先取得用户同意，并说明该属性需由外部签发，系统不会自行比较年龄。',
      '6. 将明确的业务属性统一为有意义的 ASCII 标识符，如医生→role_doctor、管理员→role_admin、A 医院→hospital_a、急诊科→dept_emergency。同一属性在策略和用户列表中使用相同标识符，不同属性不得冲突；先合并已明确的同义表述。用户已提供的合法标识符及大小写保持不变。用户可以拥有策略之外的属性，不得删除或替换它们。',
      '7. 自然语言属性转换时提供 attributeMapping，覆盖本次策略及用户列表的全部标识符；已使用的 ASCII 属性可同名映射。纯标识符请求可省略映射。后续对话沿用已有映射，只修改用户指定的内容；发现冲突先澄清。映射只是演示命名，不代表身份认证或真实属性签发。',
      '8. 策略和测试属性均明确且不存在未解决的语义歧义时直接调用，无需逐次要求确认。用户未指定明文时使用非敏感的 demo；显式空字符串保持为空。禁止传入密钥、口令或敏感明文。',
      '属性名区分大小写，须为 1 至 64 位 ASCII 字母、数字、下划线或连字符，首字符不能为连字符，不能使用保留字 AND 或 OR；用户属性须为 1 至 64 个且不能重复。不支持中文属性名、等号或引号。',
      '回答时展示工具返回的策略、用户属性和映射；映射检查失败时先修正或询问，不声称已运行。必须逐字展示工具返回的完整 Java 代码块，不删节或改写；即使验证失败也展示已生成源码，并说明生成、编译、运行、清理和 outcome。POLICY_NOT_SATISFIED 表示正确拒绝访问，不推断为算法故障；其他失败按工具结果说明，不臆测原因。',
    ].join('\n'),
    parameters: {
      policy: { type: 'string', required: true, description: '明确的访问策略，仅使用合法 ASCII 属性名、AND、OR、括号，例如 dept_emergency AND role_doctor；不支持中文、等号或引号' },
      attributes: { type: 'array', required: true, items: { type: 'string' }, description: '本次测试用户拥有的属性，必须来自用户明确提供的信息或明确授权构造的模拟身份。未提供身份时先询问，禁止默认取策略中的属性。使用与策略一致的 ASCII 命名，例如 dept_emergency、role_doctor' },
      plaintext: { type: 'string', required: true, description: '非敏感演示明文，可为空字符串' },
      attributeMapping: {
        type: 'array',
        description: '可选。自然语言转换时提供本次全部属性的名称与标识符映射；须覆盖策略和用户属性（含策略之外的用户属性），不同名称不能共用标识符，不含未使用的映射。纯 ASCII 请求可省略。',
        items: {
          type: 'object', additionalProperties: false,
          properties: {
            label: { type: 'string', required: true, description: '统一后的业务属性名称，如医生、A 医院；原有 ASCII 属性可直接同名' },
            identifier: { type: 'string', required: true, description: '与 policy、attributes 完全一致且符合 B 模块规则的标识符，如 role_doctor' },
          },
        },
      },
    },
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    async execute(args) {
      const mappingError = validateAttributeMapping(args)
      if (mappingError) return `属性映射检查未通过：${mappingError}\n尚未请求 B 服务，未生成、编译或运行代码。请修正映射；业务含义有歧义时先询问用户。`
      const { policy, attributes, plaintext } = args
      const response = await fetch(`${base}/internal/codegen/verify`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ policy, attributes, plaintext }),
      })
      if (!response.ok) throw new Error(`ABE 代码生成服务返回 HTTP ${response.status}`)
      return renderResult(await response.json() as VerifyResult, args)
    },
  }))
}
