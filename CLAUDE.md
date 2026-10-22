# pii-masker

STT/TTS 이력 DB(MariaDB)의 개인정보를 탐지·마스킹해 별도 테이블에 적재하는 설치형 도구.
전체 계획·단계·완료 기준은 `docs/PLAN.md` 를 따른다. 작업은 PLAN 의 T 항목 하나씩, 계획 승인 후 구현한다.

## 구조
- `masking-core/`  탐지·병합·치환 엔진. 순수 Java, Spring·DB 의존성 금지
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
- 배치는 Spring Batch 대신 같은 구조(keyset reader → processor → upsert writer)의 `JobRunner` 로 구현. T8 에서 Spring Batch 6 로 전환
- 스케줄은 Quartz 대신 Spring `ThreadPoolTaskScheduler`. 영속 스케줄이 필요해지면 4단계에서 검토
- 로그인 없음. 대신 127.0.0.1 바인딩. T10 에서 추가
- 규칙 기반 이름 탐지(`NameRuleDetector`)를 2단계에서 당겨 넣음(베이스라인 용도)
