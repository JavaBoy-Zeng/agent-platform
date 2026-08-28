import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown.js'

describe('renderMarkdown inline code 增强', () => {
  it('类名、文件名、工具名自动渲染为 inline code', () => {
    const html = renderMarkdown('ToolDispatcher 通过 file_read 读取 README.md')
    expect(html).toContain('<code>ToolDispatcher</code>')
    expect(html).toContain('<code>file_read</code>')
    expect(html).toContain('<code>README.md</code>')
  })

  it('方法调用与包路径渲染为 inline code', () => {
    const html = renderMarkdown('调用 name() 方法，包名 com.github.agentos.kernel')
    expect(html).toContain('<code>name()</code>')
    expect(html).toContain('<code>com.github.agentos.kernel</code>')
  })

  it('代码块与行内代码保持原样不被二次处理', () => {
    const fenced = renderMarkdown('```\nfile_read ToolDispatcher\n```')
    expect(fenced).toContain('<pre>')
    expect(fenced).not.toContain('<code>file_read</code>')

    const inline = renderMarkdown('使用 `file_read` 工具')
    expect(inline).toContain('<code>file_read</code>')
    expect(inline.match(/<code>/g)).toHaveLength(1)
  })

  it('品牌词与普通文本不误标', () => {
    const html = renderMarkdown('GitHub 上托管，the quick brown fox jumps')
    expect(html).not.toContain('<code>')
  })

  it('Markdown 链接中的 URL 不被破坏', () => {
    const html = renderMarkdown('[文档](https://example.com/docs/guide.html)')
    expect(html).toContain('<a href="https://example.com/docs/guide.html">')
    expect(html).not.toContain('<code>')
  })

  it('中文语境下的标识符正确识别', () => {
    const html = renderMarkdown('先看ChatStreamEvent.java再调用run_command即可')
    expect(html).toContain('<code>ChatStreamEvent.java</code>')
    expect(html).toContain('<code>run_command</code>')
  })

  it('完整路径整体渲染为单个 inline code，不被片段规则拆碎', () => {
    const html = renderMarkdown('梳理一下 /Users/whale_fall/developer/java/agent-platform 这个项目')
    expect(html).toContain('<code>/Users/whale_fall/developer/java/agent-platform</code>')
    expect(html.match(/<code>/g)).toHaveLength(1)
  })

  it('多级相对路径整体识别', () => {
    const html = renderMarkdown('修改 agentos-console/src/utils/markdown.js 后跑测试')
    expect(html).toContain('<code>agentos-console/src/utils/markdown.js</code>')
    expect(html).not.toContain('<code>markdown.js</code>')
  })

  it('路径末尾的句号不进胶囊', () => {
    const html = renderMarkdown('见 /Users/whale_fall/agent-platform.')
    expect(html).toContain('<code>/Users/whale_fall/agent-platform</code>.')
  })

  it('斜杠短语与纯数字组合不误标', () => {
    const html = renderMarkdown('全天候 24/7 服务，yes/no 或 and/or 均可')
    expect(html).not.toContain('<code>')
  })

  it('家目录与相对前缀路径识别', () => {
    const html = renderMarkdown('回到 ~/developer 与 ../pom.xml')
    expect(html).toContain('<code>~/developer</code>')
    expect(html).toContain('<code>../pom.xml</code>')
  })
})
