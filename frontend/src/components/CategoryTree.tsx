import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { CategoryNode } from '../lib/api'

/** 사이드바용 접고 펴는 카테고리 트리. 글 수는 하위 포함(totalCount). */
export default function CategoryTree({ nodes, activeId, activePathIds = [] }:
  { nodes: CategoryNode[]; activeId: number | null; activePathIds?: number[] }) {
  return <ul className="cat-tree">{nodes.map((n) => <Node key={n.id} node={n} activeId={activeId} activePathIds={activePathIds} />)}</ul>
}

/**
 * 펼침 상태 규칙(R10)
 * - 기본: 활성 경로에 있거나 1단계면 펼침.
 * - 사용자가 직접 토글하면 그 값이 우선한다(활성 경로여도 접을 수 있음).
 * - 다른 곳에서 이동해 와 이 노드가 새로 활성 경로에 들어오면 사용자 토글을 지우고 다시 펼친다.
 */
function Node({ node, activeId, activePathIds }: { node: CategoryNode; activeId: number | null; activePathIds: number[] }) {
  const onPath = activePathIds.includes(node.id)
  const active = activeId === node.id
  const hasKids = node.children.length > 0
  const [userOpen, setUserOpen] = useState<boolean | null>(null)
  const [prevOnPath, setPrevOnPath] = useState(onPath)
  if (onPath !== prevOnPath) {
    setPrevOnPath(onPath)
    if (onPath) setUserOpen(null)
  }
  const expanded = userOpen ?? (onPath || node.depth === 1)

  // 이름 클릭: 이동 + 접힘이면 펼치고, 이미 펼쳐진 활성 노드면 접는다(펼쳐진 다른 노드는 그대로 두고 이동만).
  const onNameClick = () => {
    if (!hasKids) return
    if (!expanded) setUserOpen(true)
    else if (active) setUserOpen(false)
  }

  return (
    <li className={onPath ? 'on-path' : undefined} data-depth={node.depth}>
      {/* R21: 선택한 카테고리까지의 경로(루트→depth)를 단계별 색으로 표시 */}
      <div className={`cat-tree__row cat-tree__row--d${node.depth}${active ? ' active' : ''}${onPath && !active ? ' on-path' : ''}`}>
        {hasKids
          ? <button type="button" className="cat-tree__toggle" aria-label={expanded ? '접기' : '펼치기'} aria-expanded={expanded} onClick={() => setUserOpen(!expanded)}>{expanded ? '▾' : '▸'}</button>
          : <span className="cat-tree__toggle" aria-hidden>·</span>}
        <Link to={`/?categoryId=${node.id}`} className="cat-tree__link" data-depth={node.depth} onClick={onNameClick}>
          <span className="ellipsis">{node.name}</span>
          <span className="cat-tree__count">{node.totalCount}</span>
        </Link>
      </div>
      {hasKids && expanded && (
        <ul>{node.children.map((c) => <Node key={c.id} node={c} activeId={activeId} activePathIds={activePathIds} />)}</ul>
      )}
    </li>
  )
}
