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
 * 엑셀 업로드를 통한 API 데이터 등록.
 * 파싱 불가 업무용 — 프론트에서 엑셀을 파싱하여 JSON 배열로 전송.
 */
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);
    private final ApiRecordRepository repository;

    public UploadController(ApiRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 엑셀 데이터 일괄 등록
     * Body: { "repoName": "...", "rows": [ { "apiPath":"/foo", "httpMethod":"GET", ... }, ... ] }
     */
    @PostMapping("/excel")
    public ResponseEntity<?> uploadExcel(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String repoName = (String) body.get("repoName");
        if (repoName == null || repoName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "repoName 필수"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        if (rows == null || rows.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "업로드 데이터가 없습니다."));

        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = req.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";

        log.info("[엑셀 업로드] repo={}, 건수={}, ip={}", repoName, rows.size(), ip);
        LocalDateTime now = LocalDateTime.now();
        int added = 0, updated = 0;

        for (Map<String, Object> row : rows) {
            String apiPath = str(row, "apiPath");
            String httpMethod = str(row, "httpMethod");
            if (apiPath == null || apiPath.isBlank()) continue;
            if (httpMethod == null || httpMethod.isBlank()) httpMethod = "GET";

            Optional<ApiRecord> existing = repository.findByRepositoryNameAndApiPathAndHttpMethod(
                    repoName, apiPath, httpMethod);

            ApiRecord r = existing.orElse(new ApiRecord());
            boolean isNew = r.getId() == null;

            if (isNew) {
                r.setRepositoryName(repoName);
                r.setApiPath(apiPath);
                r.setHttpMethod(httpMethod);
                r.setCreatedIp(ip);
                r.setNew(true);
                r.setStatus("사용");
            }

            r.setDataSource("UPLOAD");
            r.setLastAnalyzedAt(now);
            r.setModifiedAt(now);
            r.setModifiedIp(ip);

            // 선택 필드 업데이트
            if (row.containsKey("controllerName")) r.setControllerName(str(row, "controllerName"));
            if (row.containsKey("methodName"))     r.setMethodName(str(row, "methodName"));
            if (row.containsKey("programId"))      r.setProgramId(str(row, "programId"));
            if (row.containsKey("fullUrl"))        r.setFullUrl(str(row, "fullUrl"));
            if (row.containsKey("apiOperationValue")) r.setApiOperationValue(str(row, "apiOperationValue"));
            if (row.containsKey("memo"))           r.setMemo(str(row, "memo"));
            if (row.containsKey("teamOverride"))   r.setTeamOverride(str(row, "teamOverride"));
            if (row.containsKey("managerOverride")) r.setManagerOverride(str(row, "managerOverride"));

            repository.save(r);
            if (isNew) added++; else updated++;
        }

        log.info("[엑셀 업로드 완료] repo={}, 추가={}, 업데이트={}", repoName, added, updated);
        return ResponseEntity.ok(Map.of("added", added, "updated", updated, "total", rows.size()));
    }

    /**
     * viewer.html 엑셀 업로드 — 기존 레코드의 편집 가능 필드만 업데이트 (신규 생성 없음)
     * Body: { "rows": [ { "repositoryName":"...", "apiPath":"/foo", "httpMethod":"GET",
     *                     "status":"사용", "statusOverridden":"확정", "blockCriteria":"...",
     *                     "memo":"...", "reviewResult":"...", "reviewOpinion":"..." } ] }
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
            // 차단완료는 편집 불가
            if ("차단완료".equals(r.getStatus())) { skipped++; continue; }

            // 상태 — 변경 시 statusOverridden 자동 true
            String newStatus = str(row, "status");
            if (newStatus != null && !newStatus.isBlank() && !newStatus.equals(r.getStatus())) {
                r.setStatus(newStatus);
                r.setStatusOverridden(true);
            }
            // 상태확정 명시적 지정 (확정/미확정)
            String ovr = str(row, "statusOverridden");
            if ("확정".equals(ovr))   r.setStatusOverridden(true);
            else if ("미확정".equals(ovr)) r.setStatusOverridden(false);

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
            String rv = str(row, "reviewResult");
            if (row.containsKey("reviewResult"))  r.setReviewResult(rv == null || rv.isBlank() ? null : rv);
            if (row.containsKey("reviewOpinion")) r.setReviewOpinion(str(row, "reviewOpinion"));

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

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString().trim() : null;
    }
}
