package com.baek.viewer.controller;

import com.baek.viewer.service.AuthService;
import com.baek.viewer.service.ItAssigneeService;
import com.baek.viewer.service.RecordProposalService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/proposals")
public class RecordProposalController {

    private static final Logger log = LoggerFactory.getLogger(RecordProposalController.class);

    private final RecordProposalService proposalService;
    private final AuthService authService;
    private final ItAssigneeService itAssigneeService;

    public RecordProposalController(RecordProposalService proposalService,
                                    AuthService authService,
                                    ItAssigneeService itAssigneeService) {
        this.proposalService = proposalService;
        this.authService = authService;
        this.itAssigneeService = itAssigneeService;
    }

    private String submittedByLabel(HttpServletRequest req) {
        String admin = req.getHeader("X-Admin-Token");
        if (authService.isAdmin(admin)) return "ADMIN";
        String ed = req.getHeader("X-Editor-Token");
        Long id = authService.getEditorAssigneeId(ed);
        if (id != null) {
            return itAssigneeService.findById(id)
                    .map(a -> a.getTeamName() + " / " + a.getAssigneeName())
                    .orElse("EDITOR:" + id);
        }
        return "UNKNOWN";
    }

    /** 관리자 제안은 알림 대상 없음(null), 편집자는 담당자 PK */
    private Long submitterAssigneeId(HttpServletRequest req) {
        if (authService.isAdmin(req.getHeader("X-Admin-Token"))) {
            return null;
        }
        return authService.getEditorAssigneeId(req.getHeader("X-Editor-Token"));
    }

    private static String clientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        return ip.split(",")[0].trim();
    }

    @PutMapping("/record/{recordId}")
    public ResponseEntity<?> putProposal(@PathVariable long recordId,
                                         @RequestBody Map<String, Object> body,
                                         HttpServletRequest req) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = (Map<String, Object>) body.get("patch");
            if (patch == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "body.patch 객체가 필요합니다."));
            }
            proposalService.saveOrUpdateProposal(recordId, patch, submittedByLabel(req), submitterAssigneeId(req), clientIp(req));
            return ResponseEntity.ok(Map.of("success", true, "recordId", recordId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[제안 저장 실패] recordId={}, {}", recordId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/record/{recordId}")
    public ResponseEntity<?> getProposal(@PathVariable long recordId) {
        Map<String, Object> m = proposalService.previewPayload(recordId);
        if (m.isEmpty()) return ResponseEntity.ok(Map.of("hasProposal", false));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasProposal", true);
        out.putAll(m);
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/record/{recordId}")
    public ResponseEntity<?> deleteProposal(@PathVariable long recordId, HttpServletRequest req) {
        try {
            boolean admin = authService.isAdmin(req.getHeader("X-Admin-Token"));
            Long editorId = authService.getEditorAssigneeId(req.getHeader("X-Editor-Token"));
            proposalService.withdraw(recordId, admin, editorId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /** 제안에서 상태·사유·요약 전환 문자열만 제거 (데이터 제안은 유지). */
    @PostMapping("/record/{recordId}/withdraw-status-fields")
    public ResponseEntity<?> withdrawStatusFields(@PathVariable long recordId, HttpServletRequest req) {
        try {
            boolean admin = authService.isAdmin(req.getHeader("X-Admin-Token"));
            Long editorId = authService.getEditorAssigneeId(req.getHeader("X-Editor-Token"));
            proposalService.withdrawStatusFieldsOnly(recordId, admin, editorId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/approve")
    public ResponseEntity<?> approve(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
        }
        List<Long> ids = rawIds.stream().map(i -> i.longValue()).toList();
        int n = proposalService.approve(ids, clientIp(req));
        return ResponseEntity.ok(Map.of("applied", n));
    }

    @PostMapping("/reject")
    public ResponseEntity<?> reject(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<Integer> rawIds = (List<Integer>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids가 비어 있습니다."));
        }
        String reason = body.get("reason") != null ? String.valueOf(body.get("reason")) : "";
        List<Long> ids = rawIds.stream().map(i -> i.longValue()).toList();
        try {
            int n = proposalService.reject(ids, reason, clientIp(req));
            return ResponseEntity.ok(Map.of("removed", n));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
