import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, findCategory, type CategoryNode, type TagCount } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Avatar } from './Avatar'
import CategoryTree from './CategoryTree'

/** 네이버 블로그의 왼쪽 프로필 + 카테고리 영역. 전사 지식 공간이므로 프로필은 "로그인한 나", 카테고리는 전사 트리. */
export default function Sidebar() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const [tree, setTree] = useState<CategoryNode[]>([])
  const [tags, setTags] = useState<TagCount[]>([])
  const [myCount, setMyCount] = useState<number | null>(null)
  const [draftCount, setDraftCount] = useState<number | null>(null)

  useEffect(() => {
    api.categories.tree().then(setTree).catch(() => setTree([]))
    api.taxonomy.tags(24).then(setTags).catch(() => setTags([]))
    if (user) {
      api.posts.list({ authorId: user.id, size: 1 }).then((p) => setMyCount(p.totalElements)).catch(() => null)
      api.posts.list({ authorId: user.id, status: 'DRAFT', size: 1 }).then((p) => setDraftCount(p.totalElements)).catch(() => null)
    }
  }, [user])

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
          <div className="row row--between"><h4>카테고리</h4>{user?.role === 'ADMIN' && <Link to="/admin/categories" className="small" style={{ color: 'var(--sky)', fontWeight: 600 }}>관리</Link>}</div>
          <ul className="cat-tree">
            <li><div className={`cat-tree__row cat-tree__row--d1${isAll ? ' active' : ''}`}><span className="cat-tree__toggle" aria-hidden>·</span><Link to="/" className="cat-tree__link"><span>전체 보기</span><span className="cat-tree__count">{total}</span></Link></div></li>
          </ul>
          <CategoryTree nodes={tree} activeId={activeId} activePathIds={pathIds} />
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

        <div className="side-write">
          <Link to="/write" className="btn btn--sky btn--block">새 글 쓰기</Link>
        </div>
      </div>
    </aside>
  )
}
