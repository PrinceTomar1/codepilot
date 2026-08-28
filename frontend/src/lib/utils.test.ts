import { describe, expect, it } from 'vitest'
import { cx, formatDate, formatRelativeDate, getErrorMessage, initials } from './utils'

describe('cx', () => {
  it('joins truthy class names with a space', () => {
    expect(cx('a', 'b', 'c')).toBe('a b c')
  })

  it('filters out false, null, and undefined', () => {
    expect(cx('a', false, null, undefined, 'b')).toBe('a b')
  })

  it('returns an empty string when nothing is truthy', () => {
    expect(cx(false, null, undefined)).toBe('')
  })
})

describe('formatDate', () => {
  it('returns an em dash for null/undefined/empty input', () => {
    expect(formatDate(null)).toBe('—')
    expect(formatDate(undefined)).toBe('—')
    expect(formatDate('')).toBe('—')
  })

  it('returns an em dash for an unparseable date string', () => {
    expect(formatDate('not-a-date')).toBe('—')
  })

  it('formats a valid ISO date string into a real date', () => {
    const result = formatDate('2026-08-24T14:59:00Z')
    // Exact rendering is locale-dependent, but it must contain the year and not be the fallback.
    expect(result).not.toBe('—')
    expect(result).toContain('2026')
  })
})

describe('formatRelativeDate', () => {
  it('returns an em dash for null/undefined/unparseable input', () => {
    expect(formatRelativeDate(null)).toBe('—')
    expect(formatRelativeDate('garbage')).toBe('—')
  })

  it('describes a moment a few seconds ago as "now"-ish, not a raw timestamp', () => {
    const fewSecondsAgo = new Date(Date.now() - 5000).toISOString()
    const result = formatRelativeDate(fewSecondsAgo)
    expect(result.toLowerCase()).toContain('second')
  })
})

describe('initials', () => {
  it('uses first two letters of a single-word name', () => {
    expect(initials('Ada')).toBe('AD')
  })

  it('uses first letter of first and last word for multi-word names', () => {
    expect(initials('Ada Lovelace')).toBe('AL')
    expect(initials('Grace Brewster Hopper')).toBe('GH')
  })

  it('returns ? for an empty/whitespace-only name', () => {
    expect(initials('')).toBe('?')
    expect(initials('   ')).toBe('?')
  })
})

describe('getErrorMessage', () => {
  it('extracts message from a plain object with a string message field', () => {
    expect(getErrorMessage({ message: 'Rate limit exceeded' })).toBe('Rate limit exceeded')
  })

  it('extracts message from a real Error instance', () => {
    expect(getErrorMessage(new Error('boom'))).toBe('boom')
  })

  it('falls back to a generic message for unrecognized shapes', () => {
    expect(getErrorMessage('a bare string')).toBe('Something went wrong. Please try again.')
    expect(getErrorMessage(null)).toBe('Something went wrong. Please try again.')
    expect(getErrorMessage(42)).toBe('Something went wrong. Please try again.')
  })

  it('falls back when message field exists but is not a string', () => {
    expect(getErrorMessage({ message: 404 })).toBe('Something went wrong. Please try again.')
  })
})
