/* ═══════════════════════════════════════════════════════════════
 * IT 담당자(EDITOR) 로그인 모달 — 모든 네비 페이지에서 공통 사용
 * 선행: auth.js (getEditorToken, setEditorToken)
 * ═══════════════════════════════════════════════════════════════ */
(function () {
  function toast(msg, type) {
    if (typeof window.showToast === 'function') window.showToast(msg, type || 'info');
    else if (msg) console.log('[assignee-login]', type, msg);
  }

  function ensureModal() {
    if (document.getElementById('assigneeLoginModal')) return;
    document.body.insertAdjacentHTML('beforeend', `
<div id="assigneeLoginModal" style="display:none;position:fixed;inset:0;z-index:10050;align-items:center;justify-content:center;background:rgba(15,23,42,0.45);padding:16px;box-sizing:border-box;" onclick="if(event.target===this)window.closeAssigneeLoginModal&&window.closeAssigneeLoginModal()">
  <div style="position:relative;max-width:440px;width:100%;background:var(--card,#fff);border-radius:12px;box-shadow:0 20px 50px rgba(0,0,0,.25);border:1px solid var(--border,#e2e8f0);color:var(--text,#1e293b);" onclick="event.stopPropagation()">
    <div style="display:flex;align-items:center;justify-content:space-between;padding:14px 18px;border-bottom:1px solid var(--border,#e2e8f0);">
      <h3 style="margin:0;font-size:16px;font-weight:700;">일반사용자 로그인</h3>
      <button type="button" class="btn btn-ghost btn-sm" onclick="window.closeAssigneeLoginModal && window.closeAssigneeLoginModal()">닫기 ✕</button>
    </div>
    <div style="padding:18px 20px;">
      <div style="display:grid;grid-template-columns:120px 1fr;align-items:center;gap:10px 12px;font-size:12px;">
        <div style="color:var(--text-muted,#64748b);font-weight:600;">팀</div>
        <div>
          <input id="assigneeTeamInput" class="df-input" list="assigneeTeamDatalist" autocomplete="organization" placeholder="팀명" style="width:100%;height:34px;box-sizing:border-box;padding:6px 10px;border:1px solid var(--border,#e2e8f0);border-radius:6px;font-size:12px;">
        </div>
        <div style="color:var(--text-muted,#64748b);font-weight:600;">담당자명</div>
        <div>
          <input id="assigneeNameInput" class="df-input" autocomplete="name" placeholder="이름" style="width:100%;height:34px;box-sizing:border-box;padding:6px 10px;border:1px solid var(--border,#e2e8f0);border-radius:6px;font-size:12px;">
        </div>
        <div style="color:var(--text-muted,#64748b);font-weight:600;">비밀번호</div>
        <div>
          <input id="assigneePasswordInput" class="df-input" type="password" autocomplete="current-password" placeholder="최초 등록 또는 로그인" style="width:100%;height:34px;box-sizing:border-box;padding:6px 10px;border:1px solid var(--border,#e2e8f0);border-radius:6px;font-size:12px;">
        </div>
      </div>
      <datalist id="assigneeTeamDatalist"></datalist>
      <div style="display:flex;gap:10px;margin-top:18px;flex-wrap:wrap;">
        <button type="button" class="btn btn-blue" onclick="window._assigneeLoginDoLogin && window._assigneeLoginDoLogin()">로그인</button>
        <button type="button" class="btn btn-ghost" onclick="window._assigneeLoginDoRegister && window._assigneeLoginDoRegister()">최초 비밀번호 등록</button>
      </div>
    </div>
  </div>
</div>`);
    const ti = document.getElementById('assigneeTeamInput');
    if (ti && !ti._bound) {
      ti._bound = true;
      ti.addEventListener('input', () => { try { scheduleAssigneeTeamHint(); } catch (e) {} });
      ti.addEventListener('focus', () => { try { loadAssigneeTeamDatalist(); } catch (e) {} });
    }
  }

  let _teamHintTimer = null;
  function scheduleAssigneeTeamHint() {
    clearTimeout(_teamHintTimer);
    _teamHintTimer = setTimeout(loadAssigneeTeamDatalist, 220);
  }

  async function loadAssigneeTeamDatalist() {
    const inp = document.getElementById('assigneeTeamInput');
    const dl = document.getElementById('assigneeTeamDatalist');
    if (!inp || !dl) return;
    const q = (inp.value || '').trim().slice(0, 48);
    try {
      const res = await fetch('/api/assignee/team-suggestions?q=' + encodeURIComponent(q));
      const data = await res.json().catch(() => ({}));
      const teams = data.teams || [];
      dl.innerHTML = teams.map(t => {
        const v = String(t).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
        return `<option value="${v}">`;
      }).join('');
    } catch (e) { /* ignore */ }
  }

  function openAssigneeLoginModal() {
    ensureModal();
    const m = document.getElementById('assigneeLoginModal');
    if (!m) return;
    try { const p = document.getElementById('assigneePasswordInput'); if (p) p.value = ''; } catch (e) {}
    m.style.display = 'flex';
    try { document.getElementById('assigneeTeamInput')?.focus(); } catch (e) {}
  }

  function closeAssigneeLoginModal() {
    const m = document.getElementById('assigneeLoginModal');
    if (m) m.style.display = 'none';
  }

  async function assigneeRegister() {
    const team = (document.getElementById('assigneeTeamInput')?.value || '').trim();
    const name = (document.getElementById('assigneeNameInput')?.value || '').trim();
    const password = document.getElementById('assigneePasswordInput')?.value || '';
    if (!team || !name || !password) { toast('팀·담당자명·비밀번호를 입력하세요.', 'error'); return; }
    try {
      const res = await fetch('/api/assignee/register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teamName: team, assigneeName: name, password })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || '등록 실패');
      toast(data.message || '등록되었습니다. 로그인하세요.', 'success');
    } catch (e) { toast(e.message || '등록 실패', 'error'); }
  }

  async function assigneeLogin() {
    const team = (document.getElementById('assigneeTeamInput')?.value || '').trim();
    const name = (document.getElementById('assigneeNameInput')?.value || '').trim();
    const password = document.getElementById('assigneePasswordInput')?.value || '';
    if (!team || !name || !password) { toast('팀·담당자명·비밀번호를 입력하세요.', 'error'); return; }
    try {
      const res = await fetch('/api/assignee/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ teamName: team, assigneeName: name, password })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || '로그인 실패');
      if (window.setEditorToken) window.setEditorToken(data.token || '');
      if (data.remainingMs != null && window.syncEditorSessionDeadlineFromRemainingMs) {
        window.syncEditorSessionDeadlineFromRemainingMs(data.remainingMs);
      } else if (window.syncEditorSessionDeadlineFromServer) {
        void window.syncEditorSessionDeadlineFromServer();
      }
      toast('담당자 로그인됨 — 변경은 제안으로 저장됩니다.', 'success');
      closeAssigneeLoginModal();
      await refreshAssigneeAuthUi();
      try { if (typeof window.applyAdminUI === 'function') window.applyAdminUI(); } catch (e) {}
    } catch (e) { toast(e.message || '로그인 실패', 'error'); }
  }

  async function assigneeLogout() {
    try {
      const tok = window.getEditorToken ? window.getEditorToken() : '';
      if (tok) {
        await fetch('/api/assignee/auth/logout', { method: 'POST', headers: { 'X-Editor-Token': tok } }).catch(() => {});
      }
    } catch (e) {}
    if (window.setEditorToken) window.setEditorToken('');
    if (window.syncEditorSessionDeadlineFromRemainingMs) window.syncEditorSessionDeadlineFromRemainingMs(0);
    await refreshAssigneeAuthUi();
    try { if (typeof window.applyAdminUI === 'function') window.applyAdminUI(); } catch (e) {}
    toast('담당자 로그아웃', 'info');
  }

  async function refreshAssigneeAuthUi() {
    const tok = window.getEditorToken ? window.getEditorToken() : '';
    if (tok) {
      try {
        const res = await fetch('/api/assignee/auth/check', { headers: { 'X-Editor-Token': tok } });
        const d = await res.json().catch(() => ({}));
        if (!d.valid || (!d.teamName && !d.assigneeName)) {
          if (window.setEditorToken) window.setEditorToken('');
          if (window.syncEditorSessionDeadlineFromRemainingMs) window.syncEditorSessionDeadlineFromRemainingMs(0);
        } else if (d.remainingMs != null && window.syncEditorSessionDeadlineFromRemainingMs) {
          window.syncEditorSessionDeadlineFromRemainingMs(d.remainingMs);
        }
      } catch (e) {
        if (window.setEditorToken) window.setEditorToken('');
        if (window.syncEditorSessionDeadlineFromRemainingMs) window.syncEditorSessionDeadlineFromRemainingMs(0);
      }
    }
    try { window.dispatchEvent(new Event('editor-auth:change')); } catch (e) {}
    try { if (window.AppNav && typeof window.AppNav.renderAssigneeSlot === 'function') window.AppNav.renderAssigneeSlot(); } catch (e) {}
    try { window.dispatchEvent(new CustomEvent('assignee-auth:refreshed', { detail: {} })); } catch (e) {}
  }

  window.openAssigneeLoginModal = openAssigneeLoginModal;
  window.closeAssigneeLoginModal = closeAssigneeLoginModal;
  window.assigneeLogout = assigneeLogout;
  window.refreshAssigneeAuthUi = refreshAssigneeAuthUi;
  window._assigneeLoginDoLogin = assigneeLogin;
  window._assigneeLoginDoRegister = assigneeRegister;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => { try { ensureModal(); } catch (e) {} });
  } else {
    try { ensureModal(); } catch (e) {}
  }
})();
