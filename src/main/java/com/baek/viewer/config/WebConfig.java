package com.baek.viewer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;

    public WebConfig(AdminInterceptor adminInterceptor) {
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns(
                        "/api/extract",           // 추출
                        "/api/db/delete-all",      // 데이터 삭제
                        "/api/config/**",          // 설정 변경
                        "/api/logs/**",            // 로그 조회
                        "/api/schedule/**",        // 스케줄 관리
                        "/api/apm/**"              // APM 데이터 관리
                )
;
    }
}
