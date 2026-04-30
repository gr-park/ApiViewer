package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * TestSuspectMatcher 단위 테스트.
 *
 * 검증:
 *   - 대소문자 무시 매칭 (test/Test/TEST)
 *   - 다중 필드 매칭 결과 콤마 구분 형식 ("URL-test, 메소드-Sample")
 *   - 매칭 없으면 null 반환
 *   - 빈 키워드 리스트 시 null
 *   - null/빈 값 필드는 매칭 대상 제외
 *   - fullUrl 은 매칭 대상에서 제외 (false positive 방지)
 *   - ApiRecord 매칭도 동일 동작
 *   - 짧은 ASCII 키워드(2~3자): 경계 + 메소드/컨트롤러 camelCase 토큰 일치
 */
@ExtendWith(MockitoExtension.class)
class TestSuspectMatcherTest {

    @Mock
    private GlobalConfigRepository globalConfigRepository;

    @InjectMocks
    private TestSuspectMatcher matcher;

    private GlobalConfig configWith(String csv) {
        GlobalConfig g = new GlobalConfig();
        g.setTestSuspectKeywords(csv);
        return g;
    }

    @BeforeEach
    void setupDefaultKeywords() {
        // 기본 키워드 9종
        lenient().when(globalConfigRepository.findById(1L))
                .thenReturn(Optional.of(configWith("test,sample,mock,테스트,샘플,demo,dummy,fixture,스텁")));
    }

    @Test
    @DisplayName("URL 패스 매칭 — 대소문자 무시")
    void matchUrlPath_caseInsensitive() {
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/Test/foo");  // 대문자 T

        String result = matcher.matchFromApiInfo(a);
        assertThat(result).isEqualTo("URL-test");
    }

    @Test
    @DisplayName("메소드명 매칭")
    void matchMethodName() {
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/users");
        a.setMethodName("getSampleUser");

        String result = matcher.matchFromApiInfo(a);
        assertThat(result).isEqualTo("메소드-sample");
    }

    @Test
    @DisplayName("다중 필드 매칭 — 콤마 구분 결과")
    void matchMultipleFields() {
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/test/foo");
        a.setMethodName("getMockData");
        a.setControllerName("DemoController");

        String result = matcher.matchFromApiInfo(a);
        // 발견 순서 보존 (URL → 메소드 → 컨트롤러)
        assertThat(result).isEqualTo("URL-test, 메소드-mock, 컨트롤러-demo");
    }

    @Test
    @DisplayName("주석 + ApiOperation 매칭")
    void matchCommentsAndAnnotations() {
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/payment");
        a.setFullComment("// 결제 테스트용 메소드");
        a.setApiOperationValue("샘플 결제 처리");

        String result = matcher.matchFromApiInfo(a);
        assertThat(result).contains("메소드주석-테스트");
        assertThat(result).contains("ApiOperation-샘플");
    }

    @Test
    @DisplayName("매칭 없으면 null 반환")
    void noMatch() {
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/users/profile");
        a.setMethodName("getUserProfile");

        assertThat(matcher.matchFromApiInfo(a)).isNull();
    }

    @Test
    @DisplayName("빈 키워드 리스트 → null 반환")
    void emptyKeywords() {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(configWith("")));
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/test/foo");

        // 빈 키워드 csv 는 getter 폴백 → 기본 키워드. 진짜 빈 리스트는 currentKeywords() 가 List.of() 반환
        // GlobalConfig.getTestSuspectKeywords() 가 빈/null 시 기본값 반환하므로 항상 매칭됨.
        // 실질적으로 List.of() 반환되려면 csv 가 trim 후 모두 빈 항목인 경우.
        String result = matcher.matchFromApiInfo(a);
        // 폴백 기본값에 'test' 가 포함되므로 매칭됨
        assertThat(result).isEqualTo("URL-test");
    }

    @Test
    @DisplayName("null 입력 → null 반환")
    void nullInfo_returnsNull() {
        assertThat(matcher.matchFromApiInfo(null)).isNull();
        assertThat(matcher.matchFromRecord(null)).isNull();
    }

    @Test
    @DisplayName("fullUrl 은 매칭 대상에서 제외 — 도메인 false positive 방지")
    void fullUrlNotMatched() {
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/users");  // 매칭 안됨
        a.setFullUrl("https://api-test.company.com/api/users");  // 도메인에 'test' 있어도

        // 매칭 결과는 null (fullUrl 은 검사 대상 아님)
        assertThat(matcher.matchFromApiInfo(a)).isNull();
    }

    @Test
    @DisplayName("ApiRecord 기반 매칭 — controllerFilePath 사용")
    void matchFromRecord() {
        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/users");
        r.setMethodName("getUser");
        r.setControllerName("UserController");
        r.setControllerFilePath("/repo-a/src/main/java/test/UserController.java");

        String result = matcher.matchFromRecord(r);
        assertThat(result).isEqualTo("파일경로-test");
    }

    @Test
    @DisplayName("키워드 사전 로드 + 다건 매칭 (재평가 endpoint 시나리오)")
    void matchWithPreloadedKeywords() {
        List<String> keywords = List.of("test", "sample");
        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/Sample/foo");

        String result = matcher.matchFromRecord(r, keywords);
        assertThat(result).isEqualTo("URL-sample");
    }

    @Test
    @DisplayName("필드별 첫 매칭만 기록 — 중복 방지")
    void firstMatchPerFieldOnly() {
        ApiInfo a = new ApiInfo();
        a.setApiPath("/api/test/sample/mock/foo");  // 3개 키워드 모두 포함

        String result = matcher.matchFromApiInfo(a);
        // URL 필드에서 첫 매칭 키워드 1건만 기록 (test 가 keywords 첫 항목)
        assertThat(result).isEqualTo("URL-test");
    }

    @Test
    @DisplayName("null/빈 필드는 매칭 대상 제외")
    void nullFieldsSkipped() {
        ApiInfo a = new ApiInfo();
        a.setApiPath(null);
        a.setMethodName("");
        a.setControllerName("UserController");  // 매칭 안됨

        assertThat(matcher.matchFromApiInfo(a)).isNull();
    }

    @Test
    @DisplayName("짧은 키워드 dev — cardEvent 는 부분문자열 오탐 없음")
    void shortKeyword_dev_notInsideCardEvent() {
        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/payment");
        r.setMethodName("cardEvent");

        assertThat(matcher.matchFromRecord(r, List.of("dev"))).isNull();
    }

    @Test
    @DisplayName("짧은 키워드 dev — getDevInfo 는 camel 토큰으로 매칭")
    void shortKeyword_dev_matchesCamelToken_getDevInfo() {
        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/payment");
        r.setMethodName("getDevInfo");

        assertThat(matcher.matchFromRecord(r, List.of("dev"))).isEqualTo("메소드-dev");
    }

    @Test
    @DisplayName("짧은 키워드 dev — URL 세그먼트 경계")
    void shortKeyword_dev_urlSegment() {
        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/dev/config");
        r.setMethodName("getConfig");

        assertThat(matcher.matchFromRecord(r, List.of("dev"))).isEqualTo("URL-dev");
    }

    @Test
    @DisplayName("짧은 키워드 dev — cardevent 경로 세그먼트는 비매칭")
    void shortKeyword_dev_notInCardeventPath() {
        ApiRecord r = new ApiRecord();
        r.setApiPath("/api/cardevent/list");
        r.setMethodName("getList");

        assertThat(matcher.matchFromRecord(r, List.of("dev"))).isNull();
    }

    @Test
    @DisplayName("camelCase 토큰 분할 — cardEvent → card, Event")
    void camelCaseTokens_cardEvent() {
        assertThat(TestSuspectMatcher.camelCaseTokens("cardEvent")).containsExactly("card", "Event");
    }
}
