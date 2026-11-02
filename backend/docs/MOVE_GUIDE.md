# 다른 서버에 데이터와 함께 설치하기

지금 쓰는 WorkBlog 의 글·카테고리·사용자·첨부를 그대로 가지고 **다른 서버(다른 망 포함)에 새로 설치**하는 절차입니다.

- 대상: 관리자(설치 담당)
- 기준 버전: v0.1.1 — **두 서버의 WorkBlog 버전을 같게** 맞춘 뒤 진행

## 1. 방법 고르기

| | A. 데이터 이전 (권장) | B. 통째 복제 (DB 복원) |
|---|---|---|
| 쓰는 것 | 화면 **관리자 › 데이터 이전** 또는 CLI `dump`·`load` | `pg_dump` · `pg_restore` · 첨부 폴더 압축 |
| 받는 서버 | 새로 설치한 서버 (이미 쓰던 서버도 가능) | 새로 설치한 **빈** 서버 |
| 결과 | 병합 — 받는 서버 데이터는 지우지 않음 | 원래 서버와 똑같은 사본 (받는 서버 데이터는 지워짐) |
| 이후 변경분 | 업데이트 덤프로 계속 반영 가능 | 다시 통째 복제 |
| 받는 서버 admin | 받는 서버 값 유지 | 원래 서버 admin 비밀번호로 바뀜 |
| 원래 서버 API 토큰 | 원래 있던 계정(admin 등)의 토큰은 옮기지 않음 | 모두 그대로 |
| 필요 권한 | WorkBlog 관리자 계정 | 두 서버의 OS·DB 관리자 |

- 망이 다르거나, 옮긴 뒤에도 원래 서버에서 계속 쓰고 변경분만 넘길 거라면 → **A**
- 서버를 완전히 바꾸고(원래 서버는 끝냄) 계정·토큰까지 똑같이 옮길 거라면 → **B**

## 2. 받는 서버 설치 (A·B 공통)

| 환경 | 명령 |
|---|---|
| 인터넷 되는 Ubuntu/Debian | `curl -fsSL https://raw.githubusercontent.com/vavibolivia/workblog/main/install/linux/install.sh \| sudo bash` |
| 인터넷 없는 Linux (systemd) | [Releases](https://github.com/vavibolivia/workblog/releases) 의 `workblog-<버전>-linux.zip` 을 옮겨 → `unzip` → `cd workblog-<버전>` → `sudo ./install.sh` |
| Docker · Podman | `workblog-<버전>-docker.tar.gz` → `.env.example` 를 `.env` 로 복사·수정 → `docker compose up -d` |

1. 인터넷 없는 서버는 Java 21 과 PostgreSQL 14 이상을 먼저 설치 (`sudo apt install -y openjdk-21-jre-headless postgresql`, 사내 미러 사용).
2. 설치가 끝나면 화면에 **주소 · 초기 아이디 `admin` · 초기 비밀번호**가 나옴 → 적어 둠.
3. 접속 확인: `curl -s http://<받는 서버>:8080/api/health` → `{"status":"UP"}`.
4. 설정 파일(Linux): `/opt/workblog/config/workblog.properties` — 바꾼 뒤 `sudo systemctl restart workblog`.

## 3. A. 데이터 이전 (덤프 → 가져오기)

```
 [원래 서버]                               [받는 서버]
 ① 전체 덤프 받기 ── zip (USB·scp 등) ──▶  ② 올리고 미리 보기 → ③ 적용 → ④ 확인
 ⑤ 업데이트 덤프 ─── zip ──────────────▶  ② → ③ → ④           (변경분이 생길 때마다)
```

### 가. 화면으로

1. 원래 서버 — 관리자, [관리 › 데이터 이전]
   1) [전체 덤프] 선택 → [덤프 받기] → `workblog-dump-<인스턴스>-<시각>-full.zip` 저장
2. 덤프 옮기기 — 사내 반출 절차에 따라 (글·첨부 원본·비밀번호 해시가 그대로 들어 있음)
3. 받는 서버 — 관리자, [관리 › 데이터 이전]
   1) [가져오기]에 zip 올림 → **미리 보기** (아무것도 바꾸지 않음)
   2) 추가·갱신·건너뜀·충돌·실패 건수와 목록 확인
   3) [적용] → 결과 보고서 확인 (기록은 같은 화면 아래 목록에 남음)
4. 이후 변경분 — 원래 서버에서 [업데이트 덤프](기본 기준 = 직전 내보내기 시각) → 3과 같이

### 나. CLI 로 (큰 덤프·반복 작업)

CLI 는 각 서버의 `/api/guide/workblog.py` 에서 받습니다(파이썬 3.9+, 설치할 패키지 없음).

```bash
# 1) 원래 서버
export WORKBLOG_USER=admin WORKBLOG_PASSWORD='<원래 서버 admin 비밀번호>'
python workblog.py --url http://<원래 서버>:8080 dump --full -o 전체.zip

# 2) 받는 서버 (비밀번호는 받는 서버 admin 것으로)
export WORKBLOG_PASSWORD='<받는 서버 admin 비밀번호>'
python workblog.py --url http://<받는 서버>:8080 load 전체.zip            # 미리 보기
python workblog.py --url http://<받는 서버>:8080 load 전체.zip --apply    # 적용

# 3) 이후 변경분
python workblog.py --url http://<원래 서버>:8080 dump -o 변경분.zip       # 직전 내보내기 이후
```

### 다. 가져오기 규칙 (요약)

| 대상 | 받는 서버에 이미 있을 때 |
|---|---|
| 사용자 | 같은 아이디면 받는 서버 값 유지 (admin 포함). 새 사용자는 원래 비밀번호로 로그인 |
| 카테고리 | 같은 경로면 그대로 사용. 원래 서버에 열람 부서 제한이 있는데 받는 서버 것이 더 넓으면 **충돌** — 그 카테고리·하위·글은 빠짐 → 받는 서버에 같은 제한을 건 뒤 다시 가져오기 |
| 글 · 댓글 · 템플릿 | 받는 서버에서 고친 것은 **충돌**로 두고 덮어쓰지 않음. 글 갱신은 새 버전으로(이력 보존) |
| 첨부 | 같은 파일(SHA-256)이 있으면 다시 만들지 않음. 본문 주소는 새 id 로 바뀜 |
| API 토큰 | 가져오기로 새로 만든 계정의 토큰만. 원래 있던 계정은 받는 서버에서 새로 발급 |

- 같은 덤프를 두 번 넣어도 결과가 같음. 중간에 실패해도 같은 덤프를 다시 넣으면 이어서 반영.
- **한 방향 전용**: 받는 서버의 덤프를 원래 서버에 다시 넣으면 원래 글이 새 글로 한 번 더 생김.
- 자기 서버가 만든 덤프는 거부됨.

## 4. B. 통째 복제 (DB 복원)

> 받는 서버의 기존 데이터는 모두 지워집니다. 새로 설치한 빈 서버에만 쓰세요.

### 가. 원래 서버에서 받기

```bash
# DB (접속 정보는 원래 서버 설정 파일의 DB_URL · DB_USERNAME · DB_PASSWORD)
# pg_dump 는 DB 서버와 같거나 높은 버전을 쓴다
PGPASSWORD='<DB 비밀번호>' pg_dump -Fc -h <DB 호스트> -U <DB 사용자> -d <DB 이름> -f workblog.dump
# 첨부 (설정 STORAGE_DIR 폴더, Linux 설치 기본 /opt/workblog/data/files)
tar -czf files.tar.gz -C <STORAGE_DIR> .
```

`install/deploy/backup.sh` 를 쓰는 서버는 매일 만들어지는 `backup/db/*.dump` 와 `backup/files/*.tar.gz` 를 그대로 써도 됩니다.

### 나. 받는 서버에 넣기 (Linux 설치 기준)

```bash
sudo systemctl stop workblog
sudo -u postgres pg_restore --clean --if-exists --no-owner --role=workblog -d workblog workblog.dump
# 인스턴스 ID 를 새로 만들게 한다(원래 서버와 같으면 둘 사이 데이터 이전이 '자기 덤프'로 거부됨)
sudo -u postgres psql -d workblog -c "DELETE FROM app_instance;"
sudo tar -xzf files.tar.gz -C /opt/workblog/data/files
sudo chown -R workblog:workblog /opt/workblog/data
sudo systemctl start workblog
curl -s http://127.0.0.1:8080/api/health
```

- 로그인 비밀번호·API 토큰은 원래 서버 것 그대로 (받는 서버 설치 때 나온 admin 초기 비밀번호는 더 이상 안 맞음).
- 로그인 세션(JWT)은 서버마다 비밀 키가 달라 모두 다시 로그인.
- 받는 서버 버전이 더 높으면 기동할 때 DB 스키마가 자동으로 올라감(Flyway). 더 낮은 버전으로는 복원하지 않음.

## 5. DB 밖에 있어 따로 옮길 것

| 항목 | 위치 (Linux 설치) | 방법 |
|---|---|---|
| 설정 | `/opt/workblog/config/workblog.properties` | 원래 서버 값을 보고 받는 서버에 맞게 다시 적음. `PUBLIC_BASE_URL` 은 받는 서버 주소로 |
| draw.io 편집기 (자체 호스팅) | `/opt/workblog/static-extra/drawio/` | 폴더째 복사. 없으면 공개 diagrams.net 을 씀(폐쇄망이면 꼭 복사) |
| 백업 예약 | cron | 받는 서버에도 백업을 설정 |

## 6. 끝난 뒤 확인

1. `/api/health` → `UP`
2. 관리자 로그인 (A: 받는 서버 admin · B: 원래 서버 admin)
3. 글 수·카테고리 트리가 원래 서버와 같은가 (A 는 충돌·실패로 빠진 것 제외)
4. 사진이 들어간 글 하나를 열어 사진이 보이는가, 첨부가 내려받아지는가
5. 일반 사용자 한 명이 원래 비밀번호로 로그인되는가
6. 열람 부서 제한 카테고리가 받는 서버에서도 제한되는가
7. A: [데이터 이전] 기록에 적용 결과가 `OK` 인가

## 7. 문제 해결

| 증상 | 원인 → 조치 |
|---|---|
| 가져오기: "자기 덤프는 가져올 수 없습니다" | 같은 서버의 덤프, 또는 B 로 복제한 뒤 `app_instance` 를 지우지 않음 → 4절 나. 의 `DELETE FROM app_instance` 후 재시작 |
| 가져오기: "덤프가 너무 큽니다" | 받는 서버 설정 `IMPORT_MAX_MB`(기본 2048) 를 늘리고 재시작 |
| 글이 '카테고리 열람 제한 충돌로 가져오지 않음' | 받는 서버의 같은 카테고리에 원래 서버와 같은 열람 부서 제한을 건 뒤 같은 덤프를 다시 적용 |
| 사진이 안 보임 (B) | 첨부 폴더 압축을 `STORAGE_DIR` 바로 아래에 풀었는지, 소유자가 `workblog` 인지 |
| 기동 실패 (B) | `journalctl -u workblog -n 80` — 대개 DB 접속 정보 또는 낮은 버전으로 복원 |
