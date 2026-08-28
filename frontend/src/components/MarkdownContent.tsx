import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/**
 * Renders LLM-generated prose (Q&A answers, onboarding docs) as actual formatted markdown
 * instead of raw text. The model naturally uses headers/bold/lists/code -- without this, a
 * well-formatted answer shows up as literal "###" and "**" characters cluttering the screen.
 *
 * `textClassName` overrides the base paragraph color (e.g. for the emerald-tinted suggestion
 * box in ReviewDetail) -- pass it instead of wrapping this in a CSS selector hack, since
 * strong/code/headings set their own explicit color classes that a wildcard descendant
 * selector can't reliably win against (equal specificity, cascade order isn't guaranteed).
 */
export default function MarkdownContent({
  content,
  textClassName = 'text-slate-200',
}: {
  content: string
  textClassName?: string
}) {
  return (
    <div className={`text-sm leading-relaxed ${textClassName}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          p: ({ children }) => <p className="mb-2.5 last:mb-0">{children}</p>,
          h1: ({ children }) => (
            <h1 className="mb-2 mt-4 text-base font-semibold text-slate-100 first:mt-0">{children}</h1>
          ),
          h2: ({ children }) => (
            <h2 className="mb-2 mt-4 text-sm font-semibold text-slate-100 first:mt-0">{children}</h2>
          ),
          h3: ({ children }) => (
            <h3 className="mb-1.5 mt-3 text-sm font-semibold text-slate-200 first:mt-0">{children}</h3>
          ),
          ul: ({ children }) => <ul className="mb-2.5 list-disc space-y-1 pl-5">{children}</ul>,
          ol: ({ children }) => <ol className="mb-2.5 list-decimal space-y-1 pl-5">{children}</ol>,
          li: ({ children }) => <li className="pl-0.5">{children}</li>,
          strong: ({ children }) => <strong className="font-semibold text-slate-100">{children}</strong>,
          a: ({ children, href }) => (
            <a
              href={href}
              target="_blank"
              rel="noreferrer"
              className="text-brand-400 underline underline-offset-2 hover:text-brand-300"
            >
              {children}
            </a>
          ),
          code: ({ children, className }) => {
            // remark renders fenced code blocks as <pre><code class="language-x">, inline
            // code as bare <code> with no className -- use that to tell them apart.
            const isBlock = Boolean(className)
            if (isBlock) {
              return <code className={className}>{children}</code>
            }
            return (
              <code className="rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[13px] text-brand-300">
                {children}
              </code>
            )
          },
          pre: ({ children }) => (
            <pre className="mb-2.5 overflow-x-auto rounded-lg border border-slate-800 bg-slate-950/70 p-3 font-mono text-[13px] text-slate-300">
              {children}
            </pre>
          ),
          blockquote: ({ children }) => (
            <blockquote className="mb-2.5 border-l-2 border-slate-700 pl-3 text-slate-400">
              {children}
            </blockquote>
          ),
          // remark-gfm enables tables -- without explicit styling these render as bare,
          // browser-default HTML tables, which look broken against the dark theme.
          table: ({ children }) => (
            <div className="mb-2.5 overflow-x-auto rounded-lg border border-slate-800">
              <table className="w-full border-collapse text-left text-[13px]">{children}</table>
            </div>
          ),
          thead: ({ children }) => <thead className="bg-slate-900">{children}</thead>,
          tbody: ({ children }) => <tbody className="divide-y divide-slate-800">{children}</tbody>,
          tr: ({ children }) => <tr>{children}</tr>,
          th: ({ children }) => (
            <th className="px-3 py-2 font-semibold text-slate-300">{children}</th>
          ),
          td: ({ children }) => <td className="px-3 py-2 text-slate-300">{children}</td>,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
