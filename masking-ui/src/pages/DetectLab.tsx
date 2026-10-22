import { useEffect, useState } from 'react'
import { api, ENTITY_LABEL, ENTITY_ORDER, POLICY_LABEL, type EntityCode, type Meta, type PolicyCode, type SpanView } from '../api'
import { EntityChips, Legend, MaskedText, SourceText } from '../components'

const SAMPLE = `상담원: 안녕하세요 고객님, 상담원 이하늘입니다. 무엇을 도와드릴까요?
고객: 카드를 잃어버려서 분실 신고하려고요.
상담원: 본인 확인을 위해 성함 말씀해 주시겠어요?
고객: 박민서입니다.
상담원: 박민서 고객님, 연락처 확인 부탁드립니다.
고객: 010-4821-7736이요. 아 회사 번호는 02-555-0192예요.
고객: 카드 번호는 4111 1111 1111 1111 이고 주민번호는 900101-1234567입니다.
고객: 환불은 국민은행 110-482-117736 계좌로 해주시고 메일은 minseo.park@example.com 으로 주세요.
상담원: 네 처리되었습니다. 이번 달 결제일은 25일입니다.
고객: 공일공 사팔이일 칠칠삼육 이 번호로 문자 주세요.
<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ko-KR"><prosody rate="slow">장나예,회원님이 맞으면 1번, 아니면 2번을 눌러주세요.</prosody></speak>
<speak version="1.0" xml:lang="ko-KR"><prosody rate="slow">KIM EUNMIN,회원님이 맞으면 1번을 눌러주세요.</prosody></speak>
<speak version="1.0" xml:lang="ko-KR"><prosody rate="slow">고,영,환</prosody></speak>
<speak version="1.0" xml:lang="ko-KR"><prosody rate="slow"><say-as interpret-as="telephone">7736</say-as></prosody></speak>
<speak version="1.0" xml:lang="ko-KR"><prosody rate="slow"><say-as interpret-as="cardinal">12.5</say-as></prosody></speak>`

export default function DetectLab() {
  const [meta, setMeta] = useState<Meta | null>(null)
  const [text, setText] = useState(SAMPLE)
  const [entities, setEntities] = useState<EntityCode[]>([...ENTITY_ORDER])
  const [policies, setPolicies] = useState<Record<string, PolicyCode>>(Object.fromEntries(ENTITY_ORDER.map((e) => [e, 'PARTIAL'])))
  const [result, setResult] = useState<{ masked: string; spans: SpanView[]; elapsedMicros: number } | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    api.meta().then(setMeta).catch(() => undefined)
  }, [])

  // 입력이 바뀌면 잠시 뒤 다시 탐지
  useEffect(() => {
    const t = setTimeout(() => {
      api
        .detect(text, entities, policies)
        .then((r) => {
          setResult(r)
          setErr(null)
        })
        .catch((e: Error) => setErr(e.message))
    }, 250)
    return () => clearTimeout(t)
  }, [text, entities, policies])

  const counts: Record<string, number> = {}
  result?.spans.forEach((s) => (counts[s.type] = (counts[s.type] ?? 0) + 1))

  return (
    <>
      <div className="page-head">
        <div>
          <h1>탐지 테스트</h1>
          <p>상담 기록을 붙여 넣고 어떤 값이 잡히는지 확인합니다. 입력한 내용은 저장하지 않습니다.</p>
        </div>
      </div>

      <div className="panel" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <div>
            <div className="small muted" style={{ marginBottom: 6 }}>찾을 개인정보</div>
            <EntityChips value={entities} onChange={setEntities} />
          </div>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            {ENTITY_ORDER.filter((e) => entities.includes(e)).map((e) => (
              <label key={e} className="small" style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <span className="muted">{ENTITY_LABEL[e]}</span>
                <select className="input" style={{ padding: '4px 8px' }} value={policies[e]} onChange={(ev) => setPolicies((p) => ({ ...p, [e]: ev.target.value as PolicyCode }))}>
                  {(Object.keys(POLICY_LABEL) as PolicyCode[]).map((p) => <option key={p} value={p}>{POLICY_LABEL[p]}</option>)}
                </select>
              </label>
            ))}
          </div>
        </div>
      </div>

      <div className="two">
        <div className="panel">
          <h2>입력</h2>
          <textarea className="input" style={{ minHeight: 360, resize: 'vertical', lineHeight: 1.7 }} value={text} onChange={(e) => setText(e.target.value)} />
        </div>
        <div className="panel">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <h2>결과</h2>
            {result && <span className="small muted">{result.spans.length}개 탐지 · {(result.elapsedMicros / 1000).toFixed(2)}ms</span>}
          </div>
          {err && <div className="notice bad">{err}</div>}
          {result && (
            <>
              <Legend counts={counts} />
              <div className="utter-text" style={{ whiteSpace: 'pre-wrap', margin: '14px 0 18px' }}>
                <SourceText text={text} spans={result.spans} />
              </div>
              <div className="small muted" style={{ marginBottom: 4 }}>저장될 값</div>
              <div className="utter-text after" style={{ whiteSpace: 'pre-wrap' }}>
                <MaskedText original={text} masked={result.masked} spans={result.spans} />
              </div>
            </>
          )}
        </div>
      </div>

      {meta && (
        <div className="panel" style={{ marginTop: 20 }}>
          <h2>탐지기 목록</h2>
          <p className="small muted" style={{ marginTop: -6 }}>
            한글로 읽은 숫자("공일공…")와 문맥 없이 말한 이름은 아직 놓칩니다. 각각 2단계(ITN 정규화)와 3단계(이름 모델)에서 다룹니다.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>탐지기</th><th>유형</th><th>방식</th><th>패턴</th></tr>
              </thead>
              <tbody>
                {meta.detectors.map((d) => (
                  <tr key={d.name}>
                    <td>{d.name}</td>
                    <td><span className="legend"><span><i className={`dot-${d.type}`} />{ENTITY_LABEL[d.type]}</span></span></td>
                    <td className="small">{d.kind}</td>
                    <td className="mono small" style={{ maxWidth: 520, overflowWrap: 'anywhere' }}>{d.pattern}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  )
}
