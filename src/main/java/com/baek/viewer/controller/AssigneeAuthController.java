package com.baek.viewer.controller;

import com.baek.viewer.model.ItAssignee;
import com.baek.viewer.service.AuthService;
import com.baek.viewer.service.ItAssigneeService;
import com.baek.viewer.service.TeamSuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IT 담당자(편집자) 등록·로그인·팀 자동완성 — 공개 엔드포인트.
 */
@RestController
@RequestMapping("/api/assignee")
public class AssigneeAuthController {

    private static final Logger log = LoggerFactory.getLogger(AssigneeAuthController.class);

    private final ItAssigneeService itAssigneeService;
    private final TeamSuggestionService teamSuggestionService;
    private final AuthService authService;

    public AssigneeAuthController(ItAssigneeService itAssigneeService,
                                  TeamSuggestionService teamSuggestionService,
                                  AuthService authService) {
        this.itAssigneeService = itAssigneeService;
        this.teamSuggestionService = teamSuggestionService;
        this.authService = authService;
    }

    @GetMapping("/team-suggestions")
    public ResponseEntity<?> teamSuggestions(@RequestParam(required = false) String q) {
        List<String> teams = teamSuggestionService.suggestTeams(q);
        return ResponseEntity.ok(Map.of("teams", teams));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            ItAssignee a = itAssigneeService.register(
                    body.get("teamName"),
                    body.get("assigneeName"),
                    body.get("password"));
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", true);
            ok.put("id", a.getId());
            ok.put("message", "등록되었습니다. 동일 정보로 로그인하세요.");
            return ResponseEntity.ok(ok);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[담당자 등록 실패] {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        Optional<ItAssignee> opt = itAssigneeService.login(
                body.get("teamName"),
                body.get("assigneeName"),
                body.get("password"));
        if (opt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "팀·담당자명 또는 비밀번호가 올바르지 않습니다."));
        }
        ItAssignee a = opt.get();
        String token = itAssigneeService.issueSessionToken(a);
        long remaining = authService.remainingMs(token);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("token", token);
        ok.put("role", "EDITOR");
        ok.put("remainingMs", remaining);
        ok.put("teamName", a.getTeamName());
        ok.put("assigneeName", a.getAssigneeName());
        ok.put("assigneeId", a.getId());
        log.info("[담당자 로그인] id={}, team={}", a.getId(), a.getTeamName());
        return ResponseEntity.ok(ok);
    }

    @GetMapping("/auth/check")
    public ResponseEntity<?> checkEditor(@RequestHeader(value = "X-Editor-Token", required = false) String token) {
        boolean valid = authService.isEditor(token);
        long remainingMs = valid ? authService.remainingMs(token) : 0L;
        Long id = valid ? authService.getEditorAssigneeId(token) : null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", valid);
        m.put("remainingMs", remainingMs);
        m.put("role", valid ? "EDITOR" : null);
        m.put("assigneeId", id);
        if (valid && id != null) {
            itAssigneeService.findById(id).ifPresent(a -> {
                m.put("teamName", a.getTeamName());
                m.put("assigneeName", a.getAssigneeName());
                String notice = a.getProposalRejectNotice();
                m.put("proposalRejectNotice", notice != null ? notice : "");
                m.put("proposalRejectNoticeAt",
                        a.getProposalRejectNoticeAt() != null ? a.getProposalRejectNoticeAt().toString() : null);
            });
        }
        return ResponseEntity.ok(m);
    }

    @PostMapping("/auth/dismiss-proposal-notice")
    public ResponseEntity<?> dismissProposalNotice(@RequestHeader(value = "X-Editor-Token", required = false) String token) {
        if (!authService.isEditor(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "담당자 로그인이 필요합니다."));
        }
        Long id = authService.getEditorAssigneeId(token);
        if (id == null) {
            return ResponseEntity.status(401).body(Map.of("error", "담당자 로그인이 필요합니다."));
        }
        itAssigneeService.clearProposalRejectNotice(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> logoutEditor(@RequestHeader(value = "X-Editor-Token", required = false) String token) {
        authService.revoke(token);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
