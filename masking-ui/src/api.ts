// 백엔드 API 타입과 호출 래퍼

export type EntityCode = 'NAME' | 'PHONE' | 'CARD' | 'RRN' | 'ACCOUNT' | 'EMAIL'
export type PolicyCode = 'TAG' | 'FULL' | 'PARTIAL' | 'HASH'

export interface Meta {
  entityTypes: { code: EntityCode; label: string; priority: number }[]
  policies: { code: PolicyCode; label: string }[]
  detectors: { name: string; type: EntityCode; kind: string; pattern: string }[]
  defaultPolicies: Record<EntityCode, PolicyCode>
}

export interface Connection {
  id: number
  name: string
  host: string
  port: number
  database: string
  username: string
  options: string | null
  hasPassword: boolean
  createdAt: string
  updatedAt: string
}

export interface ConnectionForm {
  name: string
  host: string
  port: number
  database: string
  username: string
  password: string
  options: string
}

export interface TestResult {
  ok: boolean
  message: string
  serverVersion: string | null
  elapsedMs: number
}

export interface TableInfo {
  name: string
  approxRows: number
  comment: string
}

export interface ColumnInfo {
  name: string
  dataType: string
  columnType: string
  nullable: boolean
  primaryKey: boolean
  textLike: boolean
  comment: string
}

export interface ColumnSpec {
  column: string
  mode: 'MASK' | 'COPY'
  entities: EntityCode[]
}

export interface Execution {
  id: number
  jobId: number
  jobName: string
  trigger: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'STOPPED'
  startedAt: string
  endedAt: string | null
  readCount: number
  maskedRows: number
  spanCount: number
  writtenCount: number
  startPk: string | null
  endPk: string | null
  errorMessage: string | null
}

export interface Job {
  id: number
  name: string
  connectionId: number
  connectionName: string
  sourceTable: string
  pkColumn: string
  targetTable: string
  chunkSize: number
  cron: string | null
  enabled: boolean
  columns: ColumnSpec[]
  policies: Record<EntityCode, PolicyCode>
  watermark: string | null
  running: boolean
  lastExecution: Execution | null
}

export interface JobRequest {
  name: string
  connectionId: number | null
  sourceTable: string
  pkColumn: string
  targetTable: string
  chunkSize: number
  cron: string
  enabled: boolean
  columns: ColumnSpec[]
  policies: Record<string, PolicyCode>
  createTarget: boolean
}

export interface SpanView {
  start: number
  end: number
  type: EntityCode
  label: string
  detector: string
}

export interface PreviewResult {
  rows: { pk: string; cells: { column: string; original: string | null; masked: string | null; spans: SpanView[] }[] }[]
  elapsedMs: number
  countsByType: Record<string, number>
}

export interface DdlView {
  ddl: string
  exists: boolean
  missingColumns: string[]
}

export interface Dashboard {
  connectionCount: number
  jobCount: number
  runningCount: number
  scheduledCount: number
  jobs: Job[]
  recentExecutions: Execution[]
  daily: { date: string; readCount: number; maskedRows: number; spanCount: number }[]
}

export interface AuditEntry {
  id: number
  actor: string
  action: string
  target: string
  outcome: string
  createdAt: string
}

export class ApiError extends Error {}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) {
    let msg = `요청 실패 (${res.status})`
    try {
      const j = JSON.parse(text)
      if (j && j.error) msg = j.error
    } catch {
      /* 본문이 JSON 이 아님 */
    }
    throw new ApiError(msg)
  }
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  meta: () => call<Meta>('GET', '/api/meta'),
  dashboard: () => call<Dashboard>('GET', '/api/dashboard'),
  executions: (limit = 100) => call<Execution[]>('GET', `/api/executions?limit=${limit}`),
  audit: (limit = 100) => call<AuditEntry[]>('GET', `/api/audit?limit=${limit}`),

  connections: () => call<Connection[]>('GET', '/api/connections'),
  createConnection: (f: ConnectionForm) => call<Connection>('POST', '/api/connections', f),
  updateConnection: (id: number, f: ConnectionForm) => call<Connection>('PUT', `/api/connections/${id}`, f),
  deleteConnection: (id: number) => call<void>('DELETE', `/api/connections/${id}`),
  testConnection: (f: ConnectionForm, existingId?: number) =>
    call<TestResult>('POST', `/api/connections/test${existingId ? `?existingId=${existingId}` : ''}`, f),
  tables: (id: number) => call<TableInfo[]>('GET', `/api/connections/${id}/tables`),
  columns: (id: number, table: string) =>
    call<ColumnInfo[]>('GET', `/api/connections/${id}/tables/${encodeURIComponent(table)}/columns`),

  jobs: () => call<Job[]>('GET', '/api/jobs'),
  createJob: (r: JobRequest) => call<Job>('POST', '/api/jobs', r),
  updateJob: (id: number, r: JobRequest) => call<Job>('PUT', `/api/jobs/${id}`, r),
  deleteJob: (id: number) => call<void>('DELETE', `/api/jobs/${id}`),
  previewDraft: (r: JobRequest, limit = 20) => call<PreviewResult>('POST', `/api/jobs/preview?limit=${limit}`, r),
  ddl: (r: JobRequest) => call<DdlView>('POST', '/api/jobs/ddl', r),
  runJob: (id: number) => call<{ executionId: number }>('POST', `/api/jobs/${id}/run`),
  stopJob: (id: number) => call<{ stopping: boolean }>('POST', `/api/jobs/${id}/stop`),
  resetWatermark: (id: number) => call<void>('POST', `/api/jobs/${id}/reset-watermark`),

  detect: (text: string, entities: EntityCode[], policies: Record<string, PolicyCode>) =>
    call<{ masked: string; spans: SpanView[]; elapsedMicros: number }>('POST', '/api/detect', { text, entities, policies }),
}

export const ENTITY_ORDER: EntityCode[] = ['NAME', 'PHONE', 'CARD', 'RRN', 'ACCOUNT', 'EMAIL']

export const ENTITY_LABEL: Record<EntityCode, string> = {
  NAME: '이름',
  PHONE: '전화번호',
  CARD: '카드번호',
  RRN: '주민등록번호',
  ACCOUNT: '계좌번호',
  EMAIL: '이메일',
}

export const POLICY_LABEL: Record<PolicyCode, string> = {
  TAG: '유형 태그',
  FULL: '전체 마스킹',
  PARTIAL: '부분 마스킹',
  HASH: '해시 토큰',
}

export const POLICY_EXAMPLE: Record<PolicyCode, string> = {
  TAG: '[전화번호]',
  FULL: '***-****-****',
  PARTIAL: '010-****-5678',
  HASH: '[PHONE:3fa9c1d2e4b5]',
}
