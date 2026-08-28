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
})
