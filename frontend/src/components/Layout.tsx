import type { ReactNode } from 'react'
import Sidebar from './Sidebar'

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-screen w-full overflow-hidden bg-slate-950">
      <Sidebar />
      <main className="min-h-0 flex-1 overflow-y-auto">{children}</main>
    </div>
  )
}
