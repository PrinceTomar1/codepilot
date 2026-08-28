export interface ImportantModule {
  path: string
  description: string
}

export interface OnboardingDoc {
  architectureOverview: string
  importantModules: ImportantModule[]
  setupInstructions: string
  dataFlow: string
  readFirst: string[]
}
