# URL Viewer — Claude Code 프로젝트 컨텍스트

> 소스코드나 비즈니스로직 변경 시 이 파일을 업데이트할 것

# 명칭 규칙

| 시스템 | 내부 명칭 (데이터·코드) | 화면 표시 명칭 |
|--------|----------------------|--------------|
| Jira | `jira` (DB 컬럼명, 함수명, API 경로 등) | **SmartWay** |

화면 레이블·버튼·메시지에서는 "Jira" 대신 SmartWay로 표기한다. 함수명·DB 컬럼·상태값 등 내부 식별자는 변경하지 않는다.

# 프로젝트 개요

Spring Boot 기반 웹 애플리케이션. Controller 소스를 파싱하여 URL 목록을 추출하고, DB에 저장·조회·관리하는 내부 도구. 로컬은 H2, 운영·내부망 반입은 PostgreSQL을 전제로 하며 **동일 코드가 양쪽에서 에러 없이 동작**하도록 유지한다(아래 동시 호환 규칙).

# 앱 버전 표기(`APP_UI_VERSION`, 캐시·배포 식별용)

표시 위치는 상단 네비 브랜드 뒤(`nav.js`의 `APP_UI_VERSION`, 현재 예: `ver1.4.22`). **화면(`static/**`)만이 아니라 서버 로직·API·배치·DB 처리 등 배포 단위로 동작이 바뀌는 변경이 있으면 반드시 버전을 올린다** — 운영/반입 후 “같은 숫자인데 동작이 다름”을 줄이기 위함. `static/**` 캐시 이슈로 브라우저에서 숫자 확인이 특히 유효하다. 배포·반입 시마다 증가시키는 것을 원칙으로 한다. 로컬 재기동: `sh stop.sh` 후 `sh run.sh`(에이전트는 `run.sh`만 백그라운드 가능, CI·읽기 전용은 생략 가능). 형식 `ver<major>.<minor>.<patch>` — patch 기본 2자리 zero-pad, 100+는 자연 확장(강제 동일 자릿수 패딩 없음). patch=소규모 수정·버그픽스·내부 로직 조정·표시·문구 개선, minor=체감 기능 추가(오르면 patch `01`부터), major=큰 개편·호환(오르면 minor/patch 초기화), 애매하면 patch→minor 보수적. 기동 완료 로그: 동일 버전 문자열+주요 링크(`UiVersionStartupLogger`가 `nav.js`에서 파싱).

- **에이전트 응답 규칙(운영 편의)**: 이번 작업에서 **`APP_UI_VERSION`을 올렸거나**, 정적 UI(`src/main/resources/static/**`)를 변경한 경우, 에이전트는 **응답 마지막 줄에 반드시 현재 `APP_UI_VERSION`을 표기**한다. 예) `현재 앱 버전: ver1.4.22`

# 기술 스택 · 환경 제약 · DB

| 구분 | 항목 | 내용 |
|------|------|------|
| 스택 | Java / Spring Boot | 17 / 3.4.0 |
| 스택 | ORM / Parser / 배치 | Spring Data JPA / JavaParser 3.25.10+Regex / Quartz |
| 스택 | 엑셀 / 빌드 | ExcelJS 로컬 번들 / Maven |
| DB | 개발 | H2 2.3.232 파일 `./data/api-viewer-db`, 인메모리 가능 |
| DB | 운영 | PostgreSQL(내부망 반입 시, 우선 고려) |
| 제약 | 네트워크·라이브러리 | 망분리(CDN·인터넷 불가), 로컬 번들·내부 Maven만 |
| 제약 | ExcelJS | `/exceljs.min.js`만(CDN 금지) |

코드: JPQL 우선, 네이티브는 H2·PG 공통(`TRUNCATE`, `CONCAT('%',:q,'%')` LIKE, `CAST(x AS string)`). DDL 개발 `ddl-auto=update`, 운영 별도 DDL. PK `GenerationType.IDENTITY`, 컬럼 snake_case.

## H2·PostgreSQL 동시 호환(필수)

신규·수정되는 **영속성 코드(엔티티 매핑, Repository JPQL/네이티브, 커스텀 쿼리, 배치 SQL)** 는 **H2(로컬·테스트)와 PostgreSQL(운영) 양쪽에서 런타임 오류 없이** 동작해야 한다. 한쪽만 맞추고 머지하는 것을 금지한다.

| 영역 | 지침 |
|------|------|
| 쿼리 선택 | 가능하면 **JPQL / Spring Data 메서드명**으로 처리. 네이티브는 최소화. |
| 네이티브 SQL | H2·PG **문법·함수·바인딩**을 동시에 만족하는지 검토. dialect 전용 함수·예약어 금지. |
| 바인딩(특히 PG) | `(:param IS NULL OR … IN (:param))` 처럼 **동일 컬렉션/옵션 파라미터를 `IS NULL`과 `IN`에 같이 쓰는 패턴**은 PostgreSQL에서 타입 추론 실패(`42P18` 등)가 날 수 있음 → **쿼리 분리**(필터 있음/없음 메서드 분리) 또는 조건을 명시적으로 나눈다. |
| 스키마 | 엔티티·컬럼 타입은 양 DB에서 매핑 가능한 형태 유지. 운영 전용 DDL이 있으면 개발용 엔티티와 **의미 불일치**가 없도록 주석·문서로 맞춘다. |
| 검증 | 통합 테스트는 기본 H2. **네이티브·타입 민감 변경**은 반입 전 **PostgreSQL 연결으로 수동 또는 프로파일 기반 테스트**로 한 번 더 확인하는 것을 권장한다. |

위 원칙은 앞으로의 개발·리뷰 기본 전제다.

# 실행 방법

| 명령 | 설명 |
|------|------|
| `sh run.sh` | 빌드 후 실행 |
| `sh run.sh --no-build` | JAR만 실행 — `src/main/resources/static/**` 변경은 JAR에 미포함이므로 UI 반영 시 빌드 포함 실행 |
| `APIVIEWER_IDE_LAUNCH=1 sh run.sh` 또는 `sh run.sh --ide` | 서버만 기동(Chrome 자동 실행 생략). VS Code/Cursor **실행 및 디버그**의 `ApiViewer: run.sh 후 Chrome`·`preLaunchTask`용 |
| `run.bat` | Windows |

`application.properties`의 `spring.web.resources.cache.period=0` 등으로 장기 캐시 완화. 구버전이면 `--no-build` 누락 여부를 먼저 확인.

# 접속 URL

2단 네비(대시보드·URLViewer·EncryptViewer·설정). `nav.js`+`nav.css`, 각 페이지는 `nav-segment`/`nav-page` 메타 + `#nav-container`. 브랜드 클릭→대시보드, `pages: []`면 2단-B 미렌더.

| URL | 접근 |
|-----|------|
| `/` → `/dashboard/`, `/dashboard/` | 공개 |
| `/url-viewer/`, `/url-viewer/viewer.html`, `/url-viewer/call-stats.html`, `/url-viewer/url-block-monitor.html`, `/url-viewer/review.html`, `/url-viewer/workflow.html` | 공개 |
| `/url-viewer/extract.html`, `/settings/`, `/h2-console` | 관리자 |
| `/encrypt-viewer/` | 공개(자리표시자) |

`viewer.html` 세로 순서(대략): **조회 조건** → **검색 필터**(조회 전에도 표시·기본 펼침) → **상태 카드**(조회 성공 후 표시) → 안내·알림·일괄바 → **스냅샷 비교**(관리자, URL 테이블 직전) → 테이블. 카드형 `details`는 **「펼치기/접기」 pill(`.collapser`)** 클릭 시에만 접힘(summary 빈 영역 클릭으로 접히지 않음).

`GET /api/db/record-by-key` — 차단 모니터링 등에서 사용. **동일 레포·`apiPath`에서 HTTP 메소드 대소문자 무시 일치**를 먼저 시도하고, 없으면 `REQUEST`/`ALL`/빈값 등은 **동일 경로 행만**으로 폴백(복수 시 GET→POST→첫 행). `httpMethod` 파라미터는 생략 가능.

구 경로는 `WebConfig.addViewControllers` 리다이렉트. 인증: `auth.js` `AuthState`, 60초·포커스 `/api/auth/check`, `auth:change`, `data-admin-only`.

---

# 상태 (status) — 9 leaf v2, 상위 4-tier (사용 / 차단완료 / ① 차단대상 / ② 추가검토대상)

DB `status` 컬럼은 leaf 값을 직접 저장한다. 화면 라벨 = DB 값 (전체 표기).
대시보드는 7카드 한 줄로 통합: `총URL / 사용 / 차단완료 / 차단대상 잔여 / 차단대상 제외건 / 검토대상 / 삭제`.

## Leaf 9종

| 상위 | DB / 화면 값 | 자동 판정 조건 | 종류 |
|------|-------------|---------------|------|
| 사용 | `사용` | 기본값 (그 외, 호출 reviewThreshold+1↑ 흡수) | 자동 |
| 차단완료 | `차단완료` | `hasUrlBlock=Y` | 자동 (수정 불가, 별도 카테고리) |
| ① 차단대상 | `①-① 차단대상` | 호출 0 + 1년 경과 (옛 ①-②/①-③ 통합) | 자동 |
| ① 차단대상 | `①-② 담당자 판단` | (옛 후순위/업무종료) — 수동 지정 | 수동 |
| ① 차단대상 | `①-③ 현업요청 제외대상` | `reviewResult='차단대상 제외'` | 자동 |
| ① 차단대상 | `①-④ 사용으로 변경` | 차단대상 → 사용 (담당자 판단) | 수동 |
| ② 추가검토대상 | `②-① 호출0건+변경있음` | 호출 0 + 1년 미만 (옛 ②-①/②-② 통합) | 자동 |
| ② 추가검토대상 | `②-② 호출 3건 이하+변경없음` | 호출 1~reviewThreshold + 1년 경과 | 자동 |
| ② 추가검토대상 | `②-③ 사용으로 변경` | 추가검토대상 → 사용 (담당자 판단) | 수동 |

상위 카테고리 매핑: `차단완료` 단일 / `①-*` → ① 차단대상 / `②-*` → ② 추가검토대상.
**대시보드 카드 그룹**: 차단대상 잔여(①-① + ①-②) / 차단대상 제외건(①-③ + ①-④) / 검토대상(②-① + ②-② + ②-③).

## 자동 재계산 규칙 (umbrella sticky, v2)

`ApiStorageService.calculateStatus` 는 `statusOverridden=false` 인 레코드에 대해서만 동작:

1. **MANUAL_STATUSES 보존** — `①-② 담당자 판단` / `①-④ 사용으로 변경` / `②-③ 사용으로 변경`
2. **`hasUrlBlock=Y` → `차단완료`** (강한 신호)
3. **`reviewResult='차단대상 제외'` → `①-③ 현업요청 제외대상`** (강한 신호)
4. **현재 ①-* 또는 ②-* leaf** (umbrella 내부) → 조건이 `사용`을 가리킬 때만 `사용` 으로 전이.
   그 외에는 leaf 보존 (차단↔추가검토 전이 금지, umbrella 내 leaf 변경 금지)
5. **현재 `사용` 또는 `차단완료`** (umbrella 외부) → 조건에 따라 leaf 자유 할당:
   - 호출 0 + 1년 경과 → ①-① 차단대상
   - 호출 0 + 1년 미만 → ②-① 호출0건+변경있음
   - 1~reviewThreshold + 1년 경과 → ②-② 호출 3건 이하+변경없음
   - 호출 reviewThreshold+1↑ → 사용 (옛 ②-④ 흡수)

이 sticky 규칙으로 인해 한 번 분류된 차단대상/추가검토대상 leaf 는 호출수·커밋·플래그가 변해도
그대로 유지되며, 사용자가 수동으로 `사용` 으로 되돌리거나 조건이 명백히 `사용` 을 가리킬 때만 전이된다.

## 보조 플래그 (하위 호환 보존, v2 에서 분기 미사용)

| 컬럼 | 의미 | 비고 |
|------|------|------|
| `log_work_excluded` | 옛 ①-②/①-③ 분기 보조값 | v2 통합으로 분기 안 함 — 컬럼만 보존 |
| `recent_log_only`   | 옛 ②-①/②-② 분기 보조값 | v2 통합으로 분기 안 함 — 컬럼만 보존 |

## 임계값 설정 (GlobalConfig)

| 키 | 기본값 | 의미 |
|----|--------|------|
| `reviewThreshold` | 3 | ②-② "호출 N건 이하" 의 N. 호출 1~N + 1년 경과 → ②-② 분류 |

## 수동 판단 상태 (담당자 결정, v2 — 3종)

`ApiStorageService.MANUAL_STATUSES` 로 정의된 3개 상태값. 사용자가 일괄변경/단건수정으로 이 상태를 선택하면 `statusOverridden=true` 가 자동 ON 되어 추출 재계산 시 보존된다.

| 상태 | 의미 | 입력 경로 |
|------|------|----------|
| `①-② 담당자 판단` | 차단대상 leaf 중 담당자가 명시적으로 판단/지정 (옛 후순위/업무종료) | 일괄변경 / 단건수정 / 엑셀 업로드 |
| `①-④ 사용으로 변경` | 자동 ①-① 차단대상이지만 담당자 검토 결과 사용중으로 확정 | 동일 |
| `②-③ 사용으로 변경` | 자동 ②-* 추가검토대상이지만 담당자 검토 결과 사용중으로 확정 | 동일 |

| 상태확정 | 설명 |
|----------|------|
| 확정 | `statusOverridden=true`, 자동 재계산 안 함, 수정 불가 (파란 배지) |
| 미확정 | `statusOverridden=false`, 자동 재계산 대상 (회색 배지) |
| 변경불가 | `차단완료` 건, 일체 수정 불가 (비활성 배지) |

# 인증

| 항목 | 내용 |
|------|------|
| 방식 | 서버 측 UUID 토큰 (AuthService, 8시간 TTL) |
| 발급 | `POST /api/verify-password` → 토큰 반환 |
| 전송 | `X-Admin-Token` 헤더에 토큰 포함 |
| 검증 | AdminInterceptor가 보호 경로에서 토큰 검증 |
| 보호 경로 | `/api/extract`, `/api/config/**`, `/api/logs/**`, `/api/schedule/**`, `/api/db/delete-all`, `/api/db/seed`, `/api/mock/**`, `/api/snapshots/**`, `/api/ai/**` |

## 사내 AI (OpenAI 호환 chat)

| 항목 | 내용 |
|------|------|
| 연결 | `global_config`: `ai_open_api_base_url`, `ai_open_api_token`, `ai_open_api_chat_path`(기본 `/v1/chat/completions`), `ai_open_api_model`, `ai_ops_digest_job_types` |
| YAML | `repos-config.yml` `global` 동명 키 — 임포트 시 DB 반영 |
| 프롬프트 | 테이블 `ai_prompt_template` (`slug`, `title`, `body`, `enabled`) — 기본 `menu_inference`, `ops_digest` |
| API | `POST /api/ai/menu-suggestion` `{recordId}` — 관련 메뉴 제안; `GET/PUT/POST/DELETE /api/config/ai/templates` — 템플릿 CRUD; `POST /api/config/ai/templates/{id}/test` `{body?}` — 샘플 변수 치환 후 AI 시험(본문 생략 시 DB 값) |
| 배치 요약 | `BatchHistoryJobListener` 성공 후 비동기 `ops_digest` (트리거 jobType 기본: GIT_PULL_EXTRACT, APM_COLLECT, DATA_BACKUP) → `global_config.ai_last_ops_digest` · 공개 `GET /api/config/ops-digest-summary` → 공통 네비 상단 노란 배너(운영·배치 요약) |

- Mock URL 분석데이터: `POST /api/mock/analysis/generate?repoName=&countMin=&countMax=` — `countMin`·`countMax` 함께 지정 시 레포당 **[min,max] 균등 랜덤** 건수. `count` 단독(1~5000)은 고정 건수(하위 호환). Mock 상태 분포에 **`삭제`**(약 5%, `statusOverridden=true`) 포함.
- 공개 경로: `GET /api/config/global`, `GET /api/config/repos`, `GET /api/config/repos/sync-warnings`, `GET /api/config/ops-digest-summary`, `GET /api/schedule/history/dashboard-daily?days=` (`WebConfig`에서 `/api/schedule/**` 예외 — 대시보드는 `days=1`로 당일·배치별 1행 집계, 기간별 상세는 설정 배치 이력)

# 주요 DB 컬럼 (api_record)

| 컬럼 | 설명 |
|------|------|
| `(repository_name, api_path, http_method)` | UNIQUE 복합키 |
| `status` / `status_overridden` | leaf 상태 / 확정 여부 |
| `has_url_block` / `is_deprecated` | 차단 throw YN / @Deprecated YN |
| `controller_file_path` | /{repoName}/{repoPath} |
| `call_count` 등 | 총·월·주 호출 |
| `block_criteria`, `team_override`, `manager_override` | 차단기준, 팀·담당 오버라이드 |
| `description_override` | 내용 우선(주석·ApiOperation 등보다 상위) |
| `manager_mappings`(repo_config) | programId→담당 JSON, 없으면 팀 대표 |
| `blocked_date` / `blocked_reason`, `review_*` | 차단일·근거, 현업검토 |
| `git_history` | 최근 5커밋 JSON |
| `status_change_log` | `" \| "` 구분 이력, FIFO 50건(`MAX_CHANGE_LOG_ENTRIES`) |
| `test_suspect_reason` | 콤마 구분 의심 사유, `POST /api/recalculate-test-suspect`, 상태 무관 |
| `path_param_pattern` | `api_path` 내 `{name}` 목록(콤마+공백 조인). 추출 시 자동·`POST /api/recalculate-path-param-pattern` 일괄 보정. 스냅샷 행에도 동일 컬럼 |

global_config(차단 모니터): `block_monitor_whatap_referer`(Referer 템플릿, `{pcode}`=`whatap_pcode`, 호스트=`whatap_url`), `bot_keywords`, `test_suspect_keywords`(기본 9종·경로 등 매칭·`fullUrl` 제외), `snapshot_retention_days`(null이면 yml/global 기본 365).

repo_config: `whatap_okinds` / `whatap_okinds_name` — ID 리스트와 동일 인덱스 표시명(콤마).

## 스냅샷(Extract 히스토리) — `api_record_snapshot` / `api_record_snapshot_row`

풀 스냅샷만(v1.2~, `source_repo` NULL). `삭제` 행 포함. diff `deleted`≠DB `삭제`. label에 trigger 레포. 레거시 `source_repo` 행은 보존·표준 경로 미사용. 생성: Extract 후·`POST /api/snapshots`. 정리: cleanup, DELETE id/ids/by-date. `restore-live`: api_record 전삭제 후 row INSERT(호출 테이블 불변). list/resolve는 풀만; `/{id}/records|counts`는 repo 필터. 메타: id, snapshot_at, snapshot_type, label, created_ip, source_repo, record_count. 행: 복합키+api_record 복제+source_id.

# 로깅

SLF4J+Logback, 콘솔+파일, MDC `[ADMIN/USER/SYSTEM] [IP]`, `./logs/app.log`, 일별 롤링 90일, 설정 페이지 달력 뷰어.

## 로깅 정책 (코드 작성 원칙)

**모든 기능 구현 시 반드시 준수한다.** DEBUG: 진입·분기·결과·외부 API 요·응답·배치 건별. INFO: 연동·배치 집계·중요 상태 전이만(CRUD·단순 조회 생략). WARN: 진행+예외적 스킵/폴백. ERROR: 중단+메시지·컨텍스트. 예: `log.debug("[SmartWay] syncRecordToJira 시작: recordId={}", recordId);`

# Quartz 배치

| Job | 설명 | 기본 상태 |
|-----|------|----------|
| GIT_PULL_EXTRACT | Git Pull 후 전체 레포 추출 | 비활성 |
| APM_COLLECT | APM 호출건수 수집 (와탭/제니퍼, jobParam=수집일수) | 비활성 |
| BLOCK_URL_MONITOR | 차단 URL 모니터링 조회·집계 로그 (jobParam=`R[0137]B[01]T[01]` 범위·봇제외·IT테스트시간대제외) | 비활성 |
| DB_SNAPSHOT | DB 파일 사이즈 일별 기록 | (환경별) |
| DATA_BACKUP | 분석데이터·호출이력 자동 백업 | 비활성 |
| JIRA_SYNC | SmartWay(Jira) 동기화 | 비활성 |
| WHATAP_KEEPALIVE | 와탭 쿠키 세션 keepalive | 비활성 |
| _(주기 옵션)_ | 매일(지정 시각) / 매주(요일+시각) / 매 N시간 / 크론 직접 | 설정 UI |

---

# 엑셀 업로드/다운로드 원칙

## 데이터 유효성 검증 (필수)
엑셀 다운로드 시 정해진 값만 허용하는 컬럼은 반드시 ExcelJS `dataValidations` 드롭다운으로 구현한다.
업로드 시에도 동일 컬럼에 대해 서버에서 허용값 외 입력을 무시하거나 로그 처리한다.

| 컬럼 | 허용 값 |
|------|---------|
| 상태 | 사용, 차단완료, 최우선 차단대상, 후순위 차단대상, 검토필요대상, ①-⑥ 사용으로 변경, ②-⑤ 사용으로 변경 |
| 상태확정 | 확정, 미확정 |
| 현업검토결과 | 차단대상 제외, 차단확정, 판단불가 |

## 업로드 시 분석일시 처리
`lastAnalyzedAt`(분석일시)은 엑셀 셀 값을 무시하고 **업로드 시각**을 사용한다.

## 업로드 매칭 키
(repositoryName, apiPath, httpMethod) 3-tuple 로 기존 레코드를 매칭한다. 미매칭 건은 스킵(생성하지 않음).

## 업로드 반영 필드
viewer.html 엑셀 업로드는 아래 사용자 편집 필드만 반영한다. 추출/APM 자동 채움 필드(호출건수·Deprecated·차단일자·차단근거·Git이력 등)는 엑셀에 값이 있어도 무시한다.

| 엑셀 헤더 | DB 필드 | 비고 |
|-----------|--------|------|
| 상태 / 상태확정 | `status` / `statusOverridden` | 상태 변경 시 `statusOverridden` 자동 true |
| 팀 | `teamOverride` | |
| 담당자 | `managerOverride` (+ `managerOverridden` 플래그) | 값 있으면 수동 플래그 ON (재추출 시 programId 매핑이 덮어쓰지 않음), 빈값이면 OFF |
| 관련 메뉴(또는 기능) | `descriptionOverride` | ApiOperation/Description/컨트롤러주석보다 우선 |
| 차단기준 / 비고 | `blockCriteria` / `memo` | |
| 현업검토결과 / 현업검토의견 | `reviewResult` / `reviewOpinion` | |
| 검토단계 | `reviewStage` | Jira 역동기화 시 덮어써질 수 있음 |
| CBO예정일자 / 배포예정일자 / 배포CSR | `cboScheduledDate` / `deployScheduledDate` / `deployCsr` | |

빈 셀은 해당 필드를 `null` 로 clear 처리한다.

## 차단완료 행
`status = '차단완료'` 행은 상태/상태확정 컬럼만 수정 불가. 그 외 편집 필드(팀·담당자·내용·검토·비고·예정일 등)는 업데이트 가능.
엑셀로 `status = '차단완료'` 로 승격시키는 변경도 무시한다(소스코드 기반 자동 판정 보호).

## 엑셀 파싱 (ExcelJS)
- 헤더 행의 셀 값으로 컬럼 인덱스를 동적 매핑 → 컬럼 순서 변경에 내성
- `xlCellStr()` 헬퍼로 RichText, null 등 다양한 셀 타입 통일 처리

# 역할 정의 및 SmartWay 운영 방침

## 역할별 책임

| 역할 | 소속/명칭 | 주요 책임 |
|------|----------|-----------|
| 관리자 | IT카드개발팀 | URLViewer 시스템 관리, URL 관리 총괄, 협조전 상신, 현업 검토 요청 |
| 개발 수행 | IT개발실 | URLViewer 결과를 업무담당자별로 배포받아 미사용 URL 검증 → 현업 검토 결과 반영 → 개발계 수정 → CBO 테스트 → 운영 배포 → 모니터링 |
| 현업 담당자 | 업무부서 | 협조전 확인, 메일로 수신한 차단대상 목록 검토, 의견 전달 |

## SmartWay(Jira) 운영 방침

- **용도**: 결재/승인보다는 **공식적인 현황 관리 및 임원·현업 공유**가 주목적
- **발행 주체**: URLViewer 시스템에서 자동 발행
- **역방향 동기화**(SmartWay → URLViewer 상태 반영): **현재 확정되지 않은 고려 대상** — 도입 여부 및 시점은 추후 재검토
- 현업 담당자의 정식 의견 수렴은 **협조전 + 메일** 경로로 이루어지며, SmartWay는 진행 가시성·이력 관리 목적

> 이 방침은 workflow.html "5. 역할별 Use Case", "7. URL 관리 상세 흐름"의 라벨과 흐름에 반영된다.

## 디자인 컬러 스키마 (workflow.html 및 공통)

| 역할/영역 | 의미 | Header/Num 배경 | Border | Body BG | 라벨 색 |
|-----------|------|-----------------|--------|---------|---------|
| 관리자(IT카드개발팀) | 다크 슬레이트 | `#1e293b` | `#cbd5e1` | `#f8fafc` | `#334155` |
| IT개발실 | 파랑 | `#2563eb` | `#93c5fd` / `#bfdbfe` | `#f0f7ff` / `#eff6ff` | `#1d4ed8` |
| 자동화 / 배치 / SmartWay | 보라 | `#7c3aed` | `#ddd6fe` / `#c4b5fd` / `#d8b4fe` | `#faf5ff` / `#ede9fe` | `#6d28d9` |
| 현업 | 연두 (차분 톤) | `#4d7c0f` | `#d9f99d` | `#f7fee7` / `#ecfccb` | `#365314` |
| CBO 배포 | 진한 노랑 | `#fde68a` / num `#d97706` | `#fde68a` | `#fffbeb` | `#92400e` / `#b45309` |
| 운영 배포 | 주황 | `#fed7aa` / num `#c2410c` | `#fdba74` | `#fff7ed` | `#c2410c` / `#9a3412` |

CBO→운영 노랑→주황 명도, 운영 주황 전용. 배지 Body/Header 톤, `.sc-use`·현업 초록 통일. 신규 색 금지·다크모드 대비.

# workflow.html 관리 규칙

공개 스윔레인 문서. 담당: `card-designer`(`.claude/agents/card-designer.md`). 인라인 CSS만·외부 CSS 금지·card-designer가 Edit로 수정·테스트 생략.

## 업데이트 트리거 — 아래 변경이 발생하면 workflow.html을 반드시 갱신한다

| 변경 유형 | 갱신 대상 |
|-----------|----------|
| 신규 페이지 추가 / 기존 페이지 제거 | 스윔레인의 step 카드 및 "8. 페이지별 접근 권한" 섹션 |
| 상태(status) 종류·판정 조건 변경 | "4. 상태 판정 기준" 비IT 요약·기술 상세 카드 그리드 |
| 내용(descriptionOverride/ApiOperation/Description/컨트롤러주석) 판정 우선순위 변경 | "11. 내용 판정 플로우차트"의 카드 체인 |
| 역할(관리자/IT개발실/배치) 업무 흐름 변경 | "3. IT개발실 업무" 스윔레인, "5. 역할별 Use Case" 카드, "7. URL 관리 상세 흐름" STEP |
| Quartz Job 종류·이름 변경 | "2. 자동 처리" 스윔레인 |
| URL 라이프사이클 단계 변경 | "6. URL 관리 Life Cycle" 타임라인 및 담당 영역 범례 |
| 인증 방식·보호 경로 변경 | "8. 페이지별 접근 권한" 인증 카드 |
| SmartWay(Jira) 연동 정책 변경 | "5. 역할별 Use Case" / "7. URL 관리 상세 흐름" 및 본 문서 "역할 정의 및 SmartWay 운영 방침" |

본문 섹션 1~12 목차·번호 변경 시 위 표와 동기화.

---

# 소스 반입 압축 규칙

| 항목 | 내용 |
|------|------|
| 제외 대상 | `target/`, `.git/`, `.idea/`, `.claude/`, `data/`, `logs/`, `.sh`, `.bat`, `mvnw`, `*.jar` (lib/*.jar 포함), `application.properties`, `repos-config.yml` (루트), `src/main/resources/repos-config.yml` (리소스) |
| 출력 경로 | `/Users/baegmyeongseon/Downloads/ApiViewer.zip` (절대 경로 사용) |
| 실행 위치 | `cd /Users/baegmyeongseon/LP_DEV` 후 `zip -r 출력경로 ApiViewer --exclude "ApiViewer/..."` |
| 기존 파일 | 기존 zip 존재 시 반드시 먼저 삭제 후 재생성 (업데이트 모드 방지) |

## application.properties 포함 여부 규칙

- **기본: 제외** — `application.properties`는 DB URL, 포트, 비밀번호 등 환경별 설정이 달라 기본 제외
- **변경 시: 사용자에게 먼저 확인** — 압축 직전 `git diff`로 변경 여부를 체크하고, 변경이 있으면 포함 여부를 사용자에게 물어본 후 결정
- 확인 메시지 예시: "`application.properties`가 변경되었습니다. 압축에 포함할까요? (포함 시 DB 접속 정보 등이 노출될 수 있습니다.)"

## repos-config.yml 위치 및 압축 규칙

- **위치**: `src/main/resources/repos-config.yml` (classpath)
  - 이유: Railway 등 JAR 배포 환경에서 작업 디렉토리에 외부 파일이 없어 자동 동기화가 누락되는 문제를 방지하기 위해 classpath 리소스로 배포
  - 앱 기동 시 `StartupConfigLoader`(`src/main/java/com/baek/viewer/StartupConfigLoader.java`)가 ① 작업 디렉토리 `./repos-config.yml` → ② classpath `repos-config.yml` 순으로 로드
  - 로컬 개발 중 임시 오버라이드가 필요하면 프로젝트 루트에 `./repos-config.yml`을 두면 우선 적용됨 (JAR 재빌드 불필요)
- **주의**: 소스 일괄 복사(반입) 시 `src/main/resources/repos-config.yml`이 **실수로 덮어씌워지지 않도록** 복사 전 백업하거나 제외 패턴을 확인한다
- **압축 시: 항상 제외** — `application.properties`와 동일하게 취급. 환경별 레포 설정·토큰 등이 포함되므로 압축에 절대 포함하지 않는다 (제외 패턴: `src/main/resources/repos-config.yml`)
