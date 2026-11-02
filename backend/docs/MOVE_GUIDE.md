# 다른 서버에 데이터와 함께 설치하기

지금 쓰는 WorkBlog 의 글·카테고리·사용자·첨부를 그대로 가지고 **다른 서버(다른 망 포함)에 새로 설치**하는 절차입니다.

- 대상: 관리자(설치 담당)
- 버전: A 는 두 서버 WorkBlog 버전을 같게 맞추는 것을 **권장**, B 는 같은 버전이 **필수**

## 1. 방법 고르기

| | A. 데이터 이전 (권장) | B. 통째 복제 (DB 복원) |
|---|---|---|
| 쓰는 것 | 화면 **관리 ▾ › 데이터 이전** 또는 CLI `dump`·`load` | `pg_dump` · `pg_restore` · 첨부 폴더 압축 |
| 받는 서버 | 새로 설치한 서버 (이미 쓰던 서버도 가능) | 새로 설치한 **빈** 서버 |
| 결과 | 병합 — 받는 서버 데이터는 지우지 않음 | 원래 서버와 똑같은 사본 (받는 서버 데이터는 지워짐) |
| 이후 변경분 | 업데이트 덤프로 계속 반영 가능 | 다시 통째 복제 (복제본과 원래 서버 사이 A 이전은 하지 않음 — 4절 다.) |
| 받는 서버 admin | 받는 서버 값 유지 | 원래 서버 admin 비밀번호로 바뀜 |
| 원래 서버 API 토큰 | 원래 있던 계정(admin 등)의 토큰은 옮기지 않음 | 모두 그대로 |
| 필요 권한 | WorkBlog 관리자 계정 | 두 서버의 OS·DB 관리자 |

- 망이 다르거나, 옮긴 뒤에도 원래 서버에서 계속 쓰고 변경분만 넘길 거라면 → **A**
- 서버를 완전히 바꾸고(원래 서버는 끝냄) 계정·토큰까지 똑같이 옮길 거라면 → **B**

## 2. 받는 서버 설치 (A·B 공통)

| 환경 | 명령 |
|---|---|
| 인터넷 되는 Ubuntu/Debian | `curl -fsSL https://raw.githubusercontent.com/vavibolivia/workblog/main/install/linux/install.sh \| sudo bash` |
| 인터넷 없는 Linux (systemd) | [Releases](https://github.com/vavibolivia/workblog/releases) 의 `workblog-<버전>-linux.tar.gz`(또는 `.zip`) 을 옮겨 → `tar -xzf …` → `cd workblog-<버전>` → `sudo ./install.sh` |
| Docker · Podman | `workblog-<버전>-docker.tar.gz` → `.env.example` 를 `.env` 로 복사·수정 → `docker compose up -d` |

1. 인터넷 없는 서버는 Java 21 과 PostgreSQL 14 이상을 먼저 설치 (Ubuntu: `sudo apt install -y openjdk-21-jre-headless postgresql`, 사내 미러 사용). RHEL·Rocky 는 `postgresql-contrib`(pg_trgm)·initdb 와, 127.0.0.1 접속을 비밀번호(md5/scram)로 받도록 `pg_hba.conf` 설정이 따로 필요.
2. Docker 판을 인터넷 없는 서버에 둘 때는 `eclipse-temurin:21-jre` · `postgres:16` 이미지를 미리 `docker save` → 옮겨서 `docker load`.
3. 설치가 끝나면 화면에 **주소 · 초기 아이디 `admin` · 초기 비밀번호**가 나옴 → 적어 둠.
4. 접속 확인: `curl -s http://<받는 서버>:8080/api/health` → 응답에 `"status":"UP"` 이 있으면 정상.
5. 설정 파일(Linux): `/opt/workblog/config/workblog.properties` — 바꾼 뒤 `sudo systemctl restart workblog`.

## 3. A. 데이터 이전 (덤프 → 가져오기)

```
 [원래 서버]                               [받는 서버]
 ① 전체 덤프 받기 ── zip (USB·scp 등) ──▶  ② 올리고 미리 보기 → ③ 적용 → ④ 확인
 ⑤ 업데이트 덤프 ─── zip ──────────────▶  ② → ③ → ④           (변경분이 생길 때마다)
```

### 가. 화면으로

1. 원래 서버 — 관리자, [관리 ▾ › 데이터 이전]
   1) [전체 덤프] 선택 → [덤프 받기] → `workblog-dump-<인스턴스 앞 8자>-<yyyyMMdd-HHmm>-full.zip` 저장
2. 덤프 옮기기 — 사내 반출 절차에 따라 (글·첨부 원본·비밀번호 해시가 그대로 들어 있음)
3. 받는 서버 — 관리자, [관리 ▾ › 데이터 이전] › '2. 가져오기 (덤프 넣기)'
   1) 파일 선택 → [올리고 미리 보기] (아무것도 바꾸지 않음)
   2) 추가·갱신·건너뜀·충돌·실패 건수와 목록 확인 — 미리 보기가 정상일 때만 [적용] 이 켜짐
   3) [적용] → 확인 창에서 [적용] 한 번 더 → 결과 보고서 확인 (아래 기록 목록에 '완료' 로 남음)
   4) 올린 덤프는 **2시간 안에** 적용 (지나면 다시 올림)
4. 이후 변경분 — 원래 서버에서 [업데이트 덤프](기본 기준 = 직전 내보내기 시각) → 3과 같이

### 나. CLI 로 (큰 덤프·반복 작업)

CLI 는 각 서버의 `/api/guide/workblog.py` 에서 받습니다(파이썬 3.9+, 설치할 패키지 없음). 서버마다 admin 비밀번호가 다르므로 **명령마다 그 서버 비밀번호**를 씁니다(틀리면 401, 반복하면 로그인 잠금).

```bash
export WORKBLOG_USER=admin

# 1) 원래 서버
python workblog.py --url http://<원래 서버>:8080 --password '<원래 서버 admin 비밀번호>' dump --full -o 전체.zip

# 2) 받는 서버
python workblog.py --url http://<받는 서버>:8080 --password '<받는 서버 admin 비밀번호>' load 전체.zip            # 미리 보기
python workblog.py --url http://<받는 서버>:8080 --password '<받는 서버 admin 비밀번호>' load 전체.zip --apply    # 적용

# 3) 이후 변경분 (직전 내보내기 이후)
python workblog.py --url http://<원래 서버>:8080 --password '<원래 서버 admin 비밀번호>' dump -o 변경분.zip
```

### 다. 가져오기 규칙 (요약)

| 대상 | 규칙 |
|---|---|
| 사용자 | 같은 아이디(대소문자 무시)면 받는 서버 값 유지 (admin 포함). 새 사용자는 원래 비밀번호·**원래 역할(ADMIN 포함)**·활성 여부 그대로 생성 → 적용 뒤 관리자 목록 확인 |
| 카테고리 | 같은 경로(이름 대소문자 무시)면 그대로 사용. 원래 서버에 열람 부서 제한이 있는데 받는 서버 것이 같거나 더 좁지 않으면(제한 없음·다른 부서 포함) **충돌** — 그 카테고리는 충돌, 하위는 '건너뜀', 글은 '실패' 목록에 나옴 → 받는 서버에 같은 제한을 건 뒤 같은 덤프를 다시 적용 |
| 글 | 원본 버전이 지난번보다 높을 때만 갱신(새 버전으로, 이력 보존). 지난번 가져온 뒤 받는 서버에서 고친 글은 **충돌**로 두고 덮어쓰지 않음. 받는 서버에서 영구 삭제한 글은 다시 만들지 않음 |
| 댓글 | 원본이 더 새로우면 갱신, 받는 서버에서 고쳤으면 **충돌** |
| 템플릿 | 원본이 더 새로우면 갱신, 받는 서버에서 고쳤으면 **충돌**. 받는 서버에 같은 이름 템플릿이 원래 있으면 계속 받는 서버 값 유지 |
| 첨부 | 이미 가져온 첨부, 또는 같은 글에 같은 파일(SHA-256)이 있으면 다시 만들지 않음. 본문 주소는 새 id 로 바뀜 |
| API 토큰 | 가져오기로 새로 만든 계정의 토큰만. 원본에서 폐기한 토큰은 받는 서버에서도 폐기. 원래 있던 계정은 받는 서버에서 새로 발급 |

- 같은 덤프를 두 번 넣어도 결과가 같음. 중간에 실패해도 같은 덤프를 다시 넣으면 이어서 반영.
- **한 방향 전용**: 받는 서버의 덤프를 원래 서버에 다시 넣으면 원래 글이 새 글로 한 번 더 생김.
- 자기 서버가 만든 덤프는 거부됨.

## 4. B. 통째 복제 (DB 복원)

> 받는 서버의 DB 를 지우고 원래 서버 DB 로 바꿉니다. 새로 설치한 빈 서버에만 쓰세요.
> 조건: 두 서버 **WorkBlog 같은 버전** · 받는 서버 PostgreSQL(과 pg_restore) 이 원래 서버와 **같거나 높은 메이저 버전** (예: 원래 16 → 받는 14 는 복원 불가).

### 가. 원래 서버에서 받기

```bash
# DB (접속 정보는 원래 서버 설정의 DB_URL · DB_USERNAME · DB_PASSWORD). pg_dump 는 DB 서버와 같거나 높은 버전
PGPASSWORD='<DB 비밀번호>' pg_dump -Fc -h <DB 호스트> -U <DB 사용자> -d <DB 이름> -f workblog.dump
# 첨부 (설정 STORAGE_DIR 폴더, Linux 설치 기본 /opt/workblog/data/files) — 폴더 안 내용만 묶음
tar -czf files.tar.gz -C <STORAGE_DIR> .
```

- `install/deploy/` 방식(podman) 서버는 포트 예 8070, 설정은 `WORKBLOG_ENV` 파일, 첨부의 호스트 경로는 `/project/workblog/data/files`(설정의 `STORAGE_DIR=/app/data/files` 는 컨테이너 안 경로).
- 이 서버의 `backup.sh` 결과물(`/project/workblog/backup/db/db_workblog-<날짜>.dump`, `/project/workblog/backup/files/files-<날짜>.tar.gz`)을 그대로 써도 됨. 단 첨부 압축 안에 **`files/` 폴더가 들어 있음**(풀 때 다르게 — 아래).

### 나. 받는 서버에 넣기 (Linux 설치 기준)

```bash
sudo systemctl stop workblog
# 빈 DB 로 새로 만든 뒤 복원 (pg_trgm 확장도 workblog 소유로 만들어짐)
sudo -u postgres dropdb workblog
sudo -u postgres createdb -O workblog workblog
sudo -u postgres pg_restore --no-owner --no-acl --role=workblog -d workblog < workblog.dump   # 파일은 리다이렉트로 넘김(권한 문제 없음)
# 첨부: 직접 묶은 압축(가.)
sudo tar -xzf files.tar.gz -C /opt/workblog/data/files
#   backup.sh 압축이면 대신:  sudo tar -xzf files-<날짜>.tar.gz -C /opt/workblog/data
sudo chown -R workblog:workblog /opt/workblog/data
sudo systemctl start workblog
curl -s http://127.0.0.1:8080/api/health
```

- 로그인 비밀번호·API 토큰은 원래 서버 것 그대로 (받는 서버 설치 때 나온 admin 초기 비밀번호는 더 이상 안 맞음).
- 받는 서버 설정의 `JWT_SECRET` 이 원래 서버와 다르면 모두 다시 로그인(같게 복사하면 로그인 유지).
- Docker 판: `docker compose exec -T db pg_restore -U workblog --no-owner --no-acl -d workblog < workblog.dump` (빈 DB 에), 첨부는 `files` 볼륨에 풀기.

### 다. 인스턴스 ID (app_instance)

복제본은 원래 서버와 **같은 인스턴스 ID** 를 가집니다.

| 경우 | 할 일 |
|---|---|
| 원래 서버를 끝내는 교체 (B 의 일반적인 용도) | **그대로 둠** — 원래 서버에서 덤프를 받아 오던 다른 서버가 새 서버를 같은 원본으로 알아봄 |
| 두 서버를 계속 함께 씀 | 복원 직후 `sudo -u postgres psql -d workblog -c "DELETE FROM app_instance;"` → 기동 때 새 ID. 단 **원래 서버 ↔ 복제본 사이 A 이전은 하지 않음**(매핑이 없어 글·댓글·첨부가 한 벌 더 생김) |

## 5. DB 밖에 있어 따로 옮길 것

| 항목 | 위치 (Linux 설치) | 방법 |
|---|---|---|
| 설정 | `/opt/workblog/config/workblog.properties` | 원래 서버 값을 보고 받는 서버에 맞게 다시 적음. **`DB_URL` · `DB_USERNAME` · `DB_PASSWORD` 는 받는 서버 설치가 만든 값 유지**(복사하면 기동 실패). `PUBLIC_BASE_URL` 은 받는 서버 주소로. `ADMIN_PASSWORD` 는 첫 기동에만 쓰임 |
| draw.io 편집기 (자체 호스팅) | `/opt/workblog/static-extra/drawio/` | 폴더째 복사. 없으면 공개 diagrams.net 을 씀(폐쇄망이면 꼭 복사). Docker 판은 compose 에 static-extra 볼륨을 추가하고 `EXTRA_STATIC_DIR` 지정 |
| 백업 예약 | cron | `install/deploy/backup.sh` 는 podman 배포용 경로(`/project/workblog`)가 고정 — Linux 설치는 같은 방식(pg_dump + 첨부 tar)으로 따로 예약 |

## 6. 끝난 뒤 확인

1. `/api/health` 응답에 `"status":"UP"`
2. 관리자 로그인 (A: 받는 서버 admin · B: 원래 서버 admin)
3. 글 수·카테고리 트리가 원래 서버와 같은가 (A 는 충돌·실패로 빠진 것 제외)
4. 사진이 들어간 글 하나를 열어 사진이 보이는가, 첨부가 내려받아지는가
5. 일반 사용자 한 명이 원래 비밀번호로 로그인되는가
6. 열람 부서 제한 카테고리가 받는 서버에서도 제한되는가
7. A: [데이터 이전] 기록에 적용 결과가 '완료' 인가 · 관리자 목록에 의도하지 않은 ADMIN 이 없는가

## 7. 문제 해결

| 증상 | 원인 → 조치 |
|---|---|
| 가져오기: "자기 덤프는 가져올 수 없습니다" | 같은 서버의 덤프, 또는 B 로 복제한 서버끼리 → 4절 다. 참고 |
| 가져오기: "덤프가 너무 큽니다" | 받는 서버 설정 `IMPORT_MAX_MB`(기본 2048) 를 늘리고 재시작 |
| 올리다가 413 · 연결 끊김 | 앞단 nginx 등의 업로드 한도(`client_max_body_size`) — 프록시 설정을 늘림 |
| 올리기 실패 · 디스크 부족 | 받는 서버 `data/imports`(설정 `IMPORT_TMP_DIR`)에 덤프 크기만큼 여유 필요 |
| 글이 '카테고리 열람 제한 충돌로 가져오지 않음' | 받는 서버의 같은 카테고리에 원래 서버와 같은 열람 부서 제한을 건 뒤 같은 덤프를 다시 적용 |
| 사진이 안 보임 (B) | 압축을 푼 위치 확인 — 직접 묶은 압축은 `data/files` 에, backup.sh 압축은 `data` 에(안에 `files/` 가 있음). 소유자가 `workblog` 인지 |
| pg_restore: could not open input file | 파일을 `sudo -u postgres` 가 못 읽음 → `< workblog.dump` 리다이렉트로 넘김 |
| pg_restore: unsupported version in file header | 받는 서버 PostgreSQL 이 원래보다 낮음 → 같거나 높은 버전으로 |
| 기동 실패 (B) | `journalctl -u workblog -n 80` — 대개 설정의 DB 접속 정보를 원래 서버 값으로 바꿨거나, WorkBlog 버전이 다름 |
