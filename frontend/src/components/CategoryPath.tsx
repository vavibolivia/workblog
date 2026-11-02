import { Link } from 'react-router-dom'
import type { CategoryRef } from '../lib/api'
import { useCategoryKinds } from '../lib/categoryLeaves'

/**
 * "정비 › 엔진 › A320" 각 단계가 그 단계의 글 목록으로 가는 링크.
 * 마지막 단계(글이 달린 카테고리) 앞에 사이드바 트리와 같은 표식: 끝(leaf) = 속 빈 원, 하위 있음 = ▸.
 */
export default function CategoryPath({ category, className = '' }: { category: CategoryRef; className?: string }) {
  const kinds = useCategoryKinds()
  const kind = kinds.get(category.id)
  return (
    <span className={`cat-path ${className}`.trim()}>
      {category.path.map((name, i) => (
        <span key={category.pathIds[i]}>
          {i > 0 && <span className="cat-path__sep" aria-hidden>›</span>}
          <Link to={`/?categoryId=${category.pathIds[i]}`}>
            {i === category.path.length - 1 && kind && (
              <span className={`cat-kind cat-kind--${kind}`} aria-label={kind === 'leaf' ? '끝 카테고리' : '하위 카테고리 있음'}
                    title={kind === 'leaf' ? '끝 카테고리 (하위 없음)' : '하위 카테고리가 있는 카테고리'}>{kind === 'branch' ? '▸' : ''}</span>
            )}
            {name}
          </Link>
        </span>
      ))}
    </span>
  )
}
