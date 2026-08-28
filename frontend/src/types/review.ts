export interface ReviewSummary {
  id: string
  pullRequestId: string
  prNumber: number
  prTitle: string
  summary: string
  createdAt: string
}

export type FindingSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | string

export interface Finding {
  file: string
  line: number
  severity: FindingSeverity
  description: string
  suggestion: string
  originalCode?: string | null
  fixedCode?: string | null
}

export interface ReviewFindings {
  bugs: Finding[]
  security: Finding[]
  codeSmells: Finding[]
  missingTests: Finding[]
  performance: Finding[]
}

export interface ReviewDetail {
  id: string
  summary: string
  findings: ReviewFindings
}
