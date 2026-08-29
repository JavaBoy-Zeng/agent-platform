import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CommandDeck from './CommandDeck.vue'

function mountDeck(overrides = {}) {
  return mount(CommandDeck, {
    props: {
      agentId: 'main-agent',
      sessionId: 'session-1',
      prompt: 'hello',
      models: [{ id: 'minimax-h3', name: 'minimax h3' }],
      selectedModelId: 'minimax-h3',
      approvalMode: 'FULL_ACCESS',
      uploadAvailable: true,
      workspaceAvailable: true,
      workspaces: [
        { id: 'workspace-1', name: 'agent-platform', root: '/local/agent-platform' },
        { id: 'workspace-2', name: 'assistant', root: '/local/assistant' }
      ],
      currentWorkspace: { id: 'workspace-1', name: 'agent-platform', root: '/local/agent-platform' },
      workspaceFiles: [
        { name: 'ReadMe.md', relativePath: 'ReadMe.md', language: 'markdown' },
        { name: 'AgentApplication.java', relativePath: 'src/main/java/AgentApplication.java', language: 'java' },
        { name: 'AgentFactory.java', relativePath: 'src/main/java/agent/AgentFactory.java', language: 'java' }
      ],
      ...overrides
    },
    global: { stubs: { Teleport: true } }
  })
}

describe('CommandDeck composer controls', () => {
  it('shows the default model and emits attachment upload', async () => {
    const wrapper = mountDeck()
    expect(wrapper.get('.model-trigger').text()).toContain('minimax h3')
    await wrapper.get('.attach-button').trigger('click')
    expect(wrapper.emitted('upload')).toHaveLength(1)
  })

  it('offers an accessible remove action for each uploaded attachment', async () => {
    const attachment = { name: 'brief.md', relativePath: 'attachments/session-1/brief.md' }
    const wrapper = mountDeck({ attachments: [attachment] })

    const remove = wrapper.get('.attachment-remove')
    expect(remove.attributes('aria-label')).toContain('brief.md')
    await remove.trigger('click')

    expect(wrapper.emitted('remove-attachment')?.[0]).toEqual([attachment])
  })

  it('changes approval mode from the custom permission menu', async () => {
    const wrapper = mountDeck()
    await wrapper.get('.permission-trigger').trigger('click')
    const options = wrapper.findAll('.permission-menu > button')
    await options[0].trigger('click')
    expect(wrapper.emitted('update:approvalMode')?.[0]).toEqual(['REQUEST_APPROVAL'])
  })

  it('adds a model through the in-app dialog', async () => {
    const wrapper = mountDeck()
    await wrapper.get('.model-trigger').trigger('click')
    await wrapper.get('.model-menu footer button').trigger('click')
    await wrapper.get('.model-dialog input').setValue('vendor/custom-reasoner-v1')
    await wrapper.get('.model-dialog form').trigger('submit')
    expect(wrapper.emitted('add-model')?.[0]).toEqual([{
      modelId: 'vendor/custom-reasoner-v1'
    }])
  })

  it('selects a recent task folder from the composer footer', async () => {
    const wrapper = mountDeck()
    await wrapper.get('.workspace-context-trigger').trigger('click')
    const recent = wrapper.findAll('.composer-workspace-menu > button')
    await recent[0].trigger('click')

    expect(wrapper.emitted('select-workspace')?.[0]).toEqual(['workspace-1'])
  })

  it('opens the native folder picker from the custom workspace menu', async () => {
    const wrapper = mountDeck()
    await wrapper.get('.workspace-context-trigger').trigger('click')
    await wrapper.get('.composer-workspace-menu footer button').trigger('click')

    expect(wrapper.emitted('pick-workspace')).toHaveLength(1)
  })

  it('renders and filters project files immediately after typing @', async () => {
    const wrapper = mountDeck({ prompt: '' })
    const input = wrapper.get('#promptInput')

    await input.setValue('请解释 @')
    expect(wrapper.get('.file-mention-menu').attributes('role')).toBe('listbox')
    expect(wrapper.findAll('.file-mention-menu [role="option"]')).toHaveLength(3)

    await input.setValue('请解释 @AgentF')
    const options = wrapper.findAll('.file-mention-menu [role="option"]')
    expect(options).toHaveLength(1)
    expect(options[0].text()).toContain('AgentFactory.java')
  })

  it('selects an @ file with the keyboard without submitting the task', async () => {
    const wrapper = mountDeck({ prompt: '' })
    const input = wrapper.get('#promptInput')

    await input.setValue('分析 @AgentApp')
    await wrapper.setProps({ prompt: '分析 @AgentApp' })
    await input.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('run')).toBeUndefined()
    expect(wrapper.emitted('update:prompt')?.at(-1)).toEqual([
      '分析 @src/main/java/AgentApplication.java '
    ])
    expect(wrapper.find('.file-mention-menu').exists()).toBe(false)
  })
})
