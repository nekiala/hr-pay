import { describe, expect, it } from 'vitest'
import { readDate, readTime, showDate } from './DateFields'

describe('date fields', () => {
  it('reads the day as people write it', () => {
    expect(readDate('14/02/1989')).toBe('1989-02-14')
    expect(readDate('1/3/2026')).toBe('2026-03-01')
    expect(readDate('14.02.1989')).toBe('1989-02-14')
    expect(readDate('14021989')).toBe('1989-02-14')
    expect(readDate('1989-02-14')).toBe('1989-02-14')
    expect(readDate('31/02/2026')).toBeNull()
    expect(readDate('02/14/1989')).toBeNull()
    expect(readDate('')).toBeNull()
  })
  it('shows the day as dd/mm/yyyy', () => {
    expect(showDate('1989-02-14')).toBe('14/02/1989')
    expect(showDate('')).toBe('')
  })
  it('reads a time on 24 hours', () => {
    expect(readTime('14:30')).toBe('14:30')
    expect(readTime('9:05')).toBe('09:05')
    expect(readTime('14h30')).toBe('14:30')
    expect(readTime('1430')).toBe('14:30')
    expect(readTime('25:00')).toBeNull()
  })
})
