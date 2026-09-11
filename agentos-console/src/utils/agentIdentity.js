export function agentLabel(id) {
  const value = String(id || '').trim()
  if (!value) return 'AGENT'
  if (value === 'main-agent') return 'PLAN EXECUTE AGENT'
  return value.replace(/[-_]+/g, ' ').toUpperCase()
}
