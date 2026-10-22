import { useEffect, useState } from 'react'
import { api, ROLE_LABEL, type RoleCode, type UserView } from '../api'
import type { Notify } from '../App'

const ROLES: RoleCode[] = ['CONFIGURER', 'OPERATOR', 'AUDITOR', 'ADMIN']

const ROLE_DESC: Record<RoleCode, string> = {
  CONFIGURER: 'DB 접속·작업 설정, 미리보기(원문이 보임)',
  OPERATOR: '작업 실행·중지',
  AUDITOR: '실행 이력·감사 로그 조회',
  ADMIN: '계정 관리·감사 로그 조회',
}

type Form = { username: string; displayName: string; password: string; roles: RoleCode[]; enabled: boolean }
const EMPTY: Form = { username: '', displayName: '', password: '', roles: ['OPERATOR'], enabled: true }

/** 사용자 관리(관리자). 삭제 대신 사용 중지 */
export default function Users({ notify }: { notify: Notify }) {
  const [users, setUsers] = useState<UserView[] | null>(null)
  const [editing, setEditing] = useState<UserView | null>(null)
  const [form, setForm] = useState<Form>(EMPTY)
  const [busy, setBusy] = useState(false)

  const load = () => api.users().then(setUsers).catch((e: Error) => notify({ kind: 'bad', text: e.message }))
  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const startNew = () => {
    setEditing(null)
    setForm(EMPTY)
  }
  const startEdit = (u: UserView) => {
    setEditing(u)
    setForm({ username: u.username, displayName: u.displayName, password: '', roles: u.roles, enabled: u.enabled })
  }
  const toggleRole = (r: RoleCode) =>
    setForm((f) => ({ ...f, roles: f.roles.includes(r) ? f.roles.filter((x) => x !== r) : [...f.roles, r] }))

  const save = async () => {
    setBusy(true)
    try {
      if (editing) {
        await api.updateUser(editing.id, { displayName: form.displayName, roles: form.roles, enabled: form.enabled })
        if (form.password) await api.resetPassword(editing.id, form.password)
        notify({ kind: 'ok', text: '저장했습니다' })
      } else {
        await api.createUser(form)
        notify({ kind: 'ok', text: `${form.username} 계정을 만들었습니다` })
        setForm(EMPTY)
      }
      load()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="page-head">
        <div>
          <h1>사용자 관리</h1>
          <p>계정과 역할을 관리합니다. 역할 변경과 사용 중지는 로그인 중인 사용자에게도 바로 적용됩니다.</p>
        </div>
        <button className="btn" onClick={startNew}>새 계정</button>
      </div>
      <div className="split">
        <div className="panel">
          {!users ? (
            <p className="muted">불러오는 중…</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>아이디</th>
                    <th>이름</th>
                    <th>역할</th>
                    <th>상태</th>
                    <th>마지막 로그인</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((u) => (
                    <tr key={u.id} className="clickable" onClick={() => startEdit(u)} aria-selected={editing?.id === u.id}>
                      <td className="mono">{u.username}</td>
                      <td>{u.displayName}</td>
                      <td className="small">{u.roles.map((r) => ROLE_LABEL[r]).join(', ')}</td>
                      <td className="small">{!u.enabled ? '사용 중지' : u.locked ? '잠김' : '사용'}</td>
                      <td className="small muted">{u.lastLoginAt ?? '-'}</td>
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
          <h2>{editing ? `${editing.username} 수정` : '새 계정'}</h2>
          {!editing && (
            <div className="field">
              <label htmlFor="u-name">아이디</label>
              <input id="u-name" className="input mono" autoComplete="off" value={form.username}
                     onChange={(e) => setForm({ ...form, username: e.target.value })} />
            </div>
          )}
          <div className="field">
            <label htmlFor="u-disp">표시 이름</label>
            <input id="u-disp" className="input" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="u-pw">{editing ? '비밀번호 재설정' : '비밀번호'}</label>
            <input id="u-pw" className="input" type="password" autoComplete="new-password"
                   placeholder={editing ? '바꿀 때만 입력(잠금도 풀림)' : '10자 이상'} value={form.password}
                   onChange={(e) => setForm({ ...form, password: e.target.value })} />
          </div>
          <div className="field">
            <span className="field-label" style={{ fontWeight: 500 }}>역할</span>
            {ROLES.map((r) => (
              <label key={r} style={{ display: 'flex', gap: 8, alignItems: 'baseline', margin: '6px 0' }}>
                <input type="checkbox" checked={form.roles.includes(r)} onChange={() => toggleRole(r)} />
                <span><b>{ROLE_LABEL[r]}</b> <span className="muted small">{ROLE_DESC[r]}</span></span>
              </label>
            ))}
          </div>
          {editing && (
            <label style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 14 }}>
              <input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
              사용
            </label>
          )}
          <div className="btn-row">
            <button type="submit" className="btn primary" disabled={busy || form.roles.length === 0 || (!editing && (!form.username || !form.password))}>
              {editing ? '변경 저장' : '만들기'}
            </button>
          </div>
        </form>
      </div>
    </>
  )
}
