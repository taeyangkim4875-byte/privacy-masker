import { useEffect, useState, type MouseEvent as ReactMouseEvent, type ReactNode } from 'react'
import { ENTITY_LABEL, ENTITY_ORDER, type EntityCode, type LabelView, type SpanView } from './api'

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

/** 드래그 끝의 click 은 무시한다(드래그를 스팬 클릭으로 덮어쓰지 않게) */
const clickOnly = (f: () => void) => () => {
  if (window.getSelection()?.isCollapsed ?? true) f()
}

/**
 * 교정 라벨을 달 수 있는 원문: 탐지 스팬을 누르면 onSpanClick, 글자를 드래그해 고르면 onSelectRange(원문 기준 위치).
 * 이름 아님 라벨은 취소선, 놓침 라벨은 점선 밑줄. 조각마다 data-off(원문 시작 위치)를 달아 선택 위치를 계산한다.
 */
export function LabelableSource({
  text,
  spans,
  labels,
  onSpanClick,
  onLabelClick,
  onSelectRange,
}: {
  text: string | null
  spans: SpanView[]
  labels: LabelView[]
  onSpanClick: (s: SpanView) => void
  onLabelClick: (l: LabelView) => void
  onSelectRange: (start: number, end: number) => void
}) {
  if (text === null) return <span className="muted">(NULL)</span>
  const cuts = new Set<number>([0, text.length])
  spans.forEach((s) => { cuts.add(s.start); cuts.add(s.end) })
  const missed = labels.filter((l) => l.label === 'MISSED' && !l.stale)
  missed.forEach((l) => { cuts.add(Math.min(l.start, text.length)); cuts.add(Math.min(l.end, text.length)) })
  const points = [...cuts].sort((a, b) => a - b)
  const out: ReactNode[] = []
  for (let i = 0; i + 1 < points.length; i++) {
    const a = points[i]
    const b = points[i + 1]
    if (a >= b) continue
    const span = spans.find((s) => s.start <= a && b <= s.end)
    const miss = missed.find((l) => l.start <= a && b <= l.end)
    const piece = text.slice(a, b)
    if (span) {
      const rejected = labels.find((l) => l.label === 'NOT_PII' && !l.stale && l.start === span.start && l.end === span.end)
      out.push(
        <mark key={a} data-off={a} className={`pii pii-${span.type}${rejected ? ' pii-rejected' : ''}${miss ? ' pii-missed' : ''}`}
              title={`${span.label} · ${span.detector}${rejected ? ' · 이름 아님으로 표시됨' : ''}`}
              onClick={clickOnly(() => (rejected ? onLabelClick(rejected) : miss ? onLabelClick(miss) : onSpanClick(span)))}>
          {piece}
        </mark>,
      )
    } else if (miss) {
      out.push(<span key={a} data-off={a} className="pii-missed" title="놓침으로 표시됨" onClick={clickOnly(() => onLabelClick(miss))}>{piece}</span>)
    } else {
      out.push(<span key={a} data-off={a}>{piece}</span>)
    }
  }
  // 선택 위치: 루트 처음부터 선택 끝점까지 잘라 낸 글자 수(렌더된 글자 = 원문이므로 UTF-16 위치가 그대로 맞다).
  // 끝점이 요소 노드이거나 루트 밖이어도 루트와 겹치는 부분만 쓴다
  const onMouseUp = (e: ReactMouseEvent<HTMLSpanElement>) => {
    const root = e.currentTarget
    const sel = window.getSelection()
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return
    const r = sel.getRangeAt(0)
    const whole = document.createRange()
    whole.selectNodeContents(root)
    const offsetOf = (node: Node, offset: number) => {
      const pre = document.createRange()
      pre.selectNodeContents(root)
      pre.setEnd(node, offset)
      return pre.toString().length
    }
    let s = r.compareBoundaryPoints(Range.START_TO_START, whole) <= 0 ? 0 : offsetOf(r.startContainer, r.startOffset)
    let t = r.compareBoundaryPoints(Range.END_TO_END, whole) >= 0 ? text.length : offsetOf(r.endContainer, r.endOffset)
    while (s < t && /\s/.test(text[s])) s++
    while (t > s && /\s/.test(text[t - 1])) t--
    if (t > s) onSelectRange(s, t)
  }
  return <span className="labelable" onMouseUp={onMouseUp}>{out}</span>
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
