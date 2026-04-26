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
 *     - "차단완료" → "①-① 차단완료"
 *     - "최우선 차단대상" + log_work_excluded=false → "①-② 호출0건+변경없음"
 *     - "최우선 차단대상" + log_work_excluded=true  → "①-③ 호출0건+변경있음(로그)"
 *     - "후순위 차단대상" → "①-④ 업무종료"
 *     - "검토필요대상" + callCount=0 + recent_log_only=true  → "②-① 호출0건+로그건"
 *     - "검토필요대상" + callCount=0 + recent_log_only=false → "②-② 호출0건+변경있음"
 *     - "검토필요대상" + 1<=callCount<=reviewThreshold → "②-③ 호출 1~reviewThreshold건"
 *     - "검토필요대상" + callCount>reviewThreshold → "②-④ 호출 reviewThreshold+1건↑"
 *     - reviewResult='차단대상 제외' AND status='사용' → status='①-⑤ 현업요청 차단제외'
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
            {"검토필요대상",          "검토필요 차단대상"},
            // NOTE: 한글 변환원본을 명시적으로 분리해 보관 (sed 자가 치환 방지)
            {"검토필요대상",          "추가" + "검토필요 차단대상"},
            {"①-⑥ 사용으로 변경",     "최우선 차단대상 → 사용"},
            {"①-⑥ 사용으로 변경",     "후순위 차단대상 → 사용"},
            {"①-⑥ 사용으로 변경",     "현업요청 사용"},
            {"①-⑥ 사용으로 변경",     "차단대상 → 사용"},
            {"②-⑤ 사용으로 변경",     "검토필요 → 사용"},
    };

    private final JdbcTemplate jdbc;

    public StatusRenameMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        // 0단계: status 컬럼 폭 확장 — 옛 VARCHAR(20) 환경에서 새 leaf 라벨(최대 ~30자) 저장 시 too-long 에러 방지.
        //   H2/PostgreSQL 양쪽 동일 문법. ddl-auto=update 가 자동 ALTER 하지 않으므로 명시적으로 처리.
        tryUpdate("ALTER TABLE api_record ALTER COLUMN status SET DATA TYPE VARCHAR(50)",
                new Object[]{}, "status 컬럼 VARCHAR(50) 확장");

        // 1단계: 단순 리네임
        for (String[] r : RENAMES) {
            String newStatus = r[0];
            String oldStatus = r[1];
            tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                    new Object[]{newStatus, oldStatus},
                    "리네임 '" + oldStatus + "' → '" + newStatus + "'");
        }

        // 2단계: (1)-(N) / (2)-(N) 번호 체계 변환
        // 차단완료 → ①-①
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                new Object[]{"①-① 차단완료", "차단완료"}, "①-① 차단완료");
        // 최우선 차단대상 → ①-② 또는 ①-③
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND (log_work_excluded IS NULL OR log_work_excluded = ?)",
                new Object[]{"①-② 호출0건+변경없음", "최우선 차단대상", false}, "①-② logWork=false");
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND log_work_excluded = ?",
                new Object[]{"①-③ 호출0건+변경있음(로그)", "최우선 차단대상", true}, "①-③ logWork=true");
        // 후순위 차단대상 → ①-④
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                new Object[]{"①-④ 업무종료", "후순위 차단대상"}, "①-④ 업무종료");
        // 현업요청 차단제외: status='사용' AND reviewResult='차단대상 제외' → '①-⑤'
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND review_result = ?",
                new Object[]{"①-⑤ 현업요청 차단제외", "사용", "차단대상 제외"}, "①-⑤ 현업요청 차단제외");

        // 검토필요대상 분기 — reviewThreshold 조회 (없으면 3)
        int reviewThreshold = readReviewThreshold();
        // ②-① 호출0+로그건
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND (call_count IS NULL OR call_count = 0) AND recent_log_only = ?",
                new Object[]{"②-① 호출0건+로그건", "검토필요대상", true}, "②-① 호출0+로그건");
        // ②-② 호출0+변경있음
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND (call_count IS NULL OR call_count = 0) AND (recent_log_only IS NULL OR recent_log_only = ?)",
                new Object[]{"②-② 호출0건+변경있음", "검토필요대상", false}, "②-② 호출0+변경있음");
        // ②-③ 호출 1~reviewThreshold
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ? AND call_count BETWEEN 1 AND ?",
                new Object[]{"②-③ 호출 1~reviewThreshold건", "검토필요대상", reviewThreshold}, "②-③ 호출 1~" + reviewThreshold);
        // ②-④ 호출 reviewThreshold+1건↑ (남은 검토필요대상)
        tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                new Object[]{"②-④ 호출 reviewThreshold+1건↑", "검토필요대상"}, "②-④ 호출 " + (reviewThreshold + 1) + "건↑");

        // 3단계: (1)-(N) / (2)-(N) 중간 표기 → 원문자 표기 (직전 단계에서 변환된 데이터가 잔존하는 경우)
        String[][] CIRCLED = {
                {"①-① 차단완료",                       "(1)-(1) 차단완료"},
                {"①-② 호출0건+변경없음",                 "(1)-(2) 호출0건+변경없음"},
                {"①-③ 호출0건+변경있음(로그)",            "(1)-(3) 호출0건+변경있음(로그)"},
                {"①-④ 업무종료",                       "(1)-(4) 업무종료"},
                {"①-⑤ 현업요청 차단제외",                "(1)-(5) 현업요청 차단제외"},
                {"②-① 호출0건+로그건",                   "(2)-(1) 호출0건+로그건"},
                {"②-② 호출0건+변경있음",                 "(2)-(2) 호출0건+변경있음"},
                {"②-③ 호출 1~reviewThreshold건",         "(2)-(3) 호출 1~reviewThreshold건"},
                {"②-④ 호출 reviewThreshold+1건↑",        "(2)-(4) 호출 reviewThreshold+1건↑"},
        };
        for (String[] r : CIRCLED) {
            tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                    new Object[]{r[0], r[1]},
                    "원문자 '" + r[1] + "' → '" + r[0] + "'");
        }

        // 4단계: 9 leaf v2 통합 — 옛 13 leaf → 신 9 leaf 압축
        //   ①-① 차단완료      → 차단완료 (번호 prefix 제거)
        //   ①-② / ①-③       → ①-① 차단대상 (호출0건 통합)
        //   ①-④ 업무종료      → ①-② 담당자 판단
        //   ①-⑤ 현업요청 차단제외 → ①-③ 현업요청 제외대상
        //   ①-⑥ 사용으로 변경  → ①-④ 사용으로 변경
        //   ②-① 호출0건+로그건 / ②-② 호출0건+변경있음 → ②-① 호출0건+변경있음 (통합)
        //   ②-③ 호출 1~N건    → ②-② 호출 3건 이하+변경없음
        //   ②-④ 호출 N+1건↑   → 사용 (흡수)
        //   ②-⑤ 사용으로 변경  → ②-③ 사용으로 변경
        String[][] V2 = {
                {"차단완료",                          "①-① 차단완료"},
                {"①-① 차단대상",                      "①-② 호출0건+변경없음"},
                {"①-① 차단대상",                      "①-③ 호출0건+변경있음(로그)"},
                {"①-② 담당자 판단",                    "①-④ 업무종료"},
                {"①-③ 현업요청 제외대상",              "①-⑤ 현업요청 차단제외"},
                {"①-④ 사용으로 변경",                  "①-⑥ 사용으로 변경"},
                {"②-① 호출0건+변경있음",                "②-① 호출0건+로그건"},
                // ②-② 호출0건+변경있음 → ②-① 호출0건+변경있음 (라벨 동일하지만 옛 ②-② 형태 잔존 가능)
                {"②-② 호출 3건 이하+변경없음",          "②-③ 호출 1~reviewThreshold건"},
                {"사용",                              "②-④ 호출 reviewThreshold+1건↑"},
                {"②-③ 사용으로 변경",                  "②-⑤ 사용으로 변경"},
        };
        for (String[] r : V2) {
            tryUpdate("UPDATE api_record SET status = ? WHERE status = ?",
                    new Object[]{r[0], r[1]},
                    "v2 '" + r[1] + "' → '" + r[0] + "'");
        }
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
