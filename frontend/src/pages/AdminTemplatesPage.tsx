import { lazy, Suspense, useEffect, useState } from 'react'
import { api, errorMessage, flattenCategories, formatDate, type CategoryNode } from '../lib/api'
import { knowledgeApi, type TemplateResponse } from '../lib/knowledgeApi'
import { htmlToText } from '../lib/html'
import { useToast } from '../lib/toast'
import Modal from '../components/Modal'
import TagInput from '../components/TagInput'
import type { UploadedFile } from '../editor/RichEditor'

const RichEditor = lazy(() => import('../editor/RichEditor'))

type Form = { name: string; description: string; content: string; categoryId: number | null; tags: string[]; sortOrder: number }
const empty: Form = { name: '', description: '', content: '<p></p>', categoryId: null, tags: [], sortOrder: 0 }

/** 템플릿은 글에 연결되지 않아 사진을 올리면 고아 첨부 정리(24시간)에 지워진다 → 업로드를 막는다. */
const noUpload = async (): Promise<UploadedFile> => { throw new Error('템플릿에는 사진·파일을 올릴 수 없습니다. 글을 쓸 때 넣어 주세요.') }

/** 관리자: 글 템플릿 관리(새 글 쓰기의 '템플릿으로 시작' 목록). */
export default function AdminTemplatesPage() {
  const toast = useToast()
  const [list, setList] = useState<TemplateResponse[] | null>(null)
  const [tree, setTree] = useState<CategoryNode[]>([])
  const [editing, setEditing] = useState<TemplateResponse | 'new' | null>(null)
  const [form, setForm] = useState<Form>(empty)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [removing, setRemoving] = useState<TemplateResponse | null>(null)

  const load = () => knowledgeApi.templates.list().then(setList).catch((e) => toast(errorMessage(e), 'error'))
  useEffect(() => { load(); api.categories.tree().then(setTree).catch(() => null) }, [])

  const open = (t: TemplateResponse | 'new') => {
    setErr(null)
    setForm(t === 'new' ? { ...empty, sortOrder: (list?.length ?? 0) + 1 }
      : { name: t.name, description: t.description ?? '', content: t.content, categoryId: t.category?.id ?? null, tags: t.tags, sortOrder: t.sortOrder })
    setEditing(t)
    window.scrollTo({ top: 0 })
  }

  const save = async () => {
    if (!form.name.trim()) { setErr('이름을 입력하세요.'); return }
    if (!htmlToText(form.content).trim() && !/<table/.test(form.content)) { setErr('본문을 입력하세요.'); return }
    setBusy(true); setErr(null)
    const body = { name: form.name.trim(), description: form.description.trim() || undefined, content: form.content, categoryId: form.categoryId, tags: form.tags, sortOrder: form.sortOrder }
    try {
      if (editing === 'new') await knowledgeApi.templates.create(body)
      else if (editing) await knowledgeApi.templates.update(editing.id, body)
      toast('템플릿을 저장했습니다.', 'success')
      setEditing(null); load()
    } catch (e) { setErr(errorMessage(e)) } finally { setBusy(false) }
  }

  const remove = async () => {
    if (!removing) return
    setBusy(true)
    try { await knowledgeApi.templates.remove(removing.id); toast(`'${removing.name}' 템플릿을 삭제했습니다.`, 'success'); load() }
    catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false); setRemoving(null) }
  }

  const categoryOptions = flattenCategories(tree)

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <h2>템플릿 관리</h2>
          <p>새 글 쓰기에서 본문이 비어 있을 때 [템플릿으로 시작]으로 고르는 양식입니다. 고르면 본문·기본 카테고리·기본 태그가 채워집니다.</p>
        </div>
        {!editing && <button type="button" className="btn btn--primary" onClick={() => open('new')}>템플릿 추가</button>}
      </div>

      {editing && (
        <section className="card tpl-edit">
          <div className="card__head"><strong>{editing === 'new' ? '새 템플릿' : `${editing.name} 편집`}</strong></div>
          <div className="stack" style={{ padding: 16 }}>
            <div className="form-row">
              <div className="field">
                <label htmlFor="t-name">이름</label>
                <input id="t-name" className="input" value={form.name} maxLength={100} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="예) 장애 보고" />
              </div>
              <div className="field">
                <label htmlFor="t-order">순서 <span className="muted" style={{ fontWeight: 400 }}>(작을수록 위)</span></label>
                <input id="t-order" type="number" className="input" value={form.sortOrder} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) || 0 })} />
              </div>
            </div>
            <div className="field">
              <label htmlFor="t-desc">설명</label>
              <input id="t-desc" className="input" value={form.description} maxLength={300} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="목록에 보이는 한 줄 설명" />
            </div>
            <div className="form-row">
              <div className="field">
                <label htmlFor="t-cat">기본 카테고리</label>
                <select id="t-cat" className="select" value={form.categoryId ?? ''} onChange={(e) => setForm({ ...form, categoryId: e.target.value === '' ? null : Number(e.target.value) })}>
                  <option value="">지정 안 함</option>
                  {categoryOptions.map((c) => <option key={c.id} value={c.id}>{'　'.repeat(c.depth - 1)}{c.depth > 1 ? '› ' : ''}{c.name}</option>)}
                </select>
              </div>
              <div className="field">
                <label>기본 태그</label>
                <TagInput value={form.tags} onChange={(tags) => setForm({ ...form, tags })} />
              </div>
            </div>
            <div className="field">
              <label>본문 <span className="muted" style={{ fontWeight: 400 }}>(사진·파일은 넣지 않습니다)</span></label>
              <Suspense fallback={<div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}>
                <RichEditor value={form.content} onChange={(content) => setForm((f) => ({ ...f, content }))} onUpload={noUpload} />
              </Suspense>
            </div>
            {err && <div className="alert alert--error">{err}</div>}
            <div className="row" style={{ justifyContent: 'flex-end' }}>
              <button type="button" className="btn" onClick={() => setEditing(null)} disabled={busy}>취소</button>
              <button type="button" className="btn btn--primary" onClick={save} disabled={busy}>{busy ? '저장 중…' : '저장'}</button>
            </div>
          </div>
        </section>
      )}

      {!editing && (
        <div className="card">
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>순서</th><th>이름</th><th>설명</th><th>기본 카테고리</th><th>기본 태그</th><th>수정일</th><th></th></tr></thead>
              <tbody>
                {list?.map((t) => (
                  <tr key={t.id}>
                    <td className="small muted">{t.sortOrder}</td>
                    <td><strong>{t.name}</strong></td>
                    <td className="small">{t.description ?? <span className="muted">–</span>}</td>
                    <td className="small">{t.category ? t.category.path.join(' › ') : <span className="muted">–</span>}</td>
                    <td>{t.tags.length > 0 ? t.tags.map((g) => <span key={g} className="chip chip--neutral chip--xs" style={{ marginRight: 4 }}>#{g}</span>) : <span className="muted">–</span>}</td>
                    <td className="small muted">{formatDate(t.updatedAt)}</td>
                    <td>
                      <div className="row" style={{ justifyContent: 'flex-end', gap: 6 }}>
                        <button type="button" className="btn btn--sm" onClick={() => open(t)}>편집</button>
                        <button type="button" className="btn btn--sm btn--danger" onClick={() => setRemoving(t)}>삭제</button>
                      </div>
                    </td>
                  </tr>
                ))}
                {list && list.length === 0 && <tr><td colSpan={7} className="empty">템플릿이 없습니다. [템플릿 추가]로 만드세요.</td></tr>}
                {!list && <tr><td colSpan={7} className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {removing && (
        <Modal title={`'${removing.name}' 템플릿을 삭제할까요?`} onClose={() => setRemoving(null)}
               footer={<>
                 <button type="button" className="btn" onClick={() => setRemoving(null)}>취소</button>
                 <button type="button" className="btn btn--danger" disabled={busy} onClick={remove}>{busy ? '삭제 중…' : '삭제'}</button>
               </>}>
          <p>템플릿만 지워지고, 이 템플릿으로 이미 쓴 글은 그대로 남습니다.</p>
        </Modal>
      )}
    </div>
  )
}
