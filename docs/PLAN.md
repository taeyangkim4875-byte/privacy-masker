# 개인정보 마스킹 도구 개발 계획서

Oct 22, 2026 · @클라이드

## 개요

STT/TTS 이력 DB(MariaDB)의 개인정보를 UI 설정만으로 탐지·마스킹해 별도 마스킹 테이블에 적재하는 단일 설치형 도구를 4단계로 만든다. 1단계 MVP만으로 번호류(전화·카드·주민·계좌) 마스킹이 운영 가능해야 하고, 이름 인식 모델은 평가셋을 확보한 3단계에 붙인다.

**범위**

- Windows·Linux 서버에서 JRE 번들 설치본 또는 Docker로 실행, 폐쇄망 전제
- 웹 UI에서 DB 접속, 테이블·컬럼 매핑, 마스킹 정책, 배치 스케줄을 설정
- 원천 테이블은 읽기만 하고, 결과는 마스킹 전용 테이블에 insert
- 기본은 비가역 마스킹. 복원 요구는 범위 밖(필요 시 별도 설계)

**개발 원칙**

1. 단계마다 계획 승인 → 구현 → 리뷰 → 완료 기준 검증 순으로 닫고 다음 단계로 간다.
2. 원문 텍스트는 어떤 로그·예외 메시지·화면 캐시에도 남기지 않는다.
3. 탐지 정확도는 Recall 우선. 놓친 개인정보가 과마스킹보다 치명적이다.
4. 마스킹 엔진은 DB·배치와 분리된 순수 모듈로 만들어 단위 테스트로 검증한다.

## 기술 스택

백엔드는 Spring Boot 4 + Spring Batch 6, 프론트는 React, 모델은 ONNX Runtime(Java)으로 JVM 하나에서 모두 돈다. Spring Batch 5.2는 2026년 6월 30일 OSS 지원이 끝났으므로 신규 프로젝트는 Boot 4 / Batch 6으로 시작한다([Spring Batch 6.0.4 릴리스](https://spring.io/blog/2026/06/10/spring-batch-6-0-4-and-5-2-6-available-now/)).

| 영역 | 선택 | 비고 |
| --- | --- | --- |
| 언어/런타임 | Java 25 (LTS) | jpackage로 JRE 번들 |
| 백엔드 | Spring Boot 4.1.x(최신 패치), Spring Batch 6.0.x, Quartz | Batch 6에서는 JobLauncher 대신 JobOperator 사용 |
| 원천 DB | MariaDB (Connector/J) | utf8mb4, 증분 기준 컬럼 인덱스 필수. 개발 서버 MariaDB로 통합 테스트 |
| 메타 DB | H2 file 모드 | 설정·잡 이력·감사 로그. 운영 요구 시 MariaDB로 전환 가능하게 JPA 사용 |
| 프론트 | React + TypeScript + Vite | TanStack Query, react-hook-form. 빌드 산출물을 Spring Boot static에 내장 |
| 모델 추론 | ONNX Runtime Java + DJL HuggingFace tokenizers | Windows·Linux x64 네이티브 포함, CPU 추론 |
| 모델 학습 | Python (transformers) | 학습 환경 전용. 배포물에는 포함하지 않음 |
| 빌드 | Gradle 멀티모듈 | 프론트 빌드를 Gradle 태스크로 연결 |

Boot 4.1을 고른 이유: 4.0은 2026년 12월 31일에 OSS 지원이 끝나고, 4.1은 2027년 6월 30일까지 지원된다(Boot 3.x는 전부 OSS 지원 종료). Java는 Boot 4가 지원하는 최신 LTS인 25로 하고, JRE를 번들하므로 고객사 서버의 Java 버전과 무관하다. ONNX Runtime·DJL tokenizers의 Java 25 동작은 T1에서 확인하고, 문제가 있으면 21로 내린다. 버전 근거: [Spring Boot 4.1.0 릴리스](https://spring.io/blog/2026/06/10/spring-boot-4/), [지원 기간 정리](https://versionlog.com/spring-boot/).

## 전체 아키텍처

하나의 Spring Boot 앱이 UI·설정·배치·마스킹 엔진을 모두 담고, 원천 테이블은 SELECT만, 결과는 마스킹 테이블에 INSERT만 한다.

&#91;embedded content: 전체 아키텍처 · 단일 Spring Boot 앱 + MariaDB + H2\]

배치 엔진이 원천 행을 청크로 읽어 마스킹 엔진에 넘기고, 엔진의 각 계층은 표시된 단계에서 추가된다.

**모듈 구조 (Gradle 멀티모듈)**

- `masking-core`: 탐지기 인터페이스, 스팬, 치환 정책, 정규식·검증기. 외부 의존성 없음
- `masking-model`: ONNX 추론 탐지기(3단계에 추가)
- `masking-app`: Spring Boot, 설정 API, 배치, 스케줄, 인증·감사
- `masking-ui`: React 프론트. 빌드 결과를 `masking-app`의 static에 포함
- 별도 저장소 `pii-ner-train`: Python 학습·평가 스크립트

## 단계별 로드맵

각 단계는 아래 완료 게이트를 통과해야 다음으로 넘어가고, 평가셋 승인은 1단계부터 병행한다. 일정은 착수 전 확인 사항이 정리된 뒤 확정한다.

&#91;embedded content: 단계별 로드맵 · 4단계, 단계별 완료 게이트\]

강조된 1단계가 지금 착수할 범위다. 게이트별 상세 기준은 각 단계 섹션의 완료 기준을 따른다.

## 1단계 MVP 상세

1단계가 끝나면 UI에서 MariaDB 테이블을 골라 정규식 기반 마스킹 배치를 증분으로 돌릴 수 있어야 한다. 작업은 아래 순서대로 진행하고, 각 항목이 하나의 Claude Code 작업 단위(계획 → 구현 → 리뷰)다.

### 작업 목록

- [ ] T1. Gradle 멀티모듈 골격, React 빌드 연동, H2 메타 DB, 공통 예외·로깅(원문 미출력) 정책
- [ ] T2. 마스킹 엔진 코어: `Detector` 인터페이스, 스팬 모델, 스팬 병합, 치환 정책(전체·부분·해시)
- [ ] T3. 정규식 탐지기 + 검증기: 휴대폰·유선, 카드(Luhn), 주민·외국인등록번호(체크섬), 계좌, 이메일
- [ ] T4. DB 접속 관리: 등록·테스트·AES 암호화 저장, 접속별 HikariCP 동적 DataSource
- [ ] T5. 스키마 탐색 API: DatabaseMetaData로 테이블·컬럼·PK·인덱스 조회
- [ ] T6. 매핑·정책 관리: 원천 테이블, PK, 증분 컬럼, 마스킹 컬럼별 엔티티 정책, 대상 테이블(DDL 생성 옵션)
- [ ] T7. 미리보기: 샘플 N건 실행, 텍스트 붙여넣기 테스트, 스팬 하이라이트 응답
- [ ] T8. 배치: Spring Batch chunk(keyset reader → processor → batch writer), 워터마크 증분, 실패 재시작
- [ ] T9. 스케줄·실행 관리: Quartz cron 등록, 수동 실행·중지, 실행 이력·건수·오류 조회
- [ ] T10. 로그인(관리자 계정)과 감사 로그

### 메타 DB 스키마

| 테이블 | 주요 컬럼 | 용도 |
| --- | --- | --- |
| `db_connection` | id, name, host, port, database, username, password\_enc, options | 원천·대상 DB 접속 정보 |
| `masking_job` | id, name, connection\_id, source\_table, pk\_column, watermark\_column, target\_table, chunk\_size, cron, enabled | 매핑 단위 설정 |
| `masking_column` | id, job\_id, column\_name, entity\_types, policy, policy\_option | 컬럼별 탐지 대상과 치환 정책 |
| `job_watermark` | job\_id, last\_value, updated\_at | 증분 처리 위치 |
| `pattern_rule` | id, entity\_type, regex, validator, enabled, priority | 사용자 추가 정규식 |
| `audit_log` | id, actor, action, target, result, created\_at | 설정 변경·실행 감사 |

Spring Batch 메타 테이블(`BATCH_*`)도 같은 H2에 둔다.

### API (요약)

- `/api/connections` 등록·수정·삭제·접속 테스트
- `/api/connections/{id}/tables`, `/tables/{table}/columns` 스키마 탐색
- `/api/jobs` 매핑 CRUD, `/api/jobs/{id}/preview` 샘플 미리보기
- `/api/jobs/{id}/run`, `/stop`, `/executions` 실행과 이력
- `/api/test/detect` 텍스트 붙여넣기 탐지 테스트

### 화면

1. 접속 관리: 목록, 등록 폼, 접속 테스트 버튼
2. 작업 설정 마법사: 접속 선택 → 테이블 선택 → PK·증분 컬럼 → 마스킹 컬럼과 정책 → 대상 테이블
3. 미리보기: 원문 대신 스팬 위치·유형 하이라이트, 마스킹 결과 비교
4. 실행 현황: 작업별 마지막 실행, 처리 건수, 오류, 수동 실행
5. 탐지 테스트: 텍스트 입력 → 탐지 결과

### 완료 기준

- [ ] 탐지기 단위 테스트: 엔티티별 정탐·오탐 케이스 각 20개 이상 통과(Luhn·체크섬 실패 번호는 미탐지)
- [ ] 10만 건 샘플 테이블 배치가 중단 후 재시작 시 중복·누락 없이 완료
- [ ] 증분 실행 시 신규 행만 처리됨을 워터마크로 확인
- [ ] 애플리케이션 로그·예외에 원문 텍스트가 없음을 로그 grep으로 확인
- [ ] Windows와 Linux에서 같은 jar로 기동·배치 실행 확인

* [ ] 개발 서버 MariaDB에서 통합 테스트(접속 테스트, 스키마 탐색, 배치 증분·재시작) 통과. 접속 정보는 환경변수·로컬 설정 파일로만 주입하고 저장소에 커밋하지 않는다
* [ ] 통합 테스트용 원천 테이블은 가짜 개인정보로 채운 시드 스크립트로 생성(실데이터 사용 금지)

## 2단계: STT 특화와 규칙 기반 이름 탐지

2단계의 목표는 STT 발화 특유의 숫자 표현을 잡고, 모델 없이 규칙만으로 이름 Recall 베이스라인을 만드는 것이다. 이 베이스라인은 3단계에서 비교 기준이자 사전 라벨러로 쓰인다.

- [ ] ITN 정규화기: "공일공 일이삼사" 같은 한글 숫자와 띄어 읽은 숫자를 숫자열로 바꾸되, 원문 오프셋 매핑을 유지
- [ ] 정규화 텍스트에서 탐지 → 원문 위치로 역매핑해 마스킹
- [ ] 사전 탐지기: 성씨 사전, 예외 사전(상품명·지점명·상담 멘트), 사용자 사전 UI
- [ ] 규칙 이름 탐지기: 성씨 + 1\~2음절 + 문맥 단서("성함", "고객님", "씨", "본인") 점수화
- [ ] 문맥 규칙: 이름·번호를 묻는 상담사 발화 직후의 고객 발화 가중치 상향(화자 컬럼이 있을 때)
- [ ] 교정 피드백: 미리보기에서 "이름 아님 / 놓침"을 클릭하면 스팬 라벨로 저장(원문은 저장하지 않고, 학습 반출은 보안 승인 절차로만)

**완료 기준**: 사내에서 만든 STT 샘플 텍스트로 ITN 경유 번호 탐지가 동작하고, 규칙 이름 탐지의 Recall·Precision을 처음 측정해 기록한다.

**착수 전 확인**: 현재 STT 엔진 출력이 이미 숫자로 역정규화되는지. 이미 된다면 ITN 작업량은 크게 줄어든다.

## 3단계: 이름 인식 모델

3단계는 실데이터 평가셋을 먼저 고정한 뒤, 합성 데이터로 학습한 소형 NER 모델이 2단계 규칙 베이스라인보다 이름 Recall이 높을 때만 엔진에 붙인다. 학습은 Python 별도 저장소에서, 추론은 ONNX로 Java에 내장한다.

### 데이터

1. 평가셋: 사내 보안 환경에서 실제 상담 발화 300\~500문장. 2단계 규칙으로 사전 라벨링 후 사람이 교정. 학습에는 절대 섞지 않는다.
2. 합성 학습셋: 상담 템플릿 × 가짜 이름 수만 문장. 실제 개인정보가 없으므로 외부 LLM으로 발화 다양화 가능.
   - 조사 결합("김태양이에요", "태양 고객님"), 띄어쓰기 오류, 한 글자씩 부르기
   - 하드 네거티브: 이름처럼 생긴 일반어·지명·상품명·상담 멘트
3. 공개 보강: KLUE-NER의 PS 태그(CC BY-SA, 라이선스 확인)
4. 운영 피드백: 2단계 교정 UI로 쌓인 라벨(보안 승인 후)

### 모델

- 한국어 소형 인코더(KoELECTRA-small 계열) 토큰 분류, BIO 태그 `PER`(필요 시 `ADDR`)
- ONNX export → INT8 양자화, 목표 크기 수십 MB, CPU 추론
- Java 측: DJL tokenizers로 offset mapping, 512토큰 초과 시 발화 단위 분할·슬라이딩 윈도우, 배치 추론
- 엔진 통합: `ModelDetector`가 다른 탐지기와 같은 스팬을 반환, 임계값은 설정값

### 완료 기준

- [ ] 평가셋에서 이름 Recall이 규칙 베이스라인보다 높고, 목표치(예: 0.95 이상, 착수 시 확정) 달성
- [ ] 엔티티별 Recall·Precision을 출력하는 평가 스크립트가 저장소에 포함
- [ ] 모델 파일 버전과 임계값이 메타 DB에 기록되고 UI에서 확인 가능
- [ ] CPU 전용 서버에서 처리량 측정치 기록(문장/초)

## 4단계: 패키징·보안·운영

4단계가 끝나면 고객사 폐쇄망 서버에 설치본 하나로 넣고, 권한·감사 요건을 충족한 상태로 운영할 수 있어야 한다.

- [ ] jpackage 설치본: Windows(.msi 또는 app-image), Linux(.deb/.rpm 또는 tar), JRE·ONNX 모델 포함
- [ ] Docker 이미지와 compose 예시(메타 DB 볼륨, 모델 경로, 마스터키 주입)
- [ ] 마스터키 분리: 환경변수 또는 키파일, 키 교체 절차
- [ ] 역할 분리: 설정자 / 실행자 / 감사자, 필요 시 사내 SAML SSO 연동
- [ ] 감사 로그 조회·내보내기 화면
- [ ] 운영 문서: 설치, 업그레이드, 백업(메타 DB), 장애 시 재처리 절차
- [ ] 보안 점검: 의존성 취약점 스캔, 관리 UI HTTPS, 세션 타임아웃, 외부 호출 0건 확인

**완료 기준**: 인터넷이 차단된 VM(Windows 1대, Linux 1대)에서 설치 → 작업 등록 → 배치 실행 → 감사 로그 확인까지 문서만 보고 재현된다.

## Claude Code로 진행하는 방법

작업 목록의 T 항목 하나를 한 사이클(계획 승인 → 구현 → 리뷰 에이전트 → 완료 기준 확인 → 커밋)로 돌린다. 사이클을 작게 유지해야 리뷰가 실제로 동작한다.

**CLAUDE.md에 넣을 규칙**

- 원문 텍스트(마스킹 전 값)를 로그·예외 메시지·테스트 스냅샷에 출력 금지
- 마스킹 엔진 모듈(`masking-core`)은 Spring·DB 의존성 금지, 순수 Java + 단위 테스트
- 새 탐지기는 `Detector` 구현 + 정탐·오탐 테스트 케이스 동반 필수
- 테스트 데이터의 개인정보는 전부 가짜(검증 통과하는 가짜 카드번호는 테스트 전용 생성기로)
- 외부 네트워크 호출 코드·CDN 리소스 금지(폐쇄망 전제)
- 원천 DB에는 SELECT만, 쓰기는 대상 테이블에만

**에이전트 분리**

- 리뷰 에이전트(read-only) 체크 항목에 "원문 유출 경로", "원천 DB 쓰기 여부", "테스트 누락"을 고정으로 넣는다.
- 3단계 모델 학습은 별도 저장소(`pii-ner-train`)와 별도 CLAUDE.md로 분리한다.

**첫 작업**: T1 골격 → T2 엔진 코어 → T3 정규식 탐지기까지는 DB 없이 진행 가능하므로, 원천 DB 접근 승인 전에도 착수할 수 있다.

## 리스크와 착수 전 확인 사항

가장 큰 리스크는 실데이터 평가셋 확보가 늦어져 3단계 모델의 효과를 증명하지 못하는 것이다. 평가셋 라벨링 승인은 1단계와 병행해 지금 요청해 둔다.

| 리스크 | 영향 | 대응 |
| --- | --- | --- |
| 평가셋용 실데이터 접근 승인 지연 | 3단계 착수 불가 | 1단계 중 승인 요청, 보안 환경 내 라벨링 절차 합의 |
| STT 출력에 한글 숫자가 많음 | 번호 미탐 | 2단계 ITN 우선순위 상향 |
| 대용량 이력 테이블 | 배치 시간 증가 | keyset 페이징, PK 범위 파티셔닝, 처리량 측정 후 chunk 조정 |
| 마스킹 결과 테이블 자체도 민감 데이터로 분류 | 접근 통제 요구 | 대상 테이블 권한 분리 문서화 |
| 과마스킹으로 분석 품질 저하 | 현업 불만 | 예외 사전, 엔티티별 정책 조정 UI |

**착수 전 확인**

- [ ] 원천 이력 테이블 구조: 텍스트 컬럼, PK, 등록일시, 화자 구분 컬럼 유무
- [ ] 이력 테이블 규모(행 수, 일 증가량)
- [ ] STT 출력의 숫자 역정규화 여부
- [ ] 마스킹 대상 엔티티 목록과 정책을 고객사 보안 담당과 확정
- [ ] 마스킹 결과 테이블을 같은 DB에 둘지 별도 DB에 둘지

---

## 현재 진행 상황 (2026-10-22 기준, 이 섹션이 위 체크리스트보다 우선)

로컬 동작본(v0.1.0)이 있다. `run.cmd`(Windows) / `run.sh`로 포터블 MariaDB + 가짜 데이터 + 앱이 뜬다.

| 항목 | 상태 | 실제 구현 |
| --- | --- | --- |
| T1 골격 | 완료 | Gradle 9.1 Kotlin DSL 멀티모듈, Spring Boot 4.1.1, Java 25 실행(컴파일 타깃 21), React+Vite 빌드를 `processResources` 로 static 에 포함, H2 메타 DB(`meta-schema.sql`) |
| T2 엔진 코어 | 완료 | `masking-core`: `Detector`, `Span`, `SpanMerger`(합집합·우선순위), `Masker`(TAG/FULL/PARTIAL/HASH) |
| T3 정규식+검증기 | 완료 | 휴대폰·유선·SSML say-as 전화, 카드(Luhn), 주민·외국인(날짜·성별), 계좌(하이픈/문맥), 이메일 |
| (2단계 일부) 규칙 이름 | 완료 | `NameRuleDetector`: 호칭·단서, SSML 단독 노드, 끊어 읽기(고,영,환), 로마자 이름 |
| T4 접속 관리 | 완료 | AES-256-GCM 비밀번호 암호화, 접속별 Hikari 풀 |
| T5 스키마 탐색 | 완료 | information_schema 기반(MariaDB 전용) |
| T6 매핑·정책 | 완료 | 컬럼별 MASK/COPY/제외, 유형별 정책, 대상 테이블 DDL 자동 생성 |
| T7 미리보기 | 완료 | 저장 전 미리보기, 스팬 하이라이트, 탐지 테스트 화면 |
| T8 배치 | 완료 | Spring Batch 6.0.5 chunk 스텝(`MaskingJobFactory`: `KeysetSourceReader` → `MaskingProcessor` → `UpsertTargetWriter`). 청크마다 대상 커밋 → 워터마크·진행 저장(Batch 메타와 같은 H2 트랜잭션). 중지는 청크 경계에서 STOPPED, 이어서 실행은 워터마크 기준(실행마다 새 JobInstance). 화면 이력·API 형식 그대로(`job_execution`). 정수 PK 숫자 바인딩. 배치 예외는 `SanitizedBatchException`(유형·SQLState 만, 원인 미첨부). 통합 테스트 9건 통과(기존 5건 무수정 + `BatchMetadataIT` 4건) |
| T9 스케줄·실행 관리 | 완료(대체) | Quartz 대신 `ThreadPoolTaskScheduler`. 실행 이력·감사 로그 화면 |
| T10 로그인·감사 | **부분** | 감사 로그만 있음. 로그인·역할 분리 없음(127.0.0.1 바인딩으로 대신) |

**1단계 완료 기준 검증 (2026-10-22, 포터블 MariaDB 11.4 대상 `integrationTest` 5건 통과)**

| 완료 기준 | 상태 | 근거 |
| --- | --- | --- |
| 실행 결과 테이블 확인 | 통과 | `BatchCountIT`: 건수·PK 집합 동일, COPY 컬럼 동일, MASK 컬럼 = 엔진 재계산 결과(전 행) |
| 10만 건 중지·재시작 무결성 | 통과 | `BatchRestartIT`: 약 10만 건(시드 3,245건 × 31, id 중간 구멍·NULL 행 포함), 30% 지점 중지 → 재실행, read_count 합 = 원천 건수. 워터마크 되감기(커밋 후 저장 전 중단 모사)도 멱등 |
| 증분 재실행 | 통과 | `BatchIncrementalIT`: 새 250건만 처리(start/end_pk, masked_at), 새 행 없으면 0건 |
| 로그·이력 원문 미출력 | 통과 | `NoPlaintextLeakIT`: 카나리 + 탐지된 원문 조각을 콘솔·로그 파일·메타 DB 덤프·메타 DB 파일에서 검색, 실패 실행 포함. 일부러 흘린 값은 네 곳 모두에서 잡힘을 확인 |
| 개발 서버 MariaDB 연동 | 미검증 | 같은 테스트를 `PIIMASK_IT_DB_*` 만 바꿔 실행하면 됨 |
| Linux 실행 | 미검증 | |

실행 방법은 README "배치 통합 테스트". 위 결과는 배치 도중 원천에 쓰기가 없는 상태 기준이다.

**검증 중 찾은 앱 쪽 후속 과제 (미수정)**

- 증분 누락 가능성: 워터마크가 "읽은 최대 PK"라서, 배치 도중 커밋이 늦은 트랜잭션의 작은 id 행은 영구히 건너뛴다. 기존 행 수정도 반영되지 않는다. 대응 후보: 안전 지연(`created_at < NOW() - 간격`)까지만 읽기, 또는 anti-join 보정 실행. 미결정(T8 은 "동작 동일 유지"가 조건이라 보류)
- `Errors.safeMessage` 가 SQL 이 아닌 예외의 메시지 앞 200자를 그대로 이력에 남긴다. 메시지에 원문을 담는 예외가 생기면 `error_message` 로 샌다. 예외 유형·코드만 남기도록 좁히고 단위 테스트 추가
- 원천=대상 검사(`JobService.toDef`)가 대소문자를 구분한다. Windows MariaDB(`lower_case_table_names=1`)에서는 `Foo`/`foo` 가 같은 테이블이므로 `equalsIgnoreCase` 로
- 원문 미출력 검사가 아직 덮지 않은 경로: HTTP 응답(미리보기는 설계상 원문 포함), 중지(STOPPED) 실행, 2글자 이름
- (T8) `BATCH_*` 테이블이 실행마다 인스턴스·실행·스텝 1건씩 쌓이고 정리하지 않는다. `job_execution` 보존 정책을 정할 때 함께 정리
- (T8) 쓰기 실패 시 Batch 가 "Retry exhausted ... not skippable" ERROR 로그를 한 줄 더 남긴다(재시도 미사용 스텝의 기본 동작, 소음)
- (T8) 10만 건 중지·재시작 테스트 시간: 전환 전 38~40초, 전환 후 33~47초(실행마다 편차)

**테스트 데이터**: `docker/mariadb/init/02-seed.sql`(`scripts/gen_seed.py` 로 생성, 전부 가짜). 테이블 `stt_call_history`(3,245), `tts_request_log`(80), `tts_ssml_log`(901, 콜봇 SSML). `CALL make_bulk(30)` 으로 약 10만 행.

**알려진 미탐(의도적으로 남김)**: 한글로 읽은 숫자("공일공 사팔이일…") → 2단계 ITN, 문장 중간에 단서 없이 나온 이름 → 3단계 모델.
