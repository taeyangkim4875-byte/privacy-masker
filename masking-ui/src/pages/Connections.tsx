import { useEffect, useState } from 'react'
import { api, type Connection, type ConnectionForm, type TestResult } from '../api'
import type { Notify } from '../App'

const EMPTY: ConnectionForm = {
  name: '로컬 MariaDB',
  host: '127.0.0.1',
  port: 13306,
  database: 'stt',
  username: 'masker',
  password: '',
  options: '',
}

export default function Connections({ notify }: { notify: Notify }) {
  const [list, setList] = useState<Connection[]>([])
  const [editing, setEditing] = useState<number | null>(null)
  const [form, setForm] = useState<ConnectionForm>(EMPTY)
  const [test, setTest] = useState<TestResult | null>(null)
  const [busy, setBusy] = useState(false)

  const load = () => api.connections().then(setList).catch((e: Error) => notify({ kind: 'bad', text: e.message }))
  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const set = <K extends keyof ConnectionForm>(k: K, v: ConnectionForm[K]) => {
    setForm((f) => ({ ...f, [k]: v }))
    setTest(null)
  }

  const startNew = () => {
    setEditing(null)
    setForm(EMPTY)
    setTest(null)
  }

  const startEdit = (c: Connection) => {
    setEditing(c.id)
    setForm({ name: c.name, host: c.host, port: c.port, database: c.database, username: c.username, password: '', options: c.options ?? '' })
    setTest(null)
  }

  const runTest = async () => {
    setBusy(true)
    try {
      setTest(await api.testConnection(form, editing ?? undefined))
    } catch (e) {
      setTest({ ok: false, message: (e as Error).message, serverVersion: null, elapsedMs: 0 })
    } finally {
      setBusy(false)
    }
  }

  const save = async () => {
    setBusy(true)
    try {
      if (editing) {
        await api.updateConnection(editing, form)
        notify({ kind: 'ok', text: `${form.name} 접속 정보를 저장했습니다` })
      } else {
        const c = await api.createConnection(form)
        setEditing(c.id)
        notify({ kind: 'ok', text: `${form.name} 접속을 등록했습니다` })
      }
      setForm((f) => ({ ...f, password: '' }))
      load()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    } finally {
      setBusy(false)
    }
  }

  const remove = async (c: Connection) => {
    if (!confirm(`${c.name} 접속 정보를 삭제할까요?`)) return
    try {
      await api.deleteConnection(c.id)
      notify({ kind: 'ok', text: `${c.name} 접속 정보를 삭제했습니다` })
      if (editing === c.id) startNew()
      load()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>DB 접속</h1>
          <p>마스킹할 이력이 있는 MariaDB 접속 정보입니다. 비밀번호는 암호화해서 저장합니다.</p>
        </div>
        <button className="btn" onClick={startNew}>새 접속</button>
      </div>

      <div className="split">
        <div className="panel">
          {list.length === 0 ? (
            <div className="empty">등록된 접속이 없습니다. 오른쪽 양식으로 첫 접속을 등록하세요.</div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>이름</th>
                    <th>주소</th>
                    <th>데이터베이스</th>
                    <th>사용자</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {list.map((c) => (
                    <tr key={c.id} className="clickable" onClick={() => startEdit(c)} aria-selected={editing === c.id}>
                      <td style={{ fontWeight: editing === c.id ? 600 : 400 }}>{c.name}</td>
                      <td className="mono">{c.host}:{c.port}</td>
                      <td className="mono">{c.database}</td>
                      <td className="mono">{c.username}</td>
                      <td style={{ textAlign: 'right' }}>
                        <button
                          className="btn small danger"
                          onClick={(ev) => {
                            ev.stopPropagation()
                            remove(c)
                          }}
                        >
                          삭제
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <form
          className="panel"
          onSubmit={(e) => {
            e.preventDefault()
            save()
          }}
        >
          <h2>{editing ? '접속 정보 수정' : '새 접속 등록'}</h2>
          <div className="field">
            <label htmlFor="c-name">이름</label>
            <input id="c-name" className="input" value={form.name} onChange={(e) => set('name', e.target.value)} />
          </div>
          <div className="row-3">
            <div className="field">
              <label htmlFor="c-host">호스트</label>
              <input id="c-host" className="input mono" value={form.host} onChange={(e) => set('host', e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="c-port">포트</label>
              <input id="c-port" className="input mono" type="number" value={form.port} onChange={(e) => set('port', Number(e.target.value))} />
            </div>
            <div className="field">
              <label htmlFor="c-db">DB</label>
              <input id="c-db" className="input mono" value={form.database} onChange={(e) => set('database', e.target.value)} />
            </div>
          </div>
          <div className="row">
            <div className="field">
              <label htmlFor="c-user">사용자</label>
              <input id="c-user" className="input mono" value={form.username} onChange={(e) => set('username', e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="c-pw">비밀번호</label>
              <input
                id="c-pw"
                className="input"
                type="password"
                autoComplete="new-password"
                placeholder={editing ? '바꿀 때만 입력' : ''}
                value={form.password}
                onChange={(e) => set('password', e.target.value)}
              />
            </div>
          </div>
          <div className="field">
            <label htmlFor="c-opt">JDBC 옵션</label>
            <input
              id="c-opt"
              className="input mono"
              placeholder="예: connectTimeout=5000&useSsl=true"
              value={form.options}
              onChange={(e) => set('options', e.target.value)}
            />
            <span className="hint">비워 두면 기본값으로 접속합니다.</span>
          </div>

          {test && (
            <div className={`notice ${test.ok ? 'ok' : 'bad'}`} style={{ marginBottom: 14 }}>
              {test.ok ? `접속 성공 · MariaDB ${test.serverVersion} · ${test.elapsedMs}ms` : test.message}
            </div>
          )}

          <div className="btn-row">
            <button type="button" className="btn" onClick={runTest} disabled={busy}>
              접속 테스트
            </button>
            <button type="submit" className="btn primary" disabled={busy}>
              {editing ? '변경 저장' : '등록'}
            </button>
          </div>
        </form>
      </div>
    </>
  )
}
