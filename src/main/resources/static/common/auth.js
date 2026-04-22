/* ═══════════════════════════════════════════════════════════════
 * URL Viewer — 공통 인증 (중앙 상태 + 이벤트 전파)
 *
 * AuthState: 서버 토큰의 실제 유효성을 주기적으로 검증, 변화 시
 *   window 에 `auth:change` CustomEvent({loggedIn, remainingMs}) 전파.
 * 기존 sessionStorage 기반 isAdmin()/getAdminToken() API 는 호환을 위해 유지하되
 *   내부적으로는 AuthState 가 단일 소스다.
 * ═══════════════════════════════════════════════════════════════ */
(function () {
  const TOKEN_KEY = 'adminToken';
  const FLAG_KEY  = 'isAdmin';
  const CHECK_INTERVAL_MS = 60_000;
  const DEFAULT_TTL_MS = 8 * 60 * 60 * 1000; // 8h — 서버 remainingMs 미제공 시 fallback

  function getToken() { return sessionStorage.getItem(TOKEN_KEY) || ''; }
  function setToken(t) {
    if (t) { sessionStorage.setItem(TOKEN_KEY, t); sessionStorage.setItem(FLAG_KEY, 'true'); }
    else   { sessionStorage.removeItem(TOKEN_KEY); sessionStorage.removeItem(FLAG_KEY); }
  }

  // 초기값: sessionStorage 에 토큰이 있으면 낙관적으로 loggedIn=true 로 시작.
  // (서버 /api/auth/check 응답 전까지 기존 페이지들의 applyAdminUI() 가 관리자 UI 를 숨기는 깜빡임 방지.
  //  이후 check() 가 실제 유효성에 따라 정정한다.)
  const _hasToken = !!(sessionStorage.getItem(TOKEN_KEY));
  const AuthState = {
    loggedIn: _hasToken,
    remainingMs: _hasToken ? DEFAULT_TTL_MS : 0,
    _lastCheck: 0,

    /** 서버에 토큰 유효성 질의 + 남은 수명 갱신 */
    async check() {
      const token = getToken();
      if (!token) { this._apply(false, 0); return false; }
      try {
        const res = await fetch('/api/auth/check', { headers: { 'X-Admin-Token': token } });
        const data = await res.json();
        this._apply(!!data.valid, data.remainingMs || 0);
        return this.loggedIn;
      } catch (e) {
        // 네트워크 오류 시 기존 상태 유지 (서버 다운으로 인한 의도치 않은 로그아웃 방지)
        return this.loggedIn;
      }
    },

    /** 로그인 성공 처리 — login 시 서버 응답 데이터로 직접 세팅 */
    loginSuccess(token, remainingMs) {
      setToken(token);
      this._apply(true, remainingMs || DEFAULT_TTL_MS);
    },

    /** 로그아웃 — 서버 폐기 + 로컬 제거 + 이벤트 */
    async logout() {
      const token = getToken();
      try {
        if (token) {
          await fetch('/api/auth/logout', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token }
          });
        }
      } catch (e) { /* 무시 — 로컬은 반드시 정리 */ }
      setToken('');
      this._apply(false, 0);
    },

    _apply(loggedIn, remainingMs) {
      const changed = (this.loggedIn !== loggedIn);
      this.loggedIn = loggedIn;
      this.remainingMs = remainingMs;
      this._lastCheck = Date.now();
      if (!loggedIn) setToken('');
      // 변화 없어도 UI 는 remainingMs 재표시가 필요하므로 매번 이벤트 발행
      window.dispatchEvent(new CustomEvent('auth:change', {
        detail: { loggedIn: this.loggedIn, remainingMs: this.remainingMs, changed }
      }));
    },

    /** 남은 시간 "7h 23m" 포맷 */
    fmtRemaining() {
      if (!this.loggedIn || this.remainingMs <= 0) return '';
      const mins = Math.floor(this.remainingMs / 60000);
      const h = Math.floor(mins / 60);
      const m = mins % 60;
      return h > 0 ? `${h}시간 ${m}분` : `${m}분`;
    }
  };

  // ─── 부팅: 즉시 1회 + 주기 + 포커스 ───────────────────────
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => AuthState.check());
  } else {
    AuthState.check();
  }
  setInterval(() => AuthState.check(), CHECK_INTERVAL_MS);
  window.addEventListener('focus', () => {
    // 포커스 시 최근 체크가 10초 이상 경과했을 때만 재확인 (과도 호출 방지)
    if (Date.now() - AuthState._lastCheck > 10_000) AuthState.check();
  });

  // remainingMs 클라이언트 측 실시간 감소 — 1분마다 UI 재렌더 목적
  setInterval(() => {
    if (AuthState.loggedIn && AuthState.remainingMs > 0) {
      AuthState.remainingMs = Math.max(0, AuthState.remainingMs - 60_000);
      window.dispatchEvent(new CustomEvent('auth:change', {
        detail: { loggedIn: AuthState.loggedIn, remainingMs: AuthState.remainingMs, changed: false, tick: true }
      }));
      if (AuthState.remainingMs === 0) AuthState._apply(false, 0); // 0이면 즉시 로그아웃 처리
    }
  }, 60_000);

  // data-admin-only 속성 자동 토글 — 페이지 어디에나 적용
  window.addEventListener('auth:change', () => {
    document.querySelectorAll('[data-admin-only]').forEach(el => {
      el.style.display = AuthState.loggedIn ? '' : 'none';
    });
  });

  // 전역 노출
  window.AuthState = AuthState;

  // ─── 기존 common.js 호환 API (점진 교체용) ─────────────────
  window.isAdmin        = () => AuthState.loggedIn;
  window.getAdminToken  = () => getToken();
  window.adminHeaders   = (extra = {}) => Object.assign({
    'Content-Type': 'application/json',
    'X-Admin-Token': getToken()
  }, extra);

  /** 관리자 비밀번호 모달 — 기존 prompt() 대체. 성공 시 true 반환 */
  window.adminLogin = async function () {
    const pw = prompt('관리자 비밀번호를 입력하세요:');
    if (!pw) return false;
    try {
      const res = await fetch('/api/auth/verify', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: pw })
      });
      const d = await res.json();
      if (d.valid) {
        AuthState.loginSuccess(d.token, d.remainingMs);
        if (window.showToast) showToast('관리자 로그인 성공', 'success');
        return true;
      }
      if (window.showToast) showToast('비밀번호가 일치하지 않습니다.', 'error');
      return false;
    } catch (e) {
      if (window.showToast) showToast('로그인 실패', 'error');
      return false;
    }
  };

  window.reAuth = async function () {
    return window.adminLogin();
  };

  /** 401 자동 재인증 래퍼 — 기존 호출부 호환 */
  window.adminFetch = async function (url, options = {}) {
    options.headers = Object.assign({
      'Content-Type': 'application/json',
      'X-Admin-Token': getToken()
    }, options.headers || {});
    let res = await fetch(url, options);
    if (res.status === 401) {
      AuthState._apply(false, 0);
      const ok = await window.adminLogin();
      if (!ok) return res;
      options.headers['X-Admin-Token'] = getToken();
      res = await fetch(url, options);
    }
    return res;
  };
})();
