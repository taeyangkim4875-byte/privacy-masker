import { useState } from 'react'
import { api, ROLE_LABEL } from '../api'
import { useMe } from '../auth'
import type { Notify } from '../App'

/** 내 계정: 역할 확인, 비밀번호 변경, 로그아웃 */
export default function Account({ notify, onLogout }: { notify: Notify; onLogout: () => void }) {
  const me = useMe()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)

  const change = async () => {
    if (next !== confirm) {
      notify({ kind: 'bad', text: '새 비밀번호 확인이 일치하지 않습니다' })
      return
    }
    setBusy(true)
    try {
      await api.changePassword(current, next)
      setCurrent('')
      setNext('')
      setConfirm('')
      notify({ kind: 'ok', text: '비밀번호를 바꿨습니다' })
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
          <h1>내 계정</h1>
          <p>{me.displayName} ({me.username}) · {me.roles.map((r) => ROLE_LABEL[r]).join(', ')}</p>
        </div>
        <button className="btn" onClick={onLogout}>로그아웃</button>
      </div>
      {me.authSource === 'LOCAL' && (
        <form
          className="panel"
          style={{ maxWidth: 420 }}
          onSubmit={(e) => {
            e.preventDefault()
            change()
          }}
        >
          <h2>비밀번호 변경</h2>
          <div className="field">
            <label htmlFor="a-cur">현재 비밀번호</label>
            <input id="a-cur" className="input" type="password" autoComplete="current-password" value={current}
                   onChange={(e) => setCurrent(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="a-new">새 비밀번호</label>
            <input id="a-new" className="input" type="password" autoComplete="new-password" value={next}
                   onChange={(e) => setNext(e.target.value)} />
            <span className="hint">10자 이상</span>
          </div>
          <div className="field">
            <label htmlFor="a-new2">새 비밀번호 확인</label>
            <input id="a-new2" className="input" type="password" autoComplete="new-password" value={confirm}
                   onChange={(e) => setConfirm(e.target.value)} />
          </div>
          <div className="btn-row">
            <button type="submit" className="btn primary" disabled={busy || !current || !next}>변경</button>
          </div>
        </form>
      )}
    </>
  )
}
