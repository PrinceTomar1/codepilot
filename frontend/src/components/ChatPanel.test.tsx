import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatPanel from './ChatPanel'
import { apiClient } from '../api/client'

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ChatPanel repoId="repo-1" />
    </QueryClientProvider>,
  )
}

/** A fake streaming Response whose body dribbles out the given SSE frames one chunk at a time. */
function streamingResponse(chunks: string[], init?: { ok?: boolean; status?: number }) {
  const encoder = new TextEncoder()
  let i = 0
  return {
    ok: init?.ok ?? true,
    status: init?.status ?? 200,
    body: {
      getReader: () => ({
        read: () =>
          Promise.resolve(
            i < chunks.length
              ? { done: false, value: encoder.encode(chunks[i++]) }
              : { done: true, value: undefined },
          ),
      }),
    },
    json: () => Promise.resolve({}),
  }
}

describe('ChatPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('streams an answer into the transcript and shows citations when done', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        streamingResponse([
          'event: token\ndata: {"text":"JWT validation lives in "}\n\n',
          'event: token\ndata: {"text":"JwtService."}\n\n',
          'event: done\ndata: {"answer":"JWT validation lives in JwtService.","citations":[{"filePath":"src/JwtService.java","startLine":12,"endLine":30,"snippet":"parse"}],"chunksRetrieved":1}\n\n',
        ]),
      ),
    )

    renderPanel()
    await userEvent.type(
      screen.getByPlaceholderText(/ask a question about this repository/i),
      'Where is JWT validation?',
    )
    await userEvent.click(screen.getByRole('button', { name: /send/i }))

    expect(await screen.findByText(/JWT validation lives in JwtService\./)).toBeInTheDocument()
    expect(await screen.findByText(/src\/JwtService\.java:12-30/)).toBeInTheDocument()
    // the send button reverts from its in-flight "Answering…" label once the stream finishes
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument(),
    )
  })

  it('shows an error banner and restores the draft when the stream fails before any token', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        streamingResponse(
          ['event: error\ndata: {"error":"AI features are not configured yet.","status":503}\n\n'],
        ),
      ),
    )

    renderPanel()
    const input = screen.getByPlaceholderText(/ask a question about this repository/i)
    await userEvent.type(input, 'Explain the code')
    await userEvent.click(screen.getByRole('button', { name: /send/i }))

    expect(await screen.findByText(/not configured yet/i)).toBeInTheDocument()
    await waitFor(() => expect(input).toHaveValue('Explain the code'))
  })

  it('keeps the partial answer visible if the stream drops mid-response', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        streamingResponse(['event: token\ndata: {"text":"The pipeline starts by"}\n\n']),
      ),
    )

    renderPanel()
    await userEvent.type(
      screen.getByPlaceholderText(/ask a question about this repository/i),
      'How does the pipeline work?',
    )
    await userEvent.click(screen.getByRole('button', { name: /send/i }))

    expect(await screen.findByText(/The pipeline starts by/)).toBeInTheDocument()
    expect(await screen.findByText(/stopped responding/i)).toBeInTheDocument()
  })
})
