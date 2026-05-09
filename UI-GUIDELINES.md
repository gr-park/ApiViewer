# URL Viewer — UI 스타일 가이드

정적 화면(`src/main/resources/static/**`) 개편·추가 시 준수한다. 구현의 단일 원천은 [`src/main/resources/static/css/common.css`](src/main/resources/static/css/common.css)로 두고, 페이지별 인라인 `<style>` 중복은 점진적으로 제거하는 것을 권장한다.

**버전**: UI 대개편·본 가이드 도입과 함께 `APP_UI_VERSION` **메이저** 승격(예: `ver4.0.01`). 이후 화면/CSS 변경 시 [CLAUDE.md](CLAUDE.md)의 버전 규칙에 따라 패치·마이너·메이저를 올린다.

---

## 1. UI 계층 — 대분류 · 중분류 · 소분류

타이포 단계는 [`common.css`](src/main/resources/static/css/common.css) 토큰으로 통일한다: **`--fs-section-major`(대)** > **`--fs-section-mid`(중)** > **`--fs-md`(본문·소)**.

### 1.1 대분류 — `.section-header`

- **용도**: 한 화면 또는 설정 탭 **안에서** 기능 묶음을 나눌 때(대시보드 블록, 설정 탭 내 구역).
- **마크업**: `<div class="section-header">` + **`<h3>`** 제목 + 선택 `<span class="section-sub">` 한 줄 설명.
- **시각**: 제목만 **`--fs-section-major`**(17px), **밑줄은 `h3` 바로 아래**에만(`border-bottom`). 부제는 밑줄 아래.
- **접근성**: 페이지당 `h1`은 앱 헤더 등 한 곳을 우선하고, 대분류는 `h2`/`h3` 중 한 단계로 일관되게 쓴다(중복 `h3`만 쌓이지 않게).

### 1.2 중분류 — `.section-heading` + `span.dot`

- **용도**: 카드·`details` 블록의 **주 제목**.
- **마크업**: `<div class="section-heading section-heading--{save|query|analyze|delete|neutral}">` + `<span class="dot"></span>` + 라벨.
- **시각**: **`--fs-section-mid`(14px)**, `font-weight: 700`, dot 10px.
- 보조 설명은 같은 줄 끝의 작은 `span`(muted) 또는 `.field-hint`로 두고, **이모지는 필수 아님**(방향 화살표 등 최소화 정책과 병행).

### 1.3 소분류 — `.section-subheading` + `span.dot`(선택)

- **용도**: **한 카드 안**의 하위 블록 제목(예: 메일 탭에서 「메일 서버」 아래 「메일 발송」).
- **마크업**: `<div class="section-subheading section-subheading--{동일 modifier}">` + `<span class="dot"></span>` + 라벨.
- **시각**: **`--fs-md`(13px, 본문과 동일)**, `font-weight: 600`, dot 7px.

### 1.4 대소문자 보존

- 위 제목류에 **`text-transform: uppercase`를 쓰지 않는다.**
- 영문·약어·DB명·제품명은 HTML에 적은 대로 표시한다.  
  예: `api_record`, `SmartWay`, `URL`, `TOP 10`

### 1.5 의미별 색 — dot + 제목 글자색

**중분류**(`.section-heading`)와 **소분류**(`.section-subheading`) 모두 동일 modifier 규칙을 쓴다. 애매하면 **중립**.

| 의미 | dot / 강조 | 타이틀 글자 | 예시 |
|------|------------|-------------|------|
| **저장** | `--primary` | 진한 파랑 계열 | 설정 저장, DB 반영, 백업 |
| **조회** | 시안 | 청록 진한색 | 조회 조건, 필터, 현황·리포트 |
| **분석** | `--purple` | 보라 진한색 | 추출·APM·진단·스냅샷 |
| **삭제** | `--danger` | `--danger-dark` | 삭제·복구 전 경고 |
| **중립** | `--text-muted` | `var(--text)` | 순수 안내, workflow 문서 |

---

## 2. 버튼

- **모양**: 주요 액션 버튼은 **둥근 사각형**으로 통일 (예: `border-radius: var(--radius-lg)` ≈ 10px).
- **의미별 색**:

| 의미 | 클래스(권장) | 색 |
|------|----------------|-----|
| 저장·확정·공식 반영 | `btn-blue` | 파랑 |
| 엑셀 업로드·다운로드·샘플 | `btn-green` | 연두 (`--success`) |
| 취소·보조 닫기 | `btn-gray` / `btn-cancel` 통일 | 슬레이트 회색 |
| 분석·파싱·추출 실행 | `btn-purple` | 보라 |
| 위험 작업 | `btn-danger` | 빨강 |

- **조회 버튼**: 기존 패턴 유지 → **`btn-blue`** (타이틀의 “조회” 톤은 시안으로 구분 가능).
- **보조**: `btn-ghost` (+추가, 새로고침, 정렬 등).

### 2.1 날짜·기간 «빠른 선택» — 붙은 버튼 그룹 (`.date-quick-seg`)

- **용도**: 조회 조건에서 **당일 / 전일 / N일** 등 연속된 기간 프리셋을 한 덩어리로 보여 줄 때. `btn-blue`·`btn-green`·`btn-ghost` 단독 버튼 나열과 겹치지 않도록 **슬레이트(회청) 계열**로 통일한다.
- **마크업**:
  - 컨테이너: `<span class="date-quick-seg" role="group" aria-label="…">` (또는 `<div>`)
  - 각 칸: `<button type="button" class="date-quick-seg__btn">레이블</button>`
  - 선택 상태(옵션): 해당 버튼에 `.active` (예: 배치 이력·차단 모니터링에서 마지막으로 누른 프리셋 강조)
- **스타일 단일 원천**: [`common.css`](src/main/resources/static/css/common.css) 의 `.date-quick-seg` / `.date-quick-seg__btn` / `.active` / 다크모드 변형. 페이지별로 색을 바꾸지 않는다.
- **구현 시**: 기존 `display:flex; gap:6px` + 여러 개 `btn-ghost` 패턴은 이 그룹으로 교체하는 것을 권장한다.

---

## 3. 접기 / 펼치기

- **pill 유지**: `border-radius: 999px`  
  예: `viewer.html`의 `.collapser`, `status-guide.js`의 `.sg-detail-toggle`, 유사 `summary-btn`
- 일반 `.btn`과 시각적으로 구분한다.

---

## 4. 적용 범위(참고)

dot 타이틀·버튼 정리 대상 페이지 예: `settings/index.html`, `url-viewer/extract.html`, `url-viewer/viewer.html`, `dashboard/index.html`, `url-viewer/url-block-monitor.html`, `url-viewer/workflow.html`, `settings/apm-match-report.html`, `url-viewer/review.html`, `url-viewer/call-stats.html` 등.

---

## 5. 다이얼로그

- `js/dialog.js`의 확인/취소는 페이지의 **저장=파랑 / 취소=회색** 톤과 맞출 것(취소는 가능하면 슬레이트 계열).

---

## 6. 변경 시

- 화면·CSS·본 문서 내용이 어긋나면 **이 파일을 먼저 고친 뒤** 코드에 반영한다.
- 배포 단위로 사용자가 버전을 확인할 수 있도록 `nav.js`의 `APP_UI_VERSION`을 [CLAUDE.md](CLAUDE.md) 규칙에 맞게 올린다.
