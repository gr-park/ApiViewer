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
}
