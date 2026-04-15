package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.JiraConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JiraService.buildDescriptionTables 단위 테스트.
 *
 * 내부망 호출 없이 JiraService 의 description 조립 로직만 검증한다.
 * 실제 Jira API 호출 경로는 별도의 통합/수동 테스트에서 확인.
 */
class JiraServiceDescriptionTest {

    private JiraService svc;
    private JiraConfig cfg;

    @BeforeEach
    void setUp() {
        // 의존성(repo)은 description 조립과 무관하므로 null 로 주입
        svc = new JiraService(null, null, null, null);
        cfg = new JiraConfig();
        cfg.setJiraBaseUrl("https://smartway.example.com");
        cfg.setProjectKey("APIV");
    }

    private ApiRecord baseRecord() {
        ApiRecord r = new ApiRecord();
        r.setRepositoryName("card-core");
        r.setApiPath("/api/card/v1/limit");
        r.setHttpMethod("GET");
        r.setControllerName("CardLimitController");
        r.setMethodName("getLimit");
        r.setFullUrl("https://card.example.com/api/card/v1/limit");
        r.setApiOperationValue("한도 조회");
        r.setDescriptionTag("카드 한도 조회 API");
        r.setFullComment("메소드 주석 본문");
        r.setControllerComment("컨트롤러 클래스 주석");
        r.setDescriptionOverride("카드-한도-조회(사용자지정)");
        r.setStatus("최우선 차단대상");
        r.setStatusOverridden(true);
        r.setCallCount(0L);
        r.setCallCountMonth(0L);
        r.setCallCountWeek(0L);
        r.setIsDeprecated("Y");
        r.setBlockCriteria("호출 0건 + 1년 경과");
        r.setBlockedDate(LocalDate.of(2026, 4, 10));
        r.setBlockedReason("침해사고 [CSR-12345] 로그 참고, 관련 OP-9999");
        r.setMemo("업무팀 합의 완료");
        r.setGitHistory("[{\"date\":\"2026-03-01\",\"author\":\"hong\",\"message\":\"한도 로직 수정\"}," +
                        "{\"date\":\"2026-02-10\",\"author\":\"kim\",\"message\":\"주석 보완\"}]");
        return r;
    }

    @Test
    @DisplayName("5개 표 타이틀이 모두 포함된다 (파란색+bold)")
    void title_bluebold_forAllFiveTables() {
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        assertThat(desc).contains("h3. {color:#1e40af}*기본정보*{color}");
        assertThat(desc).contains("h3. {color:#1e40af}*상세정보*{color}");
        assertThat(desc).contains("h3. {color:#1e40af}*상태정보*{color}");
        assertThat(desc).contains("h3. {color:#1e40af}*차단정보*{color}");
        assertThat(desc).contains("h3. {color:#1e40af}*소스변경이력");
    }

    @Test
    @DisplayName("기본정보 표에 레포/URL/메소드가 포함된다")
    void basicInfo_containsCoreFields() {
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        assertThat(desc).contains("||항목||값||");
        assertThat(desc).contains("|업무명|카드업무|");
        assertThat(desc).contains("|레포지토리|card-core|");
        assertThat(desc).contains("|URL 경로|/api/card/v1/limit|");
        assertThat(desc).contains("|HTTP Method|GET|");
    }

    @Test
    @DisplayName("상세정보 표에 사용자지정(override)과 자동파싱 주석이 모두 포함된다")
    void detail_containsOverrideAndParsedComments() {
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        assertThat(desc).contains("|관련메뉴(사용자지정)|카드-한도-조회(사용자지정)|");
        assertThat(desc).contains("|ApiOperation|한도 조회|");
        assertThat(desc).contains("|Description 주석|카드 한도 조회 API|");
    }

    @Test
    @DisplayName("상태정보: 최우선 차단대상은 빨강(#991b1b) + bold 로 채색된다")
    void status_topPriority_coloredRed() {
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        assertThat(desc).contains("{color:#991b1b}*최우선 차단대상*{color}");
        assertThat(desc).contains("|상태확정|확정|");
        assertThat(desc).contains("|1년 호출건|0건|");
        assertThat(desc).contains("|Deprecated|Y|");
    }

    @Test
    @DisplayName("상태 색상 매핑: 사용/후순위/추가검토필요/차단완료")
    void status_colorMapping() {
        ApiRecord r = baseRecord();

        r.setStatus("사용");
        assertThat(svc.buildDescriptionTables(cfg, r, "x"))
                .contains("{color:#166534}*사용*{color}");

        r.setStatus("후순위 차단대상");
        assertThat(svc.buildDescriptionTables(cfg, r, "x"))
                .contains("{color:#c2410c}*후순위 차단대상*{color}");

        r.setStatus("추가검토필요 차단대상");
        assertThat(svc.buildDescriptionTables(cfg, r, "x"))
                .contains("{color:#92400e}*추가검토필요 차단대상*{color}");

        r.setStatus("차단완료");
        assertThat(svc.buildDescriptionTables(cfg, r, "x"))
                .contains("{color:#166534}*차단완료*{color}");
    }

    @Test
    @DisplayName("차단근거 셀: [CSR-xxx]/OP-xxx 패턴이 SmartWay URL 링크로 변환된다")
    void blockedReason_csrAndOpKeys_linkified() {
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        // [CSR-12345] → [CSR-12345|https://smartway.example.com/browse/CSR-12345]
        assertThat(desc).contains("[CSR-12345|https://smartway.example.com/browse/CSR-12345]");
        // bare OP-9999 도 링크로
        assertThat(desc).contains("[OP-9999|https://smartway.example.com/browse/OP-9999]");
    }

    @Test
    @DisplayName("차단정보 표에 기준/일자/비고가 포함된다")
    void block_containsCriteriaDateMemo() {
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        assertThat(desc).contains("|차단기준|호출 0건 + 1년 경과|");
        assertThat(desc).contains("|차단일자|2026-04-10|");
        assertThat(desc).contains("|비고|업무팀 합의 완료|");
    }

    @Test
    @DisplayName("소스변경이력 표: gitHistory JSON 5건 이내 렌더링")
    void gitHistory_renderedAsTable() {
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        assertThat(desc).contains("||#||날짜||변경자||내용||");
        assertThat(desc).contains("|1|2026-03-01|hong|한도 로직 수정|");
        assertThat(desc).contains("|2|2026-02-10|kim|주석 보완|");
    }

    @Test
    @DisplayName("gitHistory 가 null/empty 일 때 '-' 행으로 표시")
    void gitHistory_empty_placeholder() {
        ApiRecord r = baseRecord();
        r.setGitHistory(null);
        String desc = svc.buildDescriptionTables(cfg, r, "카드업무");
        assertThat(desc).contains("||#||날짜||변경자||내용||");
        assertThat(desc).contains("|-|-|-|-|");
    }

    @Test
    @DisplayName("null/빈 값은 '-' 로, 표 구분자 '|' 는 전각 '｜' 로 치환")
    void cellSafety_nullAndPipeEscaping() {
        ApiRecord r = baseRecord();
        r.setApiOperationValue(null);
        r.setDescriptionTag("");
        r.setFullComment("pipe|in|value");
        String desc = svc.buildDescriptionTables(cfg, r, "카드업무");
        assertThat(desc).contains("|ApiOperation|-|");
        assertThat(desc).contains("|Description 주석|-|");
        assertThat(desc).contains("|메소드 주석|pipe｜in｜value|");
    }

    @Test
    @DisplayName("JiraConfig.baseUrl 이 null 이면 CSR 키가 링크로 치환되지 않고 원문 유지")
    void blockedReason_noBaseUrl_plainText() {
        cfg.setJiraBaseUrl(null);
        String desc = svc.buildDescriptionTables(cfg, baseRecord(), "카드업무");
        // 링크 마크업이 생기지 않아야 한다
        assertThat(desc).doesNotContain("/browse/");
        // 원문은 여전히 포함
        assertThat(desc).contains("CSR-12345");
    }
}
