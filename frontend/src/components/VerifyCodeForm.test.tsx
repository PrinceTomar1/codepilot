import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import VerifyCodeForm from './VerifyCodeForm'
import * as authApi from '../api/auth'

describe('VerifyCodeForm', () => {
  it('strips non-digit characters and caps input at 6 digits as the user types', async () => {
    const user = userEvent.setup()
    render(<VerifyCodeForm email="dev@example.com" onVerified={vi.fn()} />)

    const input = screen.getByPlaceholderText('123456')
    await user.type(input, 'a1b2c3d4e5f6g7')

    expect(input).toHaveValue('123456')
  })

  it('disables the Verify button until exactly 6 digits are entered', async () => {
    const user = userEvent.setup()
    render(<VerifyCodeForm email="dev@example.com" onVerified={vi.fn()} />)

    const button = screen.getByRole('button', { name: 'Verify' })
    expect(button).toBeDisabled()

    await user.type(screen.getByPlaceholderText('123456'), '123')
    expect(button).toBeDisabled()

    await user.type(screen.getByPlaceholderText('123456'), '456')
    expect(button).toBeEnabled()
  })

  it('calls verifyCode with the email and code, then onVerified on success', async () => {
    const verifyCodeSpy = vi.spyOn(authApi, 'verifyCode').mockResolvedValue({
      message: 'Email verified. You can sign in now.',
    })
    const onVerified = vi.fn()
    const user = userEvent.setup()
    render(<VerifyCodeForm email="dev@example.com" onVerified={onVerified} />)

    await user.type(screen.getByPlaceholderText('123456'), '482913')
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    expect(verifyCodeSpy).toHaveBeenCalledWith('dev@example.com', '482913')
    expect(onVerified).toHaveBeenCalledOnce()
  })

  it('shows the server error message and does not call onVerified when the code is wrong', async () => {
    vi.spyOn(authApi, 'verifyCode').mockRejectedValue({
      message: 'Invalid or expired code.',
    })
    const onVerified = vi.fn()
    const user = userEvent.setup()
    render(<VerifyCodeForm email="dev@example.com" onVerified={onVerified} />)

    await user.type(screen.getByPlaceholderText('123456'), '000000')
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByText('Invalid or expired code.')).toBeInTheDocument()
    expect(onVerified).not.toHaveBeenCalled()
  })

  it('shows a validation error instead of calling the API when the email prop is blank', async () => {
    const verifyCodeSpy = vi.spyOn(authApi, 'verifyCode')
    const user = userEvent.setup()
    render(<VerifyCodeForm email="" onVerified={vi.fn()} />)

    await user.type(screen.getByPlaceholderText('123456'), '123456')
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByText('Enter your email above first.')).toBeInTheDocument()
    expect(verifyCodeSpy).not.toHaveBeenCalled()
  })
})
