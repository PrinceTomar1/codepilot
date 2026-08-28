import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FixDiff } from './ReviewDetail'

describe('FixDiff', () => {
  it('renders nothing when there is no fixed code', () => {
    const { container } = render(<FixDiff originalCode="x = 1" fixedCode={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the fixed code lines even with no original code (e.g. a missing-tests finding)', () => {
    render(<FixDiff originalCode={null} fixedCode={'def test_x():\n    assert x == 1'} />)
    expect(screen.getByText('def test_x():')).toBeInTheDocument()
    expect(screen.getByText('assert x == 1')).toBeInTheDocument()
  })

  it('renders both original and fixed lines when both are present', () => {
    render(<FixDiff originalCode="x = 1" fixedCode="x = 2" />)
    expect(screen.getByText('x = 1')).toBeInTheDocument()
    expect(screen.getByText('x = 2')).toBeInTheDocument()
  })

  it('splits multi-line snippets into one row per line', () => {
    render(<FixDiff originalCode={null} fixedCode={'line1\nline2\nline3'} />)
    expect(screen.getByText('line1')).toBeInTheDocument()
    expect(screen.getByText('line2')).toBeInTheDocument()
    expect(screen.getByText('line3')).toBeInTheDocument()
  })

  it('copies the fixed code to the clipboard and shows confirmation', async () => {
    // userEvent.setup() installs its own Clipboard API stub on navigator.clipboard (jsdom has
    // none by default) -- it must run BEFORE spying, and the spy must wrap the property userEvent
    // installed rather than replace the object, or the component's calls go to a different,
    // unobserved clipboard than the one this test is asserting against.
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined)

    render(<FixDiff originalCode={null} fixedCode="fixed code here" />)
    await user.click(screen.getByRole('button', { name: 'Copy fix' }))

    expect(writeText).toHaveBeenCalledWith('fixed code here')
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })
})
