import { useState } from 'react'
import { api } from '../api'

/** 로그인. 성공하면 onDone 으로 사용자 정보를 다시 읽는다 */
export function Login({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.login(username.trim(), password)
      setPassword('')
      onDone()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-wrap">
      <form
        className="panel auth-card"
        onSubmit={(e) => {
          e.preventDefault()
          submit()
        }}
      >
        <h1>개인정보 마스킹</h1>
        <p className="muted" style={{ marginTop: 0 }}>로그인하세요</p>
        <div className="field">
          <label htmlFor="l-user">아이디</label>
          <input id="l-user" className="input" autoComplete="username" autoFocus value={username}
                 onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="l-pw">비밀번호</label>
          <input id="l-pw" className="input" type="password" autoComplete="current-password" value={password}
                 onChange={(e) => setPassword(e.target.value)} />
        </div>
        {error && <div className="notice bad" style={{ marginBottom: 14 }}>{error}</div>}
        <button type="submit" className="btn primary" disabled={busy || !username || !password}>로그인</button>
      </form>
    </div>
  )
}

/** 최초 관리자 만들기: 서버의 data/setup.token 값을 입력 */
export function Setup({ onDone }: { onDone: () => void }) {
  const [token, setToken] = useState('')
  const [username, setUsername] = useState('admin')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    if (password !== confirm) {
      setError('비밀번호 확인이 일치하지 않습니다')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.setup(token.trim(), username.trim(), password, displayName.trim())
      await api.login(username.trim(), password)
      onDone()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-wrap">
      <form
        className="panel auth-card"
        onSubmit={(e) => {
          e.preventDefault()
          submit()
        }}
      >
        <h1>초기 관리자 만들기</h1>
        <p className="muted" style={{ marginTop: 0 }}>
          처음 한 번만 합니다. 설정 토큰은 서버의 <span className="mono">data/setup.token</span> 파일에 있습니다.
          관리자는 계정 관리와 감사 로그 조회만 할 수 있으니, 만든 뒤 설정자·실행자 계정을 추가하세요.
        </p>
        <div className="field">
          <label htmlFor="s-token">설정 토큰</label>
          <input id="s-token" className="input mono" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="s-user">관리자 아이디</label>
          <input id="s-user" className="input" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="s-name">표시 이름</label>
          <input id="s-name" className="input" placeholder="비우면 아이디" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="s-pw">비밀번호</label>
          <input id="s-pw" className="input" type="password" autoComplete="new-password" value={password}
                 onChange={(e) => setPassword(e.target.value)} />
          <span className="hint">10자 이상</span>
        </div>
        <div className="field">
          <label htmlFor="s-pw2">비밀번호 확인</label>
          <input id="s-pw2" className="input" type="password" autoComplete="new-password" value={confirm}
                 onChange={(e) => setConfirm(e.target.value)} />
        </div>
        {error && <div className="notice bad" style={{ marginBottom: 14 }}>{error}</div>}
        <button type="submit" className="btn primary" disabled={busy || !token || !username || !password}>만들고 로그인</button>
      </form>
    </div>
  )
}
