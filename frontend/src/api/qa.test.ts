import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSSEParser, streamQuestion, type StreamCallbacks } from './qa'
import { UNAUTHORIZED_EVENT } from './client'

describe('createSSEParser', () => {
  it('parses a single complete frame', () => {
    const parser = createSSEParser()
    const frames = parser.push('event: token\ndata: {"text":"hi"}\n\n')
    expect(frames).toEqual([{ event: 'token', data: '{"text":"hi"}' }])
  })

  it('buffers a frame split across chunks and emits it once complete', () => {
    const parser = createSSEParser()
    expect(parser.push('event: to')).toEqual([])
    expect(parser.push('ken\ndata: {"text":"')).toEqual([])
    expect(parser.push('hi"}\n\n')).toEqual([{ event: 'token', data: '{"text":"hi"}' }])
  })

  it('emits multiple frames delivered in one chunk', () => {
    const parser = createSSEParser()
    const frames = parser.push(
      'event: token\ndata: {"text":"a"}\n\nevent: token\ndata: {"text":"b"}\n\n',
    )
    expect(frames.map((f) => f.data)).toEqual(['{"text":"a"}', '{"text":"b"}'])
  })

  it('normalizes CRLF line endings', () => {
    const parser = createSSEParser()
    const frames = parser.push('event: done\r\ndata: {"answer":"x"}\r\n\r\n')
    expect(frames).toEqual([{ event: 'done', data: '{"answer":"x"}' }])
  })

  it('ignores a frame that carries no data line', () => {
    const parser = createSSEParser()
    expect(parser.push(': keep-alive comment\n\n')).toEqual([])
  })
})

function mockFetchStreaming(chunks: string[], init?: { status?: number; ok?: boolean }) {
  const encoder = new TextEncoder()
  let i = 0
  const reader = {
    read: () =>
      Promise.resolve(
        i < chunks.length
          ? { done: false, value: encoder.encode(chunks[i++]) }
          : { done: true, value: undefined },
      ),
  }
  return vi.fn().mockResolvedValue({
    ok: init?.ok ?? true,
    status: init?.status ?? 200,
    body: { getReader: () => reader },
    json: () => Promise.resolve({}),
  })
}

function collect() {
  const tokens: string[] = []
  let done: Parameters<StreamCallbacks['onDone']>[0] | null = null
  let error: string | null = null
  const callbacks: StreamCallbacks = {
    onToken: (t) => tokens.push(t),
    onDone: (d) => {
      done = d
    },
    onError: (m) => {
      error = m
    },
  }
  return { callbacks, get tokens() { return tokens }, get done() { return done }, get error() { return error } }
}

describe('streamQuestion', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('delivers tokens in order then the final done payload', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchStreaming([
        'event: token\ndata: {"text":"foo "}\n\n',
        'event: token\ndata: {"text":"bar"}\n\n',
        'event: done\ndata: {"answer":"foo bar","citations":[{"filePath":"src/foo.py","startLine":1,"endLine":5,"snippet":"x"}],"chunksRetrieved":1}\n\n',
      ]),
    )
    const sink = collect()

    await streamQuestion('repo-1', 'what is foo?', sink.callbacks)

    expect(sink.tokens).toEqual(['foo ', 'bar'])
    expect(sink.done).toEqual({
      answer: 'foo bar',
      citations: [{ filePath: 'src/foo.py', startLine: 1, endLine: 5, snippet: 'x' }],
      chunksRetrieved: 1,
    })
    expect(sink.error).toBeNull()
  })

  it('reports an inline error frame and does not call onDone', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetchStreaming([
        'event: token\ndata: {"text":"partial"}\n\n',
        'event: error\ndata: {"error":"AI provider rate limit reached","status":429}\n\n',
      ]),
    )
    const sink = collect()

    await streamQuestion('repo-1', 'q', sink.callbacks)

    expect(sink.tokens).toEqual(['partial'])
    expect(sink.error).toBe('AI provider rate limit reached')
    expect(sink.done).toBeNull()
  })

  it('surfaces a non-OK response body message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        body: null,
        json: () => Promise.resolve({ message: 'AI features are not configured yet.' }),
      }),
    )
    const sink = collect()

    await streamQuestion('repo-1', 'q', sink.callbacks)

    expect(sink.error).toBe('AI features are not configured yet.')
  })

  it('handles a 401 by clearing auth and dispatching the unauthorized event', async () => {
    const listener = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, listener)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 401, body: null, json: () => Promise.resolve({}) }),
    )
    const sink = collect()

    await streamQuestion('repo-1', 'q', sink.callbacks)

    expect(listener).toHaveBeenCalled()
    expect(sink.error).toMatch(/session has expired/i)
    window.removeEventListener(UNAUTHORIZED_EVENT, listener)
  })

  it('errors if the stream ends without a done or error frame', async () => {
    vi.stubGlobal('fetch', mockFetchStreaming(['event: token\ndata: {"text":"cut"}\n\n']))
    const sink = collect()

    await streamQuestion('repo-1', 'q', sink.callbacks)

    expect(sink.tokens).toEqual(['cut'])
    expect(sink.error).toMatch(/stopped responding/i)
  })

  it('stays silent when aborted', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(Object.assign(new Error('aborted'), { name: 'AbortError' })),
    )
    const sink = collect()

    await streamQuestion('repo-1', 'q', sink.callbacks, AbortSignal.abort())

    expect(sink.tokens).toEqual([])
    expect(sink.error).toBeNull()
    expect(sink.done).toBeNull()
  })
})
