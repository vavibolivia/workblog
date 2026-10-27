import { Suspense, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, NavLink, Outlet, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { Avatar } from './Avatar'
import { BrandMark } from './Icons'

function SearchIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
    </svg>
  )
}

export default function Layout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [q, setQ] = useState(params.get('q') ?? '')
  const [open, setOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => { setQ(params.get('q') ?? '') }, [params])

  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => { if (!menuRef.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDoc); document.removeEventListener('keydown', onKey) }
  }, [open])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const term = q.trim()
    navigate(term ? `/search?q=${encodeURIComponent(term)}` : '/')
  }

  return (
    <>
      <header className="gnb">
        <div className="gnb__inner">
          <Link to="/" className="brand" aria-label="WorkBlog 홈">
            <span className="brand__mark" aria-hidden><BrandMark size={32} /></span>
            <span className="brand__name">WorkBlog</span>
            <span className="brand__sub">사내 지식 공유</span>
          </Link>

          <form className="gnb__search" role="search" onSubmit={submit}>
            <SearchIcon />
            <input className="input" value={q} onChange={(e) => setQ(e.target.value)}
                   placeholder="글, 태그, 작성자를 검색" aria-label="검색" />
          </form>

          <nav className="gnb__nav" aria-label="주 메뉴">
            <NavLink to="/" end className="gnb__link">홈</NavLink>
            <NavLink to="/history" className="gnb__link">이력</NavLink>
            {user?.role === 'ADMIN' && <NavLink to="/admin/users" className="gnb__link">사용자</NavLink>}
            {user?.role === 'ADMIN' && <NavLink to="/admin/categories" className="gnb__link">카테고리</NavLink>}
            <NavLink to="/help" className="gnb__link">도움말</NavLink>
            <Link to="/write" className="btn btn--primary btn--sm" style={{ marginLeft: 8 }}>글쓰기</Link>

            <div className="gnb__user" ref={menuRef}>
              <button type="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
                <Avatar name={user?.displayName ?? '?'} />
              </button>
              {open && user && (
                <div className="menu" role="menu">
                  <div className="menu__head">
                    <strong>{user.displayName}</strong>
                    <span className="small muted">{user.department ?? '부서 미지정'} · {user.role === 'ADMIN' ? '관리자' : '구성원'}</span>
                  </div>
                  <Link to="/write" role="menuitem" className="menu__mobile" onClick={() => setOpen(false)}>글쓰기</Link>
                  <Link to="/history" role="menuitem" className="menu__mobile" onClick={() => setOpen(false)}>변경 이력</Link>
                  {user.role === 'ADMIN' && <Link to="/admin/users" role="menuitem" className="menu__mobile" onClick={() => setOpen(false)}>사용자 관리</Link>}
                  {user.role === 'ADMIN' && <Link to="/admin/categories" role="menuitem" className="menu__mobile" onClick={() => setOpen(false)}>카테고리 관리</Link>}
                  <Link to="/help" role="menuitem" className="menu__mobile" onClick={() => setOpen(false)}>도움말</Link>
                  <Link to={`/?authorId=${user.id}`} role="menuitem" onClick={() => setOpen(false)}>내가 쓴 글</Link>
                  <Link to={`/?authorId=${user.id}&status=DRAFT`} role="menuitem" onClick={() => setOpen(false)}>임시 저장 글</Link>
                  <Link to="/trash" role="menuitem" onClick={() => setOpen(false)}>삭제한 글</Link>
                  <Link to="/me" role="menuitem" onClick={() => setOpen(false)}>내 정보</Link>
                  <button type="button" role="menuitem" className="menu__danger" onClick={() => { setOpen(false); logout(); navigate('/login') }}>로그아웃</button>
                </div>
              )}
            </div>
          </nav>
        </div>
      </header>
      <Suspense fallback={<div className="screen-center" style={{ minHeight: '50vh' }}><div className="spinner" aria-label="불러오는 중" /></div>}>
        <Outlet />
      </Suspense>
    </>
  )
}
