import { useEffect, useState, type ReactNode } from 'react'
import { ENTITY_LABEL, ENTITY_ORDER, type EntityCode, type SpanView } from './api'

/** 원문 + 탐지 스팬 → 유형별 색 하이라이트 */
export function SourceText({ text, spans }: { text: string | null; spans: SpanView[] }) {
  if (text === null) return <span className="muted">(NULL)</span>
  const out: ReactNode[] = []
  let pos = 0
  spans.forEach((s, i) => {
    if (s.start > pos) out.push(text.slice(pos, s.start))
    out.push(
      <mark key={i} className={`pii pii-${s.type}`} title={`${s.label} · ${s.detector}`}>
        {text.slice(s.start, s.end)}
      </mark>,
    )
    pos = s.end
  })
  if (pos < text.length) out.push(text.slice(pos))
  return <>{out}</>
}

/**
 * 마스킹 결과에서 치환된 자리를 찾아 표시한다.
 * 스팬 사이의 원문 조각은 결과에도 그대로 있으므로, 그 조각을 기준으로 치환 구간을 복원한다.
 */
export function MaskedText({ original, masked, spans }: { original: string | null; masked: string | null; spans: SpanView[] }) {
  if (masked === null || original === null) return <span className="muted">(NULL)</span>
  if (spans.length === 0) return <>{masked}</>
  const out: ReactNode[] = []
  let po = 0
  let pm = 0
  spans.forEach((s, i) => {
    const gap = original.slice(po, s.start)
    out.push(gap)
    pm += gap.length
    const next = spans[i + 1]
    const after = original.slice(s.end, next ? next.start : original.length)
    let endM: number
    if (after.length > 0) {
      const idx = masked.indexOf(after, pm)
      endM = idx < 0 ? masked.length : idx
    } else {
      endM = next ? pm + (s.end - s.start) : masked.length
    }
    out.push(
      <span key={i} className="redacted" title={s.label}>
        {masked.slice(pm, endM)}
      </span>,
    )
    pm = endM
    po = s.end
  })
  if (pm < masked.length) out.push(masked.slice(pm))
  return <>{out}</>
}

export function EntityChips({
  value,
  onChange,
  compact,
}: {
  value: EntityCode[]
  onChange: (v: EntityCode[]) => void
  compact?: boolean
}) {
  return (
    <div className="chip-row">
      {ENTITY_ORDER.map((e) => {
        const on = value.includes(e)
        return (
          <button
            type="button"
            key={e}
            className="chip"
            aria-pressed={on}
            style={compact ? { padding: '2px 8px', fontSize: 12.5 } : undefined}
            onClick={() => onChange(on ? value.filter((x) => x !== e) : ENTITY_ORDER.filter((x) => x === e || value.includes(x)))}
          >
            <span className={`dot dot-${e}`} />
            {ENTITY_LABEL[e]}
          </button>
        )
      })}
    </div>
  )
}

export function Legend({ counts }: { counts?: Record<string, number> }) {
  return (
    <div className="legend">
      {ENTITY_ORDER.map((e) => (
        <span key={e}>
          <i className={`dot-${e}`} />
          {ENTITY_LABEL[e]}
          {counts && <b className="mono">{counts[e] ?? 0}</b>}
        </span>
      ))}
    </div>
  )
}

const STATUS_LABEL: Record<string, string> = {
  RUNNING: '실행 중',
  SUCCESS: '완료',
  FAILED: '실패',
  STOPPED: '중지됨',
  IDLE: '대기',
}

export function Status({ value }: { value: string }) {
  return <span className={`status ${value}`}>{STATUS_LABEL[value] ?? value}</span>
}

export function num(n: number | null | undefined) {
  return (n ?? 0).toLocaleString('ko-KR')
}

export type ToastMsg = { kind: 'ok' | 'bad' | 'info'; text: string } | null

export function Toast({ msg, onClose }: { msg: ToastMsg; onClose: () => void }) {
  useEffect(() => {
    if (!msg) return
    const t = setTimeout(onClose, msg.kind === 'bad' ? 7000 : 3500)
    return () => clearTimeout(t)
  }, [msg, onClose])
  if (!msg) return null
  return (
    <div className={`notice ${msg.kind} toast`} role="status">
      {msg.text}
    </div>
  )
}

/** 주기 갱신 훅 */
export function usePoll<T>(load: () => Promise<T>, ms: number, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let alive = true
    const run = () =>
      load()
        .then((d) => {
          if (alive) {
            setData(d)
            setError(null)
          }
        })
        .catch((e: Error) => alive && setError(e.message))
    run()
    const t = setInterval(run, ms)
    return () => {
      alive = false
      clearInterval(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, tick, ...deps])
  return { data, error, reload: () => setTick((x) => x + 1) }
}
