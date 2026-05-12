# URL Viewer — Cursor Rules Index

실제 작업 규칙은 아래 Cursor Rules 파일을 기준으로 한다.

## Primary Rule Files

- `.cursor/rules/apiviewer-core.mdc`
- `.cursor/rules/backend-status-persistence.mdc`
- `.cursor/rules/static-ui.mdc`
- `.cursor/rules/url-viewer-status.mdc`
- `.cursor/rules/release-handoff.mdc`

## Recent Behavior Notes

- `URL 분석 오류 요약` 상단 배너 닫힘은 브라우저 저장소가 아니라 서버 전역 상태를 사용한다. 현재 리포트를 닫으면 관리자 세션 전반에서 숨겨지고, 새 extract issue 리포트가 저장되면 다시 표시된다.
- `URL 현황`의 관리자 스냅샷 비교 기준일자는 조회 화면과 같은 커스텀 달력을 사용하며, 스냅샷이 있는 날짜를 붉게 표시한다. 변경일시에는 관리자 IP와 수정자 팀/담당자 표시를 함께 노출한다.
- `URL 현황` 테이블은 `간략히 보기 / 자세히 보기` 토글을 지원한다. 토글은 조회 필터 첫 줄에 두고, 간략 모드에서는 레포~상태와 관련메뉴~비고 중심의 주요 컬럼만 남기며 엑셀/업로드 스키마는 기존 전체 컬럼 기준을 유지한다.
