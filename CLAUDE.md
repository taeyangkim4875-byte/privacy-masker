# pii-masker

STT/TTS 이력 DB(MariaDB)의 개인정보를 탐지·마스킹해 별도 테이블에 적재하는 설치형 도구.
전체 계획·단계·완료 기준은 `docs/PLAN.md` 를 따른다. 작업은 PLAN 의 T 항목 하나씩, 계획 승인 후 구현한다.

## 구조
- `masking-core/`  탐지·병합·치환 엔진. 순수 Java, Spring·DB 의존성 금지. `normalize/` 는 ITN(한글로 읽은 숫자 → 숫자열, 원문 오프셋 유지), `dict/` 는 이름 사전(기본 성씨·예외어는 리소스 파일 `com/piimask/core/dict/*.txt`)
  - 탐지 규칙을 바꾸면 `SeedRegressionTest`(시드 전체 ITN 끔/켬 비교, 유형별 건수 출력)가 함께 돈다
  - 이름 Recall·Precision 은 `NameEvalTest`(`src/test/resources/eval/`). 보류셋(`names-holdout*.txt`)을 보고 규칙을 고치지 않는다. 새로 고칠 땐 새 보류셋을 먼저 쓴다
- `masking-app/`   Spring Boot 4.1: 접속·작업 설정 API, 증분 배치, 스케줄, H2 메타 DB
- `masking-ui/`    React + Vite. 빌드 결과는 Gradle 이 app 의 static/ 에 넣는다
- `docker/`        로컬 테스트용 MariaDB 스키마와 가짜 시드(`scripts/gen_seed.py` 로 생성)

## 빌드·실행
- 전체 실행: `./run.sh` (Windows `run.cmd`) → http://127.0.0.1:8080
  - Docker 가 없으면 포터블 MariaDB(`.local/`, 127.0.0.1:13306, stt / masker / masker-local)를 자동으로 쓴다
  - `run.cmd -ResetDb`: 가짜 데이터 초기화, `-SkipUi`: 화면 빌드 생략
- 백엔드 컴파일만: `gradlew.bat :masking-app:compileJava -PskipUi`
- 엔진 테스트: `./gradlew :masking-core:test`
- 화면만 수정: 앱 실행 상태에서 `cd masking-ui && npm run dev` (5173, /api 는 8080 으로 프록시)

## 반드시 지킬 규칙
- 원문 텍스트(마스킹 전 값)를 로그·예외 메시지·감사 로그·테스트 스냅샷에 출력하지 않는다. 예외는 `Errors.safeMessage` 를 거친다
- 원천 DB 에는 SELECT 만. 쓰기는 대상(마스킹) 테이블에만
- 동적 SQL 의 테이블·컬럼명은 메타데이터로 존재를 확인한 뒤 `Sql.q()` 로만 넣는다
- 새 탐지기는 `Detector` 구현 + 정탐·오탐 테스트를 함께 추가한다. 탐지는 Recall 우선
- 테스트 데이터의 개인정보는 전부 가짜. 실데이터를 저장소·테스트 DB 에 넣지 않는다
- 외부 네트워크 호출 코드·CDN 리소스 금지(폐쇄망 전제). 폰트 등은 npm 으로 번들
- 접속 비밀번호·마스터키는 커밋 금지(`data/` 는 .gitignore)

## 작업 방식
- 진행 상황과 남은 일은 `docs/PLAN.md` 맨 아래 "현재 진행 상황" 표가 기준이다
- 한 번에 T 항목 하나만. 코드 작성 전에 계획을 보여주고 승인받는다
- 구현 후 `code-reviewer` 에이전트로 리뷰, 완료 기준 확인, 그다음 커밋
- 끝나면 PLAN.md 진행 상황 표를 갱신한다
- 탐지 규칙을 바꾸면 `masking-core` 테스트와 함께, 기존 가짜 데이터에서 탐지 건수가 의도치 않게 변하지 않았는지 확인한다

## 계획 대비 현재 상태 (1단계 동작본)
- 배치는 Spring Batch 6 chunk 스텝(`MaskingJobFactory`: keyset reader → processor → upsert writer). 실행마다 새 JobInstance, "이어서 실행"은 Batch 재시작이 아니라 `job_watermark` 로 한다. 화면 이력은 `job_execution`(BATCH_* 는 내부용)
- 배치 단계(reader/processor/writer)에서 나가는 예외는 `SanitizedBatchException` 으로 감싼다(원인 미첨부). Batch 가 스택 트레이스를 로그·EXIT_MESSAGE 에 남기기 때문
- 우리 메타 `JobRepository` 빈 이름은 `maskingJobRepository`(Batch 의 `jobRepository` 와 충돌 방지)
- 스케줄은 Quartz 대신 Spring `ThreadPoolTaskScheduler`. 영속 스케줄이 필요해지면 4단계에서 검토
- 로그인·역할(T10): 권한 표는 `security/SecurityConfig` 한 곳. 새 API 를 만들면 표와 `AccessMatrixTest` 에 함께 추가한다(표에 없는 /api/** 는 denyAll)
- 원문이 보이는 기능(미리보기·탐지 테스트)은 설정자 전용. 감사 로그 actor 는 `CurrentUser`(요청 밖은 system / system:scheduler)
- 127.0.0.1 바인딩은 유지(HTTPS·외부 공개는 4단계)
- 규칙 기반 이름 탐지(`NameRuleDetector`)를 2단계에서 당겨 넣음(베이스라인 용도)
- 엔진은 `dict/EngineProvider.current()` 로 받는다(사용자 사전 반영, 사전이 바뀌면 새 엔진으로 교체). 배치는 실행을 만들 때 한 번 받는다. `MaskingEngine` 빈은 기본 사전만 쓰는 엔진(사용자 사전 미반영)
- 화자 문맥: 작업 설정의 통화 ID·화자 컬럼으로 배치가 `DialogBinding` 을 만든다. 행은 PK 순서로 한 번씩만 처리한다는 전제라 스텝을 fault-tolerant 로 바꾸지 않는다. 메타 DB 컬럼 추가는 `support/MetaMigration`(meta-schema.sql 에서 ALTER 하지 않음: 연결마다 실행됨)
- 교정 라벨(`label/`)은 원문을 저장하지 않는다: 원천 위치 + HMAC 행 지문(`CryptoService.fingerprint`)만. 원천 값이 필요하면 서버가 다시 SELECT 한다. 학습 반출 기능은 보안 승인 전까지 만들지 않는다
- 사전 단어(특히 추가 탐지어)는 실명일 수 있다: 로그·감사·오류 메시지·`/api/meta` 에 단어를 넣지 않는다(종류·건수·id 만)
- 기본 예외 사전에 이름일 수 있는 말을 넣지 않는다(`SeedRegressionTest` 가 시드 이름 형태의 예외어를 막는다)
