import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TranscriptPanel from './TranscriptPanel.vue'

beforeEach(() => {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) }
  })
})

describe('TranscriptPanel result presentation', () => {
  it('groups each user request and its execution records into one conversation turn', () => {
    const wrapper = mount(TranscriptPanel, {
      props: {
        messages: [
          { id: 'u1', role: 'user', content: '修复 #A', createdAt: '2026-09-10T08:00:00Z' },
          { id: 'ops1', role: 'ops', kind: 'edit', expanded: false, createdAt: '2026-09-10T08:00:02Z', items: [] },
          { id: 'a1', role: 'assistant', content: '#A 已修复', createdAt: '2026-09-10T08:04:08Z', runEnd: true, runStatus: 'COMPLETED' },
          { id: 'u2', role: 'user', content: '继续', createdAt: '2026-09-10T08:05:00Z' },
          { id: 'a2', role: 'assistant', content: '已继续', createdAt: '2026-09-10T08:05:03Z', runEnd: true, runStatus: 'COMPLETED' }
        ]
      }
    })

    expect(wrapper.findAll('.conversation-turn')).toHaveLength(2)
    expect(wrapper.findAll('.user-message-bubble')).toHaveLength(2)
    expect(wrapper.findAll('.assistant-content')).toHaveLength(2)
    expect(wrapper.findAll('.task-status-line')).toHaveLength(2)
    expect(wrapper.findAll('.message-metadata')).toHaveLength(2)
    expect(wrapper.findAll('.conversation-turn')[0].get('.task-status-line').text()).toContain('4分8秒')
  })

  it('renders assistant output as an unboxed document stream with a separate pending status', () => {
    const wrapper = mount(TranscriptPanel, {
      props: {
        busy: true, executingAgentId: 'react-agent',
        messages: [
          { id: 'plan', role: 'assistant', agentId: 'plan-execute-agent', content: 'planned', createdAt: new Date().toISOString() },
          { id: 'react', role: 'assistant', agentId: 'react-agent', content: 'observed', createdAt: new Date().toISOString() },
          { id: 'old', role: 'assistant', content: 'unknown', createdAt: new Date().toISOString() }
        ]
      }
    })
    expect(wrapper.findAll('.assistant-message')).toHaveLength(3)
    expect(wrapper.find('.message').exists()).toBe(false)
    expect(wrapper.get('.assistant-pending').text()).toMatch(/Agent Loop|running/i)
    expect(wrapper.find('.assistant-message header').exists()).toBe(false)
  })

  it('keeps an action result collapsed until its summary is opened', async () => {
    const wrapper = mount(TranscriptPanel, {
      props: {
        messages: [{
          id: 'op-1', role: 'ops', kind: 'edit', expanded: false,
          items: [{ toolName: 'file_write', arguments: { path: 'a.js' }, summary: 'saved', success: true }]
        }]
      }
    })
    expect(wrapper.find('.op-result').exists()).toBe(false)
    expect(wrapper.get('.op-summary').attributes('aria-expanded')).toBe('false')
    await wrapper.get('.op-summary').trigger('click')
    expect(wrapper.get('.op-result').text()).toBe('saved')
    expect(wrapper.get('.op-summary').attributes('aria-expanded')).toBe('true')
  })

  it('offers copy, retry and token usage on a completed answer', async () => {
    const wrapper = mount(TranscriptPanel, {
      props: {
        messages: [{
          id: 'answer-1', role: 'assistant', content: 'done', createdAt: new Date().toISOString(),
          runEnd: true, runStatus: 'COMPLETED', retryPrompt: 'again', tokenUsage: 28
        }]
      }
    })
    expect(wrapper.get('.token-usage').text()).toContain('Tokens: 28')
    const buttons = wrapper.findAll('.message-metadata button')
    await buttons[0].trigger('click')
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('done')
    await buttons[1].trigger('click')
    expect(wrapper.emitted('retry')?.[0]).toEqual(['again'])
  })

  it('labels cancelled output as abnormal', () => {
    const wrapper = mount(TranscriptPanel, {
      props: {
        messages: [{
          id: 'cancelled-1', role: 'event', content: '任务已取消。', createdAt: new Date().toISOString(),
          runEnd: true, runStatus: 'CANCELLED', retryPrompt: 'retry'
        }]
      }
    })
    expect(wrapper.get('.runtime-result-status').text()).toMatch(/手动终止输出|Output stopped manually/)
  })

  it('keeps a sent bare URL intact and opens it from the transcript', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mount(TranscriptPanel, {
      props: {
        messages: [{
          id: 'link-1', role: 'user',
          content: 'https://m.cq.bendibao.com/live/67253.shtm 总结一下',
          createdAt: new Date().toISOString()
        }]
      }
    })

    const link = wrapper.get('.user-message-bubble a')
    expect(link.text()).toBe('https://m.cq.bendibao.com/live/67253.shtm')
    await link.trigger('click')
    expect(openSpy).toHaveBeenCalledWith('https://m.cq.bendibao.com/live/67253.shtm', '_blank', 'noopener,noreferrer')
    openSpy.mockRestore()
  })
})
