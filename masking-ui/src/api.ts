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

/** 대화 문맥(선택): 통화 ID·화자 컬럼, 상담사 화자 값 */
export interface DialogSpec {
  callColumn: string
  speakerColumn: string
  agentValue: string
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
  dialog: DialogSpec | null
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
  dialog: DialogSpec | null
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

/** 교정 라벨(원문 없음). stale: 저장 뒤 원천 값이 바뀜 */
export type LabelKind = 'NOT_PII' | 'MISSED'

export interface LabelView {
  id: number
  pk: string
  column: string
  start: number
  end: number
  label: LabelKind
  entityType: EntityCode
  detector: string | null
  createdBy: string
  createdAt: string
  stale: boolean
}

export interface LabelSummaryRow {
  connectionId: number
  sourceTable: string
  label: LabelKind
  entityType: EntityCode
  detector: string | null
  count: number
  /** 테이블을 지정해 요청했을 때만 */
  stale: number | null
}

export interface LabelAdd {
  connectionId: number
  sourceTable: string
  pkColumn: string
  pk: string
  column: string
  start: number
  end: number
  label: LabelKind
  entityType: EntityCode
  detector: string | null
  jobId: number | null
  addException: boolean
}

export type DictKind = 'EXCEPT' | 'FORCE'

export interface DictEntry {
  id: number
  kind: DictKind
  term: string
  createdBy: string
  createdAt: string
}

export interface DictionaryView {
  builtIn: { surnames: number; exceptions: number }
  entries: DictEntry[]
}

export type RoleCode = 'ADMIN' | 'CONFIGURER' | 'OPERATOR' | 'AUDITOR'

export const ROLE_LABEL: Record<RoleCode, string> = {
  ADMIN: '관리자',
  CONFIGURER: '설정자',
  OPERATOR: '실행자',
  AUDITOR: '감사자',
}

export interface UserView {
  id: number
  username: string
  displayName: string
  roles: RoleCode[]
  enabled: boolean
  authSource: string
  locked: boolean
  lastLoginAt: string | null
  createdAt: string
}

export interface UserRequest {
  username?: string
  displayName: string
  password?: string
  roles: RoleCode[]
  enabled: boolean
}

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

/** 세션이 끝났을 때(401) 화면이 로그인으로 돌아가도록 알린다 */
export const UNAUTHORIZED_EVENT = 'piimask:unauthorized'
/** 권한 없음(403): 역할이 바뀌었을 수 있으니 화면이 사용자 정보를 다시 읽는다 */
export const FORBIDDEN_EVENT = 'piimask:forbidden'

/** Spring Security 가 내려주는 CSRF 쿠키 값. 쓰기 요청 헤더로 돌려보낸다 */
function csrfToken(): string | null {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : null
}

async function call<T>(method: string, path: string, body?: unknown, form?: URLSearchParams): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (form) headers['Content-Type'] = 'application/x-www-form-urlencoded'
  const token = csrfToken()
  if (method !== 'GET' && token) headers['X-XSRF-TOKEN'] = token
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
  })
  const text = await res.text()
  // 로그인 시도·로그인 확인·초기 설정의 401 은 각 화면이 직접 처리한다
  if (res.status === 401 && !['/api/auth/login', '/api/auth/me', '/api/auth/setup'].includes(path)) {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
  if (res.status === 403) window.dispatchEvent(new Event(FORBIDDEN_EVENT))
  if (!res.ok) {
    let msg = `요청 실패 (${res.status})`
    try {
      const j = JSON.parse(text)
      if (j && j.error) msg = j.error
    } catch {
      /* 본문이 JSON 이 아님 */
    }
    throw new ApiError(msg, res.status)
  }
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  // 로그인·계정
  me: () => call<UserView>('GET', '/api/auth/me'),
  login: (username: string, password: string) =>
    call<void>('POST', '/api/auth/login', undefined, new URLSearchParams({ username, password })),
  logout: () => call<void>('POST', '/api/auth/logout'),
  setupStatus: () => call<{ setupRequired: boolean }>('GET', '/api/auth/setup-status'),
  setup: (token: string, username: string, password: string, displayName: string) =>
    call<void>('POST', '/api/auth/setup', { token, username, password, displayName }),
  changePassword: (currentPassword: string, newPassword: string) =>
    call<void>('POST', '/api/auth/password', { currentPassword, newPassword }),
  users: () => call<UserView[]>('GET', '/api/users'),
  createUser: (r: UserRequest) => call<UserView>('POST', '/api/users', r),
  updateUser: (id: number, r: UserRequest) => call<UserView>('PUT', `/api/users/${id}`, r),
  resetPassword: (id: number, password: string) => call<void>('POST', `/api/users/${id}/password`, { password }),

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

  addLabel: (r: LabelAdd) => call<LabelView>('POST', '/api/labels', r),
  queryLabels: (connectionId: number, sourceTable: string, pkColumn: string, pks: string[]) =>
    call<LabelView[]>('POST', '/api/labels/query', { connectionId, sourceTable, pkColumn, pks }),
  deleteLabel: (id: number) => call<void>('DELETE', '/api/labels/' + id),
  labelSummary: (connectionId?: number, sourceTable?: string) =>
    call<LabelSummaryRow[]>('GET', '/api/labels/summary' + (connectionId !== undefined && sourceTable
      ? `?connectionId=${connectionId}&sourceTable=${encodeURIComponent(sourceTable)}` : '')),

  dictionary: () => call<DictionaryView>('GET', '/api/dictionary'),
  addDictionary: (kind: DictKind, terms: string[]) =>
    call<{ added: number; skipped: number }>('POST', '/api/dictionary', { kind, terms }),
  deleteDictionary: (id: number) => call<void>('DELETE', `/api/dictionary/${id}`),

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
