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
    expect(wrapper.get('.answer-token').text()).toContain('28 token')
    const buttons = wrapper.findAll('.answer-actions button')
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
    expect(wrapper.get('.answer-status').text()).toMatch(/手动终止输出|Output stopped manually/)
  })
})
