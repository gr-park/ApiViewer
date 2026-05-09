package com.baek.viewer.controller;

import com.baek.viewer.model.ItAssignee;
import com.baek.viewer.service.ItAssigneeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 설정 화면 — IT 담당자 계정 관리 (AdminInterceptor 가 /api/config/** 보호).
 */
@RestController
@RequestMapping("/api/config/it-assignees")
public class ItAssigneeAdminController {

    private static final Logger log = LoggerFactory.getLogger(ItAssigneeAdminController.class);
    private static final DateTimeFormatter CREATED_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SORT_FIELDS = Set.of("id", "teamName", "assigneeName", "createdAt");

    private final ItAssigneeService itAssigneeService;

    public ItAssigneeAdminController(ItAssigneeService itAssigneeService) {
        this.itAssigneeService = itAssigneeService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String team,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        int p = Math.max(0, page);
        int sz = Math.min(100, Math.max(1, size));
        Sort sort = resolveSort(sortBy, sortDir);
        Pageable pageable = PageRequest.of(p, sz, sort);
        Page<ItAssignee> result = itAssigneeService.listPaged(team, name, pageable);
        List<Map<String, Object>> rows = result.getContent().stream()
                .map(this::toRow)
                .collect(Collectors.toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignees", rows);
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("number", result.getNumber());
        body.put("size", result.getSize());
        return ResponseEntity.ok(body);
    }

    private static Sort resolveSort(String sortBy, String sortDir) {
        String field = sortBy == null ? "id" : sortBy.trim();
        if (!SORT_FIELDS.contains(field)) {
            field = "id";
        }
        Sort.Direction d = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(d, field);
    }

    private Map<String, Object> toRow(ItAssignee a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("teamName", a.getTeamName());
        m.put("assigneeName", a.getAssigneeName());
        m.put("createdAt", a.getCreatedAt() != null ? CREATED_FMT.format(a.getCreatedAt()) : null);
        m.put("updatedAt", a.getUpdatedAt() != null ? CREATED_FMT.format(a.getUpdatedAt()) : null);
        return m;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        try {
            itAssigneeService.deleteById(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.warn("[IT담당자 삭제 실패] id={}, {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable long id, @RequestBody Map<String, String> body) {
        try {
            String pwd = body.get("newPassword");
            itAssigneeService.resetPassword(id, pwd);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("[IT담당자 비밀번호 리셋 실패] id={}, {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
