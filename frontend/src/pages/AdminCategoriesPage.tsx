import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { api, CATEGORY_MAX_DEPTH, errorMessage, findCategory, flattenCategories, type CategoryNode } from '../lib/api'
import { useToast } from '../lib/toast'
import Modal from '../components/Modal'

type Dialog = { kind: 'new'; parent: CategoryNode | null } | { kind: 'edit'; node: CategoryNode } | null

/** 관리자: 카테고리 트리 생성·이름 변경·이동·순서·삭제. 깊이는 최대 5. */
export default function AdminCategoriesPage() {
  const toast = useToast()
  const [tree, setTree] = useState<CategoryNode[]>([])
  const [dialog, setDialog] = useState<Dialog>(null)
  const [name, setName] = useState('')
  const [parentId, setParentId] = useState<number | null>(null)
  /** 열람 부서 제한(쉼표 구분). 편집 폼에서만 */
  const [depts, setDepts] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<CategoryNode | null>(null)

  const load = () => api.categories.tree().then(setTree).catch((e) => toast(errorMessage(e), 'error'))
  useEffect(() => { load() }, [])

  const flat = useMemo(() => flattenCategories(tree), [tree])
  const total = flat.length

  const subtreeHeight = (n: CategoryNode): number => 1 + Math.max(0, ...n.children.map(subtreeHeight))
  const isDescendantOrSelf = (anc: CategoryNode, id: number): boolean => anc.id === id || anc.children.some((c) => isDescendantOrSelf(c, id))

  const openNew = (parent: CategoryNode | null) => { setErr(null); setName(''); setParentId(parent?.id ?? null); setDialog({ kind: 'new', parent }) }
  const openEdit = (node: CategoryNode) => { setErr(null); setName(node.name); setParentId(node.parentId); setDepts((node.restrictedDepartments ?? []).join(', ')); setDialog({ kind: 'edit', node }) }

  /** 편집 중인 노드를 옮길 수 있는 부모 후보: 자기/자손 제외, 깊이 제한 통과 */
  const parentOptions = useMemo(() => {
    if (!dialog) return []
    if (dialog.kind === 'new') return flat.filter((n) => n.depth < CATEGORY_MAX_DEPTH)
    const h = subtreeHeight(dialog.node)
    return flat.filter((n) => !isDescendantOrSelf(dialog.node, n.id) && n.depth + h <= CATEGORY_MAX_DEPTH)
  }, [dialog, flat])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!dialog) return
    setBusy(true); setErr(null)
    try {
      if (dialog.kind === 'new') {
        await api.categories.create(name.trim(), parentId)
        toast(`'${name.trim()}' 카테고리를 만들었습니다.`, 'success')
      } else {
        const moving = parentId !== dialog.node.parentId
        const restrictedDepartments = depts.split(',').map((d) => d.trim()).filter(Boolean)
        await api.categories.update(dialog.node.id, { name: name.trim(), parentId: parentId ?? undefined, moveToRoot: moving && parentId === null, restrictedDepartments })
        toast('저장했습니다.', 'success')
      }
      setDialog(null); load()
    } catch (er) { setErr(errorMessage(er)) } finally { setBusy(false) }
  }

  const remove = async () => {
    if (!confirmDelete) return
    setBusy(true)
    try { await api.categories.remove(confirmDelete.id); toast(`'${confirmDelete.name}' 카테고리를 삭제했습니다.`, 'success'); setConfirmDelete(null); load() }
    catch (er) { toast(errorMessage(er), 'error') } finally { setBusy(false) }
  }

  const move = async (n: CategoryNode, dir: -1 | 1) => {
    try { await api.categories.move(n.id, dir); load() } catch (er) { toast(errorMessage(er), 'error') }
  }

  const renderRows = (nodes: CategoryNode[]): React.ReactNode[] => nodes.flatMap((n, i) => [
    <tr key={n.id}>
      <td>
        <div className={`cat-admin__name cat-admin__name--d${n.depth}`}>
          <span className="cat-admin__depth" title={`${n.depth}단계`}>{n.depth}</span>
          <strong>{n.name}</strong>
          {n.depth === CATEGORY_MAX_DEPTH && <span className="chip chip--draft" style={{ height: 20, fontSize: 11 }}>마지막 단계</span>}
        </div>
      </td>
      <td className="small muted">{n.postCount}</td>
      <td>
        <div className="row" style={{ gap: 4, justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn--sm btn--ghost" disabled={i === 0} onClick={() => move(n, -1)} aria-label="위로">↑</button>
          <button type="button" className="btn btn--sm btn--ghost" disabled={i === nodes.length - 1} onClick={() => move(n, 1)} aria-label="아래로">↓</button>
          <button type="button" className="btn btn--sm" disabled={n.depth >= CATEGORY_MAX_DEPTH} title={n.depth >= CATEGORY_MAX_DEPTH ? `최대 ${CATEGORY_MAX_DEPTH}단계` : undefined} onClick={() => openNew(n)}>하위 추가</button>
          <button type="button" className="btn btn--sm" onClick={() => openEdit(n)}>편집</button>
          <button type="button" className="btn btn--sm btn--danger" onClick={() => setConfirmDelete(n)}>삭제</button>
        </div>
      </td>
    </tr>,
    ...renderRows(n.children),
  ])

  const pathOf = (id: number | null): string => {
    if (id == null) return '최상위'
    const n = findCategory(tree, id); if (!n) return ''
    const names: string[] = []; let cur: CategoryNode | undefined = n
    while (cur) { names.unshift(cur.name); cur = cur.parentId == null ? undefined : findCategory(tree, cur.parentId) }
    return names.join(' › ')
  }

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <h2>카테고리 관리</h2>
          <p>최대 {CATEGORY_MAX_DEPTH}단계까지 만들고 연결할 수 있습니다. 상위 카테고리를 고르면 하위 글까지 함께 보입니다. 전체 {total}개</p>
        </div>
        <button type="button" className="btn btn--primary" onClick={() => openNew(null)}>최상위 카테고리 추가</button>
      </div>

      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>이름</th><th>발행 글</th><th style={{ textAlign: 'right' }}>작업</th></tr></thead>
          <tbody>
            {renderRows(tree)}
            {tree.length === 0 && <tr><td colSpan={3} className="empty">카테고리가 없습니다. 최상위 카테고리를 먼저 만드세요.</td></tr>}
          </tbody>
        </table>
      </div>

      {dialog && (
        <Modal title={dialog.kind === 'new' ? (dialog.parent ? `'${dialog.parent.name}' 아래에 추가` : '최상위 카테고리 추가') : `'${dialog.node.name}' 편집`} onClose={() => setDialog(null)}
               footer={<><button type="button" className="btn" onClick={() => setDialog(null)}>취소</button><button type="submit" form="cat-form" className="btn btn--primary" disabled={busy}>{busy ? '저장 중…' : dialog.kind === 'new' ? '만들기' : '저장'}</button></>}>
          <form id="cat-form" onSubmit={submit} className="stack">
            <div className="field">
              <label htmlFor="c-name">이름</label>
              <input id="c-name" className="input" value={name} onChange={(e) => setName(e.target.value)} required maxLength={50} autoFocus />
            </div>
            <div className="field">
              <label htmlFor="c-parent">상위 카테고리</label>
              <select id="c-parent" className="select" value={parentId ?? ''} onChange={(e) => setParentId(e.target.value === '' ? null : Number(e.target.value))}>
                <option value="">최상위 (1단계)</option>
                {parentOptions.map((n) => <option key={n.id} value={n.id}>{'　'.repeat(n.depth - 1)}{n.name} ({n.depth}단계)</option>)}
              </select>
              <span className="field__hint">
                {dialog.kind === 'edit' && parentId !== dialog.node.parentId ? `하위 ${subtreeHeight(dialog.node) - 1}단계를 포함해 '${pathOf(parentId)}' 아래로 옮깁니다. ` : ''}
                이 위치에서 새 깊이는 {(parentId == null ? 0 : (findCategory(tree, parentId)?.depth ?? 0)) + 1}단계입니다. {CATEGORY_MAX_DEPTH}단계를 넘는 위치는 목록에 없습니다.
              </span>
            </div>
            {dialog.kind === 'edit' && (
              <div className="field">
                <label htmlFor="c-depts">열람 부서 제한 <span className="muted" style={{ fontWeight: 400 }}>(선택)</span></label>
                <input id="c-depts" className="input" value={depts} onChange={(e) => setDepts(e.target.value)} maxLength={1000} placeholder="예) 인프라팀, 운영팀 (비우면 전체 공개)" />
                <span className="field__hint">
                  쉼표로 구분한 부서의 구성원만 이 카테고리와 하위 카테고리의 글을 보고 검색할 수 있습니다. 작성자와 관리자는 항상 봅니다.
                  서버 정보처럼 민감한 글에 쓰세요. 부서 이름은 사용자 정보의 부서와 똑같이 적어야 합니다(대소문자·앞뒤 공백 무시).
                </span>
              </div>
            )}
            {err && <div className="alert alert--error">{err}</div>}
          </form>
        </Modal>
      )}

      {confirmDelete && (
        <Modal title={`'${confirmDelete.name}' 카테고리를 삭제할까요?`} onClose={() => setConfirmDelete(null)}
               footer={<><button type="button" className="btn" onClick={() => setConfirmDelete(null)}>취소</button><button type="button" className="btn btn--danger" disabled={busy} onClick={remove}>삭제</button></>}>
          <p>하위 카테고리나 연결된 글이 있으면 삭제되지 않습니다. 먼저 옮기거나 지워 주세요. 글의 변경 이력에는 이 카테고리 이름이 그대로 남습니다.</p>
        </Modal>
      )}
    </div>
  )
}
