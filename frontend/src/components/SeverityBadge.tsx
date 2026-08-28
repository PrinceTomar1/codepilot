import { cx } from '../lib/utils'
import type { FindingSeverity } from '../types'

const STYLES: Record<string, string> = {
  CRITICAL: 'bg-rose-500/15 text-rose-400 ring-rose-500/30',
  HIGH: 'bg-orange-500/15 text-orange-400 ring-orange-500/30',
  MEDIUM: 'bg-amber-500/15 text-amber-400 ring-amber-500/30',
  LOW: 'bg-slate-500/15 text-slate-400 ring-slate-500/30',
}

export default function SeverityBadge({
  severity,
}: {
  severity: FindingSeverity
}) {
  const key = severity.toUpperCase()
  return (
    <span
      className={cx(
        'inline-flex items-center rounded-md px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide ring-1 ring-inset',
        STYLES[key] ?? 'bg-slate-500/15 text-slate-400 ring-slate-500/30',
      )}
    >
      {severity}
    </span>
  )
}
