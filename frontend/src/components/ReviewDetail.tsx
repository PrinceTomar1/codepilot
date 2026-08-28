import { useState } from 'react'
import { useReviewDetail } from '../api/reviews'
import MarkdownContent from './MarkdownContent'
import SeverityBadge from './SeverityBadge'
import type { Finding, ReviewFindings } from '../types'

const CATEGORY_META: Record<
  keyof ReviewFindings,
  { label: string; icon: string; description: string }
> = {
  bugs: {
    label: 'Bugs',
    icon: '🐛',
    description: 'Logic errors and incorrect behavior',
  },
  security: {
    label: 'Security',
    icon: '🛡️',
    description: 'Vulnerabilities and unsafe patterns',
  },
  codeSmells: {
    label: 'Code smells',
    icon: '🧹',
    description: 'Maintainability and design concerns',
  },
  missingTests: {
    label: 'Missing tests',
    icon: '🧪',
    description: 'Under-tested or untested code paths',
  },
  performance: {
    label: 'Performance',
    icon: '⚡',
    description: 'Inefficiencies and scalability risks',
  },
}

const CATEGORY_ORDER: (keyof ReviewFindings)[] = [
  'security',
  'bugs',
  'performance',
  'codeSmells',
  'missingTests',
]

/** Splits a code snippet into lines for the diff view -- kept trivially simple (no line-matching
 * / Myers-diff algorithm) since these are short, focused snippets the model already wrote as a
 * direct before/after pair, not two independent large files that need aligning. */
function toLines(code: string): string[] {
  return code.replace(/\n$/, '').split('\n')
}

export function FixDiff({ originalCode, fixedCode }: { originalCode?: string | null; fixedCode?: string | null }) {
  const [copied, setCopied] = useState(false)

  if (!fixedCode) return null

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(fixedCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard access can be denied by the browser -- the code is still visible to select/copy
      // manually, so this isn't worth surfacing as an error.
    }
  }

  return (
    <div className="mt-2 overflow-hidden rounded-md border border-slate-800">
      <div className="flex items-center justify-between border-b border-slate-800 bg-slate-900 px-2.5 py-1.5">
        <span className="text-[11px] font-medium uppercase tracking-wide text-slate-500">
          Suggested fix
        </span>
        <button
          type="button"
          onClick={handleCopy}
          className="rounded px-2 py-0.5 text-[11px] font-medium text-slate-400 hover:bg-slate-800 hover:text-slate-200"
        >
          {copied ? 'Copied' : 'Copy fix'}
        </button>
      </div>
      <pre className="overflow-x-auto p-0 font-mono text-[12.5px] leading-5">
        {originalCode &&
          toLines(originalCode).map((line, i) => (
            <div key={`orig-${i}`} className="bg-rose-500/10 px-3 text-rose-300/90">
              <span className="select-none text-rose-500/60">- </span>
              {line}
            </div>
          ))}
        {toLines(fixedCode).map((line, i) => (
          <div key={`fix-${i}`} className="bg-emerald-500/10 px-3 text-emerald-300/90">
            <span className="select-none text-emerald-500/60">+ </span>
            {line}
          </div>
        ))}
      </pre>
    </div>
  )
}

function FindingRow({ finding }: { finding: Finding }) {
  return (
    <div className="rounded-lg border border-slate-800 bg-slate-950/50 p-3.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-mono text-xs text-brand-400">
          {finding.file}:{finding.line}
        </span>
        <SeverityBadge severity={finding.severity} />
      </div>
      <div className="mt-2">
        <MarkdownContent content={finding.description} />
      </div>
      {finding.suggestion && (
        <div className="mt-2 flex gap-2 rounded-md bg-emerald-500/5 px-2.5 py-2">
          <span className="mt-0.5 shrink-0">💡</span>
          <div className="min-w-0 flex-1">
            <MarkdownContent content={finding.suggestion} textClassName="text-emerald-300/90" />
          </div>
        </div>
      )}
      <FixDiff originalCode={finding.originalCode} fixedCode={finding.fixedCode} />
    </div>
  )
}

export default function ReviewDetail({ reviewId }: { reviewId: string }) {
  const { data: review, isLoading, isError } = useReviewDetail(reviewId)

  if (isLoading) {
    return (
      <div className="space-y-3 p-6">
        <div className="h-6 w-2/3 animate-pulse rounded bg-slate-800" />
        <div className="h-24 animate-pulse rounded-xl bg-slate-900" />
        <div className="h-24 animate-pulse rounded-xl bg-slate-900" />
      </div>
    )
  }

  if (isError || !review) {
    return (
      <div className="p-6 text-sm text-rose-400">
        Couldn&apos;t load this review.
      </div>
    )
  }

  const totalFindings = CATEGORY_ORDER.reduce(
    (sum, key) => sum + (review.findings[key]?.length ?? 0),
    0,
  )

  return (
    <div className="space-y-6 p-6">
      <div className="card p-5">
        <h3 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Summary
        </h3>
        <MarkdownContent content={review.summary} />
        <p className="mt-3 text-xs text-slate-500">
          {totalFindings} finding{totalFindings === 1 ? '' : 's'} across{' '}
          {CATEGORY_ORDER.filter((k) => (review.findings[k]?.length ?? 0) > 0).length}{' '}
          categories
        </p>
      </div>

      {CATEGORY_ORDER.map((key) => {
        const findings = review.findings[key] ?? []
        if (findings.length === 0) return null
        const meta = CATEGORY_META[key]
        return (
          <div key={key}>
            <div className="mb-3 flex items-center gap-2">
              <span>{meta.icon}</span>
              <h3 className="text-sm font-semibold text-slate-100">
                {meta.label}
              </h3>
              <span className="rounded-full bg-slate-800 px-2 py-0.5 text-xs text-slate-400">
                {findings.length}
              </span>
              <span className="text-xs text-slate-600">{meta.description}</span>
            </div>
            <div className="space-y-2">
              {findings.map((finding, idx) => (
                <FindingRow key={`${key}-${idx}`} finding={finding} />
              ))}
            </div>
          </div>
        )
      })}

      {totalFindings === 0 && (
        <div className="flex flex-col items-center justify-center gap-2 py-12 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-full bg-emerald-500/10 text-2xl">
            ✅
          </div>
          <p className="text-sm font-medium text-slate-300">
            No issues found
          </p>
          <p className="max-w-xs text-sm text-slate-500">
            The AI review agents didn&apos;t flag anything in this pull
            request.
          </p>
        </div>
      )}
    </div>
  )
}
