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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/db/record-by-key} — 차단 모니터링 연계 완화 매칭(메소드 → 경로 폴백).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecordByKeyIntegrationTest {

    private static final String R = "test-record-by-key";

    @Autowired
    ApiRecordRepository recordRepo;
    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        recordRepo.deleteByRepositoryName(R);
        recordRepo.saveAll(List.of(
                rec(R, "/api/x", "GET", "사용"),
                rec(R, "/api/x", "POST", "사용"),
                rec(R, "/api/y", "POST", "①-① 차단대상")
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

    @Test
    @DisplayName("record-by-key — 정확한 메소드 매칭")
    void exactMethod() throws Exception {
        mockMvc.perform(get("/api/db/record-by-key")
                        .param("repositoryName", R)
                        .param("apiPath", "/api/x")
                        .param("httpMethod", "POST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpMethod").value("POST"));
    }

    @Test
    @DisplayName("record-by-key — 메소드 대소문자 무시")
    void methodCaseInsensitive() throws Exception {
        mockMvc.perform(get("/api/db/record-by-key")
                        .param("repositoryName", R)
                        .param("apiPath", "/api/x")
                        .param("httpMethod", "get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpMethod").value("GET"));
    }

    @Test
    @DisplayName("record-by-key — REQUEST 등 일반 토큰은 동일 경로에서 GET 우선")
    void genericVerbPrefersGet() throws Exception {
        mockMvc.perform(get("/api/db/record-by-key")
                        .param("repositoryName", R)
                        .param("apiPath", "/api/x")
                        .param("httpMethod", "REQUEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpMethod").value("GET"));
    }

    @Test
    @DisplayName("record-by-key — 단일 행 경로는 메소드 불일치여도 반환")
    void singleRowPathFallback() throws Exception {
        mockMvc.perform(get("/api/db/record-by-key")
                        .param("repositoryName", R)
                        .param("apiPath", "/api/y")
                        .param("httpMethod", "GET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpMethod").value("POST"));
    }

    @Test
    @DisplayName("record-by-key — httpMethod 생략 시 경로 폴백")
    void omitMethodUsesPathFallback() throws Exception {
        mockMvc.perform(get("/api/db/record-by-key")
                        .param("repositoryName", R)
                        .param("apiPath", "/api/y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpMethod").value("POST"));
    }

    @Test
    @DisplayName("record-by-key — 없는 경로 404")
    void notFound() throws Exception {
        mockMvc.perform(get("/api/db/record-by-key")
                        .param("repositoryName", R)
                        .param("apiPath", "/none")
                        .param("httpMethod", "GET"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("record-by-key — repositoryName 누락 시 400")
    void badRequest() throws Exception {
        mockMvc.perform(get("/api/db/record-by-key")
                        .param("apiPath", "/api/x")
                        .param("httpMethod", "GET"))
                .andExpect(status().isBadRequest());
    }
}
