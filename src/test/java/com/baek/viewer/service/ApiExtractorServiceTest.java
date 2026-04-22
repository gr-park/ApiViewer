package com.baek.viewer.service;

import com.baek.viewer.model.ApiInfo;
import com.baek.viewer.model.ExtractRequest;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * ApiExtractorService 단위테스트.
 * 실제 git 실행 / DB 저장을 피하기 위해:
 *  - repositoryName 을 비워서 DB 저장 분기 skip
 *  - gitBinPath 를 "/bin/false" 로 지정해 git pull 실패해도 catch 에서 흡수
 *  - @TempDir 에 작은 Controller 파일을 만들어 JavaParser 경로를 테스트
 */
@ExtendWith(MockitoExtension.class)
class ApiExtractorServiceTest {

    @Mock
    private ApiStorageService storageService;

    @Mock
    private ApmCollectionService apmCollectionService;

    @Mock
    private RepoConfigRepository repoConfigRepository;

    @Mock
    private GlobalConfigRepository globalConfigRepository;

    private ApiExtractorService service;

    @BeforeEach
    void setUp() {
        service = new ApiExtractorService(storageService, apmCollectionService,
                repoConfigRepository, globalConfigRepository);
        ReflectionTestUtils.setField(service, "defaultGitBinPath", "/bin/false");
    }

    @Test
    @DisplayName("isExtracting — 초기값 false")
    void isExtracting_initiallyFalse() {
        assertThat(service.isExtracting()).isFalse();
    }

    @Test
    @DisplayName("getCached — 초기값 빈 리스트")
    void getCached_initiallyEmpty() {
        assertThat(service.getCached()).isEmpty();
    }

    @Test
    @DisplayName("getProgress — 기본 필드 포함")
    void getProgress_containsBasicFields() {
        Map<String, Object> p = service.getProgress();
        assertThat(p).containsKeys("extracting", "total", "processed", "currentFile", "percent", "logs");
        assertThat(p).containsEntry("extracting", false);
    }

    @Test
    @DisplayName("extract — rootPath 가 존재하지 않으면 RuntimeException 래핑")
    void extract_nonExistentRoot_throws() {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        ExtractRequest req = new ExtractRequest();
        req.setRootPath("/does/not/exist/xyz");

        assertThatThrownBy(() -> service.extract(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("추출 실패");
        // 상태 복원 확인
        assertThat(service.isExtracting()).isFalse();
    }

    @Test
    @DisplayName("extract — Controller 파일이 없는 빈 디렉토리도 정상 완료")
    void extract_emptyDir_returnsEmpty(@TempDir Path tmp) {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString());
        req.setDomain("http://example.com");
        // repositoryName 미지정 → DB 저장 skip

        List<ApiInfo> result = service.extract(req);

        assertThat(result).isEmpty();
        assertThat(service.isExtracting()).isFalse();
    }

    @Test
    @DisplayName("extract — 간단한 Controller 파일에서 @GetMapping 추출")
    void extract_simpleController(@TempDir Path tmp) throws Exception {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        Path javaFile = tmp.resolve("HelloController.java");
        Files.writeString(javaFile, """
                package test;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api")
                public class HelloController {
                    @GetMapping("/hello")
                    public String hello() { return "hi"; }
                }
                """);

        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString());
        req.setDomain("http://example.com");

        List<ApiInfo> result = service.extract(req);

        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(a -> "/api/hello".equals(a.getApiPath()) && "GET".equals(a.getHttpMethod()));
    }

    @Test
    @DisplayName("extract — 메서드 경로가 빈 문자열이면 trailing slash 없이 저장")
    void extract_emptyMethodPath_noTrailingSlash(@TempDir Path tmp) throws Exception {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        Path javaFile = tmp.resolve("BenefitsController.java");
        Files.writeString(javaFile, """
                package test;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/family-card/benefits")
                public class BenefitsController {
                    @GetMapping("")
                    public String list() { return ""; }
                }
                """);

        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString());
        req.setDomain("http://example.com");

        List<ApiInfo> result = service.extract(req);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getApiPath()).isEqualTo("/api/family-card/benefits");
        assertThat(result.get(0).getApiPath()).doesNotEndWith("/");
    }

    @Test
    @DisplayName("extract — 클래스/메서드 경로에 trailing slash 가 있어도 정규화")
    void extract_trailingSlashInDeclaration_normalized(@TempDir Path tmp) throws Exception {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        Path javaFile = tmp.resolve("FooController.java");
        Files.writeString(javaFile, """
                package test;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/foo/")
                public class FooController {
                    @GetMapping("/bar/")
                    public String bar() { return ""; }
                }
                """);

        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString());
        req.setDomain("http://example.com");

        List<ApiInfo> result = service.extract(req);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getApiPath()).isEqualTo("/api/foo/bar");
    }

    @Test
    @DisplayName("extract — 추출 중에 다시 호출하면 IllegalStateException — 호출 후엔 다시 가능")
    void extract_subsequentCallOk(@TempDir Path tmp) {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString());

        // 1차 호출: 완료되면 extracting=false
        service.extract(req);
        assertThat(service.isExtracting()).isFalse();

        // 2차 호출: 예외 없음
        service.extract(req);
        assertThat(service.isExtracting()).isFalse();
    }

    @Test
    @DisplayName("extract — 원본 javadoc 과 별도로 /** @deprecated [URL차단작업] */ 블록이 @Deprecated 앞에 있어도 태그 인식")
    void extract_urlBlockTagInSeparateJavadocBlock(@TempDir Path tmp) throws Exception {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        // 개발자 관행: 기존 메서드 javadoc 은 그대로 두고, @Deprecated 바로 위에 별도 /** @deprecated [URL차단작업]... */ 블록을 덧붙임.
        Path javaFile = tmp.resolve("ZZSCMAController.java");
        Files.writeString(javaFile, """
                package test;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class ZZSCMAController {
                    /**
                     * 로그인한 사용자의 비밀번호를 변경한다.
                     * @date 2020.03.01
                     * @author InswaveSystems
                     * @example
                     */
                    /** @deprecated [URL차단작업][2026-04-09][OP-13863]*/
                    @Deprecated
                    @RequestMapping("/commons/changePassword")
                    public String changePassword() {
                        if (true) throw new UnsupportedOperationException("차단된 URL 입니다.");
                        return "";
                    }
                }
                """);

        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString());
        req.setDomain("http://example.com");

        List<ApiInfo> result = service.extract(req);

        assertThat(result).hasSize(1);
        ApiInfo info = result.get(0);
        assertThat(info.getApiPath()).isEqualTo("/commons/changePassword");
        assertThat(info.getIsDeprecated()).isEqualTo("Y");
        assertThat(info.getHasUrlBlock()).isEqualTo("Y");
        assertThat(info.getFullComment()).contains("[URL차단작업]");
        // 완전 표기 — 미흡 플래그 false
        assertThat(info.isBlockMarkingIncomplete()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // blockMarkingIncomplete 플래그 — "실질 차단이지만 주석/어노테이션 누락" 회귀 테스트
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("표기미흡 — @Deprecated 누락: 첫문장 throw + [URL차단작업] 주석 있으나 @Deprecated 없음 → true")
    void markingIncomplete_whenDeprecatedMissing(@TempDir Path tmp) throws Exception {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        Path f = tmp.resolve("FooController.java");
        Files.writeString(f, """
                package test;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class FooController {
                    /** @deprecated [URL차단작업][2026-04-09][OP-1] */
                    @RequestMapping("/foo/a")
                    public String a() {
                        if (true) throw new UnsupportedOperationException("차단");
                        return "";
                    }
                }
                """);
        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString()); req.setDomain("");
        List<ApiInfo> result = service.extract(req);
        assertThat(result).hasSize(1);
        ApiInfo info = result.get(0);
        assertThat(info.getHasUrlBlock()).isEqualTo("Y");
        assertThat(info.getIsDeprecated()).isEqualTo("N");
        assertThat(info.isBlockMarkingIncomplete()).isTrue();
    }

    @Test
    @DisplayName("표기미흡 — [URL차단작업] 주석 누락: 첫문장 throw + @Deprecated 있으나 주석 태그 없음 → true")
    void markingIncomplete_whenCommentTagMissing(@TempDir Path tmp) throws Exception {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        Path f = tmp.resolve("FooController.java");
        Files.writeString(f, """
                package test;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class FooController {
                    /** 미사용 API */
                    @Deprecated
                    @RequestMapping("/foo/b")
                    public String b() {
                        throw new UnsupportedOperationException("차단");
                    }
                }
                """);
        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString()); req.setDomain("");
        List<ApiInfo> result = service.extract(req);
        assertThat(result).hasSize(1);
        ApiInfo info = result.get(0);
        assertThat(info.getHasUrlBlock()).isEqualTo("Y");
        assertThat(info.getIsDeprecated()).isEqualTo("Y");
        assertThat(info.getFullComment()).doesNotContain("[URL차단작업]");
        assertThat(info.isBlockMarkingIncomplete()).isTrue();
    }

    @Test
    @DisplayName("표기미흡 — 첫 문장이 throw 가 아니면 false: 선행 로직 뒤 throw 는 실질 차단으로 보지 않음")
    void markingIncomplete_whenThrowNotFirstStatement(@TempDir Path tmp) throws Exception {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        Path f = tmp.resolve("FooController.java");
        Files.writeString(f, """
                package test;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class FooController {
                    @RequestMapping("/foo/c")
                    public String c() {
                        int x = 1;
                        if (x > 0) throw new UnsupportedOperationException("조건부");
                        return "";
                    }
                }
                """);
        ExtractRequest req = new ExtractRequest();
        req.setRootPath(tmp.toString()); req.setDomain("");
        List<ApiInfo> result = service.extract(req);
        assertThat(result).hasSize(1);
        ApiInfo info = result.get(0);
        // 첫 문장은 int x=1; 이므로 UnsupportedOperationException 이 첫 실행 문장이 아님 → markingIncomplete=false
        assertThat(info.isBlockMarkingIncomplete()).isFalse();
    }
}
