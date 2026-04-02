# ApiViewer — Claude Code 프로젝트 컨텍스트

소스코드나 비즈니스로직이 변경되는 작업들은 CLAUDE.MD파일에 무조건 정리해서 업데이트 함. 이때 기존의 내용은 되도록 수정하지 않음

## 프로젝트 개요
Spring Boot 기반의 웹 애플리케이션. Spring MVC Controller 소스를 파싱하여 API 목록을 추출하고, H2 임베디드 DB에 저장·조회하는 내부 도구.

## 기술 스택
- **Java**: 17
- **Spring Boot**: 3.4.0
- **ORM**: Spring Data JPA
- **DB**: H2 2.3.232 (파일 모드, `./data/api-viewer-db`)
- **Parser**: JavaParser 3.25.10 (Controller 소스 분석, Regex 폴백)
- **빌드**: Maven (`run.sh` / `run.bat`)
- **설정 파일**: `application.properties` (Spring Boot), `repos-config.yml` (레포별 설정)

## 실행 방법
```sh
sh run.sh              # 빌드 후 실행
sh run.sh --no-build   # JAR 있을 때 바로 실행
run.bat                # Windows
```

### 접속 URL
| URL | 설명 |
|-----|------|
| http://localhost:8080 | 대시보드 (통계) |
| http://localhost:8080/extract.html | API 추출 페이지 |
| http://localhost:8080/viewer.html | DB 저장 이력 조회 |
| http://localhost:8080/settings.html | 레포지토리 설정 관리 |
| http://localhost:8080/h2-console | H2 DB 콘솔 (JDBC: `jdbc:h2:file:./data/api-viewer-db`, user: sa, pw: 없음) |

---

## 디렉토리 구조
```
src/main/java/com/baek/viewer/
├── controller/
│   ├── ApiViewController.java   # 추출·조회·진행상황·Whatap·DB조회 API
│   └── ConfigController.java    # 레포설정 CRUD, 공통설정 API
├── model/
│   ├── ApiInfo.java             # 추출 결과 (git1~git5 포함, 메모리)
│   ├── ApiRecord.java           # JPA 엔티티 (DB 저장용, gitHistory JSON)
│   ├── ExtractRequest.java      # 추출 요청 DTO
│   ├── GlobalConfig.java        # 공통 설정 엔티티 (startDate, endDate)
│   ├── RepoConfig.java          # 레포별 설정 엔티티
│   ├── WhatapRequest.java       # Whatap 조회 요청 DTO
│   └── WhatapResult.java        # Whatap 조회 결과 DTO
├── repository/
│   ├── ApiRecordRepository.java
│   ├── GlobalConfigRepository.java
│   └── RepoConfigRepository.java
└── service/
    ├── ApiExtractorService.java  # 핵심: Controller 파싱 (JavaParser + Regex 폴백)
    ├── ApiStorageService.java    # 추출 결과 H2 저장/갱신
    └── WhatapService.java        # Whatap APM 호출건수 조회

src/main/resources/static/
├── index.html      # 대시보드 (통계: 전체/팀별/담당자별/레포별/상태별/Method별)
├── extract.html    # API 추출 페이지
├── viewer.html     # DB 이력 조회 페이지
└── settings.html   # 레포지토리 설정 관리 페이지
```

---

## 주요 기능 및 동작 방식

### 1. API 추출 (index.html)
- Controller 파일 탐색 → JavaParser로 파싱 → Regex 폴백
- `parallelStream`으로 병렬 처리
- 추출은 **비동기** (POST `/api/extract` → 202 즉시 반환)
- 프론트에서 500ms 간격으로 GET `/api/progress` 폴링 → 오버레이 진행 표시
- 완료 후 GET `/api/list`로 결과 수신
- **레포지토리명 입력 시** 추출 완료 후 자동으로 H2에 저장

### 2. 설정 관리 (settings.html)
- **repos-config.yml이 유일한 소스**: 기동 시 자동 동기화 (`StartupConfigLoader`), settings.html에서 🔄 YAML 동기화 버튼으로 수동 재동기화 가능
- 레포 직접 추가/편집 UI 제거 → YAML 파일 편집 후 동기화 방식
- YAML 파싱: SnakeYAML (`ReposYamlConfig` POJO 바인딩)
- `global.period.startDate/endDate` → GlobalConfig 저장 (whatap → period로 변경, APM 도구 중립)
- `repositories[]` → RepoConfig 저장 (레포명 기준 upsert)
- **YAML 파일 경로**: `application.properties`의 `api.viewer.repos-config-path` (기본값: `./repos-config.yml`)

### viewer.html URL 호출 컬럼
- API 경로 컬럼 바로 옆에 "URL 호출" 컬럼 추가
- `ApiRecord.fullUrl` (= domain + apiPath, 추출 시 저장) 값으로 `<a target="_blank">` 하이퍼링크 표시
- **공통 설정**: Whatap 조회 시작일/종료일 (GlobalConfig, id=1 단일 레코드)
- **레포별 설정**: RepoConfig (REPO_NAME, ROOT_PATH, DOMAIN, GIT_BIN_PATH, TEAM_NAME, MANAGER_NAME, API_PATH_PREFIX, PATH_CONSTANTS, Whatap 전체)
- index.html 상단 드롭다운에서 레포 선택 시 모든 입력 필드 자동 입력

### 3. Git 커밋 이력
- 파일당 최근 **5개** 커밋 조회 (`git log -5 --pretty=format:%as|%an|%s`)
- ApiInfo: git1~git5 (String[] 각 [날짜, 커미터, 메시지])
- DB 저장: `git_history` TEXT 컬럼에 JSON 배열로 직렬화
  ```json
  [{"date":"2025-01-15","author":"홍길동","message":"fix bug"}, ...]
  ```
- index.html 테이블: git1~git5 모두 표시 (구분선으로 구분)

### 4. DB 저장 규칙 (ApiStorageService)
- 키: `(repository_name, api_path, http_method)` — 날짜는 키에서 제거
- **upsert**: 동일 키가 있으면 업데이트, 없으면 신규 삽입
- `last_analyzed_date`: 추출할 때마다 오늘 날짜로 갱신 (키 아님)
- `statusOverridden=true`인 레코드는 추출/호출건수 업데이트 시 status 변경 안 함

### 5. 상태(status) 자동 계산 로직
| 상태 | 조건 |
|------|------|
| 차단완료 | `isDeprecated=Y` AND `fullComment`에 `[URL차단작업]` 포함 |
| 사용 | 그 외 (기본값) |
- `statusOverridden=true`: 사용자 수동 변경 시 설정, 이후 자동 재계산 안 함
- 자동계산 복원: PATCH `/api/db/status`에 `status: null` 전송 → `statusOverridden=false`로 리셋

### 5-1. 차단대상 / 차단대상기준 (수동 설정 전용)
- `blockTarget`: `최우선 차단대상`, `후순위 차단대상`, null (프로그램 자동 계산 안 함)
- `blockCriteria`: 사유 텍스트 (프로그램 자동 계산 안 함)
- 최우선 차단대상 기준 옵션: `호출 0건 + 커밋1년경과`, `호출 0건 + 커밋1년경과(침해사고 로그 제외)`, `IT담당자검토건`, `기타`
- 후순위 차단대상 기준 옵션: `호출 0건 + 커밋1년미만`, `호출 1~3건 + 커밋1년경과`, `호출 4건이상 + 커밋1년경과`, `기타`
- PATCH `/api/db/status`에 `blockTarget`, `blockCriteria` 필드 포함하여 일괄 변경 가능
- viewer.html 하단 bulk bar에서 상태/차단대상/차단대상기준 동시 변경 가능

### 6. 이력 조회 (viewer.html)
- 레포지토리 선택 → 조회 버튼 클릭 → API 목록 조회 (달력 제거)
- 상태 배지: 사용(초록), 차단검토필요(노랑), 차단대상(주황), 차단완료(회색)
- 체크박스로 여러 항목 선택 → 하단 bulk bar에서 상태 일괄 변경
- 수동 설정된 항목에는 🔒 아이콘 표시
- 통계 pill 클릭 → 해당 상태 필터 적용
- `callCount`, `lastAnalyzedDate` 컬럼 추가

### 7. Whatap APM 연동
- 기간+쿠키 입력 → 실제 호출건수 조회 → API 목록에 매핑
- 레포 설정의 Whatap 정보 + 공통 설정의 기간이 자동 입력됨
- 조회 완료 후 자동으로 POST `/api/db/call-counts`로 DB에 호출건수 반영 → 상태 재계산

---

## API 엔드포인트 목록

### ApiViewController (`/api`)
| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/extract` | 추출 시작 (비동기, 202 반환) |
| GET | `/api/progress` | 추출 진행상황 조회 |
| GET | `/api/list` | 메모리 캐시된 추출 결과 |
| GET | `/api/status` | 추출 중 여부 확인 |
| GET | `/api/db/repositories` | DB 저장된 레포 목록 |
| GET | `/api/db/apis?repository=` | DB에서 API 목록 조회 (repository 생략 시 전체 조회) |
| GET | `/api/db/stats` | 전체 통계 (상태별/Method별/팀별/담당자별/레포별) |
| PATCH | `/api/db/status` | 상태 일괄 변경 `{ids:[],status:"차단대상"}` |
| POST | `/api/db/call-counts` | Whatap 호출건수 DB 반영 `{repoName,callCounts:{}}` |
| POST | `/api/whatap/stats` | Whatap 호출건수 조회 |

### ConfigController (`/api/config`)
| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/config/global` | 공통 설정 조회 |
| PUT | `/api/config/global` | 공통 설정 저장 |
| GET | `/api/config/repos` | 레포 설정 목록 |
| GET | `/api/config/repos/{id}` | 레포 설정 단건 조회 |
| POST | `/api/config/repos` | 레포 설정 추가 |
| PUT | `/api/config/repos/{id}` | 레포 설정 수정 |
| DELETE | `/api/config/repos/{id}` | 레포 설정 삭제 |

---

## DB 테이블 구조

### api_record
추출된 API 이력. `(repository_name, api_path, http_method)` UNIQUE 제약.
- `last_analyzed_date`: 마지막 추출 날짜 (키 아님, 추출마다 갱신)
- `status`: 사용/차단검토필요/차단대상/차단완료 (기본: 사용)
- `status_overridden`: 수동 상태 설정 여부 (true면 자동 재계산 안 함)
- `call_count`: Whatap 조회 건수 (null=미조회)
- `is_deprecated`: @Deprecated 어노테이션 여부 (Y/N, 상태 계산용 내부 필드)

### repo_config
레포지토리별 설정. `repo_name` UNIQUE.

### global_config
공통 설정. id=1 단일 레코드 (startDate, endDate, reviewThreshold).

---

## 업무명(businessName) 필드
- `repos-config.yml`의 각 레포 항목에 `businessName` 필드 추가 (예: `businessName: 카드회원관리`)
- `ReposYamlConfig.RepoEntry`, `RepoConfig` 엔티티에 `businessName` 필드 포함
- `YamlConfigService.importFromYaml()`에서 `rc.setBusinessName(entry.getBusinessName())` 저장
- **viewer.html**: 테이블 앞부분에 레포지토리(+업무명 sub-text), 팀, 담당자 3개 컬럼 추가
- **index.html (대시보드)**: 레포지토리별 테이블에 업무명 컬럼 추가, 담당자별 테이블에 팀 컬럼 추가
- **settings.html**: 모달 폼에 업무명 입력 필드 추가
- **엑셀 다운로드**: 레포지토리, 업무명, 팀, 담당자 순서로 앞 컬럼에 포함
- **GET `/api/db/stats`**: byRepo 항목에 `businessName` 포함, byManager 항목에 `team` 포함

## 추출 페이지 (extract.html) 구조
- 추출 전용 페이지 — 결과 테이블 없음
- **전체 레포 추출**: 드롭다운에서 "🔄 전체 레포 추출" 선택 → 설정된 모든 레포를 순차 추출
- **개별 레포 추출**: 드롭다운에서 특정 레포 선택 → 해당 레포만 추출
- **인라인 진행 카드**: 프로그레스바 + 파일 처리 현황 (팝업 아님, 하단에 표시)
- **로그 패널**: 터미널 스타일, 서버에서 실시간 로그 수신 (파일별 처리 결과, JavaParser 폴백, 에러 상세)
- **결과 요약**: 추출 완료 후 성공/실패 요약 + "이력 조회 →" 링크 (자동 이동 없음)
- **서버 로그**: `ApiExtractorService.extractLogs` — 파일별 OK/WARN/ERROR 로그 축적, `/api/progress` 응답에 `logs` 배열 포함
- Whatap 호출건수 조회 카드는 유지 (접힘/펼침)

## viewer.html 테이블 컬럼 (17개)
| 순서 | 컬럼명 |
|------|--------|
| 0 | 체크박스 |
| 1 | 레포지토리/업무명 |
| 2 | 팀 |
| 3 | 담당자 |
| 4 | Method |
| 5 | API 경로 |
| 6 | URL 호출 |
| 7 | 컨트롤러 |
| 8 | 메소드명 |
| 9 | 프로그램ID |
| 10 | 설명 |
| 11 | 호출건수 |
| 12 | 상태 |
| 13 | 차단대상 |
| 14 | 차단대상기준 |
| 15 | 마지막분석일 |
| 16 | 최근 커밋 이력 |

---

## 주의사항 / 개발 규칙
- Controller 파일 탐색 조건: 파일명에 `Controller` 또는 `Conrtoller`(오타 허용) 포함
- JavaParser 파싱 실패 시 Regex 폴백 자동 적용
- `parallelStream` 사용으로 `currentFile` 추적 시 race condition 있음 (진행 표시 참고용)
- H2 `ddl-auto=update` → 엔티티 변경 시 컬럼 자동 추가 (기존 데이터 보존)
- 기존 DB 레코드에 새 컬럼 추가 시 해당 컬럼은 null로 표시됨
- git_history가 null인 레코드는 viewer에서 "이력 없음"으로 표시
