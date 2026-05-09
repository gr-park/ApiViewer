package com.baek.viewer.config;

import com.baek.viewer.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminInterceptor.class);

    private final AuthService authService;

    public AdminInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String uri = request.getRequestURI();
        if ("GET".equalsIgnoreCase(request.getMethod()) &&
                (uri.equals("/api/config/global") || uri.equals("/api/config/repos")
                        || uri.equals("/api/config/repos/sync-warnings")
                        || uri.equals("/api/config/ops-digest-summary")
                        || uri.equals("/api/config/apm-match-summary")
                        || uri.equals("/api/config/extract-issue-summary")
                        || uri.equals("/api/apm/data") || uri.equals("/api/jira/config"))) {
            return true;
        }

        String adminToken = request.getHeader("X-Admin-Token");
        String editorToken = request.getHeader("X-Editor-Token");

        // 제안 승인/반려 — 관리자만
        if (uri.startsWith("/api/proposals/approve") || uri.startsWith("/api/proposals/reject")) {
            if (authService.isAdmin(adminToken)) return true;
            log.warn("[인증 차단] 관리자 전용 제안 처리 401 {} {} (IP={})", request.getMethod(), uri, request.getRemoteAddr());
            return write401(response);
        }

        // 제안 저장/조회/철회 — 관리자 또는 편집자
        if (uri.startsWith("/api/proposals")) {
            if (authService.isAdmin(adminToken) || authService.isEditor(editorToken)) return true;
            log.warn("[인증 차단] 401 {} {} (IP={})", request.getMethod(), uri, request.getRemoteAddr());
            return write401(response);
        }

        // 그 외 등록된 보호 경로 — 관리자만 (기존 동작)
        if (authService.isAdmin(adminToken)) return true;

        log.warn("[인증 차단] 401 {} {} (IP={})", request.getMethod(), uri, request.getRemoteAddr());
        return write401(response);
    }

    private static boolean write401(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"관리자 인증이 필요합니다.\"}");
        return false;
    }
}
