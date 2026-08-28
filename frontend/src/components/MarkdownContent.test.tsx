import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import MarkdownContent from './MarkdownContent'

describe('MarkdownContent', () => {
  it('renders headers, bold text, and lists as real HTML elements, not literal markdown', () => {
    render(
      <MarkdownContent content={'## A header\n\n**bold text** and a list:\n\n- one\n- two'} />,
    )

    expect(screen.getByRole('heading', { level: 2, name: 'A header' })).toBeInTheDocument()
    expect(screen.getByText('bold text').tagName).toBe('STRONG')
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    // The literal markdown syntax characters must not leak into the rendered text.
    expect(screen.queryByText(/##/)).not.toBeInTheDocument()
    expect(screen.queryByText(/\*\*/)).not.toBeInTheDocument()
  })

  it('distinguishes inline code from fenced code blocks', () => {
    const { container } = render(
      <MarkdownContent content={'Use `foo()` inline.\n\n```python\ndef foo():\n    pass\n```'} />,
    )

    const inlineCode = screen.getByText('foo()')
    expect(inlineCode.tagName).toBe('CODE')
    // Inline code gets the pill-style background class; a fenced block's <code> does not.
    expect(inlineCode.className).toContain('bg-slate-800')

    const pre = container.querySelector('pre')
    expect(pre).not.toBeNull()
    expect(pre?.textContent).toContain('def foo():')
  })

  it('renders a GFM table with header and body cells', () => {
    const table = '| Name | Value |\n|------|-------|\n| a | 1 |\n| b | 2 |'
    render(<MarkdownContent content={table} />)

    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'a' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '2' })).toBeInTheDocument()
  })

  it('applies a custom textClassName to the wrapping container', () => {
    const { container } = render(
      <MarkdownContent content="hi" textClassName="text-emerald-300/90" />,
    )
    expect(container.firstElementChild?.className).toContain('text-emerald-300/90')
  })

  it('renders external links with target=_blank and rel=noreferrer', () => {
    render(<MarkdownContent content="[docs](https://example.com/docs)" />)
    const link = screen.getByRole('link', { name: 'docs' })
    expect(link).toHaveAttribute('href', 'https://example.com/docs')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noreferrer')
  })
})
