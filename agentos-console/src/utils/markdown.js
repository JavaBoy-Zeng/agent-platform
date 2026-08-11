import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.use({
  gfm: true,
  breaks: true
})

export function renderMarkdown(content) {
  const html = marked.parse(String(content || ''))
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true }
  })
}
