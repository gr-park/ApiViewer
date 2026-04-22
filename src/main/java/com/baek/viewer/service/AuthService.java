package com.baek.viewer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버 측 관리자 토큰 관리.
 * 비밀번호 인증 성공 시 토큰 발급, 인터셉터에서 검증.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // token → 발급시각 (ms)
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 8 * 60 * 60 * 1000; // 8시간

    /** 새 토큰 발급 */
    public String issueToken() {
        cleanup();
        String token = UUID.randomUUID().toString();
        tokens.put(token, System.currentTimeMillis());
        log.info("[토큰 발급] 현재 활성 토큰 수={}", tokens.size());
        return token;
    }

    /** 토큰 유효성 검증 */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) return false;
        Long issued = tokens.get(token);
        if (issued == null) {
            log.warn("[토큰 검증 실패] 존재하지 않는 토큰");
            return false;
        }
        if (System.currentTimeMillis() - issued > TOKEN_TTL_MS) {
            tokens.remove(token);
            log.warn("[토큰 검증 실패] 만료된 토큰");
            return false;
        }
        return true;
    }

    /** 토큰 남은 수명(ms). 유효하지 않으면 0. */
    public long remainingMs(String token) {
        if (token == null || token.isBlank()) return 0L;
        Long issued = tokens.get(token);
        if (issued == null) return 0L;
        long left = TOKEN_TTL_MS - (System.currentTimeMillis() - issued);
        return Math.max(0L, left);
    }

    /** 토큰 폐기 (로그아웃) */
    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
            log.info("[토큰 폐기] 남은 활성 토큰 수={}", tokens.size());
        }
    }

    /** 만료된 토큰 정리 */
    private void cleanup() {
        long now = System.currentTimeMillis();
        int before = tokens.size();
        tokens.entrySet().removeIf(e -> now - e.getValue() > TOKEN_TTL_MS);
        int removed = before - tokens.size();
        if (removed > 0) {
            log.info("[토큰 정리] 만료 토큰 {}건 제거", removed);
        }
    }
}
