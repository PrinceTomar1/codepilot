import { useEffect, useMemo, useState } from 'react'
import ReactFlow, {
  Background,
  Controls,
  MarkerType,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
  type NodeMouseHandler,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { useArchitecture } from '../api/architecture'
import { getErrorMessage } from '../lib/utils'

const LANGUAGE_COLORS: Record<string, string> = {
  python: '#3b82f6',
  javascript: '#eab308',
  typescript: '#38bdf8',
  java: '#f97316',
  go: '#22d3ee',
  ruby: '#f43f5e',
  php: '#a78bfa',
}
const DEFAULT_COLOR = '#64748b'

const EDGE_COLOR_DIM = '#334155'
const EDGE_COLOR_ACTIVE = '#38bdf8'

export const COLUMN_WIDTH = 260
export const ROW_HEIGHT = 70

/** Groups files by their immediate parent directory into columns, stacking each group's files
 * vertically -- a simple, dependency-free layout computed from the real file paths (no fixed
 * positions). Grouping by the FULL directory (not just the top-level segment) matters in
 * practice: a typical repo keeps nearly everything under one top-level folder (e.g. `src/`), so
 * a top-level-only grouping dumps almost the entire repo into a single, very tall unusable
 * column -- observed live on an 87-file repo where 80+ files landed in one "src" column.
 * Exported for direct unit testing rather than only exercising it through the full component. */
export function layoutNodes(nodeIds: string[]): Map<string, { x: number; y: number }> {
  const groups = new Map<string, string[]>()
  for (const id of nodeIds) {
    const lastSlash = id.lastIndexOf('/')
    const dir = lastSlash === -1 ? '(root)' : id.slice(0, lastSlash)
    const group = groups.get(dir) ?? []
    group.push(id)
    groups.set(dir, group)
  }

  const positions = new Map<string, { x: number; y: number }>()
  let col = 0
  for (const [, ids] of [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    ids.sort().forEach((id, row) => {
      positions.set(id, { x: col * COLUMN_WIDTH, y: row * ROW_HEIGHT })
    })
    col += 1
  }
  return positions
}

/** Splits a node's edges by direction relative to it -- which files it imports, and which files
 * import it. Returns empty arrays when nothing is active. Exported for direct unit testing, same
 * reasoning as layoutNodes above: real bug found live was that highlighting a node's edges on the
 * canvas isn't enough on its own -- the connected node is very often scrolled off-screen in a
 * dense, multi-column graph, so the highlighted line visually goes nowhere as far as the user can
 * tell. A plain-text list of the actual connected file names is needed regardless of where they
 * sit on the canvas. */
export function getConnections(
  activeNodeId: string | null,
  edges: { source: string; target: string }[],
): { imports: string[]; importedBy: string[] } {
  if (!activeNodeId) return { imports: [], importedBy: [] }
  return {
    imports: edges.filter((e) => e.source === activeNodeId).map((e) => e.target),
    importedBy: edges.filter((e) => e.target === activeNodeId).map((e) => e.source),
  }
}

/** Every node id directly connected to `activeNodeId` (either direction), plus the node itself.
 * Returns null when nothing is active -- the "show everything, nothing highlighted" state.
 * Exported for direct unit testing, same reasoning as layoutNodes above. */
export function getConnectedNodeIds(
  activeNodeId: string | null,
  edges: { source: string; target: string }[],
): Set<string> | null {
  if (!activeNodeId) return null
  const { imports, importedBy } = getConnections(activeNodeId, edges)
  return new Set([activeNodeId, ...imports, ...importedBy])
}

function ConnectionList({
  title,
  files,
  onSelect,
}: {
  title: string
  files: string[]
  onSelect: (id: string) => void
}) {
  if (files.length === 0) return null
  return (
    <div>
      <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {title} ({files.length})
      </p>
      <ul className="mt-1 space-y-0.5">
        {files.map((id) => (
          <li key={id}>
            <button
              type="button"
              title={id}
              onClick={() => onSelect(id)}
              className="max-w-full truncate text-left text-xs text-slate-300 hover:text-brand-400 hover:underline"
            >
              {id.split('/').pop()}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

function ArchitectureCanvas({
  nodes,
  edges,
  activeNodeId,
  connectedNodeIds,
  connections,
  onNodeClick,
  onSelect,
  onClear,
}: {
  nodes: Node[]
  edges: Edge[]
  activeNodeId: string | null
  connectedNodeIds: Set<string> | null
  connections: { imports: string[]; importedBy: string[] }
  onNodeClick: NodeMouseHandler
  onSelect: (id: string) => void
  onClear: () => void
}) {
  const { fitView } = useReactFlow()

  // Real bug found live: highlighting a node's edges isn't enough on its own -- in a dense,
  // multi-column graph the connected node is very often scrolled off-screen, so the highlighted
  // line visually points at nothing the user can see. Reframe the camera to the active node's
  // whole neighborhood (or back out to the full graph once nothing is selected).
  useEffect(() => {
    if (connectedNodeIds) {
      fitView({ nodes: [...connectedNodeIds].map((id) => ({ id })), padding: 0.4, duration: 300 })
    } else {
      fitView({ padding: 0.1, duration: 300 })
    }
  }, [connectedNodeIds, fitView])

  return (
    <div className="relative h-full">
      <div className="pointer-events-none absolute left-1/2 top-3 z-10 -translate-x-1/2 rounded-full bg-slate-900/80 px-3 py-1 text-xs text-slate-400 shadow">
        {activeNodeId
          ? `Showing connections for ${activeNodeId.split('/').pop()} -- click it again to reset`
          : 'Click a file to see its connections'}
      </div>

      {activeNodeId && (connections.imports.length > 0 || connections.importedBy.length > 0) && (
        <div className="absolute right-3 top-3 z-10 max-h-[70%] w-56 overflow-y-auto rounded-lg border border-slate-800 bg-slate-900/95 p-3 shadow-lg">
          <p className="mb-2 truncate text-xs font-medium text-slate-200" title={activeNodeId}>
            {activeNodeId.split('/').pop()}
          </p>
          <div className="space-y-3">
            <ConnectionList title="Imports" files={connections.imports} onSelect={onSelect} />
            <ConnectionList title="Imported by" files={connections.importedBy} onSelect={onSelect} />
          </div>
        </div>
      )}

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodeClick={onNodeClick}
        onPaneClick={onClear}
        nodesDraggable
        nodesConnectable={false}
        elementsSelectable={false}
        proOptions={{ hideAttribution: true }}
      >
        <Background color="#1e293b" gap={20} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  )
}

export default function ArchitectureGraph({ repoId }: { repoId: string }) {
  const { data, isLoading, isError, error } = useArchitecture(repoId)
  // Which node's connections to spotlight -- null means "show the whole graph dimmed, nothing
  // highlighted." A dense repo (observed live: 87 nodes, 121 edges) renders every edge as an
  // unbundled straight line at once, which is unreadable regardless of column layout -- most
  // edges converge on a handful of shared-utility files (e.g. every UI component importing the
  // same lib/utils.ts), fanning out into visual noise. Dimming by default and lighting up only
  // the clicked node's own edges turns that into something actually explorable.
  const [activeNodeId, setActiveNodeId] = useState<string | null>(null)

  const positions = useMemo(
    () => layoutNodes(data?.nodes.map((n) => n.id) ?? []),
    [data],
  )

  const connectedNodeIds = useMemo(
    () => (data ? getConnectedNodeIds(activeNodeId, data.edges) : null),
    [activeNodeId, data],
  )

  const connections = useMemo(
    () => (data ? getConnections(activeNodeId, data.edges) : { imports: [], importedBy: [] }),
    [activeNodeId, data],
  )

  const { nodes, edges } = useMemo<{ nodes: Node[]; edges: Edge[] }>(() => {
    if (!data) return { nodes: [], edges: [] }

    const flowNodes: Node[] = data.nodes.map((n) => {
      const color = (n.language && LANGUAGE_COLORS[n.language]) || DEFAULT_COLOR
      const pos = positions.get(n.id) ?? { x: 0, y: 0 }
      const short = n.id.split('/').pop() ?? n.id
      const dimmed = connectedNodeIds !== null && !connectedNodeIds.has(n.id)
      return {
        id: n.id,
        position: pos,
        data: { label: short },
        title: n.id,
        style: {
          background: '#0f172a',
          border: `1.5px solid ${color}`,
          borderRadius: 8,
          color: '#e2e8f0',
          fontSize: 12,
          padding: '6px 10px',
          width: 200,
          opacity: dimmed ? 0.35 : 1,
        },
      }
    })

    const flowEdges: Edge[] = data.edges.map((e) => {
      const active = activeNodeId !== null && (e.source === activeNodeId || e.target === activeNodeId)
      const color = active ? EDGE_COLOR_ACTIVE : EDGE_COLOR_DIM
      return {
        id: `${e.source}->${e.target}`,
        source: e.source,
        target: e.target,
        animated: false,
        style: { stroke: color, strokeWidth: active ? 2 : 1, opacity: active ? 1 : 0.25 },
        markerEnd: active ? { type: MarkerType.ArrowClosed, color } : undefined,
        zIndex: active ? 1 : 0,
      }
    })

    return { nodes: flowNodes, edges: flowEdges }
  }, [data, positions, activeNodeId, connectedNodeIds])

  const handleNodeClick: NodeMouseHandler = (_event, node) => {
    setActiveNodeId((current) => (current === node.id ? null : node.id))
  }

  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-700 border-t-brand-500" />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 text-center">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-rose-500/10 text-2xl">
          ⚠️
        </div>
        <p className="text-sm font-medium text-slate-300">
          Couldn&apos;t generate the architecture graph
        </p>
        <p className="max-w-sm text-sm text-slate-500">{getErrorMessage(error)}</p>
      </div>
    )
  }

  if (!data || data.nodes.length === 0) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-slate-600">
        No indexed files to graph yet.
      </div>
    )
  }

  return (
    <ReactFlowProvider>
      <ArchitectureCanvas
        nodes={nodes}
        edges={edges}
        activeNodeId={activeNodeId}
        connectedNodeIds={connectedNodeIds}
        connections={connections}
        onNodeClick={handleNodeClick}
        onSelect={(id) => setActiveNodeId(id)}
        onClear={() => setActiveNodeId(null)}
      />
    </ReactFlowProvider>
  )
}
