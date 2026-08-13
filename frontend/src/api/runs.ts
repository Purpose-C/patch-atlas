export type RunState = 'QUEUED' | 'GENERATING' | 'REPLAYING' | 'COMPLETED' | 'FAILED'

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly problem?: ProblemDetail

  constructor(status: number, message: string, problem?: ProblemDetail) {
    super(message)
    this.status = status
    this.problem = problem
  }
}

export interface RunListItem {
  runId: string
  mode: string
  state: RunState
  issueTitle: string
  repositoryUrl: string
  verdict: string | null
  failureCategory: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export interface RunListResponse {
  items: RunListItem[]
  nextCursor: string | null
}

export type RecordedUsageStatus =
  | 'TRACKING_UNAVAILABLE'
  | 'NONE_RECORDED'
  | 'PARTIALLY_RECORDED'
  | 'RECORDED_FOR_ALL_ATTEMPTS'

export interface EstimatedCost {
  amount: string
  currency: string
  pricingEffectiveDate: string
  pricingSource: string
}

export interface RunDetail {
  runId: string
  mode: string
  state: RunState
  caseId: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
  input: {
    repositoryUrl: string
    issueUrl: string | null
    issueTitle: string
    issueBody: string
    buggyRevision: string
    fixedRevision: string | null
    modulePath: string
  }
  executionPolicy: { javaVersion: string; networkMode: string }
  generation: {
    attemptCount: number
    modelProvider: string | null
    modelName: string | null
    inputTokens: number
    outputTokens: number
    totalTokens: number
    usageRecordCount: number | null
    usageStatus: RecordedUsageStatus
    estimatedCost: EstimatedCost | null
  }
  candidate: {
    patchText: string
    patchSha256: string
    targetClass: string
    targetMethod: string
  } | null
  result: {
    verdict: string | null
    failureStage: string | null
    failureCategory: string | null
    failureSummary: string | null
  } | null
  attempts: Array<{
    replayRound: number
    side: string
    attemptOrdinal: number
    phase: string
    outcome: string | null
    targetEvidence: string
    diagnostic: string | null
    sandboxStatus: string | null
    exitCode: number | null
    elapsedMs: number | null
    timedOut: boolean | null
    commandJson: string | null
    image: string | null
    limitsJson: string | null
    networkMode: string | null
    logSummary: string | null
    targetTestCase: {
      className: string
      methodName: string
      status: string
      message: string | null
      elapsedMs: number | null
      exceptionType: string | null
    } | null
    evidenceSchemaVersion: number
  }>
}

const TERMINAL: RunState[] = ['COMPLETED', 'FAILED']

export function isTerminalState(state: string): boolean {
  return TERMINAL.includes(state as RunState)
}

async function parseError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined
  try {
    problem = (await response.json()) as ProblemDetail
  } catch {
    // ignore
  }
  return new ApiError(response.status, problem?.detail ?? `HTTP ${response.status}`, problem)
}

export async function fetchRuns(
  limit = 20,
  cursor?: string | null,
  signal?: AbortSignal,
): Promise<RunListResponse> {
  const params = new URLSearchParams({ limit: String(limit) })
  if (cursor) params.set('cursor', cursor)
  const response = await fetch(`/api/runs?${params}`, { signal })
  if (!response.ok) throw await parseError(response)
  return response.json() as Promise<RunListResponse>
}

export async function fetchRun(runId: string, signal?: AbortSignal): Promise<RunDetail> {
  const response = await fetch(`/api/runs/${encodeURIComponent(runId)}`, { signal })
  if (!response.ok) throw await parseError(response)
  return response.json() as Promise<RunDetail>
}
