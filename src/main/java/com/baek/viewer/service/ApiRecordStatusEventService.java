package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecordStatusEvent;
import com.baek.viewer.repository.ApiRecordStatusEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ApiRecordStatusEventService {

    public static final String EVENT_PROPOSAL_SUBMITTED = "PROPOSAL_SUBMITTED";
    public static final String EVENT_PROPOSAL_APPROVED = "PROPOSAL_APPROVED";
    public static final String EVENT_PROPOSAL_REJECTED = "PROPOSAL_REJECTED";
    public static final String EVENT_DIRECT_STATUS_CHANGED = "DIRECT_STATUS_CHANGED";
    public static final String EVENT_AUTO_STATUS_APPLIED = "AUTO_STATUS_APPLIED";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_EDITOR = "EDITOR";
    public static final String ROLE_SYSTEM = "SYSTEM";

    private final ApiRecordStatusEventRepository repository;

    public ApiRecordStatusEventService(ApiRecordStatusEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ApiRecordStatusEvent recordEvent(Long recordId, String eventType,
                                            String fromStatus, String toStatus,
                                            String actorRole, String actorLabel, String actorIp,
                                            String reason, String summaryText,
                                            LocalDateTime createdAt) {
        ApiRecordStatusEvent e = new ApiRecordStatusEvent();
        e.setRecordId(recordId);
        e.setEventType(eventType);
        e.setFromStatus(normalizeStatus(fromStatus));
        e.setToStatus(normalizeStatus(toStatus));
        e.setActorRole(actorRole);
        e.setActorLabel(normalizeActorLabel(actorRole, actorLabel));
        e.setActorIp(normalizeText(actorIp));
        e.setReason(normalizeText(reason));
        e.setSummaryText(normalizeText(summaryText));
        e.setCreatedAt(createdAt != null ? createdAt : LocalDateTime.now());
        return repository.save(e);
    }

    public List<ApiRecordStatusEvent> findByRecordId(Long recordId) {
        return repository.findByRecordIdOrderByCreatedAtAscIdAsc(recordId);
    }

    public Optional<ApiRecordStatusEvent> findLatestByRecordId(Long recordId) {
        return repository.findTopByRecordIdOrderByCreatedAtDescIdDesc(recordId);
    }

    public Map<Long, ApiRecordStatusEvent> findLatestByRecordIds(Collection<Long> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ApiRecordStatusEvent> out = new HashMap<>();
        for (ApiRecordStatusEvent e : repository.findLatestCandidatesByRecordIds(recordIds)) {
            out.putIfAbsent(e.getRecordId(), e);
        }
        return out;
    }

    public List<Map<String, Object>> toResponseList(Long recordId) {
        return findByRecordId(recordId).stream()
                .map(this::toResponseMap)
                .toList();
    }

    public Map<String, Object> toResponseMap(ApiRecordStatusEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("recordId", e.getRecordId());
        m.put("eventType", e.getEventType());
        m.put("eventTypeLabel", eventTypeLabel(e.getEventType()));
        m.put("fromStatus", e.getFromStatus());
        m.put("toStatus", e.getToStatus());
        m.put("actorRole", e.getActorRole());
        m.put("actorLabel", e.getActorLabel());
        m.put("actorIp", e.getActorIp());
        m.put("reason", e.getReason());
        m.put("summaryText", e.getSummaryText());
        m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        return m;
    }

    public String eventTypeLabel(String eventType) {
        if (Objects.equals(EVENT_PROPOSAL_SUBMITTED, eventType)) return "승인요청";
        if (Objects.equals(EVENT_PROPOSAL_APPROVED, eventType)) return "승인완료";
        if (Objects.equals(EVENT_PROPOSAL_REJECTED, eventType)) return "반려";
        if (Objects.equals(EVENT_DIRECT_STATUS_CHANGED, eventType)) return "직접변경";
        if (Objects.equals(EVENT_AUTO_STATUS_APPLIED, eventType)) return "자동분석상태 반영";
        return eventType != null ? eventType : "";
    }

    private static String normalizeActorLabel(String actorRole, String actorLabel) {
        String raw = normalizeText(actorLabel);
        if (ROLE_ADMIN.equals(actorRole)) {
            if (raw == null || "ADMIN".equalsIgnoreCase(raw)) {
                return "관리자";
            }
            return raw;
        }
        if (ROLE_SYSTEM.equals(actorRole)) {
            return raw != null ? raw : "시스템";
        }
        return raw;
    }

    private static String normalizeStatus(String status) {
        String s = normalizeText(status);
        if (s == null || s.isBlank()) {
            return "자동계산";
        }
        return s;
    }

    private static String normalizeText(String value) {
        if (value == null) return null;
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }
}
