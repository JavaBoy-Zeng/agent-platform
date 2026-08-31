import { describe, expect, it } from 'vitest'
import { appendOperationGroup, compactOperationGroups } from './operationGroups.js'

const operation = (id, kind, toolName) => ({
  id,
  kind,
  createdAt: `2026-08-29T00:00:0${id}.000Z`,
  item: { toolName, arguments: {}, summary: '', success: true }
})

describe('appendOperationGroup', () => {
  it('merges consecutive operations of the same kind', () => {
    const messages = []
    appendOperationGroup(messages, operation('1', 'read', 'file_read'))
    appendOperationGroup(messages, operation('2', 'read', 'file_read'))

    expect(messages).toHaveLength(1)
    expect(messages[0].items).toHaveLength(2)
    expect(messages[0].createdAt).toBe('2026-08-29T00:00:02.000Z')
  })

  it('starts a new group after a different operation kind', () => {
    const messages = []
    appendOperationGroup(messages, operation('1', 'read', 'file_read'))
    appendOperationGroup(messages, operation('2', 'command', 'run_command'))
    appendOperationGroup(messages, operation('3', 'read', 'file_read'))

    expect(messages.map(message => message.kind)).toEqual(['read', 'command', 'read'])
  })

  it('compacts consecutive operation groups restored from an older cache', () => {
    const messages = [
      { id: 'user', role: 'user', content: '读取附件' },
      { id: 'read-1', role: 'ops', kind: 'read', items: [{ toolName: 'file_read' }] },
      { id: 'read-2', role: 'ops', kind: 'read', items: [{ toolName: 'file_read' }] },
      { id: 'command', role: 'ops', kind: 'command', items: [{ toolName: 'run_command' }] }
    ]

    const compacted = compactOperationGroups(messages)

    expect(compacted).toHaveLength(3)
    expect(compacted[1].items).toHaveLength(2)
    expect(compacted.map(message => message.role === 'ops' ? message.kind : message.role))
      .toEqual(['user', 'read', 'command'])
  })
})
