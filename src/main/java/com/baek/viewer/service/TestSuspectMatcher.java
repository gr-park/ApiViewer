package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 테스트용 의심 URL 판정기.
 *
 * URL 경로 / 메소드명 / 컨트롤러명 / 파일경로 / 메소드주석 / 컨트롤러주석 / @ApiOperation / @Description 에서
 * 사전 정의 키워드(test, sample, mock 등)를 대소문자 무시로 검사한다.
 * 영문·숫자만으로 이루어진 2~3글자 키워드는 비(문자·숫자) 경계 매칭 + 메소드/컨트롤러는 camelCase 토큰 정확 일치로
 * cardEvent↔dev 같은 부분문자열 오탐을 줄인다. 그 외 키워드는 부분일치(contains)다.
 *
 * fullUrl 은 의도적으로 제외 — 도메인에 test/stg 가 포함된 환경(api-test.company.com)에서
 * 모든 레코드가 의심 처리되는 false positive 방지.
 *
 * 매칭 결과 형식: "URL-test, 메소드-Sample" (필드별 1건씩, 발견 순서 보존).
 */
@Service
public class TestSuspectMatcher {

    private static final Logger log = LoggerFactory.getLogger(TestSuspectMatcher.class);

    private final GlobalConfigRepository globalConfigRepository;

    public TestSuspectMatcher(GlobalConfigRepository globalConfigRepository) {
        this.globalConfigRepository = globalConfigRepository;
    }

    /** 신규 추출 시 ApiInfo 기반 매칭 — DB 저장 전 단계. null = 비의심. */
    public String matchFromApiInfo(ApiInfo a) {
        if (a == null) return null;
        return doMatch(buildContextFromInfo(a), currentKeywords());
    }

    /** 재평가 시 ApiRecord 기반 매칭 — 이미 저장된 데이터 평가. null = 비의심. */
    public String matchFromRecord(ApiRecord r) {
        if (r == null) return null;
        return doMatch(buildContextFromRecord(r), currentKeywords());
    }

    /** 키워드 1회 로드 후 다건 매칭 — 재평가 endpoint 에서 N+1 회피용 */
    public String matchFromRecord(ApiRecord r, List<String> keywords) {
        if (r == null) return null;
        return doMatch(buildContextFromRecord(r), keywords);
    }

    /** 현재 GlobalConfig 의 키워드 리스트 (콤마 분리, trim, 빈값 제외). */
    public List<String> currentKeywords() {
        String csv = globalConfigRepository.findById(1L)
                .map(GlobalConfig::getTestSuspectKeywords)
                .orElse(null);
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private record Field(String label, String value) {}

    private List<Field> buildContextFromInfo(ApiInfo a) {
        return List.of(
                new Field("URL",          a.getApiPath()),
                new Field("메소드",       a.getMethodName()),
                new Field("컨트롤러",     a.getControllerName()),
                new Field("파일경로",     a.getRepoPath()),
                new Field("메소드주석",   a.getFullComment()),
                new Field("컨트롤러주석", a.getControllerComment()),
                new Field("ApiOperation", a.getApiOperationValue()),
                new Field("Description",  a.getDescriptionTag())
        );
    }

    private List<Field> buildContextFromRecord(ApiRecord r) {
        return List.of(
                new Field("URL",          r.getApiPath()),
                new Field("메소드",       r.getMethodName()),
                new Field("컨트롤러",     r.getControllerName()),
                new Field("파일경로",     r.getControllerFilePath()),
                new Field("메소드주석",   r.getFullComment()),
                new Field("컨트롤러주석", r.getControllerComment()),
                new Field("ApiOperation", r.getApiOperationValue()),
                new Field("Description",  r.getDescriptionTag())
        );
    }

    private String doMatch(List<Field> fields, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return null;
        List<PreparedKeyword> prepared = keywords.stream()
                .map(PreparedKeyword::from)
                .filter(pk -> !pk.lower().isEmpty())
                .toList();
        if (prepared.isEmpty()) return null;
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (Field f : fields) {
            if (f.value() == null || f.value().isBlank()) continue;
            String lower = f.value().toLowerCase(Locale.ROOT);
            for (PreparedKeyword pk : prepared) {
                boolean matched = pk.shortAscii()
                        ? matchesShortKeyword(f.label(), f.value(), lower, pk)
                        : lower.contains(pk.lower());
                if (matched) {
                    hits.add(f.label() + "-" + pk.display());
                    break;
                }
            }
        }
        if (hits.isEmpty()) return null;
        String result = String.join(", ", hits);
        log.debug("[테스트의심] 매칭 — {}", result);
        return result;
    }

    /**
     * 영문·숫자만, 길이 2~3 → 짧은 키워드(경계 + camel 토큰 규칙).
     * 1글자는 너무 광범위하고, 4글자 이상은 contains 로 충분히 안전하다고 본다.
     */
    static boolean isShortAsciiKeyword(String kw) {
        if (kw == null) return false;
        int n = kw.length();
        if (n < 2 || n > 3) return false;
        for (int i = 0; i < n; i++) {
            char c = kw.charAt(i);
            if (c > 0x7f || !(Character.isLetterOrDigit(c))) return false;
        }
        return true;
    }

    private record PreparedKeyword(String display, String lower, boolean shortAscii, Pattern boundary) {
        static PreparedKeyword from(String kw) {
            String lower = kw.toLowerCase(Locale.ROOT);
            boolean shortK = isShortAsciiKeyword(kw);
            Pattern p = shortK
                    ? Pattern.compile(
                    "(^|[^\\p{L}\\p{N}])" + Pattern.quote(lower) + "([^\\p{L}\\p{N}]|$)",
                    Pattern.UNICODE_CHARACTER_CLASS)
                    : null;
            return new PreparedKeyword(kw, lower, shortK, p);
        }
    }

    /** 짧은 ASCII 키워드: 전체 문자열 경계, URL/파일은 세그먼트 경계, 메소드·컨트롤러는 camelCase 토큰 정확 일치. */
    private static boolean matchesShortKeyword(String label, String raw, String lower, PreparedKeyword pk) {
        Pattern boundary = pk.boundary();
        if (boundary.matcher(lower).find()) return true;
        if ("메소드".equals(label) || "컨트롤러".equals(label)) {
            for (String t : camelCaseTokens(raw)) {
                if (pk.lower().equals(t.toLowerCase(Locale.ROOT))) return true;
            }
        }
        if ("URL".equals(label) || "파일경로".equals(label)) {
            for (String seg : pathSegments(raw)) {
                if (seg.isBlank()) continue;
                if (boundary.matcher(seg.toLowerCase(Locale.ROOT)).find()) return true;
            }
        }
        return false;
    }

    static List<String> camelCaseTokens(String s) {
        if (s == null || s.isBlank()) return List.of();
        String spaced = s
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        String[] parts = spaced.split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    static List<String> pathSegments(String path) {
        if (path == null || path.isBlank()) return List.of();
        return Arrays.stream(path.split("[/\\\\]+"))
                .map(String::trim)
                .filter(seg -> !seg.isEmpty())
                .toList();
    }
}
