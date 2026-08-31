/**
 * 把连续同类工具操作合并到一个可折叠记录中；遇到普通消息或不同类型操作时自然分组。
 */
export function appendOperationGroup(messages, operation) {
  const last = messages.at(-1)
  if (last?.role === 'ops' && last.kind === operation.kind) {
    last.items.push(operation.item)
    last.createdAt = operation.createdAt || last.createdAt
    return last
  }

  const group = {
    id: operation.id,
    role: 'ops',
    kind: operation.kind,
    items: [operation.item],
    expanded: false,
    createdAt: operation.createdAt
  }
  messages.push(group)
  return group
}

/** 兼容旧缓存：加载时收拢已经拆散的连续操作分组。 */
export function compactOperationGroups(messages) {
  if (!Array.isArray(messages)) return []

  return messages.reduce((compacted, message) => {
    if (message?.role !== 'ops') {
      compacted.push(message)
      return compacted
    }

    const items = Array.isArray(message.items) ? message.items : []
    const last = compacted.at(-1)
    if (last?.role === 'ops' && last.kind === message.kind) {
      last.items.push(...items)
      last.expanded = Boolean(last.expanded || message.expanded)
      last.createdAt = message.createdAt || last.createdAt
      return compacted
    }

    compacted.push({ ...message, items: [...items] })
    return compacted
  }, [])
}
