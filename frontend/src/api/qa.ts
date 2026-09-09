import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient, baseURL, tokenStore, UNAUTHORIZED_EVENT } from './client'
import type { AskQuestionResponse, Citation, QAHistoryEntry } from '../types'

const qaHistoryKey = (repoId: string) => ['repositories', repoId, 'qa-history'] as const

async function fetchQAHistory(repoId: string): Promise<QAHistoryEntry[]> {
  const { data } = await apiClient.get<QAHistoryEntry[]>(
    `/repositories/${repoId}/qa-history`,
  )
  return data
}

async function askQuestion(
  repoId: string,
  question: string,
): Promise<AskQuestionResponse> {
  const { data } = await apiClient.post<AskQuestionResponse>(
    `/repositories/${repoId}/ask`,
    { question },
  )
  return data
}

export function useQAHistory(repoId: string | undefined) {
  return useQuery({
    queryKey: qaHistoryKey(repoId ?? ''),
    queryFn: () => fetchQAHistory(repoId as string),
    enabled: Boolean(repoId),
  })
}

/**
 * Non-streaming ask. Kept as a fallback (and for environments without a readable response body);
 * the chatbot UI uses {@link streamQuestion}.
 */
export function useAskQuestion(repoId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (question: string) => askQuestion(repoId as string, question),
    onSuccess: () => {
      if (repoId) {
        queryClient.invalidateQueries({ queryKey: qaHistoryKey(repoId) })
      }
    },
  })
}

// ---------------------------------------------------------------------------
// Streaming ask (Server-Sent Events)
// ---------------------------------------------------------------------------

export interface StreamDonePayload {
  answer: string
  citations: Citation[]
  chunksRetrieved?: number
}

export interface StreamCallbacks {
  /** A chunk of answer text. Fired zero-or-more times, in order. */
  onToken: (text: string) => void
  /** The authoritative final answer + citations. Fired exactly once on success. */
  onDone: (payload: StreamDonePayload) => void
  /** A user-facing error message. Fired instead of onDone when something goes wrong. */
  onError: (message: string) => void
}

interface SSEFrame {
  event: string
  data: string
}

/**
 * Incremental parser for an SSE byte stream. `push` accepts an arbitrary slice of the stream
 * (frame boundaries can fall anywhere, even mid-line) and returns whatever complete
 * `event:`/`data:` frames are now available, buffering the rest for the next call.
 */
export function createSSEParser() {
  let buffer = ''
  return {
    push(chunk: string): SSEFrame[] {
      buffer += chunk.replace(/\r\n/g, '\n')
      const frames: SSEFrame[] = []
      let boundary: number
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const rawFrame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        let event = 'message'
        const dataLines: string[] = []
        for (const line of rawFrame.split('\n')) {
          if (line.startsWith('event:')) {
            event = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).replace(/^ /, ''))
          }
        }
        if (dataLines.length > 0) {
          frames.push({ event, data: dataLines.join('\n') })
        }
      }
      return frames
    },
  }
}

function safeJsonParse(text: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}

const GENERIC_ERROR =
  'The assistant is unavailable right now. Please try again in a moment.'

/**
 * POSTs a question to the streaming Q&A endpoint and drives `callbacks` as the answer arrives.
 * Resolves when the stream ends (whether via onDone or onError). Aborting `signal` stops it
 * silently.
 */
export async function streamQuestion(
  repoId: string,
  question: string,
  callbacks: StreamCallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const token = tokenStore.get()

  let response: Response
  try {
    response = await fetch(`${baseURL}/repositories/${repoId}/ask/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ question }),
      signal,
    })
  } catch (err) {
    if ((err as Error)?.name === 'AbortError') return
    callbacks.onError("Couldn't reach the server. Check your connection and try again.")
    return
  }

  if (response.status === 401) {
    tokenStore.set(null)
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    callbacks.onError('Your session has expired. Please sign in again.')
    return
  }

  if (!response.ok || !response.body) {
    let message = GENERIC_ERROR
    try {
      const body = await response.json()
      message = (body?.message as string) || (body?.error as string) || message
    } catch {
      /* keep the generic message */
    }
    callbacks.onError(message)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const parser = createSSEParser()
  let sawTerminalFrame = false

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      for (const frame of parser.push(decoder.decode(value, { stream: true }))) {
        const data = safeJsonParse(frame.data)
        if (!data) continue

        if (frame.event === 'token' && typeof data.text === 'string') {
          callbacks.onToken(data.text)
        } else if (frame.event === 'done') {
          sawTerminalFrame = true
          callbacks.onDone({
            answer: typeof data.answer === 'string' ? data.answer : '',
            citations: Array.isArray(data.citations) ? (data.citations as Citation[]) : [],
            chunksRetrieved:
              typeof data.chunksRetrieved === 'number' ? data.chunksRetrieved : undefined,
          })
        } else if (frame.event === 'error') {
          sawTerminalFrame = true
          callbacks.onError(
            typeof data.error === 'string' && data.error ? data.error : GENERIC_ERROR,
          )
          return
        }
      }
    }
  } catch (err) {
    if ((err as Error)?.name === 'AbortError') return
    callbacks.onError('The connection was interrupted before the answer finished. Please try again.')
    return
  }

  if (!sawTerminalFrame) {
    callbacks.onError('The assistant stopped responding before finishing. Please try again.')
  }
}
