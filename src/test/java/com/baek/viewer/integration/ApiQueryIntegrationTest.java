package com.baek.viewer.integration;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * URL 분석현황 조회 (`/api/db/apis`) 통합 테스트.
 *
 * 시나리오:
 *   - 전체 조회 (필터 없음)
 *   - 1개 레포 조회 (?repository=A)
 *   - 다수 레포 동시 조회 (?repositories=A,B)
 *   - status 단건 필터
 *   - statusGroup=block (①-* leaf) prefix 필터
 *   - statusGroup=review (②-* leaf) prefix 필터
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiQueryIntegrationTest {

    private static final String REPO_A = "test-q-A";
    private static final String REPO_B = "test-q-B";
    private static final String REPO_C = "test-q-C";

    @Autowired ApiRecordRepository recordRepo;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void setup() {
        recordRepo.deleteByRepositoryName(REPO_A);
        recordRepo.deleteByRepositoryName(REPO_B);
        recordRepo.deleteByRepositoryName(REPO_C);

        // REPO_A: 사용 2 / ①-② 1 / ②-③ 1 = 4건
        recordRepo.saveAll(List.of(
                rec(REPO_A, "/a/1", "GET",  "사용"),
                rec(REPO_A, "/a/2", "POST", "사용"),
                rec(REPO_A, "/a/3", "GET",  "①-① 차단대상"),
                rec(REPO_A, "/a/4", "GET",  "②-② 호출 3건 이하+변경없음")
        ));
        // REPO_B: ①-① 1 / ①-④ 1 / ②-② 1 = 3건
        recordRepo.saveAll(List.of(
                rec(REPO_B, "/b/1", "GET", "차단완료"),
                rec(REPO_B, "/b/2", "GET", "①-② 담당자 판단"),
                rec(REPO_B, "/b/3", "GET", "②-① 호출0건+변경있음")
        ));
        // REPO_C: 사용 1
        recordRepo.saveAll(List.of(
                rec(REPO_C, "/c/1", "GET", "사용")
        ));
    }

    private ApiRecord rec(String repo, String path, String method, String status) {
        ApiRecord r = new ApiRecord();
        r.setRepositoryName(repo);
        r.setApiPath(path);
        r.setHttpMethod(method);
        r.setStatus(status);
        return r;
    }

    private String body(MvcResult res) throws Exception {
        return res.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("전체 조회 — 모든 레포 데이터 합계 (DB 직접)")
    void queryAll() {
        long total = recordRepo.findByRepositoryName(REPO_A).size()
                + recordRepo.findByRepositoryName(REPO_B).size()
                + recordRepo.findByRepositoryName(REPO_C).size();
        assertThat(total).isEqualTo(8);
    }

    @Test
    @DisplayName("1개 레포 조회 — ?repository=A 만 매칭")
    void querySingleRepo() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/db/apis")
                        .param("repository", REPO_A)
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String b = body(res);
        assertThat(b).contains(REPO_A);
        assertThat(b).doesNotContain(REPO_B);
        assertThat(b).doesNotContain(REPO_C);
    }

    @Test
    @DisplayName("다수 레포 조회 — ?repositories=A,B 양쪽 모두 매칭")
    void queryMultiRepo() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/db/apis")
                        .param("repositories", REPO_A + "," + REPO_B)
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String b = body(res);
        assertThat(b).contains(REPO_A);
        assertThat(b).contains(REPO_B);
        assertThat(b).doesNotContain(REPO_C);
    }

    @Test
    @DisplayName("status 필터 — 단건 leaf 매칭")
    void queryByStatus() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/db/apis")
                        .param("status", "차단완료")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String b = body(res);
        assertThat(b).contains("/b/1");
        assertThat(b).doesNotContain("/a/1");
        assertThat(b).doesNotContain("/a/3");  // ①-② 는 다른 leaf
    }

    @Test
    @DisplayName("statusGroup=block — ①-* leaf 모두 매칭 (prefix LIKE)")
    void queryStatusGroupBlock() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/db/apis")
                        .param("statusGroup", "block")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String b = body(res);
        // ①- prefix leaf 3건 (REPO_A /a/3 + REPO_B /b/1, /b/2)
        assertThat(b).contains("/a/3");  // ①-②
        assertThat(b).contains("/b/1");  // ①-①
        assertThat(b).contains("/b/2");  // ①-④
        // ②- leaf 는 제외
        assertThat(b).doesNotContain("/a/4");  // ②-③
        assertThat(b).doesNotContain("/b/3");  // ②-②
        // 사용 도 제외
        assertThat(b).doesNotContain("/a/1");
        assertThat(b).doesNotContain("/c/1");
    }

    @Test
    @DisplayName("statusGroup=review — ②-* leaf 모두 매칭")
    void queryStatusGroupReview() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/db/apis")
                        .param("statusGroup", "review")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String b = body(res);
        assertThat(b).contains("/a/4");  // ②-③
        assertThat(b).contains("/b/3");  // ②-②
        assertThat(b).doesNotContain("/b/1");  // ①-① 제외
        assertThat(b).doesNotContain("/b/2");  // ①-④ 제외
    }

    @Test
    @DisplayName("dbStats — 전체/1개레포/다수레포 응답 totalRecords 검증")
    void dbStatsAcrossRepoFilters() throws Exception {
        MvcResult all = mockMvc.perform(get("/api/db/stats")).andExpect(status().isOk()).andReturn();
        MvcResult one = mockMvc.perform(get("/api/db/stats").param("repository", REPO_A))
                .andExpect(status().isOk()).andReturn();
        MvcResult two = mockMvc.perform(get("/api/db/stats")
                        .param("repositories", REPO_A + "," + REPO_B))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(all)).contains("\"total\"");
        assertThat(body(one)).contains("\"total\"");
        assertThat(body(two)).contains("\"total\"");
    }

    @Test
    @DisplayName("상세조회 GET /api/db/record/{id} — 신규 leaf status + 모든 필드 응답")
    void detailRecord_returnsAllFields() throws Exception {
        // /b/2 = ①-② 담당자 판단 — 상세 응답에 leaf 라벨, recentLogOnly, logWorkExcluded, statusChangeLog 포함
        ApiRecord target = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO_B, "/b/2", "GET").orElseThrow();
        target.setStatusChangeLog("①-① 차단대상 → ①-② 담당자 판단 | 호출건수 0→5건 발생");
        target.setStatusChanged(true);
        target.setRecentLogOnly(false);
        recordRepo.save(target);

        MvcResult res = mockMvc.perform(get("/api/db/record/" + target.getId()))
                .andExpect(status().isOk()).andReturn();
        String b = body(res);
        // 신규 leaf 라벨이 그대로 응답
        assertThat(b).contains("①-② 담당자 판단");
        // statusChangeLog 가 응답에 포함 (상세 화면이 이력 렌더링에 사용)
        assertThat(b).contains("호출건수 0→5건 발생");
        // 보조 플래그 필드도 응답
        assertThat(b).contains("\"recentLogOnly\"");
        assertThat(b).contains("\"logWorkExcluded\"");
        assertThat(b).contains("\"statusChanged\"");
    }

    @Test
    @DisplayName("상세조회 — 존재하지 않는 ID 는 404")
    void detailRecord_notFound() throws Exception {
        mockMvc.perform(get("/api/db/record/9999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("?testSuspect=true — testSuspectReason 가 NOT NULL & 빈문자열 아닌 레코드만 매칭")
    void queryTestSuspectTrue() throws Exception {
        // /a/3 에 의심 사유 세팅
        ApiRecord target = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO_A, "/a/3", "GET").orElseThrow();
        target.setTestSuspectReason("URL-test");
        recordRepo.save(target);

        MvcResult res = mockMvc.perform(get("/api/db/apis")
                        .param("testSuspect", "true")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk()).andReturn();
        String b = body(res);
        assertThat(b).contains("/a/3");
        assertThat(b).contains("URL-test");
        // 의심 사유 없는 다른 레코드는 제외
        assertThat(b).doesNotContain("/b/1");
        assertThat(b).doesNotContain("/c/1");
    }

    @Test
    @DisplayName("?testSuspect=false — testSuspectReason NULL 또는 빈문자열 레코드만 매칭")
    void queryTestSuspectFalse() throws Exception {
        ApiRecord susp = recordRepo.findByRepositoryNameAndApiPathAndHttpMethod(REPO_A, "/a/3", "GET").orElseThrow();
        susp.setTestSuspectReason("URL-test");
        recordRepo.save(susp);

        MvcResult res = mockMvc.perform(get("/api/db/apis")
                        .param("testSuspect", "false")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk()).andReturn();
        String b = body(res);
        assertThat(b).doesNotContain("/a/3");  // 의심 사유 있어 제외
        assertThat(b).contains("/c/1");        // 의심 없음
    }
}
