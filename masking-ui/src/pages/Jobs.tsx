import { useEffect, useMemo, useState } from 'react'
import {
  api,
  ENTITY_LABEL,
  ENTITY_ORDER,
  POLICY_LABEL,
  type ColumnInfo,
  type Connection,
  type DdlView,
  type EntityCode,
  type Job,
  type JobRequest,
  type PolicyCode,
  type PreviewResult,
  type TableInfo,
} from '../api'
import { EntityChips, Legend, MaskedText, num, SourceText, Status } from '../components'
import type { Go, Notify } from '../App'

export default function Jobs({ go, notify, arg }: { go: Go; notify: Notify; arg: string | null }) {
  const [jobs, setJobs] = useState<Job[] | null>(null)

  const load = () => api.jobs().then(setJobs).catch((e: Error) => notify({ kind: 'bad', text: e.message }))
  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [arg])

  if (arg === 'new') return <JobEditor key="new" job={null} go={go} notify={notify} onSaved={load} />
  if (arg && jobs) {
    const j = jobs.find((x) => String(x.id) === arg)
    if (j) return <JobEditor key={j.id} job={j} go={go} notify={notify} onSaved={load} />
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>마스킹 작업</h1>
          <p>원천 테이블의 어떤 컬럼을 어떻게 가려서 어느 테이블에 쌓을지 정한 설정입니다.</p>
        </div>
        <button className="btn primary" onClick={() => go('jobs', 'new')}>새 작업 만들기</button>
      </div>
      <div className="panel">
        {!jobs ? (
          <p className="muted">불러오는 중…</p>
        ) : jobs.length === 0 ? (
          <div className="empty">작업이 없습니다. 새 작업을 만들어 원천 테이블과 마스킹 규칙을 정하세요.</div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>작업</th>
                  <th>DB 접속</th>
                  <th>원천 → 대상</th>
                  <th>마스킹 컬럼</th>
                  <th>예약</th>
                  <th>상태</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((j) => (
                  <tr key={j.id} className="clickable" onClick={() => go('jobs', j.id)}>
                    <td style={{ fontWeight: 500 }}>{j.name}</td>
                    <td>{j.connectionName}</td>
                    <td className="mono">{j.sourceTable} → {j.targetTable}</td>
                    <td className="mono small">{j.columns.filter((c) => c.mode === 'MASK').map((c) => c.column).join(', ')}</td>
                    <td className="small">{j.cron && j.enabled ? <span className="mono">{j.cron}</span> : <span className="muted">수동</span>}</td>
                    <td><Status value={j.running ? 'RUNNING' : j.lastExecution?.status ?? 'IDLE'} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}

// ------------------------------------------------------------------

type ColMode = 'MASK' | 'COPY' | 'SKIP'

const CRON_PRESETS: { label: string; value: string }[] = [
  { label: '수동 실행만', value: '' },
  { label: '매시 정각', value: '0 0 * * * *' },
  { label: '매일 새벽 2시', value: '0 0 2 * * *' },
  { label: '10분마다', value: '0 */10 * * * *' },
]

function JobEditor({ job, go, notify, onSaved }: { job: Job | null; go: Go; notify: Notify; onSaved: () => void }) {
  const [connections, setConnections] = useState<Connection[]>([])
  const [tables, setTables] = useState<TableInfo[]>([])
  const [columns, setColumns] = useState<ColumnInfo[]>([])

  const [name, setName] = useState(job?.name ?? '')
  const [connectionId, setConnectionId] = useState<number | null>(job?.connectionId ?? null)
  const [sourceTable, setSourceTable] = useState(job?.sourceTable ?? '')
  const [pkColumn, setPkColumn] = useState(job?.pkColumn ?? '')
  const [targetTable, setTargetTable] = useState(job?.targetTable ?? '')
  const [modes, setModes] = useState<Record<string, ColMode>>({})
  const [entities, setEntities] = useState<Record<string, EntityCode[]>>({})
  const [policies, setPolicies] = useState<Record<string, PolicyCode>>(
    job?.policies ?? Object.fromEntries(ENTITY_ORDER.map((e) => [e, 'PARTIAL'])),
  )
  const [chunkSize, setChunkSize] = useState(job?.chunkSize ?? 500)
  const [cron, setCron] = useState(job?.cron ?? '')
  const [enabled, setEnabled] = useState(job?.enabled ?? true)
  const [createTarget, setCreateTarget] = useState(true)

  const [preview, setPreview] = useState<PreviewResult | null>(null)
  const [ddl, setDdl] = useState<DdlView | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    api.connections().then((cs) => {
      setConnections(cs)
      if (!job && cs.length > 0) setConnectionId((cur) => cur ?? cs[0].id)
    })
  }, [job])

  useEffect(() => {
    if (connectionId === null) return
    api.tables(connectionId).then(setTables).catch((e: Error) => setErr(e.message))
  }, [connectionId])

  useEffect(() => {
    if (connectionId === null || !sourceTable) {
      setColumns([])
      return
    }
    api
      .columns(connectionId, sourceTable)
      .then((cols) => {
        setColumns(cols)
        const pk = cols.find((c) => c.primaryKey)
        const saved = job && job.sourceTable === sourceTable ? job.columns : null
        if (!saved && pk) setPkColumn(pk.name)
        const m: Record<string, ColMode> = {}
        const en: Record<string, EntityCode[]> = {}
        cols.forEach((c) => {
          if (c.primaryKey) return
          const s = saved?.find((x) => x.column === c.name)
          m[c.name] = s ? s.mode : saved ? 'SKIP' : c.textLike && ['text', 'mediumtext', 'longtext', 'varchar'].includes(c.dataType) && /text|utter|content|script|memo|body|request|answer|stt|tts/i.test(c.name) ? 'MASK' : 'COPY'
          en[c.name] = s && s.entities.length > 0 ? s.entities : [...ENTITY_ORDER]
        })
        setModes(m)
        setEntities(en)
      })
      .catch((e: Error) => setErr(e.message))
  }, [connectionId, sourceTable, job])

  const request: JobRequest = useMemo(
    () => ({
      name: name || `${sourceTable} 마스킹`,
      connectionId,
      sourceTable,
      pkColumn,
      targetTable,
      chunkSize,
      cron,
      enabled,
      createTarget,
      policies,
      columns: columns
        .filter((c) => !c.primaryKey && modes[c.name] && modes[c.name] !== 'SKIP')
        .map((c) => ({ column: c.name, mode: modes[c.name] as 'MASK' | 'COPY', entities: modes[c.name] === 'MASK' ? entities[c.name] ?? [] : [] })),
    }),
    [name, connectionId, sourceTable, pkColumn, targetTable, chunkSize, cron, enabled, createTarget, policies, columns, modes, entities],
  )

  const maskCount = request.columns.filter((c) => c.mode === 'MASK').length
  const step1 = !!(connectionId && sourceTable && pkColumn && targetTable)
  const step2 = step1 && maskCount > 0

  const runPreview = async () => {
    setBusy(true)
    setErr(null)
    try {
      const [p, d] = await Promise.all([api.previewDraft(request, 20), api.ddl(request)])
      setPreview(p)
      setDdl(d)
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const save = async () => {
    setBusy(true)
    setErr(null)
    try {
      const saved = job ? await api.updateJob(job.id, request) : await api.createJob(request)
      notify({ kind: 'ok', text: `${saved.name} 작업을 저장했습니다` })
      onSaved()
      go('jobs', saved.id)
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    if (!job || !confirm(`${job.name} 작업을 삭제할까요? 대상 테이블과 이미 마스킹된 데이터는 그대로 남습니다.`)) return
    try {
      await api.deleteJob(job.id)
      notify({ kind: 'ok', text: `${job.name} 작업을 삭제했습니다` })
      onSaved()
      go('jobs')
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    }
  }

  const runNow = async () => {
    if (!job) return
    try {
      const r = await api.runJob(job.id)
      notify({ kind: 'ok', text: `실행을 시작했습니다 (실행 #${r.executionId}). 현황에서 진행 상황을 볼 수 있습니다` })
      go('dashboard')
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    }
  }

  const resetWm = async () => {
    if (!job || !confirm('처리 위치를 초기화하면 다음 실행 때 처음부터 다시 마스킹합니다. 이미 있는 행은 덮어씁니다. 계속할까요?')) return
    try {
      await api.resetWatermark(job.id)
      notify({ kind: 'ok', text: '처리 위치를 초기화했습니다' })
      onSaved()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    }
  }

  if (connections.length === 0) {
    return (
      <>
        <div className="page-head"><div><h1>새 작업</h1></div></div>
        <div className="panel empty">
          작업을 만들려면 먼저 DB 접속을 등록해야 합니다.
          <div className="btn-row" style={{ justifyContent: 'center', marginTop: 12 }}>
            <button className="btn primary" onClick={() => go('connections')}>DB 접속 등록</button>
          </div>
        </div>
      </>
    )
  }

  const stepClass = (done: boolean, current: boolean) => `step${done ? ' done' : current ? ' current' : ''}`

  return (
    <>
      <div className="page-head">
        <div>
          <h1>{job ? job.name : '새 작업'}</h1>
          <p>
            {job
              ? `처리 위치 PK ${job.watermark ?? '없음(처음부터)'} · 마지막 실행 ${job.lastExecution ? `${job.lastExecution.startedAt} ${num(job.lastExecution.readCount)}건` : '없음'}`
              : '원천 테이블을 고르고, 가릴 컬럼과 방식을 정한 뒤 미리보기로 확인하고 저장합니다.'}
          </p>
        </div>
        <div className="btn-row">
          <button className="btn" onClick={() => go('jobs')}>목록</button>
          {job && <button className="btn" onClick={resetWm}>처리 위치 초기화</button>}
          {job && <button className="btn danger" onClick={remove}>삭제</button>}
          {job && <button className="btn primary" onClick={runNow} disabled={job.running}>{job.running ? '실행 중' : '지금 실행'}</button>}
        </div>
      </div>

      <div className="steps">
        {/* 1. 원천 */}
        <section className={stepClass(step1, !step1)}>
          <div className="step-no">1</div>
          <div className="panel">
            <h2>원천 테이블</h2>
            <div className="row">
              <div className="field">
                <label htmlFor="j-name">작업 이름</label>
                <input id="j-name" className="input" value={name} placeholder={sourceTable ? `${sourceTable} 마스킹` : ''} onChange={(e) => setName(e.target.value)} />
              </div>
              <div className="field">
                <label htmlFor="j-conn">DB 접속</label>
                <select id="j-conn" className="input" value={connectionId ?? ''} onChange={(e) => { setConnectionId(Number(e.target.value)); setSourceTable(''); setPreview(null) }}>
                  {connections.map((c) => (
                    <option key={c.id} value={c.id}>{c.name} ({c.host}/{c.database})</option>
                  ))}
                </select>
              </div>
            </div>
            <div className="row-3">
              <div className="field">
                <label htmlFor="j-src">원천 테이블</label>
                <select
                  id="j-src"
                  className="input mono"
                  value={sourceTable}
                  onChange={(e) => {
                    setSourceTable(e.target.value)
                    setTargetTable(e.target.value ? `${e.target.value}_masked` : '')
                    setPreview(null)
                  }}
                >
                  <option value="">선택하세요</option>
                  {tables.filter((t) => !t.name.endsWith('_masked')).map((t) => (
                    <option key={t.name} value={t.name}>{t.name} (약 {num(t.approxRows)}행)</option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="j-pk">PK 컬럼</label>
                <input id="j-pk" className="input mono" value={pkColumn} readOnly />
                <span className="hint">기본키를 자동으로 씁니다</span>
              </div>
              <div className="field">
                <label htmlFor="j-tgt">대상 테이블</label>
                <input id="j-tgt" className="input mono" value={targetTable} onChange={(e) => { setTargetTable(e.target.value); setPreview(null) }} />
              </div>
            </div>
            <p className="small muted" style={{ margin: 0 }}>원천 테이블은 읽기만 하고, 결과는 대상 테이블에만 씁니다.</p>
          </div>
        </section>

        {/* 2. 컬럼 */}
        <section className={stepClass(step2, step1 && !step2)}>
          <div className="step-no">2</div>
          <div className="panel">
            <h2>컬럼별 처리</h2>
            {columns.length === 0 ? (
              <p className="muted">원천 테이블을 고르면 컬럼이 나옵니다.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>컬럼</th>
                      <th>타입</th>
                      <th>처리</th>
                      <th>찾을 개인정보</th>
                    </tr>
                  </thead>
                  <tbody>
                    {columns.map((c) => (
                      <tr key={c.name}>
                        <td className="mono">
                          {c.name}
                          {c.comment && <div className="col-comment">{c.comment}</div>}
                        </td>
                        <td className="mono small muted">{c.columnType}</td>
                        <td>
                          {c.primaryKey ? (
                            <span className="small muted">기본키 · 자동 복사</span>
                          ) : (
                            <div className="seg" role="group" aria-label={`${c.name} 처리 방식`}>
                              {(['MASK', 'COPY', 'SKIP'] as ColMode[]).map((m) => (
                                <button
                                  key={m}
                                  type="button"
                                  aria-pressed={modes[c.name] === m}
                                  onClick={() => { setModes((x) => ({ ...x, [c.name]: m })); setPreview(null) }}
                                >
                                  {m === 'MASK' ? '마스킹' : m === 'COPY' ? '복사' : '제외'}
                                </button>
                              ))}
                            </div>
                          )}
                        </td>
                        <td>
                          {modes[c.name] === 'MASK' && (
                            <EntityChips
                              compact
                              value={entities[c.name] ?? []}
                              onChange={(v) => { setEntities((x) => ({ ...x, [c.name]: v })); setPreview(null) }}
                            />
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </section>

        {/* 3. 정책 */}
        <section className={stepClass(step2, false)}>
          <div className="step-no">3</div>
          <div className="panel">
            <h2>가리는 방식과 실행 주기</h2>
            <div className="policy-grid" style={{ marginBottom: 20 }}>
              {ENTITY_ORDER.map((e) => (
                <div className="policy-card" key={e}>
                  <div className="head">
                    <span className={`dot dot-${e}`} />
                    {ENTITY_LABEL[e]}
                  </div>
                  <select className="input" value={policies[e]} onChange={(ev) => { setPolicies((p) => ({ ...p, [e]: ev.target.value as PolicyCode })); setPreview(null) }}>
                    {(Object.keys(POLICY_LABEL) as PolicyCode[]).map((p) => (
                      <option key={p} value={p}>{POLICY_LABEL[p]}</option>
                    ))}
                  </select>
                  <div className="eg mono">{POLICY_EG[e][policies[e]]}</div>
                </div>
              ))}
            </div>
            <div className="row-3">
              <div className="field">
                <label htmlFor="j-cron">실행 주기</label>
                <select id="j-cron" className="input" value={CRON_PRESETS.some((p) => p.value === cron) ? cron : '__custom'} onChange={(e) => e.target.value !== '__custom' && setCron(e.target.value)}>
                  {CRON_PRESETS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
                  <option value="__custom">직접 입력</option>
                </select>
                <input className="input mono" style={{ marginTop: 6 }} placeholder="초 분 시 일 월 요일" value={cron} onChange={(e) => setCron(e.target.value)} />
                <span className="hint">Spring cron 6자리. 비우면 수동 실행만 합니다.</span>
              </div>
              <div className="field">
                <label htmlFor="j-chunk">청크 크기</label>
                <input id="j-chunk" className="input mono" type="number" min={10} max={10000} value={chunkSize} onChange={(e) => setChunkSize(Number(e.target.value))} />
                <span className="hint">한 번에 읽고 커밋하는 행 수</span>
              </div>
              <div className="field">
                <label>예약 실행</label>
                <label className="small" style={{ display: 'flex', gap: 8, alignItems: 'center', fontWeight: 400 }}>
                  <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
                  주기에 맞춰 자동 실행
                </label>
              </div>
            </div>
          </div>
        </section>

        {/* 4. 미리보기·저장 */}
        <section className={stepClass(!!preview, step2 && !preview)}>
          <div className="step-no">4</div>
          <div className="panel">
            <div className="page-head" style={{ marginBottom: 12 }}>
              <div>
                <h2 style={{ margin: 0 }}>미리보기와 저장</h2>
                <p className="small">원천 앞부분 20건에 지금 설정을 적용해 봅니다. 대상 테이블에는 쓰지 않습니다.</p>
              </div>
              <div className="btn-row">
                <button className="btn" onClick={runPreview} disabled={!step2 || busy}>미리보기</button>
                <button className="btn primary" onClick={save} disabled={!step2 || busy}>{job ? '변경 저장' : '작업 저장'}</button>
              </div>
            </div>

            {err && <div className="notice bad" style={{ marginBottom: 12 }}>{err}</div>}

            {ddl && (
              <div style={{ marginBottom: 16 }}>
                {ddl.exists ? (
                  ddl.missingColumns.length > 0 ? (
                    <div className="notice bad">대상 테이블 {targetTable}이 이미 있는데 필요한 컬럼이 없습니다: {ddl.missingColumns.join(', ')}. 다른 이름을 쓰거나 테이블을 정리하세요.</div>
                  ) : (
                    <div className="notice info">대상 테이블 {targetTable}이 이미 있습니다. 같은 PK 행은 덮어씁니다.</div>
                  )
                ) : (
                  <details>
                    <summary className="small" style={{ cursor: 'pointer', marginBottom: 8 }}>
                      <label onClick={(e) => e.stopPropagation()} style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                        <input type="checkbox" checked={createTarget} onChange={(e) => setCreateTarget(e.target.checked)} />
                        저장할 때 대상 테이블 {targetTable} 만들기
                      </label>
                      <span className="muted"> · DDL 보기</span>
                    </summary>
                    <pre className="code">{ddl.ddl}</pre>
                  </details>
                )}
              </div>
            )}

            {preview && (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
                  <Legend counts={preview.countsByType} />
                  <span className="small muted">{preview.rows.length}건 · {preview.elapsedMs}ms</span>
                </div>
                <div className="transcript">
                  <div className="transcript-head">
                    <span>PK</span>
                    <span>원문 · 탐지 위치</span>
                    <span>저장될 값</span>
                  </div>
                  {preview.rows.flatMap((r) =>
                    r.cells.map((cell) => (
                      <div className="utter" key={`${r.pk}-${cell.column}`}>
                        <div className="utter-head">
                          <span className="mono">{r.pk}</span>
                          {r.cells.length > 1 && <div>{cell.column}</div>}
                        </div>
                        <div className="utter-text"><SourceText text={cell.original} spans={cell.spans} /></div>
                        <div className="utter-text after"><MaskedText original={cell.original} masked={cell.masked} spans={cell.spans} /></div>
                      </div>
                    )),
                  )}
                </div>
              </>
            )}
          </div>
        </section>
      </div>
    </>
  )
}

const POLICY_EG: Record<EntityCode, Record<PolicyCode, string>> = {
  NAME: { TAG: '[이름]', FULL: '***', PARTIAL: '김*양', HASH: '[NAME:9c1e…]' },
  PHONE: { TAG: '[전화번호]', FULL: '***-****-****', PARTIAL: '010-****-5678', HASH: '[PHONE:3fa9…]' },
  CARD: { TAG: '[카드번호]', FULL: '****-****-****-****', PARTIAL: '4111-11**-****-1111', HASH: '[CARD:7b20…]' },
  RRN: { TAG: '[주민등록번호]', FULL: '******-*******', PARTIAL: '900101-1******', HASH: '[RRN:e44d…]' },
  ACCOUNT: { TAG: '[계좌번호]', FULL: '***-***-******', PARTIAL: '***-***-**6789', HASH: '[ACCOUNT:51ac…]' },
  EMAIL: { TAG: '[이메일]', FULL: '******@*******.***', PARTIAL: 'ki****@example.com', HASH: '[EMAIL:0d3f…]' },
}
