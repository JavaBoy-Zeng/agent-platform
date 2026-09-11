import { describe, expect, it } from 'vitest'
import { agentLabel } from './agentIdentity.js'

describe('actual agent identity', () => {
  it('formats actual ids and uses a neutral label when identity is unavailable', () => {
    expect(agentLabel('react-agent')).toBe('REACT AGENT')
    expect(agentLabel('plan-execute-agent')).toBe('PLAN EXECUTE AGENT')
    expect(agentLabel('main-agent')).toBe('PLAN EXECUTE AGENT')
    expect(agentLabel('')).toBe('AGENT')
  })
})
