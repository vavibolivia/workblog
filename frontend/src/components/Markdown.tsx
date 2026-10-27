import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export default function Markdown({ children, className = '' }: { children: string; className?: string }) {
  return (
    <div className={`md ${className}`.trim()}>
      <ReactMarkdown remarkPlugins={[remarkGfm]}
        components={{
          // 내부 링크(`/`로 시작)는 같은 창, 그 밖의 모든 링크는 새 창(R11)
          a: ({ node: _n, ...props }) => props.href?.startsWith('/')
            ? <a {...props} />
            : <a {...props} target="_blank" rel="noopener noreferrer" />,
        }}>
        {children}
      </ReactMarkdown>
    </div>
  )
}
