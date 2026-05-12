package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ApiRecordProposal;
import com.baek.viewer.model.ItAssignee;
import com.baek.viewer.repository.ApiRecordProposalRepository;
import com.baek.viewer.repository.ApiRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecordProposalService {

    private static final Logger log = LoggerFactory.getLogger(RecordProposalService.class);

    private final ApiRecordProposalRepository proposalRepository;
    private final ApiRecordRepository recordRepository;
    private final ApiRecordPatchService patchService;
    private final ApiRecordStatusEventService statusEventService;
    private final ItAssigneeService itAssigneeService;
    private final NavNoticeService navNoticeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecordProposalService(ApiRecordProposalRepository proposalRepository,
                                 ApiRecordRepository recordRepository,
                                 ApiRecordPatchService patchService,
                                 ApiRecordStatusEventService statusEventService,
                                 ItAssigneeService itAssigneeService,
                                 NavNoticeService navNoticeService) {
        this.proposalRepository = proposalRepository;
        this.recordRepository = recordRepository;
        this.patchService = patchService;
        this.statusEventService = statusEventService;
        this.itAssigneeService = itAssigneeService;
        this.navNoticeService = navNoticeService;
    }

    public Optional<ApiRecordProposal> findByRecordId(long recordId) {
        return proposalRepository.findByRecordId(recordId);
    }

    static String formatStatusChangeSummary(String fromDb, Object toPatch) {
        String from = (fromDb == null || fromDb.isBlank()) ? "-" : fromDb.trim();
        String to;
        if (toPatch == null) {
            to = "자동계산";
        } else {
            String s = toPatch.toString().trim();
            to = s.isEmpty() ? "자동계산" : s;
        }
        return from + " → " + to;
    }

    @Transactional
    public ApiRecordProposal saveOrUpdateProposal(long recordId, Map<String, Object> patch,
                                                    String submittedBy, Long submitterAssigneeId,
                                                    String clientIp) {
        Map<String, Object> filtered = ApiRecordPatchService.filterProposalKeys(patch);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("저장할 제안 필드가 없습니다.");
        }
        if (!filtered.containsKey("status")) {
            filtered.remove("statusChangeSummary");
            filtered.remove("statusChangeReason");
        }
        if (filtered.containsKey("statusChangeReason") && !filtered.containsKey("status")) {
            throw new IllegalArgumentException("상태 변경사유는 상태 변경과 함께 제출해야 합니다.");
        }
        if (filtered.containsKey("status")) {
            Object reason = filtered.get("statusChangeReason");
            if (reason == null || reason.toString().trim().isEmpty()) {
                throw new IllegalArgumentException("상태 변경 시 상태 변경사유를 입력하세요.");
            }
        }
        filtered.remove("statusChangeSummary");

        if (submitterAssigneeId != null && filtered.containsKey("status")) {
            proposalRepository.findByRecordId(recordId).ifPresent(ex -> {
                String payload = ex.getPayloadJson();
                if (payload == null || payload.isBlank()) {
                    return;
                }
                try {
                    Map<String, Object> prev = objectMapper.readValue(payload, new TypeReference<>() {});
                    if (prev == null || !prev.containsKey("status")) {
                        return;
                    }
                    Object newSt = filtered.get("status");
                    Object oldSt = prev.get("status");
                    String ns = newSt == null ? "" : newSt.toString().trim();
                    String os = oldSt == null ? "" : oldSt.toString().trim();
                    Object newR = filtered.get("statusChangeReason");
                    Object oldR = prev.get("statusChangeReason");
                    String nr = newR == null ? "" : newR.toString().trim();
                    String or = oldR == null ? "" : oldR.toString().trim();
                    if (ns.equals(os) && nr.equals(or)) {
                        return;
                    }
                } catch (Exception parseEx) {
                    log.debug("[제안] 기존 payload 파싱 스킵 recordId={}: {}", recordId, parseEx.getMessage());
                    return;
                }
                throw new IllegalArgumentException(
                        "이미 승인요청 중인 상태 변경이 있습니다. 관리자 승인·반려 후 다시 시도하거나, 상태 제안 철회 후 재요청하세요.");
            });
        }

        ApiRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("레코드를 찾을 수 없습니다."));
        if (filtered.containsKey("status")) {
            filtered.put("statusChangeSummary", formatStatusChangeSummary(record.getStatus(), filtered.get("status")));
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(filtered);
        } catch (Exception e) {
            throw new IllegalArgumentException("제안 직렬화 실패");
        }
        String summary = ProposalSummaryFormatter.format(filtered);
        LocalDateTime now = LocalDateTime.now();
        ApiRecordProposal p = proposalRepository.findByRecordId(recordId).orElseGet(ApiRecordProposal::new);
        p.setRecordId(recordId);
        p.setPayloadJson(json);
        p.setSubmittedBy(submittedBy);
        p.setSubmitterAssigneeId(submitterAssigneeId);
        p.setSummaryText(summary.isEmpty() ? null : summary);
        if (p.getSubmittedAt() == null) p.setSubmittedAt(now);
        p.setUpdatedAt(now);
        ApiRecordProposal saved = proposalRepository.save(p);
        if (filtered.containsKey("status")) {
            statusEventService.recordEvent(
                    recordId,
                    ApiRecordStatusEventService.EVENT_PROPOSAL_SUBMITTED,
                    record.getStatus(),
                    stringOrNull(filtered.get("status")),
                    submitterAssigneeId != null ? ApiRecordStatusEventService.ROLE_EDITOR : ApiRecordStatusEventService.ROLE_ADMIN,
                    submittedBy,
                    clientIp,
                    stringOrNull(filtered.get("statusChangeReason")),
                    stringOrNull(filtered.get("statusChangeSummary")),
                    now
            );
        }
        log.debug("[제안 저장] recordId={}, keys={}", recordId, filtered.keySet());
        return saved;
    }

    /**
     * @param isAdmin      관리자면 제출자 무관 철회
     * @param editorId     편집자 토큰의 담당자 ID (관리자면 무시)
     */
    @Transactional
    public void withdraw(long recordId, boolean isAdmin, Long editorId) {
        Optional<ApiRecordProposal> opt = proposalRepository.findByRecordId(recordId);
        if (opt.isEmpty()) {
            return;
        }
        ApiRecordProposal p = opt.get();
        if (!isAdmin) {
            Long sub = p.getSubmitterAssigneeId();
            if (sub == null || editorId == null || !sub.equals(editorId)) {
                throw new IllegalStateException("본인이 제출한 제안만 철회할 수 있습니다.");
            }
        }
        proposalRepository.delete(p);
        log.info("[제안 철회] recordId={}, admin={}", recordId, isAdmin);
    }

    /**
     * 제안 JSON에서 상태 변경 관련 키만 제거. 나머지 필드가 없으면 제안 행 삭제.
     */
    @Transactional
    public void withdrawStatusFieldsOnly(long recordId, boolean isAdmin, Long editorId) {
        Optional<ApiRecordProposal> opt = proposalRepository.findByRecordId(recordId);
        if (opt.isEmpty()) {
            return;
        }
        ApiRecordProposal p = opt.get();
        if (!isAdmin) {
            Long sub = p.getSubmitterAssigneeId();
            if (sub == null || editorId == null || !sub.equals(editorId)) {
                throw new IllegalStateException("본인이 제출한 제안만 철회할 수 있습니다.");
            }
        }
        Map<String, Object> map;
        try {
            map = objectMapper.readValue(p.getPayloadJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[제안 상태 부분 철회] JSON 파싱 실패 recordId={}", recordId);
            return;
        }
        LinkedHashMap<String, Object> next = new LinkedHashMap<>(map);
        next.remove("status");
        next.remove("statusChangeReason");
        next.remove("statusChangeSummary");
        next = new LinkedHashMap<>(ApiRecordPatchService.filterProposalKeys(next));
        if (next.isEmpty()) {
            proposalRepository.delete(p);
            log.info("[제안 상태 부분 철회→전체삭제] recordId={}", recordId);
            return;
        }
        try {
            p.setPayloadJson(objectMapper.writeValueAsString(next));
        } catch (Exception e) {
            throw new IllegalArgumentException("제안 직렬화 실패");
        }
        String s = ProposalSummaryFormatter.format(next);
        p.setSummaryText(s.isEmpty() ? null : s);
        p.setUpdatedAt(LocalDateTime.now());
        proposalRepository.save(p);
        log.info("[제안 상태 부분 철회] recordId={}", recordId);
    }

    @Transactional
    public int approve(List<Long> recordIds, String clientIp) {
        int applied = 0;
        for (Long rid : recordIds) {
            if (rid == null) continue;
            Optional<ApiRecordProposal> opt = proposalRepository.findByRecordId(rid);
            if (opt.isEmpty()) continue;
            ApiRecordProposal prop = opt.get();
            Map<String, Object> map;
            try {
                map = objectMapper.readValue(prop.getPayloadJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("[제안 승인 스킵] recordId={}, JSON 파싱 실패: {}", rid, e.getMessage());
                continue;
            }
            map = ApiRecordPatchService.filterProposalKeys(map);
            ApiRecord r = recordRepository.findById(rid).orElse(null);
            if (r == null) {
                proposalRepository.delete(prop);
                continue;
            }
            String oldStatus = r.getStatus();
            String reason = stringOrNull(map.get("statusChangeReason"));
            String summary = stringOrNull(map.get("statusChangeSummary"));
            boolean hasStatusPatch = map.containsKey("status");
            patchService.applyPatch(r, map, clientIp);
            if (hasStatusPatch) {
                statusEventService.recordEvent(
                        rid,
                        ApiRecordStatusEventService.EVENT_PROPOSAL_APPROVED,
                        oldStatus,
                        r.getStatus(),
                        ApiRecordStatusEventService.ROLE_ADMIN,
                        "관리자",
                        clientIp,
                        reason,
                        summary,
                        r.getModifiedAt()
                );
            }
            recordRepository.save(r);
            proposalRepository.delete(prop);
            applied++;
        }
        log.info("[제안 승인] 요청={}건, 반영={}건", recordIds.size(), applied);
        return applied;
    }

    private static final class ProposalRejectBundle {
        final long recordId;
        final ApiRecordProposal proposal;
        final ApiRecord record;

        ProposalRejectBundle(long recordId, ApiRecordProposal proposal, ApiRecord record) {
            this.recordId = recordId;
            this.proposal = proposal;
            this.record = record;
        }
    }

    private static String formatRecordPathLine(ApiRecord rec, long recordId) {
        if (rec != null) {
            List<String> parts = new ArrayList<>();
            if (rec.getRepositoryName() != null && !rec.getRepositoryName().isBlank()) {
                parts.add(rec.getRepositoryName().trim());
            }
            if (rec.getApiPath() != null && !rec.getApiPath().isBlank()) {
                parts.add(rec.getApiPath().trim());
            }
            if (rec.getHttpMethod() != null && !rec.getHttpMethod().isBlank()) {
                parts.add(rec.getHttpMethod().trim());
            }
            if (!parts.isEmpty()) {
                return String.join(" - ", parts);
            }
        }
        return "record #" + recordId;
    }

    private static String buildProposalRejectNoticeForGroup(List<ProposalRejectBundle> group, String reason) {
        List<ProposalRejectBundle> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingLong(b -> b.recordId));
        ProposalRejectBundle first = sorted.get(0);
        String line = formatRecordPathLine(first.record, first.recordId);
        if (sorted.size() <= 1) {
            return reason + "\n\n" + line;
        }
        return reason + "\n\n" + line + " 외 " + (sorted.size() - 1) + "건이 상태변경 반려되었습니다.";
    }

    @Transactional
    public int reject(List<Long> recordIds, String reason, String clientIp) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw new IllegalArgumentException("반려 사유를 입력하세요.");
        }
        List<ProposalRejectBundle> bundles = new ArrayList<>();
        for (Long rid : recordIds) {
            if (rid == null) {
                continue;
            }
            Optional<ApiRecordProposal> opt = proposalRepository.findByRecordId(rid);
            if (opt.isEmpty()) {
                continue;
            }
            ApiRecordProposal p = opt.get();
            ApiRecord rec = recordRepository.findById(rid).orElse(null);
            bundles.add(new ProposalRejectBundle(rid, p, rec));
        }
        Map<Long, List<ProposalRejectBundle>> byAssignee = new LinkedHashMap<>();
        List<ProposalRejectBundle> noAssignee = new ArrayList<>();
        for (ProposalRejectBundle b : bundles) {
            Long aid = b.proposal.getSubmitterAssigneeId();
            if (aid == null) {
                noAssignee.add(b);
            } else {
                byAssignee.computeIfAbsent(aid, k -> new ArrayList<>()).add(b);
            }
        }
        int n = 0;
        for (ProposalRejectBundle b : noAssignee) {
            proposalRepository.delete(b.proposal);
            n++;
        }
        for (Map.Entry<Long, List<ProposalRejectBundle>> e : byAssignee.entrySet()) {
            String batchId = UUID.randomUUID().toString();
            List<ProposalRejectBundle> group = e.getValue();
            String notice = buildProposalRejectNoticeForGroup(group, r);
            itAssigneeService.setProposalRejectNotice(e.getKey(), notice);
            for (ProposalRejectBundle b : group) {
                recordRejectedStatusEvent(b, r, clientIp);
                navNoticeService.recordProposalReject(b.recordId, e.getKey(), r, b.record, b.proposal.getSummaryText(), batchId);
                proposalRepository.delete(b.proposal);
                n++;
            }
        }
        log.info("[상태변경 반려] 제거={}건", n);
        return n;
    }

    private void recordRejectedStatusEvent(ProposalRejectBundle bundle, String rejectReason, String clientIp) {
        if (bundle == null || bundle.record == null || bundle.proposal == null) {
            return;
        }
        Map<String, Object> patch;
        try {
            patch = objectMapper.readValue(bundle.proposal.getPayloadJson(), new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("[반려 이력 저장 스킵] recordId={}, payload 파싱 실패: {}", bundle.recordId, e.getMessage());
            return;
        }
        if (patch == null || !patch.containsKey("status")) {
            return;
        }
        statusEventService.recordEvent(
                bundle.recordId,
                ApiRecordStatusEventService.EVENT_PROPOSAL_REJECTED,
                bundle.record.getStatus(),
                stringOrNull(patch.get("status")),
                ApiRecordStatusEventService.ROLE_ADMIN,
                "관리자",
                clientIp,
                rejectReason,
                stringOrNull(patch.get("statusChangeSummary")),
                LocalDateTime.now()
        );
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 제안 제출자 표시명 — `submitter_assignee_id` 우선, 없으면 `submitted_by` 파싱 */
    public String resolveProposalSubmitterDisplayName(ApiRecordProposal p) {
        if (p == null) {
            return "";
        }
        Long sid = p.getSubmitterAssigneeId();
        if (sid != null) {
            Optional<ItAssignee> oa = itAssigneeService.findById(sid.longValue());
            if (oa.isPresent()) {
                String n = oa.get().getAssigneeName();
                if (n != null && !n.isBlank()) {
                    return n.trim();
                }
            }
        }
        String sb = p.getSubmittedBy() != null ? p.getSubmittedBy().trim() : "";
        if (sb.isEmpty()) {
            return "";
        }
        if ("ADMIN".equalsIgnoreCase(sb)) {
            return "관리자";
        }
        int slash = sb.lastIndexOf(" / ");
        if (slash >= 0) {
            return sb.substring(slash + 3).trim();
        }
        if (sb.startsWith("EDITOR:")) {
            return "";
        }
        return sb;
    }

    public Map<String, Object> previewPayload(long recordId) {
        return proposalRepository.findByRecordId(recordId)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("recordId", p.getRecordId());
                    m.put("submittedBy", p.getSubmittedBy());
                    m.put("submittedAt", p.getSubmittedAt() != null ? p.getSubmittedAt().toString() : null);
                    m.put("updatedAt", p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);
                    m.put("summary", p.getSummaryText() != null ? p.getSummaryText() : "");
                    m.put("proposalSubmitterName", resolveProposalSubmitterDisplayName(p));
                    try {
                        m.put("patch", objectMapper.readValue(p.getPayloadJson(), new TypeReference<>() {}));
                    } catch (Exception e) {
                        m.put("patch", Map.of());
                    }
                    return m;
                })
                .orElse(Map.of());
    }
}
