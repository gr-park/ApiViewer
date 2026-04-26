package com.baek.viewer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 상태값 리네임 일회성 마이그레이션.
 *
 * 변환 이력 (모두 최종 형태로 수렴):
 *  1. 기존 "검토필요 차단대상" / "추가검토필요 차단대상" → 중간 단계 "검토필요대상"
 *  2. 수동 판단 상태 3종 통합 → "차단대상 → 사용" / "검토필요 → 사용"
 *  3. (1)-(N) / (2)-(N) 번호 체계 도입:
 *     - "차단완료" → "(1)-(1) 차단완료"
 *     - "최우선 차단대상" + log_work_excluded=false → "(1)-(2) 호출0건+변경없음"
 *     - "최우선 차단대상" + log_work_excluded=true  → "(1)-(3) 호출0건+변경있음(로그)"
 *     - "후순위 차단대상" → "(1)-(4) 업무종료"
 *     - "검토필요대상" + callCount=0 + recent_log_only=true  → "(2)-(1) 호출0건+로그건"
 *     - "검토필요대상" + callCount=0 + recent_log_only=false → "(2)-(2) 호출0건+변경있음"
 *     - "검토필요대상" + 1<=callCount<=reviewThreshold → "(2)-(3) 호출 1~reviewThreshold건"
 *     - "검토필요대상" + callCount>reviewThreshold → "(2)-(4) 호출 reviewThreshold+1건↑"
 *     - reviewResult='차단대상 제외' AND status='사용' → status='(1)-(5) 현업요청 차단제외'
 *  4. 수동 상태(차단대상 → 사용 / 검토필요 → 사용) — 옵션A: 그대로 유지
 *
 * 특성:
 * - Idempotent: 이미 변환된 상태면 0건 업데이트되고 로그만 남김.
 * - 순서 중요: (3) 분기 변환은 보조 플래그(log_work_excluded, recent_log_only) 와
 *   reviewThreshold 값에 의존. 한 번 (1)-(*) 로 변환된 후엔 매칭 안 됨.
 */
@Component
@Order(1)
public class StatusRenameMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StatusRenameMigrator.class);

    /** 1단계: 단순 리네임 (newStatus, oldStatus) — 신규 변환 추가 시 이 배열에만 추가. */
    private static final String[][] RENAMES = {
            {"검토필요대상",     "검토필요 차단대상"},
            // NOTE: 한글 변환원본을 명시적으로 분리해 보관 (sed 자가 치환 방지)
            {"검토필요대상",     "추가" + "검토필요 차단대상"},
            {"차단대상 → 사용",  "최우선 차단대상 → 사용"},
            {"차단대상 → 사용",  "후순위 차단대상 → 사용"},
            {"차단대상 → 사용",  "현업요청 사용"},
    };

    private final JdbcTemplate jdbc;

    public StatusRenameMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        // 1단계: 단순 리네임
        for (String[] r : RENAMES) {
            String newStatus = r[0];
            String oldStatus = r[1];
            tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                    new Object[]{newStatus, oldStatus},
                    "리네임 '" + oldStatus + "' → '" + newStatus + "'");
        }

        // 2단계: (1)-(N) / (2)-(N) 번호 체계 변환
        // 차단완료 → (1)-(1)
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                new Object[]{"(1)-(1) 차단완료", "차단완료"}, "(1)-(1) 차단완료");
        // 최우선 차단대상 → (1)-(2) 또는 (1)-(3)
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND (log_work_excluded IS NULL OR log_work_excluded = ?)",
                new Object[]{"(1)-(2) 호출0건+변경없음", "최우선 차단대상", false}, "(1)-(2) logWork=false");
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND log_work_excluded = ?",
                new Object[]{"(1)-(3) 호출0건+변경있음(로그)", "최우선 차단대상", true}, "(1)-(3) logWork=true");
        // 후순위 차단대상 → (1)-(4)
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                new Object[]{"(1)-(4) 업무종료", "후순위 차단대상"}, "(1)-(4) 업무종료");
        // 현업요청 차단제외: status='사용' AND reviewResult='차단대상 제외' → '(1)-(5)'
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND review_result = ?",
                new Object[]{"(1)-(5) 현업요청 차단제외", "사용", "차단대상 제외"}, "(1)-(5) 현업요청 차단제외");

        // 검토필요대상 분기 — reviewThreshold 조회 (없으면 3)
        int reviewThreshold = readReviewThreshold();
        // (2)-(1) 호출0+로그건
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND (call_count IS NULL OR call_count = 0) AND recent_log_only = ?",
                new Object[]{"(2)-(1) 호출0건+로그건", "검토필요대상", true}, "(2)-(1) 호출0+로그건");
        // (2)-(2) 호출0+변경있음
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND (call_count IS NULL OR call_count = 0) AND (recent_log_only IS NULL OR recent_log_only = ?)",
                new Object[]{"(2)-(2) 호출0건+변경있음", "검토필요대상", false}, "(2)-(2) 호출0+변경있음");
        // (2)-(3) 호출 1~reviewThreshold
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND call_count BETWEEN 1 AND ?",
                new Object[]{"(2)-(3) 호출 1~reviewThreshold건", "검토필요대상", reviewThreshold}, "(2)-(3) 호출 1~" + reviewThreshold);
        // (2)-(4) 호출 reviewThreshold+1건↑ (남은 검토필요대상)
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                new Object[]{"(2)-(4) 호출 reviewThreshold+1건↑", "검토필요대상"}, "(2)-(4) 호출 " + (reviewThreshold + 1) + "건↑");
    }

    private int readReviewThreshold() {
        try {
            Integer v = jdbc.queryForObject(
                    "SELECT review_threshold FROM global_config WHERE id = 1", Integer.class);
            return v != null ? v : 3;
        } catch (Exception e) {
            return 3;
        }
    }

    private void tryUpdate(String sql, Object[] params, String label) {
        try {
            int updated = jdbc.update(sql, params);
            if (updated > 0) {
                log.warn("[마이그레이션] {} {}건 변환 완료", label, updated);
            } else {
                log.info("[마이그레이션] {} 대상 없음 (이미 변환됨)", label);
            }
        } catch (Exception e) {
            log.warn("[마이그레이션] {} 스킵: {}", label, e.getMessage());
        }
    }
}
