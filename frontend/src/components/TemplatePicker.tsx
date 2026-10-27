import { useEffect, useState } from 'react'
import { errorMessage } from '../lib/api'
import { knowledgeApi, type TemplateResponse } from '../lib/knowledgeApi'
import Modal from './Modal'

/** 새 글 쓰기의 '템플릿으로 시작' 목록. 고르면 onPick(본문·카테고리·태그는 호출 쪽이 채운다). */
export default function TemplatePicker({ onPick, onClose }: { onPick: (t: TemplateResponse) => void; onClose: () => void }) {
  const [list, setList] = useState<TemplateResponse[] | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => { knowledgeApi.templates.list().then(setList).catch((e) => setErr(errorMessage(e, '템플릿을 불러오지 못했습니다.'))) }, [])

  return (
    <Modal title="템플릿으로 시작" onClose={onClose} footer={<button type="button" className="btn" onClick={onClose}>닫기</button>}>
      {err && <div className="alert alert--error">{err}</div>}
      {!list && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
      {list && list.length === 0 && <div className="empty">등록된 템플릿이 없습니다. 관리자가 [템플릿] 메뉴에서 만들 수 있습니다.</div>}
      {list && list.length > 0 && (
        <ul className="tpl-list">
          {list.map((t) => (
            <li key={t.id}>
              <button type="button" className="tpl-list__item" onClick={() => onPick(t)}>
                <strong>{t.name}</strong>
                {t.description && <span className="small muted">{t.description}</span>}
                {(t.category || t.tags.length > 0) && (
                  <span className="tpl-list__meta small">
                    {t.category && <span className="chip chip--neutral chip--xs">{t.category.path.join(' › ')}</span>}
                    {t.tags.map((g) => <span key={g} className="chip chip--neutral chip--xs">#{g}</span>)}
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
      <p className="small muted" style={{ marginTop: 12 }}>고르면 본문이 템플릿 내용으로 바뀌고, 기본 카테고리·태그가 채워집니다.</p>
    </Modal>
  )
}
