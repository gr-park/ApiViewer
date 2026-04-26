package com.baek.viewer.controller;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 엑셀 업로드 — viewer.html에서 기존 레코드 편집 가능 필드 일괄 업데이트.
 */
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);
    private final ApiRecordRepository repository;

    public UploadController(ApiRecordRepository repository) {
        this.repository = repository;
    }

    private static final String BLOCKED = "차단완료";

    /**
     * viewer.html 엑셀 업로드 — 기존 레코드의 편집 가능 필드 일괄 업데이트 (신규 생성 없음).
     *
     * 업데이트 대상 필드:
     *   - 기본 9개: status, statusOverridden, blockCriteria, memo, reviewResult, reviewOpinion,
     *              cboScheduledDate, deployScheduledDate, deployCsr
     *   - 추가 4개: teamOverride, managerOverride(+managerOverridden 플래그), descriptionOverride, reviewStage
     *
     * 차단완료 정책:
     *   - 행 skip 제거. 차단완료 행도 비(非)상태 필드는 업데이트 가능.
     *   - DB.status='차단완료' ↔ incoming status 불일치, 또는 incoming status='차단완료' 인 경우
     *     status/statusOverridden 변경은 무시 (엑셀로 차단완료 상태 교차 이동 금지).
     *
     * 분석일시(lastAnalyzedAt)는 엑셀 값을 무시하고 업로드 시각으로 자동 설정.
     */
    @PostMapping("/excel-viewer")
    public ResponseEntity<?> uploadExcelViewer(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        if (rows == null || rows.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "업로드 데이터가 없습니다."));

        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";

        log.info("[viewer 엑셀 업로드] 건수={}, ip={}", rows.size(), ip);
        LocalDateTime now = LocalDateTime.now();
        int updated = 0, skipped = 0;
        List<Long> updatedIds = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String repoName  = str(row, "repositoryName");
            String apiPath   = str(row, "apiPath");
            String httpMethod = str(row, "httpMethod");
            if (repoName == null || repoName.isBlank() || apiPath == null || apiPath.isBlank()) { skipped++; continue; }
            if (httpMethod == null || httpMethod.isBlank()) httpMethod = "GET";

            Optional<ApiRecord> opt = repository.findByRepositoryNameAndApiPathAndHttpMethod(repoName, apiPath, httpMethod);
            if (opt.isEmpty()) { skipped++; continue; }

            ApiRecord r = opt.get();

            // 상태/상태확정: 차단완료 교차(in/out) 변경은 무시 — 소스코드 기반 자동 판정 보호
            boolean statusLocked = BLOCKED.equals(r.getStatus());
            String newStatus = str(row, "status");
            if (newStatus != null && !newStatus.isBlank() && !newStatus.equals(r.getStatus())) {
                if (!statusLocked && !BLOCKED.equals(newStatus)) {
                    r.setStatus(newStatus);
                    r.setStatusOverridden(true);
                }
            }
            if (!statusLocked) {
                String ovr = str(row, "statusOverridden");
                if ("확정".equals(ovr))   r.setStatusOverridden(true);
                else if ("미확정".equals(ovr)) r.setStatusOverridden(false);
            }

            if (row.containsKey("blockCriteria")) r.setBlockCriteria(str(row, "blockCriteria"));
            if (row.containsKey("memo"))          r.setMemo(str(row, "memo"));
            if (row.containsKey("cboScheduledDate")) {
                String ds = str(row, "cboScheduledDate");
                r.setCboScheduledDate(ds == null || ds.isBlank() ? null : java.time.LocalDate.parse(ds));
            }
            if (row.containsKey("deployScheduledDate")) {
                String ds = str(row, "deployScheduledDate");
                r.setDeployScheduledDate(ds == null || ds.isBlank() ? null : java.time.LocalDate.parse(ds));
            }
            if (row.containsKey("deployCsr")) r.setDeployCsr(str(row, "deployCsr"));
            if (row.containsKey("deployManager")) r.setDeployManager(str(row, "deployManager"));
            if (row.containsKey("reviewResult"))  r.setReviewResult(str(row, "reviewResult"));
            if (row.containsKey("reviewOpinion")) r.setReviewOpinion(str(row, "reviewOpinion"));

            if (row.containsKey("teamOverride")) r.setTeamOverride(str(row, "teamOverride"));
            if (row.containsKey("managerOverride")) {
                String mgrVal = str(row, "managerOverride");
                r.setManagerOverride(mgrVal);
                // 단건 PUT / 일괄 변경과 동일 규칙: 값 있으면 수동 플래그 ON, 비우면 OFF
                r.setManagerOverridden(mgrVal != null);
            }
            if (row.containsKey("descriptionOverride")) r.setDescriptionOverride(str(row, "descriptionOverride"));
            if (row.containsKey("reviewStage")) r.setReviewStage(str(row, "reviewStage"));

            // 분석일시는 업로드 시각으로 고정
            r.setLastAnalyzedAt(now);
            r.setModifiedAt(now);
            r.setModifiedIp(ip);

            repository.save(r);
            updatedIds.add(r.getId());
            updated++;
        }

        log.info("[viewer 엑셀 업로드 완료] 업데이트={}, 스킵={}", updated, skipped);
        return ResponseEntity.ok(Map.of("updated", updated, "skipped", skipped, "total", rows.size(), "updatedIds", updatedIds));
    }

    /** 셀 값 → 트림 문자열. 빈/공백 셀은 null 로 정규화 (필드 clear 의미로 처리). */
    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
