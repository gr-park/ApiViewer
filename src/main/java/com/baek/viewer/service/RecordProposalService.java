package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.ApiRecordProposal;
import com.baek.viewer.repository.ApiRecordProposalRepository;
import com.baek.viewer.repository.ApiRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RecordProposalService {

    private static final Logger log = LoggerFactory.getLogger(RecordProposalService.class);

    private final ApiRecordProposalRepository proposalRepository;
    private final ApiRecordRepository recordRepository;
    private final ApiRecordPatchService patchService;
    private final ItAssigneeService itAssigneeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecordProposalService(ApiRecordProposalRepository proposalRepository,
                                 ApiRecordRepository recordRepository,
                                 ApiRecordPatchService patchService,
                                 ItAssigneeService itAssigneeService) {
        this.proposalRepository = proposalRepository;
        this.recordRepository = recordRepository;
        this.patchService = patchService;
        this.itAssigneeService = itAssigneeService;
    }

    public Optional<ApiRecordProposal> findByRecordId(long recordId) {
        return proposalRepository.findByRecordId(recordId);
    }

    @Transactional
    public ApiRecordProposal saveOrUpdateProposal(long recordId, Map<String, Object> patch,
                                                    String submittedBy, Long submitterAssigneeId) {
        Map<String, Object> filtered = ApiRecordPatchService.filterProposalKeys(patch);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("저장할 제안 필드가 없습니다.");
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
            patchService.applyPatch(r, map, clientIp);
            recordRepository.save(r);
            proposalRepository.delete(prop);
            applied++;
        }
        log.info("[제안 승인] 요청={}건, 반영={}건", recordIds.size(), applied);
        return applied;
    }

    @Transactional
    public int reject(List<Long> recordIds, String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw new IllegalArgumentException("반려 사유를 입력하세요.");
        }
        int n = 0;
        for (Long rid : recordIds) {
            if (rid == null) continue;
            Optional<ApiRecordProposal> opt = proposalRepository.findByRecordId(rid);
            if (opt.isEmpty()) continue;
            ApiRecordProposal p = opt.get();
            Long aid = p.getSubmitterAssigneeId();
            if (aid != null) {
                itAssigneeService.setProposalRejectNotice(aid, r);
            }
            proposalRepository.delete(p);
            n++;
        }
        log.info("[제안 반려] 제거={}건", n);
        return n;
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
