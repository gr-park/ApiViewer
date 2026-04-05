package com.baek.viewer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;
    private final PageGuardInterceptor pageGuardInterceptor;

    public WebConfig(AdminInterceptor adminInterceptor, PageGuardInterceptor pageGuardInterceptor) {
        this.adminInterceptor = adminInterceptor;
        this.pageGuardInterceptor = pageGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // API 엔드포인트 보호 (X-Admin-Token 헤더)
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns(
                        "/api/extract",           // 추출
                        "/api/db/delete-all",      // 데이터 삭제
                        "/api/db/purge-deleted",   // 삭제건 영구 삭제
                        "/api/db/monitor/**",      // DB 모니터링
                        "/api/config/**",          // 설정 변경
                        "/api/logs/**",            // 로그 조회
                        "/api/schedule/**",        // 스케줄 관리
                        "/api/apm/**",             // APM 데이터 관리
                        "/api/upload/**"           // 엑셀 업로드
                )
                .excludePathPatterns(
                        "/api/apm/chart"           // 차트 조회는 공개 (viewer.html)
                );

        // 관리자 전용 HTML 페이지 보호 (adminToken 쿠키)
        registry.addInterceptor(pageGuardInterceptor)
                .addPathPatterns(
                        "/extract.html",
                        "/settings.html"
                );
    }
}
