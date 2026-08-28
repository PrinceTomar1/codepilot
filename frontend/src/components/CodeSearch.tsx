import { useState, type FormEvent } from 'react'
import { useCodeSearch } from '../api/search'
import { getErrorMessage } from '../lib/utils'
import type { SearchResult } from '../types'

function MatchBadge({ result }: { result: SearchResult }) {
  if (result.matchType === 'exact') {
    return (
      <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[11px] font-medium text-emerald-400">
        exact match
      </span>
    )
  }
  const pct = result.relevanceScore !== null ? Math.round(result.relevanceScore * 100) : null
  return (
    <span className="rounded-full bg-slate-800 px-2 py-0.5 text-[11px] font-medium text-slate-400">
      {pct !== null ? `${pct}% match` : 'similarity match'}
    </span>
  )
}

function ResultCard({ result }: { result: SearchResult }) {
  const lineLabel =
    result.startLine === result.endLine
      ? `:${result.startLine}`
      : `:${result.startLine}-${result.endLine}`

  return (
    <div className="rounded-lg border border-slate-800 bg-slate-950/50 p-3.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-mono text-xs text-brand-400">
          {result.filePath}
          {lineLabel}
        </span>
        <div className="flex items-center gap-2">
          {result.symbolName && (
            <span className="rounded-full bg-slate-800 px-2 py-0.5 font-mono text-[11px] text-slate-400">
              {result.symbolName}
            </span>
          )}
          <MatchBadge result={result} />
        </div>
      </div>
      <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md border border-slate-800 bg-slate-950/70 px-3 py-2 font-mono text-[12.5px] leading-relaxed text-slate-300">
        {result.snippet}
      </pre>
    </div>
  )
}

export default function CodeSearch({ repoId }: { repoId: string }) {
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const search = useCodeSearch(repoId)

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    const trimmed = query.trim()
    if (!trimmed) return
    setSubmittedQuery(trimmed)
    search.mutate(trimmed)
  }

  return (
    <div className="mx-auto flex h-full max-w-3xl flex-col px-6 py-4">
      <form onSubmit={handleSubmit} className="flex gap-2">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search for a function, class, file, or concept (e.g. &quot;JWT validation&quot;, &quot;database connection&quot;)"
          className="flex-1 rounded-lg border border-slate-700 bg-slate-900 px-3.5 py-2.5 text-sm text-slate-200 placeholder:text-slate-600 focus:border-brand-500 focus:outline-none"
        />
        <button
          type="submit"
          disabled={search.isPending || !query.trim()}
          className="rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-brand-500 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {search.isPending ? 'Searching…' : 'Search'}
        </button>
      </form>

      <div className="mt-4 flex-1 overflow-y-auto">
        {search.isPending && (
          <div className="space-y-2">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-24 animate-pulse rounded-lg bg-slate-900" />
            ))}
          </div>
        )}

        {search.isError && (
          <div className="text-sm text-rose-400">{getErrorMessage(search.error)}</div>
        )}

        {search.isSuccess && search.data.length === 0 && (
          <div className="flex flex-col items-center justify-center gap-2 py-16 text-center">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-slate-800 text-2xl">
              🔍
            </div>
            <p className="text-sm font-medium text-slate-300">No matches found</p>
            <p className="max-w-xs text-sm text-slate-500">
              Nothing in the indexed code matched &quot;{submittedQuery}&quot;. Try a different
              term, or check the Ask tab for a broader question.
            </p>
          </div>
        )}

        {search.isSuccess && search.data.length > 0 && (
          <div className="space-y-2">
            {search.data.map((result, idx) => (
              <ResultCard key={`${result.filePath}-${result.startLine}-${idx}`} result={result} />
            ))}
          </div>
        )}

        {search.isIdle && (
          <div className="flex flex-col items-center justify-center gap-2 py-16 text-center">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-slate-800 text-2xl">
              💡
            </div>
            <p className="text-sm font-medium text-slate-300">Search this codebase directly</p>
            <p className="max-w-sm text-sm text-slate-500">
              Unlike Ask, this returns matched code snippets directly with no AI-generated
              explanation -- fast, and works even when the AI provider is unavailable.
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
