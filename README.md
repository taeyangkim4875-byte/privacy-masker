# 개인정보 마스킹 도구 (1단계 로컬 동작본)

STT·TTS 이력 테이블에서 이름·전화번호·카드번호·주민등록번호·계좌번호·이메일을 찾아 가린 뒤, 별도 마스킹 테이블에 쌓는 도구입니다. 화면에서 DB 접속·테이블·컬럼·가리는 방식을 정하고, 미리보기로 확인한 뒤 배치를 돌립니다.

서버에 올리지 않고 내 PC에서 기능과 화면을 확인하는 것이 이 버전의 목적입니다.

## 1. 준비물

| 항목 | 버전 | 확인 명령 |
| --- | --- | --- |
| JDK | 21 이상 (계획 기준 25 권장) | `java -version` |
| Node.js | 22 LTS (20.19 이상) | `node -v` |
| Docker Desktop | 선택 사항 | 없으면 포터블 MariaDB를 자동으로 사용 |

처음 실행할 때 Gradle·라이브러리·npm 패키지를 내려받으므로 인터넷이 필요합니다. 두 번째부터는 캐시를 씁니다.

## 2. 실행

Windows (탐색기에서 더블클릭하거나 터미널에서):

```
run.cmd
```

Linux / macOS:

```
./run.sh
```

스크립트가 하는 일:

1. Java·Node·Docker 확인
2. MariaDB 시작 (`127.0.0.1:13306`, 가짜 상담 이력 3,245행 자동 적재)
   - Docker Desktop이 켜져 있으면 Docker로 띄웁니다
   - 없으면 **포터블 MariaDB**를 씁니다: 처음 한 번 공식 zip(약 80MB)을 받아 SHA-256을 검증하고 프로젝트의 `.local/` 폴더에 풉니다. 설치·관리자 권한이 필요 없고, `run.cmd`를 끄면 DB도 같이 꺼집니다
3. React 화면 빌드 → Spring Boot 실행

`Started PiiMaskApplication` 이 보이면 브라우저에서 **http://127.0.0.1:8080** 을 엽니다.

| 옵션 | Windows | Linux/macOS | 용도 |
| --- | --- | --- | --- |
| Docker 없이 | `run.cmd -NoDb` | `./run.sh --no-db` | 개발 서버 MariaDB 등 직접 접속 |
| 포터블 DB 강제 | `run.cmd -Portable` | - | Docker가 있어도 포터블 사용 |
| 가짜 데이터 초기화 | `run.cmd -ResetDb` | - | 포터블 DB를 지우고 시드 다시 적재 |
| 화면 빌드 생략 | `run.cmd -SkipUi` | `./run.sh --skip-ui` | 백엔드만 다시 띄울 때 |
| 포트 변경 | `set PIIMASK_PORT=9090` | `PIIMASK_PORT=9090 ./run.sh` | 8080 이 사용 중일 때 |

## 3. 기능 확인 순서 (약 10분)

**⓪ 첫 로그인** — 처음 열면 "초기 관리자 만들기" 화면이 나옵니다
- 설정 토큰: `data/setup.token` 파일 내용(기동 로그에 파일 위치가 나옵니다). 관리자를 만들면 파일은 지워집니다
- 무인 설치는 첫 기동 전에 `PIIMASK_ADMIN_USER`, `PIIMASK_ADMIN_PASSWORD` 환경변수로 대신할 수 있습니다
- 관리자는 **계정 관리와 감사 로그 조회만** 합니다. `사용자 관리`에서 설정자·실행자 계정을 만들거나, 혼자 쓴다면 내 계정에 역할을 더 붙이세요

| 역할 | 할 수 있는 일 |
| --- | --- |
| 설정자 | DB 접속·작업 설정, 미리보기·탐지 테스트(원문이 보임), 실행 이력 |
| 실행자 | 작업 실행·중지, 실행 이력 |
| 감사자 | 실행 이력·감사 로그 조회 |
| 관리자 | 계정 관리, 감사 로그 조회 |

비밀번호는 10자 이상이고, 5번 틀리면 10분간 잠깁니다. 세션은 30분 동안 아무 요청이 없으면 끝납니다.
관리자가 모두 사용 중지됐다면 `PIIMASK_ADMIN_USER`, `PIIMASK_ADMIN_PASSWORD` 를 지정하고 다시 기동하면 그 계정이 관리자로 복구됩니다.

**① DB 접속 등록** — 메뉴 `DB 접속` (설정자)
- 기본값이 로컬 Docker MariaDB로 채워져 있습니다. 비밀번호 `masker-local` 입력 → `접속 테스트` → `등록`

**② 탐지 테스트** — 메뉴 `탐지 테스트`
- 예시 상담 기록이 들어 있습니다. 가리는 방식(부분·전체·태그·해시)을 바꿔 가며 결과를 봅니다
- 마지막 줄 "공일공 사팔이일…"처럼 한글로 읽은 번호도 ITN 정규화로 잡습니다(탐지기 이름에 "(ITN)")

**③ 작업 만들기** — `마스킹 작업` → `새 작업 만들기`
1. 원천 테이블 `stt_call_history` 선택 (PK·대상 테이블 이름 자동)
2. `utterance` 는 마스킹, 나머지는 복사로 자동 선택됨
3. 가리는 방식 확인, 실행 주기는 `수동 실행만`
4. `미리보기` → 원문 하이라이트와 저장될 값 비교 → `작업 저장` (대상 테이블 자동 생성)

**④ 실행** — 작업 화면의 `지금 실행` 또는 `현황`의 `실행`
- 3,245행이 몇 초 안에 끝납니다. 현황 화면에서 건수를 확인합니다

**⑤ 결과 테이블 확인** — DB 클라이언트(DBeaver 등)로 `127.0.0.1:13306 / stt / masker` 접속

```sql
SELECT COUNT(*) FROM stt_call_history_masked;                 -- 3245
SELECT utterance FROM stt_call_history_masked LIMIT 30;
-- 4자리 이상 숫자가 남은 행 (결제일 같은 정상 숫자 외에는 0건이어야 함)
SELECT utterance FROM stt_call_history_masked WHERE utterance REGEXP '[0-9]{4}' LIMIT 20;
```

**⑥ 증분·재시작 확인**
```sql
CALL make_bulk(30);   -- 원천을 약 10만 행으로 불림
```
- 중지 시점을 잡기 쉽도록 작업의 청크 크기를 `50` 정도로 낮춰 저장합니다
- `실행` → 진행 중에 `중지` → 다시 `실행`: 멈춘 지점부터 이어서 처리되고 최종 건수가 원천과 같아야 합니다
- 같은 작업을 한 번 더 실행하면 새 행이 없으므로 0건 처리로 끝납니다(증분)
- `처리 위치 초기화` 후 실행하면 처음부터 다시 덮어씁니다

**⑦ 이력** — `실행·감사 이력` 에서 실행 결과와 설정 변경 기록 확인

## 4. 개발 서버 MariaDB로 테스트할 때

`run.cmd -NoDb` 로 실행한 뒤 화면에서 개발 서버 접속을 등록합니다. 계정은 최소 권한으로 만드는 것을 권장합니다.

```sql
CREATE USER 'masker'@'%' IDENTIFIED BY '...';
GRANT SELECT ON stt_dev.stt_call_history TO 'masker'@'%';           -- 원천: 읽기만
GRANT CREATE, SELECT, INSERT, UPDATE ON stt_dev.`stt_call_history_masked` TO 'masker'@'%';  -- 대상만 쓰기
```

대상 테이블을 미리 만들 경우 작업 화면 4단계의 `DDL 보기` 내용을 DBA에게 전달하면 됩니다.
**실데이터로 테스트할 때는 미리보기 화면에 원문이 보이므로 보안 승인 범위 안에서만 사용하세요.**

## 5. 화면만 고칠 때

앱을 실행해 둔 상태에서:

```
cd masking-ui
npm run dev
```

http://127.0.0.1:5173 에서 저장할 때마다 바로 반영됩니다. API는 8080으로 넘어갑니다.

## 6. 엔진 테스트

```
./gradlew :masking-core:test        (Windows: gradlew.bat :masking-core:test)
```

### 배치 통합 테스트 (실제 MariaDB)

`masking-app` 의 `@Tag("integration")` 테스트는 기본 `test` 에서 빠지고 `integrationTest` 로만 돕니다.
시드 테이블(`stt_call_history`)을 `it_*` 테이블로 복사해 쓰고 끝나면 지웁니다. 시드와 `./data` 는 건드리지 않습니다.

| 테스트 | 확인 내용 |
| --- | --- |
| `BatchCountIT` | 실행 후 대상 건수·PK 집합 = 원천, COPY 컬럼 동일, MASK 컬럼 = 엔진 재계산 결과 |
| `BatchRestartIT` | 약 10만 건을 30% 지점에서 중지 → 재실행해도 중복·누락 없음, 워터마크를 되감아도 같은 결과 |
| `BatchIncrementalIT` | 재실행 시 새로 들어온 행만 처리(read_count, start/end_pk, masked_at), 새 행이 없으면 0건 |
| `NoPlaintextLeakIT` | 가짜 개인정보(카나리)와 원천에서 탐지된 원문 조각이 콘솔·로그 파일·메타 DB(덤프와 파일)에 없음. 실패 실행 포함 |
| `BatchMetadataIT` | Batch 상태(COMPLETED/STOPPED/FAILED)가 실행 이력과 짝이 맞음, 실행 중 재시작은 409, 실패 원인에 드라이버 예외 없음, 문자열 PK 증분 |

접속 정보는 환경변수로만 넣습니다(저장소에 기본값 없음). 포터블 DB를 띄운 상태(`run.cmd`)에서:

```powershell
$env:PIIMASK_IT_DB_URL      = "jdbc:mariadb://127.0.0.1:13306/stt"
$env:PIIMASK_IT_DB_USER     = "masker"
$env:PIIMASK_IT_DB_PASSWORD = "masker-local"
.\gradlew.bat :masking-app:integrationTest -PskipUi "-Dorg.gradle.jvmargs=-Dfile.encoding=COMPAT"
```

- 약 1분 30초 걸립니다. 결과: `masking-app/build/reports/tests/integrationTest/index.html`
- `PIIMASK_IT_KEEP=1` 이면 끝난 뒤 `it_*` 테이블을 남겨 둡니다(실패 원인 확인용)
- `-Dorg.gradle.jvmargs=-Dfile.encoding=COMPAT` 는 사용자 폴더가 한글(예: `C:\Users\김태양`)인 Windows 에서만 필요합니다. 없으면 테스트 JVM 이 `GradleWorkerMain` 을 못 찾고 죽습니다(엔진 테스트도 같음)

## 7. 현재 범위와 계획 대비 차이

계획서 `docs/PLAN.md` 기준 1단계(T1~T9)와 2단계 일부입니다.

- 배치: Spring Batch 6 chunk 스텝(keyset 청크 → 마스킹 → upsert, 청크마다 대상 커밋 후 처리 위치 저장). Batch 메타 테이블(`BATCH_*`)은 메타 DB(H2)에 함께 있음
- 스케줄: Quartz 대신 Spring 스케줄러(앱이 떠 있을 때만 동작)
- 로그인: 로컬 계정(bcrypt) + 세션, 역할 4개. SAML SSO 는 구조만 열어 둠(미구현). 여전히 `127.0.0.1` 에만 바인딩(HTTPS·외부 공개는 4단계)
- 이름 탐지: 규칙 기반(문맥 단서 필요). "네 김태양"처럼 단서 없이 말하면 놓침 → 3단계 모델
- 한글로 읽은 숫자: ITN 정규화(한 자리씩 읽은 9자리 이상, 구분자는 공백·-·,·.·"에"). "앞자리 … 뒷자리 …"처럼 단어가 끼면 놓침, 자릿수 단어("삼십만원")는 대상 아님
- 복합 기본키 테이블 미지원(단일 PK 필요). 처리 위치는 PK 값을 문자열로 저장하고, 비교할 때 정수형 PK 는 숫자로, 그 외는 문자열로 바인딩
- Gradle 9.1 래퍼를 씁니다(JDK 25 로도 빌드 가능)

## 8. 문제 해결

| 증상 | 해결 |
| --- | --- |
| `Port 8080 was already in use` | `PIIMASK_PORT` 로 다른 포트 지정 |
| 13306 포트 충돌 | `docker-compose.yml` 의 `13306` 을 바꾸고 접속 정보도 같이 변경 |
| 시드 데이터를 처음 상태로 | 포터블: `run.cmd -ResetDb` / Docker: `docker compose down -v` 후 다시 실행 |
| 포터블 DB 초기화 실패(한글 경로) | `set PIIMASK_DB_HOME=C:\piimask-local` 후 실행 |
| 포터블 DB를 완전히 지우기 | 앱 종료 후 `.local` 폴더 삭제 |
| 메타 설정(접속·작업)을 초기화 | 앱 종료 후 `data/` 폴더 삭제 |
| `복호화 실패` | `data/master.key` 가 바뀌었거나 지워짐. 접속 정보를 다시 등록 |
| `PIIMASK_DATA_DIR` 지정 후 기동 실패 | 절대 경로나 `./` 로 시작하는 경로로 지정(H2 규칙) |
| 화면이 안 바뀜 | `-SkipUi` 없이 다시 실행하거나 브라우저 강력 새로고침 |
| 테스트 실행 시 `ClassNotFoundException: ...GradleWorkerMain` | 한글 사용자 폴더 문제. gradlew 에 `"-Dorg.gradle.jvmargs=-Dfile.encoding=COMPAT"` 추가 |
