import { describe, expect, it } from 'vitest'
import { pendingLabel } from './Button'

describe('pendingLabel', () => {
  it('turns the label into what is happening', () => {
    expect(pendingLabel('Save')).toBe('Saving…')
    expect(pendingLabel('Save changes')).toBe('Saving…')
    expect(pendingLabel('Sign in')).toBe('Signing in…')
    expect(pendingLabel('Approve')).toBe('Approving…')
    expect(pendingLabel(['Add for ', '2026-11'])).toBe('Adding…')
  })
  it('falls back to a neutral text', () => {
    expect(pendingLabel('2027')).toBe('Please wait…')
    expect(pendingLabel(null)).toBe('Please wait…')
  })
})
