import type { ReactNode } from 'react'
import { useOnboarding } from '../api/onboarding'
import MarkdownContent from './MarkdownContent'

function Section({
  title,
  children,
}: {
  title: string
  children: ReactNode
}) {
  return (
    <section className="card p-6">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
        {title}
      </h2>
      {children}
    </section>
  )
}

export default function OnboardingView({ repoId }: { repoId: string }) {
  const { data, isLoading, isError, error, refetch, isFetching } =
    useOnboarding(repoId)

  if (isLoading) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-24 text-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-700 border-t-brand-500" />
        <div>
          <p className="text-sm font-medium text-slate-300">
            Generating onboarding docs…
          </p>
          <p className="mt-1 max-w-sm text-sm text-slate-500">
            The AI is analyzing the codebase for the first time. This can
            take a little while — it&apos;s only slow once, the result is
            cached after this.
          </p>
        </div>
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-24 text-center">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-rose-500/10 text-2xl">
          ⚠️
        </div>
        <p className="text-sm font-medium text-slate-300">
          Couldn&apos;t generate onboarding docs
        </p>
        <p className="max-w-sm text-sm text-slate-500">
          {error instanceof Object && 'message' in error
            ? String((error as { message: unknown }).message)
            : 'Something went wrong.'}
        </p>
        <button
          type="button"
          className="btn-secondary mt-2"
          onClick={() => refetch()}
          disabled={isFetching}
        >
          Try again
        </button>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl space-y-5 p-6">
      <Section title="Architecture overview">
        <MarkdownContent content={data.architectureOverview} />
      </Section>

      <Section title="Read this first">
        <ol className="space-y-1.5">
          {data.readFirst.map((path, idx) => (
            <li key={path} className="flex items-center gap-2.5 text-sm">
              <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-600/15 text-[11px] font-semibold text-brand-300">
                {idx + 1}
              </span>
              <code className="font-mono text-slate-300">{path}</code>
            </li>
          ))}
        </ol>
      </Section>

      <Section title="Important modules">
        <div className="space-y-3">
          {data.importantModules.map((mod) => (
            <div
              key={mod.path}
              className="rounded-lg border border-slate-800 bg-slate-950/50 p-3.5"
            >
              <code className="font-mono text-sm text-brand-300">
                {mod.path}
              </code>
              <div className="mt-1.5 text-slate-400">
                <MarkdownContent content={mod.description} />
              </div>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Data flow">
        <MarkdownContent content={data.dataFlow} />
      </Section>

      <Section title="Setup instructions">
        <MarkdownContent content={data.setupInstructions} />
      </Section>
    </div>
  )
}
