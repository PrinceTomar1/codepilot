import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useAskQuestion, useQAHistory } from '../api/qa'
import { getErrorMessage, formatDate } from '../lib/utils'
import CitationBadge from './CitationBadge'
import MarkdownContent from './MarkdownContent'
import type { AskQuestionResponse } from '../types'

interface ChatMessage {
  id: string
  question: string
  answer: string
  citations: AskQuestionResponse['citations']
  createdAt: string
  pending?: boolean
}

export default function ChatPanel({ repoId }: { repoId: string }) {
  const { data: history, isLoading: historyLoading } = useQAHistory(repoId)
  const askQuestion = useAskQuestion(repoId)

  const [draft, setDraft] = useState('')
  const [localMessages, setLocalMessages] = useState<ChatMessage[]>([])
  const [error, setError] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  const historyMessages: ChatMessage[] = (history ?? [])
    .slice()
    .reverse()
    .map((entry) => ({
      id: entry.id,
      question: entry.question,
      answer: entry.answer,
      citations: entry.citations,
      createdAt: entry.createdAt,
    }))

  const messages = [...historyMessages, ...localMessages]

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight })
  }, [messages.length])

  // A successful question invalidates the qa-history query (see useAskQuestion), which refetches
  // in the background and will eventually include this exact Q&A. Once it does, drop the local
  // optimistic copy so it isn't rendered twice -- but only once the server-backed version is
  // actually available, so the answer never has a gap where it's shown then briefly disappears.
  useEffect(() => {
    if (!history || history.length === 0) return
    setLocalMessages((prev) => {
      const historyQuestions = new Set(history.map((h) => h.question))
      const next = prev.filter((m) => m.pending || !historyQuestions.has(m.question))
      return next.length === prev.length ? prev : next
    })
  }, [history])

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    const question = draft.trim()
    if (!question || askQuestion.isPending) return

    setError(null)
    setDraft('')

    const pendingId = `pending-${Date.now()}`
    setLocalMessages((prev) => [
      ...prev,
      {
        id: pendingId,
        question,
        answer: '',
        citations: [],
        createdAt: new Date().toISOString(),
        pending: true,
      },
    ])

    try {
      const response = await askQuestion.mutateAsync(question)
      setLocalMessages((prev) =>
        prev.map((m) =>
          m.id === pendingId
            ? {
                ...m,
                answer: response.answer,
                citations: response.citations,
                pending: false,
              }
            : m,
        ),
      )
    } catch (err) {
      setError(getErrorMessage(err))
      setLocalMessages((prev) => prev.filter((m) => m.id !== pendingId))
      setDraft(question)
    }
  }

  return (
    <div className="flex h-full flex-col">
      <div ref={scrollRef} className="flex-1 space-y-6 overflow-y-auto px-1 py-4">
        {historyLoading && messages.length === 0 && (
          <div className="space-y-3">
            {[0, 1].map((i) => (
              <div key={i} className="h-20 animate-pulse rounded-xl bg-slate-900" />
            ))}
          </div>
        )}

        {!historyLoading && messages.length === 0 && (
          <div className="flex h-full flex-col items-center justify-center py-16 text-center">
            <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-brand-600/10 text-2xl">
              💬
            </div>
            <p className="text-sm font-medium text-slate-300">
              Ask anything about this codebase
            </p>
            <p className="mt-1 max-w-sm text-sm text-slate-500">
              e.g. &ldquo;Where is JWT validation implemented?&rdquo; or
              &ldquo;How does the PR review pipeline work?&rdquo;
            </p>
          </div>
        )}

        {messages.map((message) => (
          <div key={message.id} className="space-y-3">
            <div className="flex justify-end">
              <div className="max-w-[85%] rounded-2xl rounded-tr-sm bg-brand-600 px-4 py-2.5 text-sm text-white">
                {message.question}
              </div>
            </div>

            <div className="flex justify-start">
              <div className="max-w-[90%] rounded-2xl rounded-tl-sm border border-slate-800 bg-slate-900 px-4 py-3">
                {message.pending ? (
                  <div className="flex items-center gap-2 text-sm text-slate-500">
                    <span className="flex gap-1">
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-500 [animation-delay:-0.3s]" />
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-500 [animation-delay:-0.15s]" />
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-500" />
                    </span>
                    Thinking through the codebase…
                  </div>
                ) : (
                  <>
                    <MarkdownContent content={message.answer} />
                    {message.citations.length > 0 && (
                      <div className="mt-3 flex flex-wrap gap-1.5 border-t border-slate-800 pt-3">
                        {message.citations.map((citation, idx) => (
                          <CitationBadge
                            key={`${citation.filePath}-${idx}`}
                            citation={citation}
                          />
                        ))}
                      </div>
                    )}
                    <p className="mt-2 text-[11px] text-slate-600">
                      {formatDate(message.createdAt)}
                    </p>
                  </>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>

      {error && (
        <p className="mb-2 rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
          {error}
        </p>
      )}

      <form
        onSubmit={handleSubmit}
        className="flex items-end gap-2 border-t border-slate-800 pt-3"
      >
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSubmit(e)
            }
          }}
          rows={1}
          placeholder="Ask a question about this repository…"
          className="input max-h-32 min-h-[42px] resize-none py-2.5"
        />
        <button
          type="submit"
          className="btn-primary shrink-0"
          disabled={!draft.trim() || askQuestion.isPending}
        >
          Send
        </button>
      </form>
    </div>
  )
}
