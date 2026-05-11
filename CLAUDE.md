# URL Viewer — Cursor Rules Entry

이 파일은 의도적으로 짧게 유지한다. 앞으로의 상세 작업 규칙은 `.cursor/rules/` 아래 파일을 기본 기준으로 사용한다.

## Primary Rule Files

- `.cursor/rules/apiviewer-core.mdc`
- `.cursor/rules/backend-status-persistence.mdc`
- `.cursor/rules/static-ui.mdc`
- `.cursor/rules/url-viewer-status.mdc`
- `.cursor/rules/release-handoff.mdc`

## Always-Keep Summary

- 화면 표기에서는 `Jira` 대신 `SmartWay`를 사용하고, 내부 식별자와 DB/코드 명칭은 `jira`를 유지한다.
- `status`는 공식 운영 상태, `autoAnalyzedStatus`는 자동분석 결과로 유지한다.
- 영속성 변경은 H2와 PostgreSQL 모두에서 런타임 오류 없이 동작해야 하며, 가능하면 JPQL 또는 Spring Data 메서드명을 우선 사용한다.
- UI, 서버 동작, 배치 동작, 배포 체감 흐름이 바뀌면 `src/main/resources/static/common/nav.js`의 `APP_UI_VERSION`을 올린다.
- 정적 UI 또는 배포 단위 동작을 바꿨다면 응답 마지막 줄에 현재 `APP_UI_VERSION`을 적는다.
- UI 상세 스타일은 `UI-GUIDELINES.md`를 따른다.

## Maintenance Rule

- 소스코드나 비즈니스 로직 변경 시, 장문의 설명을 이 파일에 누적하지 말고 관련 `.cursor/rules/*.mdc` 또는 별도 문서를 갱신한다.
- 이 파일은 "어디를 봐야 하는지"와 "항상 지켜야 하는 핵심 요약"만 유지한다.
