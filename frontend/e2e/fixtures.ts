import type { Locating, RunDetail, RunListItem } from '../src/api/runs'

export function listItem(id: string, overrides: Partial<RunListItem> = {}): RunListItem {
  return {
    runId: id,
    mode: 'HISTORICAL',
    state: 'FAILED',
    issueTitle: `issue-${id.slice(0, 8)}`,
    repositoryUrl: 'https://github.com/ex/repo.git',
    verdict: null,
    failureCategory: 'GENERATION_EXHAUSTED',
    createdAt: '2026-08-12T10:00:00Z',
    updatedAt: '2026-08-12T10:01:00Z',
    completedAt: '2026-08-12T10:01:00Z',
    ...overrides,
  }
}

export function emptyLocating(): Locating {
  return {
    contextOrigin: null,
    recorded: false,
    toolCallsApplicable: false,
    toolCallCount: null,
    stepKindCounts: {},
    errorCount: 0,
    usageStatus: 'NONE_RECORDED',
    inputTokens: null,
    outputTokens: null,
    totalTokens: null,
    budgetEvents: [],
    graphBuild: null,
    steps: [],
    selectedPaths: [],
    truncated: false,
    stepLimit: 200,
  }
}

export function sampleDetail(overrides: Partial<RunDetail> = {}): RunDetail {
  return {
    runId: '11111111-1111-1111-1111-111111111111',
    mode: 'HISTORICAL',
    runPurpose: 'STANDARD',
    state: 'FAILED',
    caseId: null,
    createdAt: '2026-08-12T10:00:00Z',
    updatedAt: '2026-08-12T10:05:00Z',
    completedAt: '2026-08-12T10:05:00Z',
    locating: emptyLocating(),
    generatorSourcePaths: [],
    input: {
      repositoryUrl: 'https://github.com/ex/repo.git',
      issueUrl: 'https://github.com/ex/repo/issues/1',
      issueTitle: 'Make persist explicit',
      issueBody: 'Accountancy add() updates existing entries.',
      buggyRevision: 'a'.repeat(40),
      fixedRevision: 'b'.repeat(40),
      modulePath: '',
    },
    executionPolicy: { javaVersion: '17', networkMode: 'ONLINE' },
    generation: {
      attemptCount: 3,
      modelProvider: null,
      modelName: null,
      inputTokens: 0,
      outputTokens: 0,
      totalTokens: 0,
      usageRecordCount: null,
      usageStatus: 'NONE_RECORDED',
      estimatedCost: null,
    },
    candidate: null,
    result: {
      verdict: null,
      failureStage: 'GENERATION',
      failureCategory: 'GENERATION_EXHAUSTED',
      failureSummary: 'generation attempts exhausted',
    },
    attempts: [],
    ...overrides,
  }
}
