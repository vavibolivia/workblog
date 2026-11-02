import { useSyncExternalStore } from 'react'
import type { CategoryNode } from './api'

/**
 * 카테고리 끝(leaf) 여부 공유 저장소. 사이드바가 트리를 불러올 때 publish 하고,
 * 글 목록·경로 표시는 useCategoryKind 로 "leaf | branch | unknown" 을 읽는다(추가 요청 없음).
 */
let kinds = new Map<number, 'leaf' | 'branch'>()
const listeners = new Set<() => void>()

export function publishCategoryTree(tree: CategoryNode[]) {
  const next = new Map<number, 'leaf' | 'branch'>()
  const walk = (ns: CategoryNode[]) => ns.forEach((n) => { next.set(n.id, n.children.length ? 'branch' : 'leaf'); walk(n.children) })
  walk(tree)
  kinds = next
  listeners.forEach((l) => l())
}

function subscribe(l: () => void) { listeners.add(l); return () => { listeners.delete(l) } }

export function useCategoryKinds() {
  return useSyncExternalStore(subscribe, () => kinds)
}
