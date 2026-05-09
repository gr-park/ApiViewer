package com.baek.viewer.config;

import com.baek.viewer.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자 전용 HTML 페이지 차단 인터셉터.
 * adminToken 쿠키가 없거나 유효하지 않으면 / (인덱스) 로 리다이렉트.
 */
@Component
public class PageGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PageGuardInterceptor.class);
    private final AuthService authService;

    public PageGuardInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // settings 내장 모드 (embedded=1) — settings에서 이미 인증되었으므로 허용
        if ("1".equals(request.getParameter("embedded"))) return true;

        String token = null;
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("adminToken".equals(c.getName())) { token = c.getValue(); break; }
            }
        }
        if (authService.isAdmin(token)) return true;

        log.warn("[페이지 차단] {} (IP={}) — 관리자 쿠키 없음/만료", request.getRequestURI(), request.getRemoteAddr());
        // 사용자를 인덱스로 돌려보냄 (캐시 방지)
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect("/?authRequired=1");
        return false;
    }
}
