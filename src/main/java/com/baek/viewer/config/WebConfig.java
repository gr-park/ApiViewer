package com.baek.viewer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;
    private final PageGuardInterceptor pageGuardInterceptor;

    public WebConfig(AdminInterceptor adminInterceptor, PageGuardInterceptor pageGuardInterceptor) {
        this.adminInterceptor = adminInterceptor;
        this.pageGuardInterceptor = pageGuardInterceptor;
    }

    /** CORS — 도메인/ngrok 등 외부 접근 허용 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")          // 모든 origin 허용
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)              // 쿠키/인증헤더 포함 허용
                .maxAge(3600);                       // preflight 캐시 1시간
    }

    /** 루트 & 구 경로 → 신 경로 리다이렉트 + 디렉토리 welcome page (대시보드 + 3개 대영역 구조) */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 루트: IT소스 관리포털 통합 대시보드
        registry.addRedirectViewController("/",                        "/dashboard/");
        // 구 파일 경로 → 신 경로 (북마크/메일링크 보호)
        registry.addRedirectViewController("/index.html",              "/dashboard/");
        registry.addRedirectViewController("/url-viewer/",             "/url-viewer/viewer.html");
        registry.addRedirectViewController("/url-viewer/index.html",   "/dashboard/");
        registry.addRedirectViewController("/viewer.html",             "/url-viewer/viewer.html");
        registry.addRedirectViewController("/extract.html",            "/url-viewer/extract.html");
        registry.addRedirectViewController("/review.html",             "/url-viewer/review.html");
        registry.addRedirectViewController("/call-stats.html",         "/url-viewer/call-stats.html");
        registry.addRedirectViewController("/url-block-monitor.html",  "/url-viewer/url-block-monitor.html");
        registry.addRedirectViewController("/workflow.html",           "/url-viewer/workflow.html");
        registry.addRedirectViewController("/settings.html",           "/settings/");

        // 디렉토리 welcome page — Spring Boot는 중첩 디렉토리의 index.html을 자동 매핑하지 않으므로 명시
        registry.addViewController("/dashboard/")      .setViewName("forward:/dashboard/index.html");
        registry.addViewController("/encrypt-viewer/") .setViewName("forward:/encrypt-viewer/index.html");
        registry.addViewController("/settings/")       .setViewName("forward:/settings/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // API 엔드포인트 보호 (X-Admin-Token 헤더)
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns(
                        "/api/extract",           // 추출
                        "/api/db/delete-all",      // 데이터 삭제
                        "/api/db/backup/**",       // 데이터 백업
                        "/api/db/restore/**",      // 데이터 복구
                        "/api/db/purge-deleted",   // 삭제건 영구 삭제
                        "/api/db/records",         // 선택 레코드 강제 삭제
                        "/api/db/monitor/**",      // DB 모니터링
                        "/api/db/seed",            // 성능테스트용 시드 데이터
                        "/api/mock/**",            // Mock 분석데이터 생성/삭제
                        "/api/config/**",          // 설정 변경
                        "/api/logs/**",            // 로그 조회
                        "/api/schedule/**",        // 스케줄 관리
                        "/api/apm/**",             // APM 데이터 관리
                        "/api/upload/**",          // 엑셀 업로드
                        "/api/mail/**",            // 메일 발송/서식 관리
                        "/api/jira/**",            // Jira 연동 관리
                        "/api/clone/**",            // Bitbucket 클론 관리
                        "/api/recalculate-test-suspect"  // 테스트 의심 키워드 재평가
                )
                .excludePathPatterns(
                        "/api/apm/chart",          // 차트 조회는 공개 (viewer.html)
                        "/api/auth/**"             // 토큰 유효성 확인 (인증 전 호출)
                );

        // 관리자 전용 HTML 페이지 보호 (adminToken 쿠키)
        registry.addInterceptor(pageGuardInterceptor)
                .addPathPatterns(
                        "/url-viewer/extract.html",
                        "/settings/",
                        "/settings/index.html",
                        "/encrypt-viewer/**"   // 암복호화 모듈 — 일반 사용자에게 노출 금지
                );
    }
}
