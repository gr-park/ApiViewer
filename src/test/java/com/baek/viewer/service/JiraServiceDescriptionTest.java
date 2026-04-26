package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.JiraConfig;
import com.baek.viewer.model.RepoConfig;
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
    private RepoConfig repoCfg;

    @BeforeEach
    void setUp() {
        // 의존성(repo)은 description 조립과 무관하므로 null 로 주입
        svc = new JiraService(null, null, null, null);
        cfg = new JiraConfig();
        cfg.setJiraBaseUrl("https://smartway.example.com");
        cfg.setProjectKey("APIV");

        repoCfg = new RepoConfig();
        repoCfg.setTeamName("카드개발팀");
        repoCfg.setManagerName("김철수");
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
        r.setStatus("(1)-(2) 호출0건+변경없음");
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
    @DisplayName("4개 표 타이틀이 모두 ■ 접두사 + 파란색+bold 포함된다")
    void title_bluebold_forAllFourTables() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        assertThat(desc).contains("h3. {color:#1e40af}*■ URL기본정보*{color}");
        assertThat(desc).contains("h3. {color:#1e40af}*■ URL상태정보*{color}");
        assertThat(desc).contains("h3. {color:#1e40af}*■ URL기타정보*{color}");
        assertThat(desc).contains("h3. {color:#1e40af}*■ URL관련 소스변경이력");
        // 이전 타이틀은 사라져야 한다
        assertThat(desc).doesNotContain("*기본정보*{color}");
        assertThat(desc).doesNotContain("*차단정보*{color}");
    }

    @Test
    @DisplayName("URL기본정보 표에 팀/담당자가 레포지토리 바로 아래에 포함된다")
    void basicInfo_containsTeamAndManager() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        assertThat(desc).contains("|팀|카드개발팀|");
        assertThat(desc).contains("|담당자|김철수|");
        // 레포지토리 → 팀 → 담당자 순서 확인
        int idxRepo = desc.indexOf("|레포지토리|");
        int idxTeam = desc.indexOf("|팀|");
        int idxManager = desc.indexOf("|담당자|");
        assertThat(idxRepo).isLessThan(idxTeam);
        assertThat(idxTeam).isLessThan(idxManager);
    }

    @Test
    @DisplayName("managerOverride 가 있으면 담당자로 우선 표시된다")
    void basicInfo_managerOverride_takePriority() {
        ApiRecord r = baseRecord();
        r.setManagerOverride("박영희");
        String desc = svc.buildDescriptionTables(cfg, repoCfg, r, "카드업무");
        assertThat(desc).contains("|담당자|박영희|");
        assertThat(desc).doesNotContain("|담당자|김철수|");
    }

    @Test
    @DisplayName("managerMappings 프로그램ID 가 apiPath 에 포함되면 해당 담당자로 표시된다")
    void basicInfo_programIdMapping_matchesApiPath() {
        ApiRecord r = baseRecord();
        r.setApiPath("/api/card/v1/LIMIT/check");
        repoCfg.setManagerMappings("[{\"programId\":\"LIMIT\",\"managerName\":\"이매퍼\"}]");
        String desc = svc.buildDescriptionTables(cfg, repoCfg, r, "카드업무");
        assertThat(desc).contains("|담당자|이매퍼|");
        assertThat(desc).doesNotContain("|담당자|김철수|");
    }

    @Test
    @DisplayName("URL기본정보 표에 핵심 필드와 관련메뉴가 포함되고 Controller/메소드는 없다")
    void basicInfo_containsCoreFields() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        assertThat(desc).contains("|업무명|카드업무|");
        assertThat(desc).contains("|레포지토리|card-core|");
        assertThat(desc).contains("|URL 경로|/api/card/v1/limit|");
        assertThat(desc).contains("|관련메뉴|카드-한도-조회(사용자지정)|");
        // Controller·메소드는 URL기타정보로 이동
        assertThat(desc).contains("|Controller|CardLimitController|");
        assertThat(desc).contains("|메소드|getLimit|");
    }

    @Test
    @DisplayName("URL기타정보 표에 Controller/메소드(최상단), HTTP Method, Deprecated 포함된다")
    void otherInfo_containsMovedFields() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        assertThat(desc).contains("|ApiOperation|한도 조회|");
        assertThat(desc).contains("|Description 주석|카드 한도 조회 API|");
        assertThat(desc).contains("|HTTP Method|GET|");
        assertThat(desc).contains("|Deprecated|Y|");
        // 이전 상세정보의 관련메뉴(사용자지정)은 더 이상 없다
        assertThat(desc).doesNotContain("관련메뉴(사용자지정)");
    }

    @Test
    @DisplayName("URL상태정보: 최우선 차단대상은 빨강(#991b1b) + bold, 상태확정 행 없음, 4-열 헤더 포함")
    void status_topPriority_coloredRed_and_4colHeader() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        assertThat(desc).contains("{color:#991b1b}*(1)-(2) 호출0건+변경없음*{color}");
        assertThat(desc).contains("||항목||값||항목||값||");
        assertThat(desc).contains("1년 호출건");
        // 상태확정 행은 제거됨
        assertThat(desc).doesNotContain("|상태확정|");
    }

    @Test
    @DisplayName("상태 색상 매핑: 사용/후순위/검토필요대상/차단완료")
    void status_colorMapping() {
        ApiRecord r = baseRecord();

        r.setStatus("사용");
        assertThat(svc.buildDescriptionTables(cfg, repoCfg, r, "x"))
                .contains("{color:#166534}*사용*{color}");

        r.setStatus("(1)-(4) 업무종료");
        assertThat(svc.buildDescriptionTables(cfg, repoCfg, r, "x"))
                .contains("{color:#c2410c}*(1)-(4) 업무종료*{color}");

        r.setStatus("(2)-(3) 호출 1~reviewThreshold건");
        assertThat(svc.buildDescriptionTables(cfg, repoCfg, r, "x"))
                .contains("{color:#92400e}*(2)-(3) 호출 1~reviewThreshold건*{color}");

        r.setStatus("(1)-(1) 차단완료");
        assertThat(svc.buildDescriptionTables(cfg, repoCfg, r, "x"))
                .contains("{color:#166534}*(1)-(1) 차단완료*{color}");
    }

    @Test
    @DisplayName("차단근거 셀: [CSR-xxx]/OP-xxx 패턴이 SmartWay URL 링크로 변환된다")
    void blockedReason_csrAndOpKeys_linkified() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        // [CSR-12345] → [CSR-12345|https://smartway.example.com/browse/CSR-12345]
        assertThat(desc).contains("[CSR-12345|https://smartway.example.com/browse/CSR-12345]");
        // bare OP-9999 도 링크로
        assertThat(desc).contains("[OP-9999|https://smartway.example.com/browse/OP-9999]");
    }

    @Test
    @DisplayName("URL상태정보 4-열에 차단기준/일자/비고(차단비고)가 포함된다")
    void block_containsCriteriaDateMemo() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        assertThat(desc).contains("|차단기준|호출 0건 + 1년 경과|");
        assertThat(desc).contains("|차단일자|2026-04-10|");
        assertThat(desc).contains("|차단비고|업무팀 합의 완료|");
        // 이전 "비고" 컬럼명은 사라져야 한다
        assertThat(desc).doesNotContain("|비고|");
    }

    @Test
    @DisplayName("소스변경이력 표: gitHistory JSON 5건 이내 렌더링")
    void gitHistory_renderedAsTable() {
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        assertThat(desc).contains("||#||날짜||변경자||내용||");
        assertThat(desc).contains("|1|2026-03-01|hong|한도 로직 수정|");
        assertThat(desc).contains("|2|2026-02-10|kim|주석 보완|");
    }

    @Test
    @DisplayName("gitHistory 가 null/empty 일 때 '-' 행으로 표시")
    void gitHistory_empty_placeholder() {
        ApiRecord r = baseRecord();
        r.setGitHistory(null);
        String desc = svc.buildDescriptionTables(cfg, repoCfg, r, "카드업무");
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
        String desc = svc.buildDescriptionTables(cfg, repoCfg, r, "카드업무");
        assertThat(desc).contains("|ApiOperation|-|");
        assertThat(desc).contains("|Description 주석|-|");
        assertThat(desc).contains("|메소드 주석|pipe｜in｜value|");
    }

    @Test
    @DisplayName("JiraConfig.baseUrl 이 null 이면 CSR 키가 링크로 치환되지 않고 원문 유지")
    void blockedReason_noBaseUrl_plainText() {
        cfg.setJiraBaseUrl(null);
        String desc = svc.buildDescriptionTables(cfg, repoCfg, baseRecord(), "카드업무");
        // 링크 마크업이 생기지 않아야 한다
        assertThat(desc).doesNotContain("/browse/");
        // 원문은 여전히 포함
        assertThat(desc).contains("CSR-12345");
    }
}
