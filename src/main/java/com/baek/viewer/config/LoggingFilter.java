package com.baek.viewer.config;

import com.baek.viewer.service.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 모든 요청에 대해 MDC에 clientIp, role을 설정.
 * 로그 패턴에서 %X{clientIp}, %X{role}로 출력.
 */
@Component
@Order(1)
public class LoggingFilter implements Filter {

    private final AuthService authService;

    public LoggingFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (request instanceof HttpServletRequest httpReq) {
                String ip = httpReq.getHeader("X-Forwarded-For");
                if (ip == null || ip.isBlank()) ip = httpReq.getRemoteAddr();
                ip = ip.split(",")[0].trim();
                if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";
                MDC.put("clientIp", ip);

                String token = httpReq.getHeader("X-Admin-Token");
                MDC.put("role", authService.isValid(token) ? "ADMIN" : "USER");
            }
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
