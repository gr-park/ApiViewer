package com.baek.viewer.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code git_history} JSON 배열에서 커밋 일자 문자열을 파싱해, 화면「최근변경」과 동일하게
 * <b>가장 최근(최대) LocalDate</b>를 구한다.
 */
public final class GitHistoryUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GitHistoryUtil() {}

    public static LocalDate maxCommitDate(String gitHistoryJson) {
        if (gitHistoryJson == null || gitHistoryJson.isBlank() || "[]".equals(gitHistoryJson.trim())) {
            return null;
        }
        try {
            List<Map<String, Object>> arr = MAPPER.readValue(gitHistoryJson, new TypeReference<>() {});
            LocalDate max = null;
            for (Map<String, Object> c : arr) {
                if (c == null) {
                    continue;
                }
                Object d = c.get("date");
                if (d == null) {
                    continue;
                }
                String ds = d.toString().trim();
                if (ds.length() >= 10) {
                    ds = ds.substring(0, 10);
                }
                try {
                    LocalDate ld = LocalDate.parse(ds);
                    if (max == null || ld.isAfter(max)) {
                        max = ld;
                    }
                } catch (Exception ignored) {
                    // skip malformed date
                }
            }
            return max;
        } catch (Exception e) {
            return null;
        }
    }
}
