import { useEffect, useState } from 'react'
import { api, type DictionaryView, type DictKind } from '../api'
import type { Notify } from '../App'

const KIND_LABEL: Record<DictKind, string> = { EXCEPT: '예외어', FORCE: '추가 탐지어' }

const KIND_DESC: Record<DictKind, string> = {
  EXCEPT:
    '이름 규칙에 걸리지만 이름이 아닌 말(상품명·지점명·상담 멘트). 문장에서 이 단어 안에 든 이름 탐지만 지웁니다. 번호 등 다른 유형에는 적용하지 않습니다. 이름일 수 있는 말은 넣지 마세요(그 이름을 가진 사람이 가려지지 않습니다).',
  FORCE:
    '문맥 단서 없이도 이름으로 가릴 말. 앞 글자가 한글이면 다른 단어의 일부로 보고 잡지 않습니다. 뒤에 붙은 글자는 보지 않으므로 짧은 말(예: 2글자)은 그 말로 시작하는 다른 단어까지 가립니다. 실명일 수 있으므로 이 목록은 설정자만 보고, 감사 로그에는 건수만 남습니다.',
}

/** 사용자 이름 사전(설정자). 바꾸면 탐지 테스트·미리보기에 바로, 배치는 다음 실행이 새로 읽는 행부터 반영 */
export default function Dictionary({ notify }: { notify: Notify }) {
  const [data, setData] = useState<DictionaryView | null>(null)
  const [kind, setKind] = useState<DictKind>('EXCEPT')
  const [text, setText] = useState('')
  const [filter, setFilter] = useState('')
  const [busy, setBusy] = useState(false)

  const load = () => api.dictionary().then(setData).catch((e: Error) => notify({ kind: 'bad', text: e.message }))
  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const lines = text.split('\n').filter((l) => l.trim())

  const add = async () => {
    setBusy(true)
    try {
      const r = await api.addDictionary(kind, text.split('\n'))
      notify({ kind: 'ok', text: `${r.added}개 추가${r.skipped ? `, 중복 ${r.skipped}개 건너뜀` : ''}` })
      setText('')
      load()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    } finally {
      setBusy(false)
    }
  }

  const remove = async (id: number) => {
    if (!confirm('이 단어를 사전에서 지울까요?')) return
    try {
      await api.deleteDictionary(id)
      load()
    } catch (e) {
      notify({ kind: 'bad', text: (e as Error).message })
    }
  }

  const entries = (data?.entries ?? []).filter((e) => e.kind === kind && (!filter || e.term.includes(filter.trim())))
  const count = (k: DictKind) => (data?.entries ?? []).filter((e) => e.kind === k).length

  return (
    <>
      <div className="page-head">
        <div>
          <h1>이름 사전</h1>
          <p>
            이름 탐지의 예외어와 추가 탐지어를 관리합니다. 바꾸면 탐지 테스트·미리보기에 바로 반영되고, 배치는 다음 실행이 새로 읽는 행부터 반영됩니다. 이미 대상 테이블에 들어간 행은 그대로이므로, 다시 가리려면 작업 화면에서 처리 위치를 초기화한 뒤 실행하세요.
            {data && ` 기본 사전: 성씨 ${data.builtIn.surnames}개, 예외어 ${data.builtIn.exceptions}개(읽기 전용).`}
          </p>
        </div>
        <div className="seg" role="tablist">
          {(['EXCEPT', 'FORCE'] as DictKind[]).map((k) => (
            <button key={k} role="tab" aria-pressed={kind === k} aria-selected={kind === k} onClick={() => setKind(k)}>
              {KIND_LABEL[k]} ({count(k)})
            </button>
          ))}
        </div>
      </div>
      <div className="split">
        <div className="panel">
          <p className="muted small" style={{ marginTop: 0 }}>{KIND_DESC[kind]}</p>
          <div className="field">
            <label htmlFor="d-filter">찾기</label>
            <input id="d-filter" className="input" value={filter} onChange={(e) => setFilter(e.target.value)} />
          </div>
          {!data ? (
            <p className="muted">불러오는 중…</p>
          ) : entries.length === 0 ? (
            <div className="empty">{filter ? '찾는 단어가 없습니다.' : `등록된 ${KIND_LABEL[kind]}가 없습니다.`}</div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>단어</th>
                    <th>추가한 사람</th>
                    <th>추가 시각</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {entries.map((e) => (
                    <tr key={e.id}>
                      <td>{e.term}</td>
                      <td className="small mono">{e.createdBy}</td>
                      <td className="small muted">{e.createdAt}</td>
                      <td style={{ textAlign: 'right' }}>
                        <button className="btn small" onClick={() => remove(e.id)}>삭제</button>
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
            add()
          }}
        >
          <h2>{KIND_LABEL[kind]} 추가</h2>
          <div className="field">
            <label htmlFor="d-terms">한 줄에 하나씩(2~50자, 한 번에 1,000줄까지)</label>
            <textarea id="d-terms" className="input" style={{ minHeight: 220, resize: 'vertical' }} value={text}
                      placeholder={kind === 'EXCEPT' ? '예: 구독권\n홍대입구점' : ''} onChange={(e) => setText(e.target.value)} />
          </div>
          <div className="btn-row">
            <button type="submit" className="btn primary" disabled={busy || lines.length === 0}>
              {lines.length}개 추가
            </button>
          </div>
        </form>
      </div>
    </>
  )
}
