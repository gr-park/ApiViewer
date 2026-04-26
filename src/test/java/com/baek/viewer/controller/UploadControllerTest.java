package com.baek.viewer.controller;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UploadController.uploadExcelViewer 범위 확장 및 차단완료 정책 검증.
 */
@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock private ApiRecordRepository repository;
    @Mock private HttpServletRequest req;

    @InjectMocks private UploadController controller;

    private Map<String, Object> row(String repo, String path, String method) {
        Map<String, Object> m = new HashMap<>();
        m.put("repositoryName", repo);
        m.put("apiPath", path);
        m.put("httpMethod", method);
        return m;
    }

    private ApiRecord existing(String status) {
        ApiRecord r = new ApiRecord();
        r.setRepositoryName("repo");
        r.setApiPath("/foo");
        r.setHttpMethod("GET");
        r.setStatus(status);
        return r;
    }

    private Map<String, Object> body(Map<String, Object>... rows) {
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return Map.of("rows", List.of(rows));
    }

    @Test
    @DisplayName("팀·담당자·내용·검토단계 필드 업데이트 반영")
    void updates_newOverrideFields() {
        ApiRecord r = existing("사용");
        when(repository.findByRepositoryNameAndApiPathAndHttpMethod("repo", "/foo", "GET"))
                .thenReturn(Optional.of(r));

        Map<String, Object> row = row("repo", "/foo", "GET");
        row.put("teamOverride", "A팀");
        row.put("managerOverride", "홍길동");
        row.put("descriptionOverride", "주문 조회");
        row.put("reviewStage", "현업검토");

        ResponseEntity<?> res = controller.uploadExcelViewer(body(row), req);

        ArgumentCaptor<ApiRecord> cap = ArgumentCaptor.forClass(ApiRecord.class);
        verify(repository).save(cap.capture());
        ApiRecord saved = cap.getValue();
        assertThat(saved.getTeamOverride()).isEqualTo("A팀");
        assertThat(saved.getManagerOverride()).isEqualTo("홍길동");
        assertThat(saved.isManagerOverridden()).isTrue();
        assertThat(saved.getDescriptionOverride()).isEqualTo("주문 조회");
        assertThat(saved.getReviewStage()).isEqualTo("현업검토");
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("담당자 빈값 → managerOverridden=false 로 해제 (매핑 재갱신 허용)")
    void blankManager_clearsOverriddenFlag() {
        ApiRecord r = existing("사용");
        r.setManagerOverride("기존값");
        r.setManagerOverridden(true);
        when(repository.findByRepositoryNameAndApiPathAndHttpMethod(any(), any(), any()))
                .thenReturn(Optional.of(r));

        Map<String, Object> row = row("repo", "/foo", "GET");
        row.put("managerOverride", "   ");

        controller.uploadExcelViewer(body(row), req);

        ArgumentCaptor<ApiRecord> cap = ArgumentCaptor.forClass(ApiRecord.class);
        verify(repository).save(cap.capture());
        ApiRecord saved = cap.getValue();
        assertThat(saved.getManagerOverride()).isNull();
        assertThat(saved.isManagerOverridden()).isFalse();
    }

    @Test
    @DisplayName("차단완료 행도 비(非)상태 필드는 업데이트")
    void blockedRow_allowsNonStatusFields() {
        ApiRecord r = existing("①-① 차단완료");
        r.setStatusOverridden(true);
        when(repository.findByRepositoryNameAndApiPathAndHttpMethod(any(), any(), any()))
                .thenReturn(Optional.of(r));

        Map<String, Object> row = row("repo", "/foo", "GET");
        row.put("memo", "재검토 필요");
        row.put("managerOverride", "이몽룡");

        controller.uploadExcelViewer(body(row), req);

        ArgumentCaptor<ApiRecord> cap = ArgumentCaptor.forClass(ApiRecord.class);
        verify(repository).save(cap.capture());
        ApiRecord saved = cap.getValue();
        assertThat(saved.getMemo()).isEqualTo("재검토 필요");
        assertThat(saved.getManagerOverride()).isEqualTo("이몽룡");
        assertThat(saved.isManagerOverridden()).isTrue();
        // 상태는 그대로 유지
        assertThat(saved.getStatus()).isEqualTo("①-① 차단완료");
    }

    @Test
    @DisplayName("차단완료 행에서 상태/상태확정 변경은 무시")
    void blockedRow_ignoresStatusChange() {
        ApiRecord r = existing("①-① 차단완료");
        r.setStatusOverridden(true);
        when(repository.findByRepositoryNameAndApiPathAndHttpMethod(any(), any(), any()))
                .thenReturn(Optional.of(r));

        Map<String, Object> row = row("repo", "/foo", "GET");
        row.put("status", "사용");
        row.put("statusOverridden", "미확정");

        controller.uploadExcelViewer(body(row), req);

        ArgumentCaptor<ApiRecord> cap = ArgumentCaptor.forClass(ApiRecord.class);
        verify(repository).save(cap.capture());
        ApiRecord saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo("①-① 차단완료");
        assertThat(saved.isStatusOverridden()).isTrue();
    }

    @Test
    @DisplayName("비차단완료 행에서 incoming='차단완료' 승격 시도는 무시")
    void promoteToBlocked_viaExcel_ignored() {
        ApiRecord r = existing("사용");
        when(repository.findByRepositoryNameAndApiPathAndHttpMethod(any(), any(), any()))
                .thenReturn(Optional.of(r));

        Map<String, Object> row = row("repo", "/foo", "GET");
        row.put("status", "①-① 차단완료");

        controller.uploadExcelViewer(body(row), req);

        ArgumentCaptor<ApiRecord> cap = ArgumentCaptor.forClass(ApiRecord.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("사용");
    }

    @Test
    @DisplayName("키 미매칭 행은 skip 카운트")
    void unmatched_skipped() {
        when(repository.findByRepositoryNameAndApiPathAndHttpMethod(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");

        Map<String, Object> row = row("repo", "/missing", "GET");
        ResponseEntity<?> res = controller.uploadExcelViewer(
                Map.of("rows", List.of(row)), req);

        @SuppressWarnings("unchecked")
        Map<String, Object> rb = (Map<String, Object>) res.getBody();
        assertThat(rb.get("skipped")).isEqualTo(1);
        assertThat(rb.get("updated")).isEqualTo(0);
        verify(repository, never()).save(any());
    }
}
