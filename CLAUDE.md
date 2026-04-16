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

| URL | 설명 | 접근 |
|-----|------|------|
| `/` | 대시보드 (5가지 상태 통계) | 공개 |
| `/extract.html` | URL 분석 | 관리자 전용 |
| `/viewer.html` | 이력 조회 | 공개 |
| `/review.html` | 현업 검토 (차단대상만) | 공개 |
| `/call-stats.html` | URL 호출현황 차트 | 공개 |
| `/workflow.html` | 업무 플로우 (스윔레인 다이어그램) | 공개 |
| `/settings.html` | 설정·로그·배치·데이터관리 | 관리자 전용 |
| `/h2-console` | H2 DB 콘솔 (sa / 빈 패스워드) | 관리자 전용 |

---

# 상태 (status) — 5가지

| 상태 | 자동 계산 조건 | 비고 |
|------|---------------|------|
| 차단완료 | ①@Deprecated ②[URL차단작업] 주석 ③UnsupportedOperationException — 3가지 AND | 모든 수정 불가 |
| 최우선 차단대상 | 호출 0건 + 커밋 1년 경과 | 자동 |
| 후순위 차단대상 | 수동 설정 (침해사고 로그, IT담당자 검토건) | 수동 |
| 검토필요 차단대상 | 호출 0건+커밋 1년 미만 / 호출 1~N건+커밋 1년 경과 | 자동 |
| 사용 | 기본값 | — |

| 상태확정 | 설명 |
|----------|------|
| 확정 | `statusOverridden=true`, 자동 재계산 안 함, 수정 불가 (파란 배지) |
| 미확정 | `statusOverridden=false`, 자동 재계산 대상 (회색 배지) |
| 변경불가 | 차단완료 건, 일체 수정 불가 (비활성 배지) |

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
| `status` | 5가지 상태값 |
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
| 상태 | 사용, 차단완료, 최우선 차단대상, 후순위 차단대상, 추가검토필요 차단대상 |
| 상태확정 | 확정, 미확정 |
| 현업검토결과 | 차단대상 제외, 차단확정, 판단불가 |

## 업로드 시 분석일시 처리
`lastAnalyzedAt`(분석일시)은 엑셀 셀 값을 무시하고 **업로드 시각**을 사용한다.

## 업로드 매칭 키
(repositoryName, apiPath, httpMethod) 3-tuple 로 기존 레코드를 매칭한다. 미매칭 건은 스킵(생성하지 않음).

## 차단완료 행
`status = '차단완료'`인 행은 업로드로도 수정 불가. 서버에서 스킵 처리.

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

---

# workflow.html 관리 규칙

`/workflow.html`은 ApiViewer 전체 업무 흐름을 **스윔레인 다이어그램**으로 시각화한 공개 문서 페이지다.  
**관리 담당**: `card-designer` 에이전트 (`.claude/agents/card-designer.md`).

## 업데이트 트리거 — 아래 변경이 발생하면 workflow.html을 반드시 갱신한다

| 변경 유형 | 갱신 대상 |
|-----------|----------|
| 신규 페이지 추가 / 기존 페이지 제거 | 스윔레인의 step 카드 및 "8. 페이지별 접근 권한" 섹션 |
| 상태(status) 종류·판정 조건 변경 | "3. 상태 판정 기준" 카드 그리드 |
| 내용(descriptionOverride/ApiOperation/Description/컨트롤러주석) 판정 우선순위 변경 | "11. 내용 판정 플로우차트"의 카드 체인 |
| 역할(관리자/사용자/배치) 업무 흐름 변경 | "4. 사용자" 스윔레인, "5. 역할별 Use Case" 카드, "7. URL 관리 상세 흐름" STEP |
| Quartz Job 종류·이름 변경 | "2. 자동 처리" 스윔레인 |
| URL 라이프사이클 단계 변경 | "6. URL 관리 Life Cycle" 타임라인 및 담당 영역 범례 |
| 인증 방식·보호 경로 변경 | "8. 페이지별 접근 권한" 인증 카드 |
| SmartWay(Jira) 연동 정책 변경 | "5. 역할별 Use Case" / "7. URL 관리 상세 흐름" 및 본 문서 "역할 정의 및 SmartWay 운영 방침" |

## 구조 요약 (현재)

```
workflow.html
├─ 1. 관리자(IT카드개발팀) 업무    (스윔레인: 소스추출→배치설정→레포매핑→상태확정)
├─ 2. 자동 처리                   (스윔레인: GIT_PULL_EXTRACT→APM_DAILY/WEEKLY→상태재계산→DATA_BACKUP)
├─ 3. 상태 판정 기준 (5가지 상태)
├─ 4. 사용자                      (스윔레인: 대시보드→URL현황→호출현황→현업검토)
├─ 5. 역할별 Use Case             (배치 시스템 → 관리자 → IT개발실 → 현업 담당자)
├─ 6. URL 관리 Life Cycle         (자동화 1·2·5 / 현업담당자 4 / IT개발실 3·6~9)
├─ 7. URL 관리 상세 흐름          (STEP 1~9, 반복 주기 안내)
├─ 8. 페이지별 접근 권한
├─ 9. URL 차단 여부 판정 기준
├─ 10. URL 차단여부 플로우차트    (개발자 참고용)
├─ 11. 내용(관련 메뉴/기능) 판정 플로우차트
└─ 12. 테이블 / 컬럼 명세서
```

## 편집 원칙

- 스타일은 페이지 내 인라인 CSS로 관리. 외부 스타일시트 추가 금지.
- `card-designer` 에이전트가 직접 Edit 도구로 수정. 별도 테스트 불필요.
- 내용이 바뀌면 이 섹션의 "구조 요약"과 "업데이트 트리거" 표도 함께 최신화한다.

---

# 소스 반입 압축 규칙

| 항목 | 내용 |
|------|------|
| 제외 대상 | `target/`, `.git/`, `.idea/`, `.claude/`, `data/`, `logs/`, `.sh`, `.bat`, `mvnw`, `*.jar` (lib/*.jar 포함), `application.properties`, `src/main/resources/repos-config.yml` |
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
