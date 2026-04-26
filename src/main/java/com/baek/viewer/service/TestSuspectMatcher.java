package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 테스트용 의심 URL 판정기.
 *
 * URL 경로 / 메소드명 / 컨트롤러명 / 파일경로 / 메소드주석 / 컨트롤러주석 / @ApiOperation / @Description 에서
 * 사전 정의 키워드(test, sample, mock 등)를 대소문자 무시 부분일치로 검사한다.
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
        // 키워드를 사전 lowercase 처리 (성능)
        List<String[]> kwPairs = keywords.stream()
                .map(kw -> new String[]{kw, kw.toLowerCase(Locale.ROOT)})
                .toList();
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (Field f : fields) {
            if (f.value() == null || f.value().isBlank()) continue;
            String lower = f.value().toLowerCase(Locale.ROOT);
            for (String[] kp : kwPairs) {
                if (kp[1].isEmpty()) continue;
                if (lower.contains(kp[1])) {
                    hits.add(f.label() + "-" + kp[0]);  // 필드별 첫 매칭만 기록
                    break;
                }
            }
        }
        if (hits.isEmpty()) return null;
        String result = String.join(", ", hits);
        log.debug("[테스트의심] 매칭 — {}", result);
        return result;
    }
}
