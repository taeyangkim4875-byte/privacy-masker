import { api, type Job } from '../api'
import { num, Status, usePoll } from '../components'
import type { Go, Notify } from '../App'

export default function Dashboard({ go, notify }: { go: Go; notify: Notify }) {
  const { data, error, reload } = usePoll(api.dashboard, 3000)

  const run = async (j: Job) => {
    try {
      const r = await api.runJob(j.id)
      notify({ kind: 'ok', text: `${j.name} 실행을 시작했습니다 (실행 #${r.executionId})` })
      reload()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    }
  }
  const stop = async (j: Job) => {
    try {
      await api.stopJob(j.id)
      notify({ kind: 'info', text: `${j.name} 중지를 요청했습니다. 진행 중인 청크까지 처리한 뒤 멈춥니다` })
      reload()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    }
  }

  if (error) return <div className="notice bad">서버에 연결할 수 없습니다: {error}</div>
  if (!data) return <p className="muted">불러오는 중…</p>

  const max = Math.max(1, ...data.daily.map((d) => d.readCount))
  const days = lastSevenDays().map((date) => data.daily.find((d) => d.date === date) ?? { date, readCount: 0, maskedRows: 0, spanCount: 0 })

  return (
    <>
      <div className="page-head">
        <div>
          <h1>현황</h1>
          <p>마스킹 작업의 상태와 최근 실행 결과입니다. 3초마다 갱신됩니다.</p>
        </div>
        <button className="btn primary" onClick={() => go('jobs', 'new')}>
          새 작업 만들기
        </button>
      </div>

      <div className="summary-line">
        <span><b>{data.jobCount}</b>개 작업</span>
        <span><b>{data.runningCount}</b>개 실행 중</span>
        <span><b>{data.scheduledCount}</b>개 예약됨</span>
        <span><b>{data.connectionCount}</b>개 DB 접속</span>
      </div>

      <div className="panel">
        <h2>작업</h2>
        {data.jobs.length === 0 ? (
          <div className="empty">
            아직 작업이 없습니다. DB 접속을 등록한 뒤 첫 작업을 만들어 보세요.
            <div className="btn-row" style={{ justifyContent: 'center', marginTop: 12 }}>
              <button className="btn" onClick={() => go('connections')}>DB 접속 등록</button>
              <button className="btn primary" onClick={() => go('jobs', 'new')}>새 작업 만들기</button>
            </div>
          </div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>작업</th>
                  <th>원천 → 대상</th>
                  <th>상태</th>
                  <th className="num">마지막 실행 읽음</th>
                  <th className="num">개인정보 포함 행</th>
                  <th>처리 위치(PK)</th>
                  <th>예약</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.jobs.map((j) => (
                  <tr key={j.id}>
                    <td>
                      <a href={`#/jobs/${j.id}`}>{j.name}</a>
                    </td>
                    <td className="mono">
                      {j.sourceTable} → {j.targetTable}
                    </td>
                    <td>
                      <Status value={j.running ? 'RUNNING' : j.lastExecution?.status ?? 'IDLE'} />
                    </td>
                    <td className="num">{num(j.lastExecution?.readCount)}</td>
                    <td className="num">{num(j.lastExecution?.maskedRows)}</td>
                    <td className="mono">{j.watermark ?? '처음부터'}</td>
                    <td className="small">{j.cron && j.enabled ? <span className="mono">{j.cron}</span> : <span className="muted">수동</span>}</td>
                    <td style={{ textAlign: 'right' }}>
                      {j.running ? (
                        <button className="btn small" onClick={() => stop(j)}>중지</button>
                      ) : (
                        <button className="btn small primary" onClick={() => run(j)}>실행</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="two" style={{ marginTop: 20 }}>
        <div className="panel">
          <h2>최근 실행</h2>
          {data.recentExecutions.length === 0 ? (
            <div className="empty">실행 이력이 없습니다.</div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>작업</th>
                    <th>상태</th>
                    <th className="num">읽음</th>
                    <th className="num">탐지</th>
                    <th>시작</th>
                  </tr>
                </thead>
                <tbody>
                  {data.recentExecutions.map((e) => (
                    <tr key={e.id} title={e.errorMessage ?? undefined}>
                      <td className="mono">{e.id}</td>
                      <td>{e.jobName}</td>
                      <td><Status value={e.status} /></td>
                      <td className="num">{num(e.readCount)}</td>
                      <td className="num">{num(e.spanCount)}</td>
                      <td className="small muted">{e.startedAt.slice(5, 16)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        <div className="panel">
          <h2>최근 7일 처리 건수</h2>
          <div className="bars" role="img" aria-label="최근 7일 일자별 처리 건수">
            {days.map((d) => (
              <div className="bar" key={d.date}>
                <span className="val">{d.readCount > 0 ? num(d.readCount) : ''}</span>
                <div className={`fill${d.readCount === 0 ? ' zero' : ''}`} style={{ height: `${(d.readCount / max) * 80}%` }} />
                <span>{d.date.slice(5).replace('-', '/')}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  )
}

function lastSevenDays() {
  const out: string[] = []
  const now = new Date()
  for (let i = 6; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i)
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`)
  }
  return out
}
