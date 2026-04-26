# URL Viewer — Claude Code 프로젝트 컨텍스트

> 소스코드나 비즈니스로직 변경 시 이 파일을 업데이트할 것

---

# 명칭 규칙

| 시스템 | 내부 명칭 (데이터·코드) | 화면 표시 명칭 |
|--------|----------------------|--------------|
| Jira | `jira` (DB 컬럼명, 함수명, API 경로 등) | **SmartWay** |

- 화면에 노출되는 레이블·버튼·메시지에서 "Jira"는 **SmartWay**로 표기한다.
- 함수명(`saveJiraConfig`, `bulkSyncToJira` 등), DB 컬럼(`jira_issue_key`), 상태값(`JIRA_ISSUED`, `JIRA_APPROVED`) 등 내부 식별자는 변경하지 않는다.

---

# 프로젝트 개요

Spring Boot 기반 웹 애플리케이션. Controller 소스를 파싱하여 URL 목록을 추출하고, H2 DB에 저장·조회·관리하는 내부 도구.

---

# 기술 스택

| 항목 | 버전/도구 |
|------|----------|
| Java | 17 |
| Spring Boot | 3.4.0 |
| ORM | Spring Data JPA |
| DB (개발) | H2 2.3.232 (파일 모드 `./data/api-viewer-db`) |
| DB (운영) | PostgreSQL (내부망 반입 시 사용, **우선 고려 대상**) |
| Parser | JavaParser 3.25.10 + Regex 폴백 |
| 배치 | Spring Quartz |
| 엑셀 | ExcelJS (로컬 번들, CDN 사용 금지) |
| 빌드 | Maven |

---

# 환경 제약

| 항목 | 내용 |
|------|------|
| 네트워크 | 망분리 환경, 외부 CDN/인터넷 접근 불가 |
| 라이브러리 | 로컬 번들 또는 내부 Maven 저장소만 사용 |
| ExcelJS | `/exceljs.min.js` 로컬 파일 사용 (CDN 절대 금지) |

---

# DB 환경 (개발 vs 운영)

| 구분 | DB | 용도 |
|------|-----|------|
| 개발 | H2 2.3.232 (인메모리/파일 모드) | 로컬 개발·테스트 |
| 운영 | PostgreSQL (내부망 반입 시) | **실제 서비스 환경, 우선 고려 대상** |

## 코드 작성 시 주의사항

- **JPQL 우선**: 네이티브 SQL 대신 JPQL/Spring Data를 사용해 양쪽 DB 모두 호환 유지
- **네이티브 쿼리**: 불가피하게 작성 시 H2·PostgreSQL 양쪽에서 동작하는 문법 사용
  - `TRUNCATE TABLE`은 양쪽 모두 지원 (현재 사용 중)
  - `LIKE` 패턴: JPQL `CONCAT('%', :q, '%')` 방식 사용 (양쪽 호환)
  - `CAST(x AS string)`: JPQL에서 양쪽 호환 (현재 사용 중)
- **DDL**: H2 자동생성(`ddl-auto=update`)으로 개발, 운영은 별도 DDL 스크립트 적용
- **시퀀스/AUTO_INCREMENT**: `GenerationType.IDENTITY` 사용 (H2·PostgreSQL 모두 지원)
- **대소문자**: PostgreSQL은 컬럼명 대소문자 구분 없음 — snake_case 유지
- **페이지네이션**: Spring Data `Pageable` 사용 시 양쪽 자동 변환됨 (LIMIT/OFFSET)
- **Boolean**: JPA `boolean` 타입 매핑 — H2는 BOOLEAN, PostgreSQL은 BOOLEAN (동일)

---

# 실행 방법

| 명령 | 설명 |
|------|------|
| `sh run.sh` | 빌드 후 실행 |
| `sh run.sh --no-build` | JAR만 실행 |
| `run.bat` | Windows |

---

# 접속 URL

네비게이션은 **대시보드 + 3개 대영역** (대시보드 / URLViewer / EncryptViewer / 설정) 2단 네비. 공통 네비는 `static/common/nav.js` + `static/common/nav.css` 가 렌더링하며 각 페이지는 `<meta name="nav-segment">`, `<meta name="nav-page">` + `<div id="nav-container">` 만 둔다. 1단 브랜드("IT소스 관리포털") 클릭 시 대시보드로 이동. 대시보드 세그먼트는 서브메뉴 없이 단일 페이지 — `pages: []` 일 때 2단-B 는 렌더링되지 않는다.

| URL | 설명 | 접근 |
|-----|------|------|
| `/` | `/dashboard/` 로 리다이렉트 | — |
| `/dashboard/` | IT소스 관리포털 통합 대시보드 (URL 분석·URL 호출·암복호화 모듈·암복호화 사용 프로그램 4개 섹션) | 공개 |
| `/url-viewer/` | `/url-viewer/viewer.html` 로 리다이렉트 | — |
| `/url-viewer/viewer.html` | URL 분석 현황 조회 | 공개 |
| `/url-viewer/call-stats.html` | URL 호출현황 차트 | 공개 |
| `/url-viewer/url-block-monitor.html` | URL차단 모니터링 (와탭 실시간 — 봇 제외) | 공개 |
| `/url-viewer/review.html` | 현업 검토 (차단대상만) | 공개 |
| `/url-viewer/extract.html` | URL 분석 | 관리자 전용 |
| `/url-viewer/workflow.html` | 업무 플로우 (스윔레인 다이어그램) | 공개 |
| `/encrypt-viewer/` | 암복호화 현황 (준비 중 자리표시자) | 공개 |
| `/settings/` | 설정·로그·배치·데이터관리 | 관리자 전용 |
| `/h2-console` | H2 DB 콘솔 (sa / 빈 패스워드) | 관리자 전용 |

**구 경로 호환**: `/viewer.html`·`/extract.html`·`/settings.html` 등 이전 경로는 `WebConfig.addViewControllers` 가 신 경로로 자동 리다이렉트.

**관리자 인증**: `static/common/auth.js` 의 `AuthState` 가 60초 주기 + 포커스 시 `/api/auth/check` 로 능동 검증, 변화 시 `auth:change` CustomEvent 로 전역 전파. 공통 네비는 `data-admin-only` 속성이 붙은 요소를 자동 토글하고, 로그인/로그아웃 인디케이터를 1단에 노출한다.

---

# 상태 (status) — 9 leaf v2, 상위 4-tier (사용 / 차단완료 / ① 차단대상 / ② 추가검토대상)

DB `status` 컬럼은 leaf 값을 직접 저장한다. 화면 라벨 = DB 값 (전체 표기).
대시보드는 7카드 한 줄로 통합: `총URL / 사용 / 차단완료 / 차단대상 잔여 / 차단대상 예외건 / 검토대상 / 삭제`.

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
**대시보드 카드 그룹**: 차단대상 잔여(①-①) / 차단대상 예외건(①-② + ①-③ + ①-④) / 검토대상(②-① + ②-② + ②-③).

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
| `reviewUpperThreshold` | 10 | v2 에서 사실상 무시 (옛 ②-④ 사용 흡수) — 호환 유지 |

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

---

# 인증

| 항목 | 내용 |
|------|------|
| 방식 | 서버 측 UUID 토큰 (AuthService, 8시간 TTL) |
| 발급 | `POST /api/verify-password` → 토큰 반환 |
| 전송 | `X-Admin-Token` 헤더에 토큰 포함 |
| 검증 | AdminInterceptor가 보호 경로에서 토큰 검증 |
| 보호 경로 | `/api/extract`, `/api/config/**`, `/api/logs/**`, `/api/schedule/**`, `/api/db/delete-all`, `/api/db/seed`, `/api/mock/**` |
| 공개 경로 | `GET /api/config/global`, `GET /api/config/repos` |

---

# 주요 DB 컬럼 (api_record)

| 컬럼 | 설명 |
|------|------|
| `(repository_name, api_path, http_method)` | UNIQUE 복합키 |
| `status` | 7가지 상태값 (자동 5 + 수동 2) |
| `status_overridden` | 상태확정 여부 (boolean) |
| `has_url_block` | UnsupportedOperationException throw 여부 (Y/N) |
| `is_deprecated` | @Deprecated 어노테이션 여부 (Y/N) |
| `controller_file_path` | /{repoName}/{repoPath} |
| `call_count` / `call_count_month` / `call_count_week` | 호출건수 3분할 (총/1달/1주) |
| `block_criteria` | 차단기준 텍스트 |
| `team_override` / `manager_override` | 레코드별 팀/담당자 오버라이드 |
| `description_override` | 내용(관련 메뉴/기능) 사용자 오버라이드. 설정 시 ApiOperation/Description/컨트롤러주석보다 우선 |
| `manager_mappings` (repo_config) | 프로그램ID별 담당자 매핑 JSON. 매칭 없으면 `managerName`(팀 대표)로 폴백 |
| `blocked_date` / `blocked_reason` | 차단일자/차단근거 (fullComment에서 파싱) |
| `review_result` / `review_opinion` | 현업검토결과/의견 |
| `git_history` | JSON 배열 (최근 5개 커밋) |
| `status_change_log` | 상태/호출건수 변경 이력 (`" \| "` 구분, FIFO 최근 50건만 보존) — `ApiStorageService.MAX_CHANGE_LOG_ENTRIES` |
| `test_suspect_reason` | 테스트용 의심 사유 — `"URL-test, 메소드-sample"` 콤마 구분 텍스트. null=비의심. 분석 추출 시 자동 매칭 + 키워드 변경 후 `POST /api/recalculate-test-suspect` 로 재평가. 상태와 독립 (사용 중 URL 도 의심 표시 가능) |

## 주요 DB 컬럼 (global_config) — URL 차단 모니터링

| 컬럼 | 설명 |
|------|------|
| `block_monitor_whatap_referer` | 와탭 `/yard/api/flush` 호출 시 Referer 헤더에 쓸 경로 템플릿 (기본 `/v2/project/apm/{pcode}/new/tx_profile`). 호스트(scheme+host+port)는 각 레포의 `repo_config.whatap_url`(프로필 폴백값)에서 자동 추출, `{pcode}`는 `repo_config.whatap_pcode`로 치환. 별도 Base URL 컬럼 없이 기존 와탭 프로필 인프라 재사용 |
| `bot_keywords` | 봇 제외 키워드 콤마 리스트. userAgent / clientType / clientName 부분일치 (대소문자 구분) |
| `test_suspect_keywords` | 테스트용 의심 키워드 콤마 리스트 (기본 9종: `test,sample,mock,테스트,샘플,demo,dummy,fixture,스텁`). URL 경로/메소드명/컨트롤러명/파일경로/주석/@ApiOperation/@Description 부분일치, **대소문자 무시**. `fullUrl` 은 의도적 제외 (도메인 false positive 방지) |

## 주요 DB 컬럼 (repo_config) — okind 매핑

| 컬럼 | 설명 |
|------|------|
| `whatap_okinds` | 와탭 okind ID 리스트 (콤마 또는 JSON 배열). 예: "1,2,3" |
| `whatap_okinds_name` | 위 ID와 **인덱스 매칭**되는 okind 표시명 (콤마). 예: "서비스A,서비스B,서비스C" |

---

# 로깅

| 항목 | 내용 |
|------|------|
| 프레임워크 | SLF4J + Logback |
| 출력 | 콘솔 + 파일 동시 |
| MDC | `[ADMIN/USER/SYSTEM] [IP]` 모든 로그에 자동 포함 |
| 파일 위치 | `./logs/app.log` |
| 롤링 | 일자별 (`app-yyyy-MM-dd.log`), 90일 보관 |
| 뷰어 | 설정 페이지에서 달력으로 일자별 조회 |

## 로깅 정책 (코드 작성 원칙)

**모든 기능 구현 시 반드시 준수한다.**

### DEBUG 레벨 — 개발자 추적용 (필수)
- **모든 메서드 진입/분기/결과**를 DEBUG로 남긴다. 운영에서 문제가 생겼을 때 DEBUG를 켜면 전체 흐름이 재현될 수 있어야 한다.
- 외부 API 호출 시: 요청 URL·파라미터·바디, 응답 상태코드·바디 모두 DEBUG로 기록
- 조건 분기마다 어떤 경로로 진입했는지 기록 (e.g., `"기존 키 존재 → UPDATE"`, `"신규 → CREATE"`)
- 루프/배치 처리 시 건별 처리 내용과 중간 상태 기록

```java
// 예시
log.debug("[SmartWay] syncRecordToJira 시작: recordId={}", recordId);
log.debug("[SmartWay] Step3. 담당자 매핑: manager={}, team={} → jiraAccountId={}", manager, team, assignee);
log.debug("[SmartWay] Step5. 발행 방식: {} (기존 issueKey={})", wasNew ? "CREATE" : "UPDATE", issueKey);
```

### INFO 레벨 — 운영 모니터링용 (판단하여 사용)
- **외부 시스템 연동 결과**: 호출 성공·실패, 생성된 ID/Key
- **배치·일괄 처리 완료**: 처리 건수 집계 (총/성공/실패)
- **상태 전이**: 중요한 데이터 상태 변화 (e.g., 이슈 생성, 동기화 완료)
- 일반 CRUD나 단순 조회는 INFO 불필요

```java
// 예시
log.info("[SmartWay] 이슈 생성 완료: key={}, self={}", key, self);
log.info("[SmartWay] 레포 {} 동기화: 대상={}, 생성={}, 갱신={}, 실패={}", repo, total, created, updated, failed);
```

### WARN / ERROR 레벨
- **WARN**: 처리는 계속되지만 예상치 못한 상황 (e.g., 이슈 미존재 스킵, 컴포넌트 생성 실패 후 폴백)
- **ERROR**: 처리가 중단되는 예외 상황. `e.getMessage()`와 URL/컨텍스트 함께 기록

---

# Quartz 배치

| Job | 설명 | 기본 상태 |
|-----|------|----------|
| GIT_PULL_EXTRACT | Git Pull 후 전체 레포 추출 | 비활성 |
| APM_DAILY | APM 호출건수 일별 수집 | 비활성 |
| APM_WEEKLY | APM 호출건수 주별 수집 | 비활성 |

| 주기 옵션 | 설명 |
|-----------|------|
| 매일 | 지정 시각에 실행 |
| 매주 | 요일 + 시각 |
| 매 N시간 | 간격 지정 |
| 크론식 | 직접 입력 |

---

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

---

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

역할·영역별 색상은 다음 규칙을 준수한다. 신규 카드·배지·스윔레인·플로우 다이어그램 작성 시 재사용한다.

| 역할/영역 | 의미 | Header/Num 배경 | Border | Body BG | 라벨 색 |
|-----------|------|-----------------|--------|---------|---------|
| 관리자(IT카드개발팀) | 다크 슬레이트 | `#1e293b` | `#cbd5e1` | `#f8fafc` | `#334155` |
| IT개발실 | 파랑 | `#2563eb` | `#93c5fd` / `#bfdbfe` | `#f0f7ff` / `#eff6ff` | `#1d4ed8` |
| 자동화 / 배치 / SmartWay | 보라 | `#7c3aed` | `#ddd6fe` / `#c4b5fd` / `#d8b4fe` | `#faf5ff` / `#ede9fe` | `#6d28d9` |
| 현업 | 연두 (차분 톤) | `#4d7c0f` | `#d9f99d` | `#f7fee7` / `#ecfccb` | `#365314` |
| CBO 배포 | 진한 노랑 | `#fde68a` / num `#d97706` | `#fde68a` | `#fffbeb` | `#92400e` / `#b45309` |
| 운영 배포 | 주황 | `#fed7aa` / num `#c2410c` | `#fdba74` | `#fff7ed` | `#c2410c` / `#9a3412` |

- 배지(pill)의 밝은 배경은 Body BG 톤, 선명한 배경은 Header 톤을 사용한다.
- 배포 영역(CBO → 운영)은 **yellow → orange 같은 hue 계열**에서 명도 차이로 단계 순서를 표현한다. 주황은 운영 배포 전용이며 다른 역할에 재사용하지 않는다.
- 현업 lime은 `.sc-use`(상태 '사용') 카드와 업무 배지에 공통 적용해 초록 계열 톤을 하나로 통일한다.
- 다크모드에서는 `.card`, `.border`, `.text-muted` 등 기본 토큰이 우선 적용되며 인라인 색상은 배경·텍스트 대비가 유지되는지 확인 필요.
- 새로 색을 추가하지 말고 위 표 색 또는 근접 톤(Tailwind 200/300/500/700 레벨)만 사용한다.

---

# workflow.html 관리 규칙

`/workflow.html`은 ApiViewer 전체 업무 흐름을 **스윔레인 다이어그램**으로 시각화한 공개 문서 페이지다.  
**관리 담당**: `card-designer` 에이전트 (`.claude/agents/card-designer.md`).

## 업데이트 트리거 — 아래 변경이 발생하면 workflow.html을 반드시 갱신한다

| 변경 유형 | 갱신 대상 |
|-----------|----------|
| 신규 페이지 추가 / 기존 페이지 제거 | 스윔레인의 step 카드 및 "8. 페이지별 접근 권한" 섹션 |
| 상태(status) 종류·판정 조건 변경 | "4. 상태 판정 기준" 카드 그리드 |
| 내용(descriptionOverride/ApiOperation/Description/컨트롤러주석) 판정 우선순위 변경 | "11. 내용 판정 플로우차트"의 카드 체인 |
| 역할(관리자/IT개발실/배치) 업무 흐름 변경 | "3. IT개발실 업무" 스윔레인, "5. 역할별 Use Case" 카드, "7. URL 관리 상세 흐름" STEP |
| Quartz Job 종류·이름 변경 | "2. 자동 처리" 스윔레인 |
| URL 라이프사이클 단계 변경 | "6. URL 관리 Life Cycle" 타임라인 및 담당 영역 범례 |
| 인증 방식·보호 경로 변경 | "8. 페이지별 접근 권한" 인증 카드 |
| SmartWay(Jira) 연동 정책 변경 | "5. 역할별 Use Case" / "7. URL 관리 상세 흐름" 및 본 문서 "역할 정의 및 SmartWay 운영 방침" |

## 구조 요약 (현재)

```
workflow.html
├─ 1. 관리자(IT카드개발팀) 업무    (스윔레인: 소스추출→배치설정→레포매핑→상태확정)
├─ 2. 자동 처리                   (스윔레인: GIT_PULL_EXTRACT→APM_DAILY/WEEKLY→상태재계산→DATA_BACKUP)
├─ 3. IT개발실 업무               (스윔레인: 대시보드→URL현황→호출현황→검토결과 업로드→차단URL 모니터링)
├─ 4. 상태 판정 기준 (자동 5가지 + 수동 2가지) ※ 자동: 차단완료/최우선 차단대상/후순위 차단대상/검토필요대상/사용. 수동: ①-⑥ 사용으로 변경 / ②-⑤ 사용으로 변경 (담당자 결정, 자동 재계산 제외)
├─ 5. 역할별 Use Case             (배치 시스템 → 관리자 → IT개발실 → 현업 담당자)
├─ 6. URL 관리 Life Cycle         (자동화 1·2·5 / 현업담당자 4 / IT개발실 3·6~8)
├─ 7. URL 관리 상세 흐름          (STEP 1~10, 반복 주기 안내 — STEP 10: 차단 URL 인입 모니터링)
├─ 8. 페이지별 접근 권한
├─ 9. URL 차단 여부 판정 기준
├─ 10. URL 차단여부 플로우차트    (개발자 참고용 — R1: 첫 실행 문장 UnsupportedOperationException throw 단일 조건)
├─ 11. 내용(관련 메뉴/기능) 판정 플로우차트
└─ 12. 테이블 / 컬럼 명세서        (block_marking_incomplete · test_suspect_reason / test_suspect_keywords 컬럼 포함)
```

## 편집 원칙

- 스타일은 페이지 내 인라인 CSS로 관리. 외부 스타일시트 추가 금지.
- `card-designer` 에이전트가 직접 Edit 도구로 수정. 별도 테스트 불필요.
- 내용이 바뀌면 이 섹션의 "구조 요약"과 "업데이트 트리거" 표도 함께 최신화한다.

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
