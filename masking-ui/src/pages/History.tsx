import { Fragment, useState } from 'react'
import { api } from '../api'
import { num, Status, usePoll } from '../components'
import { useHas } from '../auth'

export default function History() {
  // 실행 이력: 설정자·실행자·감사자 / 감사 로그: 감사자·관리자
  const canExec = useHas('CONFIGURER', 'OPERATOR', 'AUDITOR')
  const canAudit = useHas('AUDITOR', 'ADMIN')
  const [tab, setTab] = useState<'exec' | 'audit'>(canExec ? 'exec' : 'audit')
  const exec = usePoll(() => (canExec ? api.executions(100) : Promise.resolve([])), 5000)
  const audit = usePoll(() => (canAudit ? api.audit(100) : Promise.resolve([])), 10000)

  return (
    <>
      <div className="page-head">
        <div>
          <h1>실행·감사 이력</h1>
          <p>배치 실행 결과와 설정 변경 기록입니다. 데이터 값은 남기지 않고 건수와 위치만 기록합니다.</p>
        </div>
        <div className="seg" role="tablist">
          {canExec && <button role="tab" aria-pressed={tab === 'exec'} aria-selected={tab === 'exec'} onClick={() => setTab('exec')}>실행 이력</button>}
          {canAudit && <button role="tab" aria-pressed={tab === 'audit'} aria-selected={tab === 'audit'} onClick={() => setTab('audit')}>감사 로그</button>}
        </div>
      </div>

      <div className="panel">
        {tab === 'exec' ? (
          !exec.data ? (
            <p className="muted">불러오는 중…</p>
          ) : exec.data.length === 0 ? (
            <div className="empty">실행 이력이 없습니다.</div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>작업</th>
                    <th>방식</th>
                    <th>상태</th>
                    <th className="num">읽음</th>
                    <th className="num">개인정보 포함 행</th>
                    <th className="num">탐지 수</th>
                    <th>PK 구간</th>
                    <th>시작</th>
                    <th>종료</th>
                  </tr>
                </thead>
                <tbody>
                  {exec.data.map((e) => (
                    <Fragment key={e.id}>
                      <tr>
                        <td className="mono">{e.id}</td>
                        <td>{e.jobName}</td>
                        <td className="small">{e.trigger === 'CRON' ? '예약' : '수동'}</td>
                        <td><Status value={e.status} /></td>
                        <td className="num">{num(e.readCount)}</td>
                        <td className="num">{num(e.maskedRows)}</td>
                        <td className="num">{num(e.spanCount)}</td>
                        <td className="mono small">{e.startPk ?? '처음'} → {e.endPk ?? '-'}</td>
                        <td className="small">{e.startedAt}</td>
                        <td className="small">{e.endedAt ?? ''}</td>
                      </tr>
                      {e.errorMessage && (
                        <tr>
                          <td />
                          <td colSpan={9} className="small" style={{ color: e.status === 'FAILED' ? 'var(--bad)' : 'var(--warn)', paddingTop: 0 }}>
                            {e.errorMessage}
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )
        ) : !audit.data ? (
          <p className="muted">불러오는 중…</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>시각</th><th>사용자</th><th>동작</th><th>대상</th><th>결과</th></tr>
              </thead>
              <tbody>
                {audit.data.map((a) => (
                  <tr key={a.id}>
                    <td className="small">{a.createdAt}</td>
                    <td className="mono small">{a.actor}</td>
                    <td className="mono small">{a.action}</td>
                    <td className="small">{a.target}</td>
                    <td className="small">{a.outcome}</td>
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
