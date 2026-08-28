export interface Citation {
  filePath: string
  startLine: number
  endLine: number
  snippet: string
}

export interface AskQuestionRequest {
  question: string
}

export interface AskQuestionResponse {
  answer: string
  citations: Citation[]
  chunksRetrieved: number
}

export interface QAHistoryEntry {
  id: string
  question: string
  answer: string
  citations: Citation[]
  createdAt: string
}
