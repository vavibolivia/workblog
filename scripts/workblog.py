#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WorkBlog CLI — 다른 Claude Code·스크립트에서 글을 읽고 쓰고 고친다. 표준 라이브러리만 쓴다(설치 불필요).

설정(환경변수 또는 옵션):
  WORKBLOG_URL    서버 주소     (기본: 이 스크립트를 받은 서버)        --url
  WORKBLOG_TOKEN  개인 API 토큰 (wbt_... , 화면 '내 정보 > API 토큰') --token
  (토큰이 없으면 WORKBLOG_USER / WORKBLOG_PASSWORD 로 로그인해 JWT 를 쓴다)

사용 예:
  python workblog.py me
  python workblog.py categories
  python workblog.py list --q "VPN" --size 20
  python workblog.py get 31                          # 글 1건(JSON)
  python workblog.py get 31 --text                   # 본문을 평문으로
  python workblog.py export 31 --format md --out 글.md
  python workblog.py create --title "제목" --file 본문.html --category "서버 정보 > AWS" --tags a,b
  python workblog.py create --title "제목" --markdown 본문.md
  python workblog.py update 31 --title "새 제목"                      # 부분 수정(PATCH)
  python workblog.py update 31 --file 본문.html --note "절차 수정"
  python workblog.py import 문서.docx --category "운영 절차" --tags 배포   # 파일로 새 글
  python workblog.py replace 31 문서.xlsx                               # 파일로 본문 교체
  python workblog.py upload 사진.png                                     # 첨부 업로드 → url
  python workblog.py delete 31
  python workblog.py revisions 31 / python workblog.py restore 31 2
  python workblog.py comment 31 "확인했습니다."
"""
import argparse, json, mimetypes, os, sys, urllib.error, urllib.parse, urllib.request, uuid

# Windows 콘솔(cp949)에서도 한글·기호가 깨지지 않게
for _s in (sys.stdout, sys.stderr):
    try: _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception: pass

# 서버가 /api/guide/workblog.py 로 줄 때 {{BASE_URL}} 이 그 서버 주소로 바뀐다. 저장소 원본은 localhost.
DEFAULT_URL = "{{BASE_URL}}" if not "{{BASE_URL}}".startswith("{{") else "http://localhost:8080"


class Client:
    def __init__(self, base, token=None, user=None, password=None):
        self.base = base.rstrip("/")
        self.token = token
        if not self.token and user and password:
            self.token = self.call("POST", "/api/auth/login", {"username": user, "password": password})["token"]
        if not self.token:
            sys.exit("토큰이 없습니다. WORKBLOG_TOKEN(개인 API 토큰) 또는 WORKBLOG_USER/WORKBLOG_PASSWORD 를 설정하세요.")

    def call(self, method, path, body=None, params=None, raw=False):
        url = self.base + path
        if params:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, method=method, data=data)
        if data is not None:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        return self._send(req, raw)

    def multipart(self, method, path, file_path, fields=None):
        boundary = "----wb" + uuid.uuid4().hex
        name = os.path.basename(file_path)
        ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
        parts = []
        for k, v in (fields or {}).items():
            if v is None:
                continue
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode("utf-8"))
        with open(file_path, "rb") as f:
            content = f.read()
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: {ctype}\r\n\r\n".encode("utf-8") + content + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode("utf-8"))
        req = urllib.request.Request(self.base + path, method=method, data=b"".join(parts))
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        req.add_header("Authorization", "Bearer " + self.token)
        return self._send(req, False)

    @staticmethod
    def _send(req, raw):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                b = r.read()
                if raw:
                    return b, r.headers
                t = b.decode("utf-8")
                return json.loads(t) if t else None
        except urllib.error.HTTPError as e:
            t = e.read().decode("utf-8", "replace")
            try:
                msg = json.loads(t).get("message", t)
            except Exception:
                msg = t
            sys.exit(f"HTTP {e.code} {req.full_url}: {msg}")
        except urllib.error.URLError as e:
            sys.exit(f"서버에 연결할 수 없습니다: {req.full_url} ({e.reason})")


def out(obj):
    print(json.dumps(obj, ensure_ascii=False, indent=2))


def read_text(path):
    with open(path, "rb") as f:
        b = f.read()
    try:
        return b.decode("utf-8-sig")
    except UnicodeDecodeError:
        return b.decode("cp949")


def main():
    ap = argparse.ArgumentParser(description="WorkBlog CLI", formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("--url", default=os.environ.get("WORKBLOG_URL", DEFAULT_URL))
    ap.add_argument("--token", default=os.environ.get("WORKBLOG_TOKEN"))
    ap.add_argument("--user", default=os.environ.get("WORKBLOG_USER"))
    ap.add_argument("--password", default=os.environ.get("WORKBLOG_PASSWORD"))
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("me", help="내 계정")
    sub.add_parser("categories", help="카테고리 트리")
    p = sub.add_parser("list", help="글 목록·검색"); p.add_argument("--q"); p.add_argument("--category", type=int, dest="categoryId"); p.add_argument("--tag"); p.add_argument("--author", type=int, dest="authorId"); p.add_argument("--status", choices=["DRAFT", "PUBLISHED"]); p.add_argument("--page", type=int, default=0); p.add_argument("--size", type=int, default=20)
    p = sub.add_parser("get", help="글 1건"); p.add_argument("id", type=int); p.add_argument("--text", action="store_true", help="본문을 평문으로 출력")
    p = sub.add_parser("export", help="내보내기(html|md|docx)"); p.add_argument("id", type=int); p.add_argument("--format", default="md", choices=["html", "md", "docx"]); p.add_argument("--out"); p.add_argument("--no-attachments", action="store_true")
    for name, help_ in (("create", "새 글"), ("update", "글 부분 수정(PATCH): 준 항목만 바뀐다")):
        p = sub.add_parser(name, help=help_)
        if name == "update":
            p.add_argument("id", type=int)
        p.add_argument("--title"); p.add_argument("--file", help="본문 HTML 파일"); p.add_argument("--markdown", help="본문 Markdown 파일"); p.add_argument("--content", help="본문 HTML 문자열")
        p.add_argument("--category", help="카테고리 경로 '상위 > 하위' 또는 id"); p.add_argument("--tags", help="쉼표 구분"); p.add_argument("--summary"); p.add_argument("--status", choices=["DRAFT", "PUBLISHED"]); p.add_argument("--visibility", choices=["PUBLIC", "PRIVATE"]); p.add_argument("--pinned", choices=["true", "false"]); p.add_argument("--note", help="변경 메모")
    p = sub.add_parser("import", help="파일(HTML·docx·xlsx·csv·md·txt)로 새 글"); p.add_argument("path"); p.add_argument("--title"); p.add_argument("--category"); p.add_argument("--tags"); p.add_argument("--summary"); p.add_argument("--status", default="PUBLISHED"); p.add_argument("--visibility", default="PUBLIC"); p.add_argument("--no-original", action="store_true", help="원본 파일을 첨부하지 않음")
    p = sub.add_parser("replace", help="파일 내용으로 기존 글 본문 교체"); p.add_argument("id", type=int); p.add_argument("path"); p.add_argument("--title"); p.add_argument("--note"); p.add_argument("--no-original", action="store_true")
    p = sub.add_parser("upload", help="첨부 파일 업로드"); p.add_argument("path")
    p = sub.add_parser("delete", help="글 삭제(휴지통)"); p.add_argument("id", type=int); p.add_argument("--purge", action="store_true", help="관리자: 휴지통의 글을 영구 삭제")
    p = sub.add_parser("undelete", help="삭제 취소"); p.add_argument("id", type=int)
    p = sub.add_parser("revisions", help="변경 이력"); p.add_argument("id", type=int)
    p = sub.add_parser("restore", help="이전 버전으로 복원"); p.add_argument("id", type=int); p.add_argument("version", type=int)
    p = sub.add_parser("comment", help="댓글 달기"); p.add_argument("id", type=int); p.add_argument("text"); p.add_argument("--parent", type=int)
    p = sub.add_parser("comments", help="댓글 목록"); p.add_argument("id", type=int)

    a = ap.parse_args()
    c = Client(a.url, a.token, a.user, a.password)

    def post_body():
        body = {}
        if a.title is not None: body["title"] = a.title
        if a.file: body["content"] = read_text(a.file); body["contentFormat"] = "HTML"
        elif a.markdown: body["content"] = read_text(a.markdown); body["contentFormat"] = "MARKDOWN"
        elif a.content is not None: body["content"] = a.content; body["contentFormat"] = "HTML"
        if a.category:
            if a.category.isdigit(): body["categoryId"] = int(a.category)
            else: body["categoryPath"] = a.category
        if a.tags is not None: body["tags"] = [t.strip() for t in a.tags.split(",") if t.strip()]
        if a.summary is not None: body["summary"] = a.summary
        if a.status: body["status"] = a.status
        if a.visibility: body["visibility"] = a.visibility
        if a.pinned: body["pinned"] = a.pinned == "true"
        if a.note: body["changeNote"] = a.note
        # 본문에 넣은 사진(/api/files/<id>)은 첨부로 연결해야 글에 남는다(연결 안 된 업로드는 자동 정리 대상)
        if "content" in body:
            import re
            ids = sorted(set(re.findall(r"/api/files/([0-9a-f-]{36})", body["content"])))
            if ids: body["attachmentIds"] = ids
        return body

    if a.cmd == "me": out(c.call("GET", "/api/auth/me"))
    elif a.cmd == "categories":
        def walk(nodes, d=0):
            for n in nodes:
                print(f"{'  ' * d}{n['id']:>4}  {n['name']}  ({n['totalCount']})"); walk(n["children"], d + 1)
        walk(c.call("GET", "/api/categories"))
    elif a.cmd == "list":
        r = c.call("GET", "/api/posts", params={"q": a.q, "categoryId": a.categoryId, "tag": a.tag, "authorId": a.authorId, "status": a.status, "page": a.page, "size": a.size})
        for p_ in r["content"]:
            print(f"{p_['id']:>5}  {' > '.join(p_['category']['path'])}  |  {p_['title']}  [{p_['status']}] v{p_['version']} {p_['author']['displayName']}")
        print(f"-- {r['totalElements']}건, 페이지 {r['page'] + 1}/{max(r['totalPages'], 1)}")
    elif a.cmd == "get":
        r = c.call("GET", f"/api/posts/{a.id}", params={"view": "false"})
        if a.text:
            import html as H, re
            t = re.sub(r"</(p|div|h\d|li|tr|blockquote|pre|figcaption)>", "\n", r["content"]); t = re.sub(r"<br\s*/?>", "\n", t); t = re.sub(r"</t[dh]>", "\t", t)
            print(f"# {r['title']}\n({' > '.join(r['category']['path'])} · {r['author']['displayName']} · v{r['version']})\n")
            print(H.unescape(re.sub(r"<[^>]+>", "", t)).strip() if r["contentFormat"] == "HTML" else r["content"])
        else: out(r)
    elif a.cmd == "export":
        b, h = c.call("GET", f"/api/posts/{a.id}/export", params={"format": a.format, "attachments": "false" if a.no_attachments else "true"}, raw=True)
        name = a.out or f"post-{a.id}.{a.format}"
        with open(name, "wb") as f: f.write(b)
        print(f"저장: {name} ({len(b)} bytes)")
    elif a.cmd == "create":
        body = post_body()
        if "title" not in body or "content" not in body: sys.exit("--title 과 본문(--file/--markdown/--content)이 필요합니다.")
        body.setdefault("tags", []); body.setdefault("status", "PUBLISHED")
        r = c.call("POST", "/api/posts", body); print(f"만들었습니다: id={r['id']} {c.base}/posts/{r['id']}")
    elif a.cmd == "update":
        body = post_body()
        if not body: sys.exit("바꿀 항목을 하나 이상 주세요.")
        r = c.call("PATCH", f"/api/posts/{a.id}", body); print(f"고쳤습니다: id={r['id']} v{r['version']} {c.base}/posts/{r['id']}")
    elif a.cmd == "import":
        fields = {"title": a.title, "tags": a.tags, "summary": a.summary, "status": a.status, "visibility": a.visibility, "attachOriginal": "false" if a.no_original else "true"}
        if a.category: fields["categoryId" if a.category.isdigit() else "categoryPath"] = a.category
        r = c.multipart("POST", "/api/posts/import", a.path, fields)
        print(f"만들었습니다: id={r['post']['id']} ({r['sourceType']}) {c.base}/posts/{r['post']['id']}")
        for w in r["warnings"]: print("  안내:", w)
    elif a.cmd == "replace":
        r = c.multipart("PUT", f"/api/posts/{a.id}/import", a.path, {"title": a.title, "changeNote": a.note, "attachOriginal": "false" if a.no_original else "true"})
        print(f"교체했습니다: id={r['post']['id']} v{r['post']['version']}")
        for w in r["warnings"]: print("  안내:", w)
    elif a.cmd == "upload":
        r = c.multipart("POST", "/api/files", a.path); print(f"{r['url']}  ({r['kind']}, {r['size']} bytes, id={r['id']})")
    elif a.cmd == "delete":
        c.call("DELETE", f"/api/posts/{a.id}", params={"purge": "true"} if a.purge else None); print("영구 삭제했습니다." if a.purge else "휴지통으로 옮겼습니다.")
    elif a.cmd == "undelete": r = c.call("POST", f"/api/posts/{a.id}/undelete"); print(f"복구했습니다: id={r['id']}")
    elif a.cmd == "revisions":
        for r in c.call("GET", f"/api/posts/{a.id}/revisions"): print(f"v{r['version']:<3} {r['changeType']:<9} {r['createdAt'][:16]} {r['editedBy']['displayName']}  {r['changeNote'] or ''}")
    elif a.cmd == "restore": r = c.call("POST", f"/api/posts/{a.id}/revisions/{a.version}/restore"); print(f"복원했습니다: 지금 v{r['version']}")
    elif a.cmd == "comment": r = c.call("POST", f"/api/posts/{a.id}/comments", {"content": a.text, "parentId": a.parent}); print(f"댓글 id={r['id']}")
    elif a.cmd == "comments":
        for r in c.call("GET", f"/api/posts/{a.id}/comments"): print(f"{'  ↳ ' if r['parentId'] else ''}[{r['id']}] {r['author']['displayName']}: {r['content'] if not r['deleted'] else '(삭제됨)'}")


if __name__ == "__main__":
    main()
