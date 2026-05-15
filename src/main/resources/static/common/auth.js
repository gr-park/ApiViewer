/* ═══════════════════════════════════════════════════════════════
 * URL Viewer — 공통 인증 (중앙 상태 + 이벤트 전파)
 *
 * AuthState: 서버 토큰의 실제 유효성을 주기적으로 검증, 변화 시
 *   window 에 `auth:change` CustomEvent({loggedIn, remainingMs}) 전파.
 * 남은 시간 표시: 서버 remainingMs 로 만료 시각을 고정한 뒤 1초마다
 *   `auth:session-tick` 으로 네비만 가볍게 갱신한다.
 * 기존 sessionStorage 기반 isAdmin()/getAdminToken() API 는 호환을 위해 유지하되
 *   내부적으로는 AuthState 가 단일 소스다.
 * ═══════════════════════════════════════════════════════════════ */
(function () {
  const TOKEN_KEY = 'adminToken';
  const FLAG_KEY  = 'isAdmin';
  const CHECK_INTERVAL_MS = 60_000;
  const DEFAULT_TTL_MS = 8 * 60 * 60 * 1000; // 8h — 서버 remainingMs 미제공 시 fallback
  const WARN_BEFORE_MS = 5 * 60 * 1000;

  /** 관리자 세션 만료 시각(ms epoch). null 이면 미로그인·미동기화 */
  let adminSessionDeadlineMs = null;
  /** 에디터 세션 만료 시각 — /api/assignee/auth/check 의 remainingMs 로 동기화 */
  let editorSessionDeadlineMs = null;
  let adminWarn5mShown = false;
  let editorWarn5mShown = false;
  /** adminLogin(비번 프롬프트) 동시 호출 시 한 번만 띄움 */
  let adminPwdPromptPromise = null;

  function getToken() { return sessionStorage.getItem(TOKEN_KEY) || ''; }
  function setToken(t) {
    if (t) { sessionStorage.setItem(TOKEN_KEY, t); sessionStorage.setItem(FLAG_KEY, 'true'); }
    else   { sessionStorage.removeItem(TOKEN_KEY); sessionStorage.removeItem(FLAG_KEY); }
  }

  function readCookie(name) {
    const esc = name.replace(/([.$?*|{}()[\]\\/+^])/g, '\\$1');
    const m = document.cookie.match(new RegExp('(?:^|; )' + esc + '=([^;]*)'));
    return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : '';
  }

  function bootstrapTokenFromCookie() {
    if (sessionStorage.getItem(TOKEN_KEY)) return;
    const c = readCookie(TOKEN_KEY);
    if (c) setToken(c);
  }
  bootstrapTokenFromCookie();

  function fmtRemainingFromMs(ms) {
    if (ms == null || ms <= 0) return '';
    const totalSec = Math.floor(ms / 1000);
    const s = totalSec % 60;
    const totalMin = Math.floor(totalSec / 60);
    const m = totalMin % 60;
    const h = Math.floor(totalMin / 60);
    if (h > 0) return `${h}시간 ${m}분 ${s}초`;
    if (totalMin > 0) return `${m}분 ${s}초`;
    return `${s}초`;
  }

  const _hasToken = !!(sessionStorage.getItem(TOKEN_KEY));
  const AuthState = {
    loggedIn: _hasToken,
    remainingMs: _hasToken ? DEFAULT_TTL_MS : 0,
    _lastCheck: 0,

    async check() {
      const token = getToken();
      if (!token) { this._apply(false, 0); return false; }
      try {
        const res = await fetch('/api/auth/check', { headers: { 'X-Admin-Token': token } });
        const data = await res.json();
        this._apply(!!data.valid, data.remainingMs || 0);
        return this.loggedIn;
      } catch (e) {
        return this.loggedIn;
      }
    },

    loginSuccess(token, remainingMs) {
      setToken(token);
      adminWarn5mShown = false;
      this._apply(true, remainingMs || DEFAULT_TTL_MS);
    },

    async logout() {
      const token = getToken();
      try {
        if (token) {
          await fetch('/api/auth/logout', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token }
          });
        }
      } catch (e) { /* 무시 */ }
      setToken('');
      this._apply(false, 0);
    },

    _apply(loggedIn, remainingMs) {
      const changed = (this.loggedIn !== loggedIn);
      this.loggedIn = loggedIn;
      this.remainingMs = remainingMs;
      this._lastCheck = Date.now();
      if (!loggedIn) {
        setToken('');
        adminSessionDeadlineMs = null;
        adminWarn5mShown = false;
      } else if (remainingMs > 0) {
        adminSessionDeadlineMs = Date.now() + remainingMs;
      } else {
        adminSessionDeadlineMs = null;
      }
      window.dispatchEvent(new CustomEvent('auth:change', {
        detail: { loggedIn: this.loggedIn, remainingMs: this.remainingMs, changed }
      }));
    },

    fmtRemaining() {
      if (!this.loggedIn || this.remainingMs <= 0) return '';
      return fmtRemainingFromMs(this.remainingMs);
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => AuthState.check());
  } else {
    AuthState.check();
  }
  setInterval(() => AuthState.check(), CHECK_INTERVAL_MS);
  window.addEventListener('focus', () => {
    if (Date.now() - AuthState._lastCheck > 10_000) AuthState.check();
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      void AuthState.check();
      if (window.getEditorToken && window.getEditorToken()) {
        void window.syncEditorSessionDeadlineFromServer();
      }
    }
  });

  // 1초: 만료 시각 기준 표시 + 만료 시 로그아웃
  setInterval(() => {
    const tok = getToken();
    if (tok && adminSessionDeadlineMs != null) {
      const rem = Math.max(0, adminSessionDeadlineMs - Date.now());
      AuthState.remainingMs = rem;
      if (rem === 0) {
        adminSessionDeadlineMs = null;
        AuthState._apply(false, 0);
        if (window.showToast) window.showToast('관리자 세션이 만료되었습니다.', 'info');
      } else {
        if (!adminWarn5mShown && rem > 0 && rem <= WARN_BEFORE_MS) {
          adminWarn5mShown = true;
          if (window.showToast) window.showToast('관리자 세션이 곧 만료됩니다. 저장할 작업을 마쳐 주세요.', 'warning');
        }
        window.dispatchEvent(new CustomEvent('auth:session-tick', {
          detail: { role: 'admin', remainingMs: rem }
        }));
      }
    }

    const edTok = window.getEditorToken && window.getEditorToken();
    if (edTok && editorSessionDeadlineMs != null) {
      const rem = Math.max(0, editorSessionDeadlineMs - Date.now());
      if (rem === 0) {
        editorSessionDeadlineMs = null;
        editorWarn5mShown = false;
        if (window.setEditorToken) window.setEditorToken('');
        try { window.dispatchEvent(new Event('editor-auth:change')); } catch (e) {}
        if (window.showToast) window.showToast('담당자 세션이 만료되었습니다.', 'info');
      } else {
        if (!editorWarn5mShown && rem > 0 && rem <= WARN_BEFORE_MS) {
          editorWarn5mShown = true;
          if (window.showToast) window.showToast('담당자 세션이 곧 만료됩니다.', 'warning');
        }
        window.dispatchEvent(new CustomEvent('auth:session-tick', {
          detail: { role: 'editor', remainingMs: rem }
        }));
      }
    }
  }, 1000);

  setInterval(() => {
    if (window.getEditorToken && window.getEditorToken()) {
      void window.syncEditorSessionDeadlineFromServer();
    }
  }, CHECK_INTERVAL_MS);

  window.AuthState = AuthState;

  window.isAdmin        = () => AuthState.loggedIn;
  window.getAdminToken  = () => getToken();
  window.adminHeaders   = (extra = {}) => Object.assign({
    'Content-Type': 'application/json',
    'X-Admin-Token': getToken()
  }, extra);

  window.syncEditorSessionDeadlineFromRemainingMs = function (ms) {
    const n = Number(ms);
    if (!n || n <= 0) {
      editorSessionDeadlineMs = null;
      return;
    }
    editorSessionDeadlineMs = Date.now() + n;
    editorWarn5mShown = false;
  };

  window.syncEditorSessionDeadlineFromServer = async function () {
    const t = window.getEditorToken ? window.getEditorToken() : '';
    if (!t) {
      editorSessionDeadlineMs = null;
      return;
    }
    try {
      const res = await fetch('/api/assignee/auth/check', { headers: { 'X-Editor-Token': t } });
      const d = await res.json().catch(() => ({}));
      if (d.valid && d.remainingMs != null) {
        window.syncEditorSessionDeadlineFromRemainingMs(d.remainingMs);
      } else {
        editorSessionDeadlineMs = null;
        if (window.setEditorToken) window.setEditorToken('');
        try { window.dispatchEvent(new Event('editor-auth:change')); } catch (e) {}
      }
    } catch (e) { /* 유지 */ }
  };

  async function adminPasswordPromptOnce() {
    let pw;
    if (typeof window.customPrompt === 'function') {
      pw = await window.customPrompt('관리자 비밀번호를 입력하세요:', { type: 'password', title: '🔑 관리자 인증' });
    } else {
      pw = prompt('관리자 비밀번호를 입력하세요:');
    }
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
  }

  window.adminLogin = async function () {
    if (adminPwdPromptPromise) return adminPwdPromptPromise;
    adminPwdPromptPromise = (async () => {
      try {
        return await adminPasswordPromptOnce();
      } finally {
        adminPwdPromptPromise = null;
      }
    })();
    return adminPwdPromptPromise;
  };

  window.reAuth = async function () {
    return window.adminLogin();
  };

  window.__centralAdminFetch = async function (url, options = {}) {
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
  window.adminFetch = window.__centralAdminFetch;

  const EDITOR_TOKEN_KEY = 'editorToken';
  window.getEditorToken = function () { return sessionStorage.getItem(EDITOR_TOKEN_KEY) || ''; };
  window.setEditorToken = function (t) {
    if (t) sessionStorage.setItem(EDITOR_TOKEN_KEY, t);
    else sessionStorage.removeItem(EDITOR_TOKEN_KEY);
  };
  window.isEditorLoggedIn = function () { return !!window.getEditorToken(); };
  window.editorHeaders = function (extra) {
    return Object.assign({ 'Content-Type': 'application/json', 'X-Editor-Token': window.getEditorToken() }, extra || {});
  };

  if (window.getEditorToken()) {
    void window.syncEditorSessionDeadlineFromServer();
  }

  window.formatSessionRemainingMs = fmtRemainingFromMs;
})();
