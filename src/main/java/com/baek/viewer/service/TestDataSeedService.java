package com.baek.viewer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 성능 테스트용 대량 더미 데이터 생성 서비스.
 *
 * JDBC batchUpdate로 api_record / apm_call_data 에 직접 INSERT.
 * 생성된 데이터는 repository_name 이 "test-repo-" 로 시작하므로, 정리 시에도
 * 해당 prefix 기준으로 안전하게 bulk DELETE 가능.
 *
 * 메서드는 @Transactional 을 걸지 않는다 — 수백만 건을 단일 트랜잭션으로 처리하면
 * H2 undo log 가 비대해져 OOM 위험이 커진다. JdbcTemplate 호출마다 커넥션 단위
 * 자동 커밋으로 청크 단위 분할 커밋되도록 설계.
 */
@Service
public class TestDataSeedService {

    private static final Logger log = LoggerFactory.getLogger(TestDataSeedService.class);

    /** 테스트 데이터 식별 prefix — 정리(clean) 기준. */
    public static final String TEST_REPO_PREFIX = "test-repo-";

    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};

    /** api_record INSERT batch 크기 */
    private static final int API_BATCH = 2000;
    /** apm_call_data INSERT batch 크기 */
    private static final int APM_BATCH = 5000;
    /** 레포당 API 분포 (20K 요청 시 10개 레포 × 2,000 API) */
    private static final int APIS_PER_REPO = 2000;

    private final JdbcTemplate jdbc;

    public TestDataSeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 성능 테스트용 대량 데이터 생성.
     *
     * @param apiCount   생성할 api_record 개수 (1~200,000)
     * @param days       생성할 apm 일수 (1~1000). apm 총 건수 = apiCount × days
     * @param cleanFirst true면 기존 test-repo-* 데이터 삭제 후 재생성
     * @return 생성 통계 (Map)
     */
    public Map<String, Object> seed(int apiCount, int days, boolean cleanFirst) {
        long t0 = System.currentTimeMillis();

        if (cleanFirst) {
            int dApm = jdbc.update("DELETE FROM apm_call_data WHERE repository_name LIKE ?", TEST_REPO_PREFIX + "%");
            int dApi = jdbc.update("DELETE FROM api_record WHERE repository_name LIKE ?", TEST_REPO_PREFIX + "%");
            log.info("[시드 초기화] api_record -{}, apm_call_data -{}", dApi, dApm);
        }

        int reposCount = Math.max(1, (int) Math.ceil((double) apiCount / APIS_PER_REPO));
        List<ApiRow> rows = buildApiRows(apiCount);
        insertApiRecords(rows);
        long t1 = System.currentTimeMillis();

        long apmTotal = insertApmCallData(rows, days);
        long t2 = System.currentTimeMillis();

        int updated = updateCallCountAggregates();
        long t3 = System.currentTimeMillis();

        log.info("[시드 완료] apis={}, apm={}, callCountUpdated={} / api={}ms apm={}ms agg={}ms 전체={}ms",
                apiCount, apmTotal, updated, (t1 - t0), (t2 - t1), (t3 - t2), (t3 - t0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiRecordInserted", apiCount);
        result.put("apmCallDataInserted", apmTotal);
        result.put("callCountUpdated", updated);
        result.put("reposCount", reposCount);
        result.put("days", days);
        result.put("apiInsertMs", t1 - t0);
        result.put("apmInsertMs", t2 - t1);
        result.put("aggregateMs", t3 - t2);
        result.put("totalMs", t3 - t0);
        return result;
    }

    /** 테스트 데이터 전체 제거 (test-repo-* 기준). */
    public Map<String, Object> cleanTestData() {
        int apm = jdbc.update("DELETE FROM apm_call_data WHERE repository_name LIKE ?", TEST_REPO_PREFIX + "%");
        int api = jdbc.update("DELETE FROM api_record WHERE repository_name LIKE ?", TEST_REPO_PREFIX + "%");
        log.warn("[시드 삭제] api_record -{}, apm_call_data -{}", api, apm);
        return Map.of("apiRecordDeleted", api, "apmCallDataDeleted", apm);
    }

    // ─────────────────────────────────────────────────────────────

    /** 배포일자 분포용 — 차단대상이 분산될 공통 배포 후보일 (오늘 기준 미래) */
    private static final int[] DEPLOY_DATE_OFFSETS = {14, 21, 30, 45, 60, 90, 120};
    /** 배포담당자 후보 — 일부 레코드에만 지정해 폴백(담당자) 동작도 검증되도록 함 */
    private static final String[] DEPLOY_MANAGERS = {"김배포", "이릴리스", "박운영", "최CSR"};

    List<ApiRow> buildApiRows(int apiCount) {
        List<ApiRow> rows = new ArrayList<>(apiCount);
        LocalDate today = LocalDate.now();
        for (int i = 0; i < apiCount; i++) {
            int repoIdx = (i / APIS_PER_REPO) + 1;
            int moduleIdx = (i / 50) % 40 + 1;
            int opIdx = (i % 50) + 1;
            String repo = String.format("%s%02d", TEST_REPO_PREFIX, repoIdx);
            String apiPath = String.format("/api/test/mod%02d/op%03d/%06d", moduleIdx, opIdx, i);
            String method = HTTP_METHODS[i % HTTP_METHODS.length];

            // 상태 분포: 사용 60 / 검토필요대상 15 / 최우선 10 / 후순위 8 / 차단완료 7
            String status;
            boolean overridden = false;
            String blockTarget = null;
            String blockCriteria = null;
            String hasUrlBlock = "N";
            String isDeprecated = "N";
            int r = i % 100;
            // 분포 v2 (9 leaf): 사용 60 / ②-① 8 / ②-② 5 / ②-③ 2 / ①-① 8 / ①-② 4 / ①-③ 2 / ①-④ 4 / 차단완료 7
            if (r < 60) {
                status = "사용";
            } else if (r < 68) {
                status = "②-① 호출0건+변경있음";
            } else if (r < 73) {
                status = "②-② 호출 3건 이하+변경없음";
            } else if (r < 75) {
                status = "②-③ 사용으로 변경";
                overridden = true;
            } else if (r < 83) {
                status = "①-① 차단대상";
            } else if (r < 87) {
                status = "①-② 담당자 판단";
                blockTarget = "①-② 담당자 판단";
                blockCriteria = "(테스트) 담당자 결정";
                overridden = true;
            } else if (r < 89) {
                status = "①-③ 현업요청 제외대상";
            } else if (r < 93) {
                status = "①-④ 사용으로 변경";
                overridden = true;
            } else {
                status = "차단완료";
                hasUrlBlock = "Y";
                isDeprecated = "Y";
            }

            // 배포일자: ①-① 차단완료는 과거 / 차단대상·추가검토대상 leaf는 70%만 미래 일자
            LocalDate deployDate = null;
            if ("차단완료".equals(status)) {
                deployDate = today.minusDays(7 + (i % 180));
            } else if (status.startsWith("①-") || status.startsWith("②-")) {
                if (i % 10 < 7) {
                    deployDate = today.plusDays(DEPLOY_DATE_OFFSETS[i % DEPLOY_DATE_OFFSETS.length]);
                }
            }

            // 배포담당자: 사용 외에서 60% 만 명시
            String deployManager = null;
            if (!"사용".equals(status) && i % 10 < 6) {
                deployManager = DEPLOY_MANAGERS[i % DEPLOY_MANAGERS.length];
            }

            // recentLogOnly 는 v2 에서 분기 미사용 — 하위호환 위해 컬럼만 false 시드
            boolean recentLogOnly = false;

            rows.add(new ApiRow(repo, apiPath, method, status, overridden,
                    blockTarget, blockCriteria, hasUrlBlock, isDeprecated, moduleIdx, i,
                    deployDate, deployManager, recentLogOnly));
        }
        return rows;
    }

    private void insertApiRecords(List<ApiRow> rows) {
        final String sql = "INSERT INTO api_record (" +
                "repository_name, api_path, http_method, status, status_overridden, " +
                "block_target, block_criteria, has_url_block, is_deprecated, " +
                "call_count, call_count_month, call_count_week, " +
                "method_name, controller_name, program_id, " +
                "api_operation_value, description_tag, full_url, controller_file_path, " +
                "last_analyzed_at, created_ip, modified_at, modified_ip, " +
                "data_source, is_new, status_changed, git_history, repo_path, full_comment, " +
                "deploy_scheduled_date, deploy_manager, recent_log_only" +
                ") VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, ?,?,?, ?,?,?,?, ?,?,?,?, ?,?,?,?,?,?, ?,?,?)";

        LocalDateTime now = LocalDateTime.now();
        Timestamp nowTs = Timestamp.valueOf(now);

        for (int start = 0; start < rows.size(); start += API_BATCH) {
            final int s = start;
            final int e = Math.min(start + API_BATCH, rows.size());
            final List<ApiRow> chunk = rows.subList(s, e);
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ApiRow row = chunk.get(i);
                    int p = 1;
                    ps.setString(p++, row.repo);
                    ps.setString(p++, row.apiPath);
                    ps.setString(p++, row.method);
                    ps.setString(p++, row.status);
                    ps.setBoolean(p++, row.overridden);
                    ps.setString(p++, row.blockTarget);
                    ps.setString(p++, row.blockCriteria);
                    ps.setString(p++, row.hasUrlBlock);
                    ps.setString(p++, row.isDeprecated);
                    // call_count 3종 — 시드 직후 집계 UPDATE로 갱신되므로 0으로 시작
                    ps.setLong(p++, 0L);
                    ps.setLong(p++, 0L);
                    ps.setLong(p++, 0L);
                    ps.setString(p++, "method" + row.uniq);
                    ps.setString(p++, "Mod" + row.module + "Controller");
                    ps.setString(p++, "TEST" + String.format("%05d", row.uniq));
                    ps.setString(p++, "테스트 API #" + row.uniq);
                    ps.setString(p++, "성능테스트 자동 생성");
                    ps.setString(p++, "http://test.local" + row.apiPath);
                    ps.setString(p++, "/" + row.repo + "/src/main/java/com/test/Mod" + row.module + "Controller.java");
                    ps.setTimestamp(p++, nowTs);
                    ps.setString(p++, "127.0.0.1");
                    ps.setTimestamp(p++, nowTs);
                    ps.setString(p++, "127.0.0.1");
                    ps.setString(p++, "ANALYSIS");
                    ps.setBoolean(p++, false);
                    ps.setBoolean(p++, false);
                    ps.setString(p++, "[]");
                    ps.setString(p++, "src/main/java/com/test/Mod" + row.module + "Controller.java");
                    ps.setString(p++, "차단완료".equals(row.status) ? "[URL차단작업][2026-01-15][CSR-99999] 테스트 차단" : null);
                    if (row.deployDate != null) {
                        ps.setDate(p++, java.sql.Date.valueOf(row.deployDate));
                    } else {
                        ps.setNull(p++, java.sql.Types.DATE);
                    }
                    ps.setString(p++, row.deployManager);
                    ps.setBoolean(p++, row.recentLogOnly);
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
            log.info("[시드 api_record] {}/{}", e, rows.size());
        }
    }

    private long insertApmCallData(List<ApiRow> rows, int days) {
        final String sql = "INSERT INTO apm_call_data (" +
                "repository_name, api_path, call_date, call_count, error_count, source, class_name" +
                ") VALUES (?,?,?,?,?,?,?)";

        LocalDate today = LocalDate.now();
        Random rnd = new Random(20260410L);
        List<Object[]> buffer = new ArrayList<>(APM_BATCH);
        long total = 0;

        for (int ri = 0; ri < rows.size(); ri++) {
            ApiRow row = rows.get(ri);
            // 상태별 호출량 분포 v2 — 차단완료/차단대상/예외건 → 0건 의미, 검토대상 → 1~3건, 사용 → 다수
            int baseMin = 0;
            int baseMax;
            String s = row.status != null ? row.status : "";
            if ("차단완료".equals(s) || s.startsWith("①-")) {
                baseMax = 1;  // 호출 0건 의미
            } else if (s.startsWith("②-")) {
                baseMax = 4;  // 검토대상 (1~3건)
            } else {
                baseMin = 10;
                baseMax = 1000; // 사용
            }
            int range = Math.max(1, baseMax - baseMin);
            String className = "com.test.Mod" + row.module + "Controller";

            for (int d = 0; d < days; d++) {
                LocalDate date = today.minusDays(d);
                long callCount = baseMin + (long) (rnd.nextDouble() * range);
                long errorCount = (long) (callCount * 0.02 * rnd.nextDouble());
                buffer.add(new Object[]{
                        row.repo, row.apiPath,
                        java.sql.Date.valueOf(date),
                        callCount, errorCount,
                        "MOCK", className
                });
                total++;
                if (buffer.size() >= APM_BATCH) {
                    jdbc.batchUpdate(sql, buffer);
                    buffer.clear();
                }
            }
            if ((ri + 1) % 1000 == 0) {
                log.info("[시드 apm_call_data] api {}/{} (누적 {}건)", ri + 1, rows.size(), total);
            }
        }
        if (!buffer.isEmpty()) {
            jdbc.batchUpdate(sql, buffer);
            buffer.clear();
        }
        return total;
    }

    /** api_record.call_count / month / week 를 apm_call_data 합계로 갱신 (test-repo-* 한정). */
    private int updateCallCountAggregates() {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);
        LocalDate monthAgo = today.minusDays(30);
        String sql = "UPDATE api_record ar SET " +
                " call_count = COALESCE((SELECT SUM(a.call_count) FROM apm_call_data a " +
                "   WHERE a.repository_name = ar.repository_name AND a.api_path = ar.api_path), 0), " +
                " call_count_month = COALESCE((SELECT SUM(a.call_count) FROM apm_call_data a " +
                "   WHERE a.repository_name = ar.repository_name AND a.api_path = ar.api_path AND a.call_date >= ?), 0), " +
                " call_count_week = COALESCE((SELECT SUM(a.call_count) FROM apm_call_data a " +
                "   WHERE a.repository_name = ar.repository_name AND a.api_path = ar.api_path AND a.call_date >= ?), 0) " +
                "WHERE ar.repository_name LIKE ?";
        return jdbc.update(sql,
                java.sql.Date.valueOf(monthAgo),
                java.sql.Date.valueOf(weekAgo),
                TEST_REPO_PREFIX + "%");
    }

    // ─────────────────────────────────────────────────────────────

    /** 내부 전용 — INSERT 파라미터 번들. package-private for unit test */
    static final class ApiRow {
        final String repo;
        final String apiPath;
        final String method;
        final String status;
        final boolean overridden;
        final String blockTarget;
        final String blockCriteria;
        final String hasUrlBlock;
        final String isDeprecated;
        final int module;
        final int uniq;
        final LocalDate deployDate;
        final String deployManager;
        final boolean recentLogOnly;

        ApiRow(String repo, String apiPath, String method, String status, boolean overridden,
               String blockTarget, String blockCriteria, String hasUrlBlock, String isDeprecated,
               int module, int uniq,
               LocalDate deployDate, String deployManager, boolean recentLogOnly) {
            this.repo = repo;
            this.apiPath = apiPath;
            this.method = method;
            this.status = status;
            this.overridden = overridden;
            this.blockTarget = blockTarget;
            this.blockCriteria = blockCriteria;
            this.hasUrlBlock = hasUrlBlock;
            this.isDeprecated = isDeprecated;
            this.module = module;
            this.uniq = uniq;
            this.deployDate = deployDate;
            this.deployManager = deployManager;
            this.recentLogOnly = recentLogOnly;
        }
    }
}
