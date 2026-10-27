import { Link } from 'react-router-dom'
import type { CategoryRef } from '../lib/api'

/** "정비 › 엔진 › A320" 각 단계가 그 단계의 글 목록으로 가는 링크. */
export default function CategoryPath({ category, className = '' }: { category: CategoryRef; className?: string }) {
  return (
    <span className={`cat-path ${className}`.trim()}>
      {category.path.map((name, i) => (
        <span key={category.pathIds[i]}>
          {i > 0 && <span className="cat-path__sep" aria-hidden>›</span>}
          <Link to={`/?categoryId=${category.pathIds[i]}`}>{name}</Link>
        </span>
      ))}
    </span>
  )
}
