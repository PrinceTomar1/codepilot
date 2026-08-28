import { useState } from 'react'
import type { Citation } from '../types'

/**
 * Renders a citation as a clickable "path:startLine-endLine" pill. Clicking
 * (or hovering, via CSS) reveals a monospace snippet preview popover.
 */
export default function CitationBadge({ citation }: { citation: Citation }) {
  const [open, setOpen] = useState(false)
  const label =
    citation.startLine === citation.endLine
      ? `${citation.filePath}:${citation.startLine}`
      : `${citation.filePath}:${citation.startLine}-${citation.endLine}`

  return (
    <span className="group relative inline-block">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="inline-flex max-w-full items-center gap-1 truncate rounded-md border border-slate-700 bg-slate-800/80 px-2 py-1 font-mono text-xs text-brand-300 transition-colors hover:border-brand-500 hover:bg-slate-800 hover:text-brand-200"
        title="Click to preview snippet"
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 20 20"
          fill="currentColor"
          className="h-3.5 w-3.5 shrink-0 opacity-70"
        >
          <path
            fillRule="evenodd"
            d="M6.28 5.22a.75.75 0 010 1.06L2.56 10l3.72 3.72a.75.75 0 01-1.06 1.06L.97 10.53a.75.75 0 010-1.06l4.25-4.25a.75.75 0 011.06 0zm7.44 0a.75.75 0 011.06 0l4.25 4.25a.75.75 0 010 1.06l-4.25 4.25a.75.75 0 01-1.06-1.06L17.44 10l-3.72-3.72a.75.75 0 010-1.06z"
            clipRule="evenodd"
          />
        </svg>
        <span className="truncate">{label}</span>
      </button>

      <span
        className={`pointer-events-none absolute left-0 top-full z-20 mt-1.5 w-max max-w-sm origin-top-left scale-95 rounded-lg border border-slate-700 bg-slate-950 opacity-0 shadow-xl transition-all duration-100 group-hover:pointer-events-auto group-hover:scale-100 group-hover:opacity-100 ${
          open ? 'pointer-events-auto scale-100 opacity-100' : ''
        }`}
      >
        <div className="border-b border-slate-800 px-3 py-1.5 font-mono text-[11px] text-slate-500">
          {citation.filePath}
        </div>
        <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words px-3 py-2 font-mono text-xs leading-relaxed text-slate-300">
          {citation.snippet}
        </pre>
      </span>
    </span>
  )
}
