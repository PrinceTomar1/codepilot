import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import CitationBadge from './CitationBadge'

const baseCitation = {
  filePath: 'src/services/llm.py',
  snippet: 'def complete(self, ...):\n    ...',
}

describe('CitationBadge', () => {
  it('renders a single line number without a range when start equals end', () => {
    render(<CitationBadge citation={{ ...baseCitation, startLine: 42, endLine: 42 }} />)
    expect(screen.getByText('src/services/llm.py:42')).toBeInTheDocument()
  })

  it('renders a line range when start and end differ', () => {
    render(<CitationBadge citation={{ ...baseCitation, startLine: 10, endLine: 25 }} />)
    expect(screen.getByText('src/services/llm.py:10-25')).toBeInTheDocument()
  })

  it('shows the snippet preview only after the badge is clicked', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <CitationBadge citation={{ ...baseCitation, startLine: 1, endLine: 2 }} />,
    )

    // The snippet text is always in the DOM (for CSS hover), but the popover container starts
    // non-interactive/hidden via pointer-events + opacity until toggled open. Must check the
    // exact class token, not a substring -- "group-hover:opacity-100" is always present and
    // would falsely match a naive `.toContain('opacity-100')`.
    const popoverContainer = container.querySelector('.pointer-events-none')
    expect(popoverContainer).not.toBeNull()
    expect(popoverContainer?.classList.contains('opacity-100')).toBe(false)

    await user.click(screen.getByRole('button'))

    expect(popoverContainer?.classList.contains('opacity-100')).toBe(true)
  })
})
