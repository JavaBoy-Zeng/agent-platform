import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ExecutionTraceCard from './ExecutionTraceCard.vue'
import { readTraceArtifact, downloadArtifact } from '../services/consoleApi.js'
vi.mock('../services/consoleApi.js', () => ({ readTraceArtifact: vi.fn(), downloadArtifact: vi.fn() }))
beforeEach(() => vi.resetAllMocks())
describe('execution trace', () => {
  it('loads original response and displays only reasoning returned by the model', async () => {
    readTraceArtifact.mockResolvedValue('data: {"choices":[{"delta":{"reasoning_content":"先核对测试结果","content":"<img src=x onerror=alert(1)>"}}]}\n\ndata: [DONE]\n')
    const wrapper = mount(ExecutionTraceCard, { props: { title: '模型返回', record: {
      traceKind: 'model_response', artifactId: 'record-1', text: 'preview', callId: 'call-1'
    } } })
    expect(readTraceArtifact).not.toHaveBeenCalled()
    await wrapper.get('.trace-trigger').trigger('click')
    await flushPromises()
    expect(wrapper.get('.trace-trigger').attributes('aria-expanded')).toBe('true')
    expect(readTraceArtifact).toHaveBeenCalledWith('record-1')
    expect(wrapper.text()).toContain('先核对测试结果')
    expect(wrapper.find('img').exists()).toBe(false)
    await wrapper.get('.trace-download').trigger('click')
    expect(downloadArtifact).toHaveBeenCalledWith('record-1')
  })
  it('shows absence of reasoning without fabricating it and retains full original text', async () => {
    const wrapper = mount(ExecutionTraceCard, { props: { title: '返回', record: {
      traceKind: 'model_response', text: '{"choices":[{"message":{"content":"结论"}}]}'
    } } })
    await wrapper.get('.trace-trigger').trigger('click')
    expect(wrapper.text()).toContain('接口未返回独立推理字段')
    expect(wrapper.text()).toContain('结论')
  })
  it('reports archive read failures and permits retry', async () => {
    readTraceArtifact.mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce('完整结果')
    const wrapper = mount(ExecutionTraceCard, { props: { title: '工具返回', record: { traceKind: 'tool_result', artifactId: 'a' } } })
    await wrapper.get('.trace-trigger').trigger('click'); await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toBe('读取失败')
    await wrapper.get('.trace-trigger').trigger('click')
    await wrapper.get('.trace-trigger').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('完整结果')
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
  })
})
