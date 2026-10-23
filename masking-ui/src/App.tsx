import { useCallback, useEffect, useState } from 'react'
import { Toast, type ToastMsg } from './components'
import { api, ApiError, FORBIDDEN_EVENT, ROLE_LABEL, UNAUTHORIZED_EVENT, type RoleCode, type UserView } from './api'
import { AuthContext } from './auth'
import Dashboard from './pages/Dashboard'
import Connections from './pages/Connections'
import Jobs from './pages/Jobs'
import DetectLab from './pages/DetectLab'
import Dictionary from './pages/Dictionary'
import History from './pages/History'
import Users from './pages/Users'
import Account from './pages/Account'
import { Login, Setup } from './pages/Login'

type Page = 'dashboard' | 'jobs' | 'connections' | 'detect' | 'dictionary' | 'history' | 'users' | 'account'

// roles 가 없으면 로그인한 모두. 서버 권한 표(SecurityConfig)와 맞춘다
const NAV: { id: Page; label: string; roles?: RoleCode[] }[] = [
  { id: 'dashboard', label: '현황' },
  { id: 'jobs', label: '마스킹 작업' },
  { id: 'connections', label: 'DB 접속', roles: ['CONFIGURER'] },
  { id: 'detect', label: '탐지 테스트', roles: ['CONFIGURER'] },
  { id: 'dictionary', label: '이름 사전', roles: ['CONFIGURER'] },
  { id: 'history', label: '실행·감사 이력', roles: ['CONFIGURER', 'OPERATOR', 'AUDITOR', 'ADMIN'] },
  { id: 'users', label: '사용자 관리', roles: ['ADMIN'] },
  { id: 'account', label: '내 계정' },
]

const allowed = (me: UserView, page: Page) => {
  const n = NAV.find((x) => x.id === page)
  return !!n && (!n.roles || n.roles.some((r) => me.roles.includes(r)))
}

function readHash(): { page: Page; arg: string | null } {
  const [p, arg] = window.location.hash.replace(/^#\/?/, '').split('/')
  const page = NAV.some((n) => n.id === p) ? (p as Page) : 'dashboard'
  return { page, arg: arg ?? null }
}

export type Go = (page: Page, arg?: string | number) => void
export type Notify = (m: ToastMsg) => void

type AuthState = { kind: 'loading' } | { kind: 'login' } | { kind: 'setup' } | { kind: 'in'; me: UserView }

export default function App() {
  const [auth, setAuth] = useState<AuthState>({ kind: 'loading' })
  const [route, setRoute] = useState(readHash)
  const [toast, setToast] = useState<ToastMsg>(null)

  const refreshMe = useCallback(async () => {
    try {
      setAuth({ kind: 'in', me: await api.me() })
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        try {
          const s = await api.setupStatus()
          setAuth({ kind: s.setupRequired ? 'setup' : 'login' })
        } catch {
          setAuth({ kind: 'login' })
        }
      } else {
        setAuth({ kind: 'login' })
      }
    }
  }, [])

  useEffect(() => {
    refreshMe()
    const onUnauthorized = () => setAuth({ kind: 'login' })
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    window.addEventListener(FORBIDDEN_EVENT, refreshMe)
    return () => {
      window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
      window.removeEventListener(FORBIDDEN_EVENT, refreshMe)
    }
  }, [refreshMe])

  useEffect(() => {
    const on = () => setRoute(readHash())
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])

  const go: Go = (page, arg) => {
    window.location.hash = arg === undefined ? `/${page}` : `/${page}/${arg}`
  }
  const notify: Notify = useCallback((m) => setToast(m), [])
  const closeToast = useCallback(() => setToast(null), [])

  const logout = async () => {
    try {
      await api.logout()
    } finally {
      setAuth({ kind: 'login' })
    }
  }

  if (auth.kind === 'loading') return null
  if (auth.kind === 'setup') return <Setup onDone={refreshMe} />
  if (auth.kind === 'login') return <Login onDone={refreshMe} />

  const me = auth.me
  const page = allowed(me, route.page) ? route.page : 'dashboard'

  return (
    <AuthContext.Provider value={me}>
      <div className="app">
        <nav className="nav" aria-label="주 메뉴">
          <div className="brand">
            <div className="brand-name">개인정보 마스킹</div>
            <div className="brand-sub">STT·TTS 이력 관리</div>
          </div>
          {NAV.filter((n) => allowed(me, n.id)).map((n) => (
            <button
              key={n.id}
              className="nav-item"
              aria-current={page === n.id ? 'page' : undefined}
              onClick={() => go(n.id)}
            >
              {n.label}
            </button>
          ))}
          <div className="nav-foot nav-user">
            <b>{me.displayName}</b> ({me.username})
            <br />
            {me.roles.map((r) => ROLE_LABEL[r]).join(', ')}
            <br />
            <button className="btn small" style={{ marginTop: 8 }} onClick={logout}>로그아웃</button>
          </div>
        </nav>
        <main className="main">
          {page === 'dashboard' && <Dashboard go={go} notify={notify} />}
          {page === 'jobs' && <Jobs go={go} notify={notify} arg={route.arg} />}
          {page === 'connections' && <Connections notify={notify} />}
          {page === 'detect' && <DetectLab />}
          {page === 'dictionary' && <Dictionary notify={notify} />}
          {page === 'history' && <History />}
          {page === 'users' && <Users notify={notify} />}
          {page === 'account' && <Account notify={notify} onLogout={logout} />}
        </main>
        <Toast msg={toast} onClose={closeToast} />
      </div>
    </AuthContext.Provider>
  )
}
