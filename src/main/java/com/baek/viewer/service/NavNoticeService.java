package com.baek.viewer.service;

import com.baek.viewer.model.AdminAssigneeNotice;
import com.baek.viewer.model.AssigneeToAdminMessage;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.ItAssignee;
import com.baek.viewer.model.ProposalRejectEvent;
import com.baek.viewer.repository.AdminAssigneeNoticeRepository;
import com.baek.viewer.repository.AssigneeToAdminMessageRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.ItAssigneeRepository;
import com.baek.viewer.repository.ProposalRejectEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class NavNoticeService {

    private static final Logger log = LoggerFactory.getLogger(NavNoticeService.class);

    private final ProposalRejectEventRepository rejectEventRepository;
    private final AdminAssigneeNoticeRepository adminAssigneeNoticeRepository;
    private final AssigneeToAdminMessageRepository assigneeToAdminRepository;
    private final GlobalConfigRepository globalConfigRepository;
    private final ItAssigneeRepository itAssigneeRepository;

    public NavNoticeService(ProposalRejectEventRepository rejectEventRepository,
                            AdminAssigneeNoticeRepository adminAssigneeNoticeRepository,
                            AssigneeToAdminMessageRepository assigneeToAdminRepository,
                            GlobalConfigRepository globalConfigRepository,
                            ItAssigneeRepository itAssigneeRepository) {
        this.rejectEventRepository = rejectEventRepository;
        this.adminAssigneeNoticeRepository = adminAssigneeNoticeRepository;
        this.assigneeToAdminRepository = assigneeToAdminRepository;
        this.globalConfigRepository = globalConfigRepository;
        this.itAssigneeRepository = itAssigneeRepository;
    }

    @Transactional
    public void recordProposalReject(long recordId, long assigneeId, String reason,
                                     ApiRecord recordOrNull, String proposalSummary, String rejectBatchId) {
        ProposalRejectEvent e = new ProposalRejectEvent();
        e.setRecordId(recordId);
        e.setAssigneeId(assigneeId);
        e.setReason(reason);
        e.setCreatedAt(LocalDateTime.now());
        e.setProposalSummary(proposalSummary);
        e.setRejectBatchId(rejectBatchId != null && !rejectBatchId.isBlank() ? rejectBatchId : null);
        if (recordOrNull != null) {
            e.setRepositoryName(recordOrNull.getRepositoryName());
            e.setApiPath(recordOrNull.getApiPath());
            e.setHttpMethod(recordOrNull.getHttpMethod());
        }
        rejectEventRepository.save(e);
        log.debug("[반려 이벤트 저장] recordId={}, assigneeId={}, batch={}", recordId, assigneeId, rejectBatchId);
    }

    public List<Map<String, Object>> listRejectEventsForAssignee(long assigneeId) {
        List<ProposalRejectEvent> raw = rejectEventRepository.findTop50ByAssigneeIdOrderByCreatedAtDesc(assigneeId);
        Set<String> seenBatch = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProposalRejectEvent e : raw) {
            String bid = e.getRejectBatchId();
            if (bid != null && !bid.isBlank()) {
                if (seenBatch.contains(bid)) {
                    continue;
                }
                seenBatch.add(bid);
                List<ProposalRejectEvent> members = new ArrayList<>();
                for (ProposalRejectEvent x : raw) {
                    if (bid.equals(x.getRejectBatchId())) {
                        members.add(x);
                    }
                }
                members.sort(Comparator.comparing(ProposalRejectEvent::getId));
                out.add(rejectBatchToMap(members));
            } else {
                out.add(rejectEventToMap(e));
            }
        }
        return out;
    }

    private static String formatRejectEventPathLine(ProposalRejectEvent e) {
        List<String> parts = new ArrayList<>();
        if (e.getRepositoryName() != null && !e.getRepositoryName().isBlank()) {
            parts.add(e.getRepositoryName().trim());
        }
        if (e.getApiPath() != null && !e.getApiPath().isBlank()) {
            parts.add(e.getApiPath().trim());
        }
        if (e.getHttpMethod() != null && !e.getHttpMethod().isBlank()) {
            parts.add(e.getHttpMethod().trim());
        }
        if (parts.isEmpty()) {
            return "record #" + e.getRecordId();
        }
        return String.join(" - ", parts);
    }

    private static Map<String, Object> rejectBatchToMap(List<ProposalRejectEvent> members) {
        ProposalRejectEvent first = members.get(0);
        String bid = first.getRejectBatchId();
        int n = members.size();
        String pathLine = formatRejectEventPathLine(first);
        String summaryLine = n <= 1 ? pathLine : pathLine + " 외 " + (n - 1) + "건이 상태변경 반려되었습니다.";
        LocalDateTime createdAt = members.stream()
                .map(ProposalRejectEvent::getCreatedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        boolean anyOpen = members.stream().anyMatch(m -> m.getDismissedAt() == null);
        LocalDateTime maxDismissed = members.stream()
                .map(ProposalRejectEvent::getDismissedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "rejectBatch");
        m.put("batchId", bid != null ? bid : "");
        m.put("reason", Optional.ofNullable(first.getReason()).orElse(""));
        m.put("summaryLine", summaryLine);
        m.put("batchCount", n);
        m.put("recordId", first.getRecordId());
        m.put("repositoryName", Optional.ofNullable(first.getRepositoryName()).orElse(""));
        m.put("apiPath", Optional.ofNullable(first.getApiPath()).orElse(""));
        m.put("httpMethod", Optional.ofNullable(first.getHttpMethod()).orElse(""));
        m.put("createdAt", createdAt != null ? createdAt.toString() : "");
        m.put("dismissedAt", anyOpen ? null : (maxDismissed != null ? maxDismissed.toString() : null));
        return m;
    }

    @Transactional
    public void dismissRejectBatch(String batchId, long assigneeId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId 가 필요합니다.");
        }
        List<ProposalRejectEvent> list = rejectEventRepository.findByAssigneeIdAndRejectBatchId(assigneeId, batchId);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("해당 반려 묶음을 찾을 수 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now();
        for (ProposalRejectEvent e : list) {
            if (e.getDismissedAt() == null) {
                e.setDismissedAt(now);
                rejectEventRepository.save(e);
            }
        }
    }

    private long countCollapsedOpenRejectItems(long assigneeId) {
        List<ProposalRejectEvent> open = rejectEventRepository.findByAssigneeIdAndDismissedAtIsNull(assigneeId);
        if (open.isEmpty()) {
            return 0;
        }
        Set<String> seenBatch = new HashSet<>();
        long c = 0;
        for (ProposalRejectEvent e : open) {
            String bid = e.getRejectBatchId();
            if (bid != null && !bid.isBlank()) {
                if (seenBatch.add(bid)) {
                    c++;
                }
            } else {
                c++;
            }
        }
        return c;
    }

    @Transactional
    public void dismissRejectEvent(long eventId, long assigneeId) {
        ProposalRejectEvent e = rejectEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        if (!e.getAssigneeId().equals(assigneeId)) {
            throw new IllegalStateException("본인 알림만 확인할 수 있습니다.");
        }
        e.setDismissedAt(LocalDateTime.now());
        rejectEventRepository.save(e);
    }

    @Transactional
    public void setPortalNotice(String text) {
        GlobalConfig gc = globalConfigRepository.findById(1L).orElseGet(GlobalConfig::new);
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            gc.setPortalNoticeText(null);
            gc.setPortalNoticeAt(null);
        } else {
            gc.setPortalNoticeText(t);
            gc.setPortalNoticeAt(LocalDateTime.now());
        }
        globalConfigRepository.save(gc);
        log.info("[포털 전체 쪽지] {}", t.isEmpty() ? "삭제" : "갱신");
    }

    public Map<String, String> getPortalNoticeSummary() {
        return globalConfigRepository.findById(1L)
                .map(gc -> {
                    String body = gc.getPortalNoticeText();
                    String at = gc.getPortalNoticeAt() != null ? gc.getPortalNoticeAt().toString() : "";
                    if (body == null || body.isBlank()) {
                        return Map.of("text", "", "at", "");
                    }
                    return Map.of("text", body.trim(), "at", at);
                })
                .orElse(Map.of("text", "", "at", ""));
    }

    @Transactional
    public int sendAssigneeNotices(List<Long> assigneeIds, String body) {
        String b = body == null ? "" : body.trim();
        if (b.isEmpty()) {
            throw new IllegalArgumentException("쪽지 내용을 입력하세요.");
        }
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            throw new IllegalArgumentException("담당자를 한 명 이상 선택하세요.");
        }
        int n = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Long id : assigneeIds) {
            if (id == null) continue;
            if (!itAssigneeRepository.existsById(id)) continue;
            AdminAssigneeNotice notice = new AdminAssigneeNotice();
            notice.setAssigneeId(id);
            notice.setBody(b);
            notice.setCreatedAt(now);
            adminAssigneeNoticeRepository.save(notice);
            n++;
        }
        if (n == 0) {
            throw new IllegalArgumentException("유효한 담당자 ID가 없습니다.");
        }
        log.info("[관리자 개별 쪽지] 수신자={}건", n);
        return n;
    }

    public List<Map<String, Object>> listAdminNoticesForAssignee(long assigneeId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AdminAssigneeNotice n : adminAssigneeNoticeRepository.findTop50ByAssigneeIdOrderByCreatedAtDesc(assigneeId)) {
            out.add(adminNoticeToMap(n));
        }
        return out;
    }

    @Transactional
    public void dismissAdminAssigneeNotice(long noticeId, long assigneeId) {
        AdminAssigneeNotice n = adminAssigneeNoticeRepository.findById(noticeId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        if (!n.getAssigneeId().equals(assigneeId)) {
            throw new IllegalStateException("본인 알림만 확인할 수 있습니다.");
        }
        n.setDismissedAt(LocalDateTime.now());
        adminAssigneeNoticeRepository.save(n);
    }

    @Transactional
    public void postMessageToAdmin(long senderAssigneeId, String text, Long replyToAdminNoticeId) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("내용을 입력하세요.");
        }
        ItAssignee a = itAssigneeRepository.findById(senderAssigneeId)
                .orElseThrow(() -> new IllegalArgumentException("담당자를 찾을 수 없습니다."));
        if (replyToAdminNoticeId != null) {
            AdminAssigneeNotice ref = adminAssigneeNoticeRepository.findById(replyToAdminNoticeId)
                    .orElseThrow(() -> new IllegalArgumentException("관리자 쪽지를 찾을 수 없습니다."));
            if (!ref.getAssigneeId().equals(senderAssigneeId)) {
                throw new IllegalStateException("본인에게 온 쪽지에만 답장할 수 있습니다.");
            }
        }
        AssigneeToAdminMessage m = new AssigneeToAdminMessage();
        m.setSenderAssigneeId(senderAssigneeId);
        m.setBody(t);
        m.setCreatedAt(LocalDateTime.now());
        m.setSenderTeamName(a.getTeamName());
        m.setSenderAssigneeName(a.getAssigneeName());
        m.setReplyToAdminNoticeId(replyToAdminNoticeId);
        assigneeToAdminRepository.save(m);
        log.info("[담당자→관리자 쪽지] assigneeId={}, replyToNotice={}", senderAssigneeId, replyToAdminNoticeId);
    }

    @Transactional
    public void replyFromAdminToAssigneeMessage(long assigneeMessageId, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("내용을 입력하세요.");
        }
        AssigneeToAdminMessage orig = assigneeToAdminRepository.findById(assigneeMessageId)
                .orElseThrow(() -> new IllegalArgumentException("메시지를 찾을 수 없습니다."));
        AdminAssigneeNotice n = new AdminAssigneeNotice();
        n.setAssigneeId(orig.getSenderAssigneeId());
        n.setBody(t);
        n.setCreatedAt(LocalDateTime.now());
        n.setReplyToAssigneeMessageId(assigneeMessageId);
        adminAssigneeNoticeRepository.save(n);
        log.info("[관리자→담당자 답장] assigneeMessageId={}, assigneeId={}", assigneeMessageId, orig.getSenderAssigneeId());
    }

    public List<Map<String, Object>> listMessagesFromAssignees(int limit) {
        int lim = Math.min(200, Math.max(1, limit));
        List<AssigneeToAdminMessage> list = assigneeToAdminRepository
                .findTop100ByAdminDismissedAtIsNullOrderByCreatedAtDesc();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < lim; i++) {
            out.add(assigneeMessageToMap(list.get(i)));
        }
        return out;
    }

    @Transactional
    public void dismissAssigneeToAdminMessage(long messageId) {
        AssigneeToAdminMessage m = assigneeToAdminRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("메시지를 찾을 수 없습니다."));
        m.setAdminDismissedAt(LocalDateTime.now());
        assigneeToAdminRepository.save(m);
    }

    public Map<String, Object> editorInboxSummary(long assigneeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("portalNotice", getPortalNoticeSummary());
        m.put("rejectEvents", listRejectEventsForAssignee(assigneeId));
        m.put("adminNotices", listAdminNoticesForAssignee(assigneeId));
        m.put("rejectOpenCount", countCollapsedOpenRejectItems(assigneeId));
        m.put("adminNoticeOpenCount", adminAssigneeNoticeRepository.countByAssigneeIdAndDismissedAtIsNull(assigneeId));
        return m;
    }

    public Map<String, Object> adminInboxSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("portalNotice", getPortalNoticeSummary());
        m.put("fromAssignees", listMessagesFromAssignees(100));
        m.put("fromAssigneesOpenCount", assigneeToAdminRepository.countByAdminDismissedAtIsNull());
        return m;
    }

    private static Map<String, Object> rejectEventToMap(ProposalRejectEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("recordId", e.getRecordId());
        m.put("reason", e.getReason());
        m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : "");
        m.put("dismissedAt", e.getDismissedAt() != null ? e.getDismissedAt().toString() : null);
        m.put("repositoryName", Optional.ofNullable(e.getRepositoryName()).orElse(""));
        m.put("apiPath", Optional.ofNullable(e.getApiPath()).orElse(""));
        m.put("httpMethod", Optional.ofNullable(e.getHttpMethod()).orElse(""));
        m.put("proposalSummary", Optional.ofNullable(e.getProposalSummary()).orElse(""));
        m.put("rejectBatchId", Optional.ofNullable(e.getRejectBatchId()).orElse(""));
        m.put("kind", "reject");
        return m;
    }

    private static Map<String, Object> adminNoticeToMap(AdminAssigneeNotice n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("body", n.getBody());
        m.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt().toString() : "");
        m.put("dismissedAt", n.getDismissedAt() != null ? n.getDismissedAt().toString() : null);
        m.put("replyToAssigneeMessageId", n.getReplyToAssigneeMessageId());
        m.put("kind", "adminDirect");
        return m;
    }

    private static Map<String, Object> assigneeMessageToMap(AssigneeToAdminMessage x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.getId());
        m.put("body", x.getBody());
        m.put("createdAt", x.getCreatedAt() != null ? x.getCreatedAt().toString() : "");
        m.put("senderTeamName", Optional.ofNullable(x.getSenderTeamName()).orElse(""));
        m.put("senderAssigneeName", Optional.ofNullable(x.getSenderAssigneeName()).orElse(""));
        m.put("senderAssigneeId", x.getSenderAssigneeId());
        m.put("replyToAdminNoticeId", x.getReplyToAdminNoticeId());
        m.put("kind", "assigneeToAdmin");
        return m;
    }
}
