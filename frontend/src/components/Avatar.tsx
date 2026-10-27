export function Avatar({ name, size }: { name: string; size?: 'xs' | 'lg' }) {
  const initial = (name || '?').trim().slice(0, 1).toUpperCase()
  return <span className={`avatar${size ? ` avatar--${size}` : ''}`} aria-hidden>{initial}</span>
}
