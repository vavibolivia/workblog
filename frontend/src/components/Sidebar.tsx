import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { api, errorMessage, findCategory, type CategoryNode, type TagCount } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'
import { Avatar } from './Avatar'
import CategoryTree from './CategoryTree'
import { publishCategoryTree } from '../lib/categoryLeaves'

/** 네이버 블로그의 왼쪽 프로필 + 카테고리 영역. 전사 지식 공간이므로 프로필은 "로그인한 나", 카테고리는 전사 트리. */
export default function Sidebar() {
  const { user } = useAuth()
  const toast = useToast()
  const [params] = useSearchParams()
  const { pathname } = useLocation()
  const [tree, setTree] = useState<CategoryNode[]>([])
  const [tags, setTags] = useState<TagCount[]>([])
  const [myCount, setMyCount] = useState<number | null>(null)
  const [draftCount, setDraftCount] = useState<number | null>(null)

  // 첫 렌더의 빈 트리는 알리지 않는다(다시 마운트될 때 카테고리 표식이 깜빡임)
  useEffect(() => { if (tree.length) publishCategoryTree(tree) }, [tree])
  const reloadTree = () => { api.categories.tree().then(setTree).catch(() => setTree([])) }
  // 다른 화면(홈 가운데 제목 편집 등)에서 카테고리를 바꾸면 알려 주는 이벤트 → 트리 다시 읽기
  useEffect(() => { const h = () => reloadTree(); window.addEventListener('workblog:categories-changed', h); return () => window.removeEventListener('workblog:categories-changed', h) }, [])

  useEffect(() => {
    api.categories.tree().then(setTree).catch(() => setTree([]))
    api.taxonomy.tags(24).then(setTags).catch(() => setTags([]))
    if (user) {
      api.posts.list({ authorId: user.id, size: 1 }).then((p) => setMyCount(p.totalElements)).catch(() => null)
      api.posts.list({ authorId: user.id, status: 'DRAFT', size: 1 }).then((p) => setDraftCount(p.totalElements)).catch(() => null)
    }
  }, [user, pathname])

  // 최상위 카테고리 만들기: 로그인 사용자 모두(하위는 트리의 ⋯ 메뉴)
  const addRoot = () => {
    const name = window.prompt('만들 최상위 카테고리 이름')?.trim()
    if (!name) return
    api.categories.create(name, null)
      .then(() => { toast(`'${name}'을(를) 만들었습니다.`, 'success'); reloadTree() })
      .catch((e) => toast(errorMessage(e), 'error'))
  }

  const total = tree.reduce((s, c) => s + c.totalCount, 0)
  const activeId = params.get('categoryId') ? Number(params.get('categoryId')) : null
  const activeTag = params.get('tag')
  const pathIds: number[] = []
  if (activeId != null) {
    // 루트에서 활성 노드까지의 id 경로
    const walk = (nodes: CategoryNode[], trail: number[]): boolean => {
      for (const n of nodes) { if (n.id === activeId) { pathIds.push(...trail, n.id); return true } if (walk(n.children, [...trail, n.id])) return true }
      return false
    }
    walk(tree, [])
  }
  const isAll = !activeId && !activeTag && !params.get('authorId') && !params.get('status')

  return (
    <aside className="shell__aside">
      <div className="profile">
        <div className="profile__head">
          <Avatar name={user?.displayName ?? '?'} size="lg" />
          <div>
            <div className="profile__name">{user?.displayName}</div>
            <div className="profile__dept">{user?.department ?? '부서 미지정'}{user?.role === 'ADMIN' ? ' · 관리자' : ''}</div>
          </div>
        </div>
        <div className="profile__stats">
          <Link to="/"><strong>{total}</strong>전체 글</Link>
          <Link to={`/?authorId=${user?.id}`}><strong>{myCount ?? '–'}</strong>내 글</Link>
          <Link to={`/?authorId=${user?.id}&status=DRAFT`}><strong>{draftCount ?? '–'}</strong>임시 저장</Link>
        </div>

        <div className="side-section">
          <div className="row row--between side-section__head"><h4>카테고리</h4>
            <span className="side-section__tools">
              <button type="button" className="small side-section__link side-section__btn" title="최상위 카테고리 만들기 (하위는 카테고리의 ⋯ 메뉴에서)" onClick={addRoot}>+ 추가</button>
              {user?.role === 'ADMIN' && <Link to="/admin/categories" className="small muted side-section__link">관리</Link>}
            </span>
          </div>
          <ul className="cat-tree">
            <li><div className={`cat-tree__row cat-tree__row--d1 cat-tree__row--all${isAll ? ' active' : ''}`}><span className="cat-tree__toggle" aria-hidden>·</span><Link to="/" className="cat-tree__link"><span>전체 보기</span><span className="cat-tree__count">{total}</span></Link></div></li>
          </ul>
          <CategoryTree nodes={tree} activeId={activeId} activePathIds={pathIds} onChanged={reloadTree} />
          {tree.length === 0 && <p className="small muted" style={{ padding: '6px 0' }}>카테고리가 없습니다.</p>}
          {activeId != null && findCategory(tree, activeId) === undefined && tree.length > 0 && <p className="small muted">선택한 카테고리를 찾을 수 없습니다.</p>}
        </div>

        {tags.length > 0 && (
          <div className="side-section">
            <h4>태그</h4>
            <div className="tag-cloud">
              {tags.map((t) => (
                <Link key={t.name} to={`/?tag=${encodeURIComponent(t.name)}`}
                      className={`chip${activeTag === t.name ? '' : ' chip--neutral'}`}>#{t.name}</Link>
              ))}
            </div>
          </div>
        )}
      </div>
    </aside>
  )
}
