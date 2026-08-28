export type SearchMatchType = 'exact' | 'similarity'

export interface SearchResult {
  filePath: string
  language: string | null
  startLine: number
  endLine: number
  snippet: string
  symbolName: string | null
  matchType: SearchMatchType
  relevanceScore: number | null
}
