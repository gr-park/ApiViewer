package com.baek.viewer.service;

import com.baek.viewer.auth.AuthRole;
import com.baek.viewer.auth.TokenPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버 측 세션 토큰 — 관리자(ADMIN) / URL현황 편집자(EDITOR).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final Map<String, TokenPayload> tokens = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 8 * 60 * 60 * 1000; // 8시간

    /** 관리자 토큰 발급 (기존 {@link #issueToken()} 과 동일). */
    public String issueToken() {
        return issueAdminToken();
    }

    public String issueAdminToken() {
        cleanup();
        String token = UUID.randomUUID().toString();
        tokens.put(token, new TokenPayload(System.currentTimeMillis(), AuthRole.ADMIN, null));
        log.info("[토큰 발급] role=ADMIN, 활성={}", tokens.size());
        return token;
    }

    public String issueEditorToken(long assigneeId) {
        cleanup();
        String token = UUID.randomUUID().toString();
        tokens.put(token, new TokenPayload(System.currentTimeMillis(), AuthRole.EDITOR, assigneeId));
        log.info("[토큰 발급] role=EDITOR assigneeId={}, 활성={}", assigneeId, tokens.size());
        return token;
    }

    public boolean isValid(String token) {
        return peek(token) != null;
    }

    public boolean isAdmin(String token) {
        TokenPayload p = peek(token);
        return p != null && p.role() == AuthRole.ADMIN;
    }

    public boolean isEditor(String token) {
        TokenPayload p = peek(token);
        return p != null && p.role() == AuthRole.EDITOR;
    }

    public AuthRole getRole(String token) {
        TokenPayload p = peek(token);
        return p == null ? null : p.role();
    }

    /** EDITOR 토큰의 담당자 PK. ADMIN 이면 null. */
    public Long getEditorAssigneeId(String token) {
        TokenPayload p = peek(token);
        return p == null ? null : p.editorAssigneeId();
    }

    private TokenPayload peek(String token) {
        if (token == null || token.isBlank()) return null;
        TokenPayload p = tokens.get(token);
        if (p == null) return null;
        if (System.currentTimeMillis() - p.issuedAtMs() > TOKEN_TTL_MS) {
            tokens.remove(token);
            return null;
        }
        return p;
    }

    public long remainingMs(String token) {
        TokenPayload p = peek(token);
        if (p == null) return 0L;
        long left = TOKEN_TTL_MS - (System.currentTimeMillis() - p.issuedAtMs());
        return Math.max(0L, left);
    }

    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
            log.info("[토큰 폐기] 남은 활성 토큰 수={}", tokens.size());
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        int before = tokens.size();
        tokens.entrySet().removeIf(e -> now - e.getValue().issuedAtMs() > TOKEN_TTL_MS);
        int removed = before - tokens.size();
        if (removed > 0) {
            log.info("[토큰 정리] 만료 토큰 {}건 제거", removed);
        }
    }
}
