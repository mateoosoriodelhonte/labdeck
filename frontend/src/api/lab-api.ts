export interface TestPlan {
  service: string
  command: string[]
  timeoutSeconds: number
}

export type LabState = 'IMPORTED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'FAILED'
export type TestStatus =
  | 'RUNNING'
  | 'CANCELLING'
  | 'PERSISTING'
  | 'PASSED'
  | 'FAILED'
  | 'ERROR'
  | 'CANCELLED'
  | 'TIMED_OUT'
export type TestOutcomeReason =
  | 'EXIT_ZERO'
  | 'NON_ZERO_EXIT'
  | 'SERVICE_NOT_ACTIVE'
  | 'DOCKER_ERROR'
  | 'RESULT_UNAVAILABLE'
  | 'LAB_CHANGED'
  | 'USER_CANCELLED'
  | 'LAB_STOPPED'
  | 'APPLICATION_SHUTDOWN'
  | 'TIMEOUT'
  | 'LEGACY'

export interface LabDetail {
  id: string
  name: string
  state: LabState
  revision: number
  plan: null | {
    manifestSha256: string
    tests: TestPlan | null
  }
}

export interface TestRun {
  id: string
  labRevision: number
  service: string
  testPlanSha256: string
  startedAt?: string | null
  recordedAt?: string
  completedAt?: string | null
  status: TestStatus
  outcomeReason?: TestOutcomeReason | null
  durationMillis: number
  exitCode: number | null
  stdout: string
  stderr: string
  stdoutTruncated: boolean
  stderrTruncated: boolean
  canCancel: boolean
}

interface CsrfResponse {
  token: string
  headerName: string
}

interface TestHistoryResponse {
  runs: TestRun[]
  activeRun: TestRun | null
}

async function readJson<T>(response: Response): Promise<T> {
  if (response.ok) return (await response.json()) as T
  const problem = (await response.json().catch(() => null)) as null | { detail?: string }
  throw new Error(problem?.detail ?? `LabDeck returned HTTP ${response.status}.`)
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  return readJson<T>(response)
}

async function postJson<T>(path: string, body: object): Promise<T> {
  const csrf = await getJson<CsrfResponse>('/api/v1/csrf')
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token,
    },
    body: JSON.stringify(body),
  })
  return readJson<T>(response)
}

export function loadLab(labId: string) {
  return getJson<LabDetail>(`/api/v1/labs/${encodeURIComponent(labId)}`)
}

export async function loadTestHistory(labId: string) {
  return getJson<TestHistoryResponse>(`/api/v1/labs/${encodeURIComponent(labId)}/tests?limit=20`)
}

export function startAssignmentTest(lab: LabDetail) {
  if (!lab.plan) throw new Error('The current lab plan is not available.')
  return postJson<TestRun>(`/api/v1/labs/${encodeURIComponent(lab.id)}/tests`, {
    expectedRevision: lab.revision,
    expectedManifestSha256: lab.plan.manifestSha256,
  })
}

export function loadTestStatus(labId: string, runId: string) {
  return getJson<TestRun>(
    `/api/v1/labs/${encodeURIComponent(labId)}/tests/${encodeURIComponent(runId)}`,
  )
}

export function cancelAssignmentTest(labId: string, runId: string) {
  return postJson<TestRun>(
    `/api/v1/labs/${encodeURIComponent(labId)}/tests/${encodeURIComponent(runId)}/cancel`,
    {},
  )
}
