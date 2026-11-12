/** About: 프로그램 소개와 원작자 정보 */
export default function AboutPage() {
  return (
    <div className="shell">
      <section>
        <div className="page-head">
          <h2>About</h2>
          <p>WorkBlog — 업무 지식을 블로그처럼 쓰고, 한곳에서 찾고, 이력으로 남기는 사내 지식 공유 블로그</p>
        </div>
        <div className="card">
          <div className="card__head"><h3>원작자</h3></div>
          <div className="card__body">
            <table className="kv"><tbody>
              <tr><th>만든 사람</th><td>정석호 (Sukho Jung)</td></tr>
              <tr><th>메일</th><td><a href="mailto:vavibolivia@gmail.com" style={{ color: 'var(--sky)' }}>vavibolivia@gmail.com</a></td></tr>
              <tr><th>저장소</th><td><a href="https://github.com/vavibolivia/workblog" target="_blank" rel="noreferrer" style={{ color: 'var(--sky)' }}>github.com/vavibolivia/workblog</a></td></tr>
            </tbody></table>
          </div>
        </div>
      </section>
    </div>
  )
}
