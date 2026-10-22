import { useCallback, useEffect, useState } from 'react'
import { Toast, type ToastMsg } from './components'
import Dashboard from './pages/Dashboard'
import Connections from './pages/Connections'
import Jobs from './pages/Jobs'
import DetectLab from './pages/DetectLab'
import History from './pages/History'

type Page = 'dashboard' | 'jobs' | 'connections' | 'detect' | 'history'

const NAV: { id: Page; label: string }[] = [
  { id: 'dashboard', label: '현황' },
  { id: 'jobs', label: '마스킹 작업' },
  { id: 'connections', label: 'DB 접속' },
  { id: 'detect', label: '탐지 테스트' },
  { id: 'history', label: '실행·감사 이력' },
]

function readHash(): { page: Page; arg: string | null } {
  const [p, arg] = window.location.hash.replace(/^#\/?/, '').split('/')
  const page = NAV.some((n) => n.id === p) ? (p as Page) : 'dashboard'
  return { page, arg: arg ?? null }
}

export type Go = (page: Page, arg?: string | number) => void
export type Notify = (m: ToastMsg) => void

export default function App() {
  const [route, setRoute] = useState(readHash)
  const [toast, setToast] = useState<ToastMsg>(null)

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

  return (
    <div className="app">
      <nav className="nav" aria-label="주 메뉴">
        <div className="brand">
          <div className="brand-name">개인정보 마스킹</div>
          <div className="brand-sub">STT·TTS 이력 관리</div>
        </div>
        {NAV.map((n) => (
          <button
            key={n.id}
            className="nav-item"
            aria-current={route.page === n.id ? 'page' : undefined}
            onClick={() => go(n.id)}
          >
            {n.label}
          </button>
        ))}
        <div className="nav-foot">
          로컬 테스트 모드
          <br />이 PC에서만 접속됩니다
        </div>
      </nav>
      <main className="main">
        {route.page === 'dashboard' && <Dashboard go={go} notify={notify} />}
        {route.page === 'jobs' && <Jobs go={go} notify={notify} arg={route.arg} />}
        {route.page === 'connections' && <Connections notify={notify} />}
        {route.page === 'detect' && <DetectLab />}
        {route.page === 'history' && <History />}
      </main>
      <Toast msg={toast} onClose={closeToast} />
    </div>
  )
}
