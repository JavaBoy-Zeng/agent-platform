import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.use({
  gfm: true,
  breaks: true
})

// 技术标识符自动增强为 inline code：完整路径、文件名、UpperCamel/lowerCamel 类名、
// snake_case 工具名、方法调用。两个负向后行断言避免破坏 Markdown 链接与裸 URL。
const IDENTIFIER_RE = new RegExp(
  '(?<!\\]\\([^)]{0,500})(?<!https?:\\/\\/\\S{0,500})'
  + '(?:'
  + [
    // 完整路径整体成块（/绝对路径、~/、./、../），优先于片段规则，
    // 防止路径内部的 snake_case 等片段被单独抠出、把路径拆碎；
    // 前瞻要求出现字母，避免 24/7 这类纯数字斜杠组合被误标；
    // 后行断言要求裸 / 处于词边界，避免 yes/no、and/or 这类斜杠短语被误标
    '(?<![\\w@.-])(?:~|\\.{1,2})?\\/(?=[\\w@./-]*[A-Za-z])(?:[\\w@.-]+\\/)*[\\w@.-]+',
    // 多级相对路径（src/main/java），同样要求含字母
    '\\b(?=[\\w@./-]*[A-Za-z])[\\w@.-]+(?:\\/[\\w@.-]+){2,}',
    '\\b[\\w.-]+\\.(?:java|kt|scala|md|markdown|txt|json|xml|yml|yaml|properties|gradle|sql|csv|ts|tsx|js|jsx|py|html|css|scss|vue|docx|pdf|log|sh|bat|toml|conf|ini)\\b',
    '\\b[a-z][a-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){2,}\\b',
    '\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b',
    '\\b[a-z]+(?:[A-Z][a-z0-9]+)+\\b',
    '\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b',
    '\\b[A-Za-z_][A-Za-z0-9_]*\\(\\s*\\)'
  ].join('|')
  + ')',
  'g'
)

// 常见品牌词不是代码标识符，避免在普通行文中被误标记
const NON_CODE_WORDS = /^(?:GitHub|GitLab|JavaScript|Markdown|YouTube|LinkedIn|WeChat|TensorFlow|PyTorch|StackOverflow|PowerPoint|WhatsApp|DeepSeek|HomeBrew|AliPay)$/

// 代码围栏与行内代码段必须原样保留，只对普通文本段做标识符增强
const CODE_SEGMENT_RE = /(```[\s\S]*?(?:```|$)|~~~[\s\S]*?(?:~~~|$)|`[^`\n]+`)/g

function chipIdentifiers(text) {
  return text.replace(IDENTIFIER_RE, (match) => {
    // 路径分支的字符类包含「.」，句末标点不进胶囊
    const core = match.replace(/[.,]+$/, '')
    if (!core || NON_CODE_WORDS.test(core)) return match
    return core === match
      ? `\`${match}\``
      : `\`${core}\`${match.slice(core.length)}`
  })
}

function enhanceInlineCode(content) {
  return String(content || '')
    .split(CODE_SEGMENT_RE)
    .map((segment, index) => (index % 2 === 1 ? segment : chipIdentifiers(segment)))
    .join('')
}

export function renderMarkdown(content) {
  const html = marked.parse(enhanceInlineCode(content))
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true }
  })
}
