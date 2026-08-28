export interface ArchitectureNode {
  id: string
  language: string | null
}

export interface ArchitectureEdge {
  source: string
  target: string
}

export interface ArchitectureGraph {
  nodes: ArchitectureNode[]
  edges: ArchitectureEdge[]
}
