/* ═══════════════════════════════════════════════════════════════
 * URL Viewer — 공통 네비게이션 (선언적 렌더링)
 *
 * 사용법: 페이지 <head> 에 아래 2개 meta 태그 추가, <body> 상단에
 *   <div id="nav-container"></div> 배치.
 *   스크립트: `auth.js` → `assignee-login.js`(선택, 담당자 로그인) → `nav.js` — 관리자 쪽지 발송(다중 담당자)은 `repo-select.js` 선행.
 *     <meta name="nav-segment" content="url-viewer">
 *     <meta name="nav-page"    content="viewer">
 *   (메타 없으면 현재 URL 기준 자동 매칭)
 *
 * 페이지 추가/변경: 아래 SEGMENTS 배열만 수정.
 * adminOnly: true 항목은 AuthState 구독해 자동 숨김/표시.
 * ═══════════════════════════════════════════════════════════════ */
(function () {
  // UI 버전 표기 (캐시/반영 여부 확인용) — 변경 시 이 값만 갱신
  const APP_UI_VERSION = 'ver12.0.01';

  const SEGMENTS = [
    {
      id: 'dashboard',
      label: '대시보드',
      icon: '📊',
      home: '/dashboard/',
      pages: []   // 서브메뉴 없음 — 단일 페이지. nav.js 가 빈 배열일 때 2단-B 를 렌더하지 않음
    },
    {
      id: 'url-viewer',
      label: 'URL 현황관리',
      icon: '🔗',
      home: '/url-viewer/viewer.html',
      pages: [
        { id: 'viewer',        label: '📋 URL분석현황',     href: '/url-viewer/viewer.html' },
        { id: 'call-stats',    label: '📈 URL호출현황',     href: '/url-viewer/call-stats.html' },
        { id: 'block-monitor', label: '🚧 차단 모니터링',   href: '/url-viewer/url-block-monitor.html' },
        { id: 'review',        label: '📝 현업 검토',        href: '/url-viewer/review.html' },
        { id: 'workflow',      label: '🗺️ 업무 흐름',       href: '/url-viewer/workflow.html' },
        { id: 'extract',       label: '🔍 URL 분석',         href: '/url-viewer/extract.html', adminOnly: true }
      ]
    },
    {
      id: 'encrypt-viewer',
      label: '암복호화 모듈 현황관리',
      icon: '🔐',
      home: '/encrypt-viewer/',
      adminOnly: true,
      pages: [
        { id: 'placeholder',   label: '(준비 중)',           href: '/encrypt-viewer/' }
      ]
    },
    {
      id: 'settings',
      label: '설정',
      icon: '⚙️',
      home: '/settings/',
      adminOnly: true,
      pages: [
        { id: 'settings-home',     label: '⚙️ 설정 관리',        href: '/settings/' },
        { id: 'apm-match-report',  label: '🧪 APM 매칭 리포트',  href: '/settings/apm-match-report.html' }
      ]
    }
  ];

  // ─── 현재 페이지 식별 ────────────────────────────────────
  function meta(name) {
    const el = document.querySelector(`meta[name="${name}"]`);
    return el ? el.getAttribute('content') : null;
  }
  function resolveCurrent() {
    let segId  = meta('nav-segment');
    let pageId = meta('nav-page');
    if (!segId) {
      // 경로로 추론
      const p = location.pathname;
      if (p.startsWith('/dashboard'))       segId = 'dashboard';
      else if (p.startsWith('/url-viewer')) segId = 'url-viewer';
      else if (p.startsWith('/encrypt-viewer')) segId = 'encrypt-viewer';
      else if (p.startsWith('/settings'))   segId = 'settings';
      else segId = 'dashboard';
    }
    return { segId, pageId };
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  // ─── HTML 렌더 ──────────────────────────────────────────
  function render() {
    const container = document.getElementById('nav-container');
    if (!container) return;

    const { segId, pageId } = resolveCurrent();
    const activeSeg = SEGMENTS.find(s => s.id === segId) || SEGMENTS[0];
    const activePage = activeSeg.pages.find(p => p.id === pageId);
    const brandSub = activePage ? activePage.label.replace(/^\S+\s*/, '') : activeSeg.label;

    // Tier 1: 로고 + 유틸 ─────────────────────────────────────
    const top = `
      <div class="app-nav-top">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>
        </svg>
        <a class="brand" href="/dashboard/">
          <h1>
            IT소스 관리포털 <small>— IT Source Management Portal</small>
            <span class="brand-ver">${esc(APP_UI_VERSION)}</span>
          </h1>
        </a>
        <span class="brand-sub">${esc(brandSub)}</span>
        <div class="utils">
          <button class="dark-toggle" onclick="toggleDarkMode && toggleDarkMode()">🌙 다크모드</button>
          <span id="nav-assignee-slot"></span>
          <span id="nav-admin-slot"></span>
        </div>
      </div>`;

    // Tier 2-A: 대영역 세그먼트 ──────────────────────────────
    const segs = SEGMENTS.map(s => `
      <a class="nav-segment${s.id === activeSeg.id ? ' active' : ''}${s.adminOnly ? ' nav-admin-hidden' : ''}"
         href="${esc(s.home)}"${s.adminOnly ? ' data-admin-only' : ''}>
        <span>${esc(s.icon)}</span><span>${esc(s.label)}</span>
      </a>`).join('');

    // Tier 2-B: 하위 페이지 ──────────────────────────────────
    // 서브메뉴가 없는 세그먼트(예: 대시보드)는 2단-B 자체를 렌더링하지 않음
    const hasSubPages = activeSeg.pages && activeSeg.pages.length > 0;
    const pages = hasSubPages ? activeSeg.pages.map(p => `
      <a class="nav-page-link${p.id === pageId ? ' active' : ''}"
         href="${esc(p.href)}"${p.adminOnly ? ' data-admin-only' : ''}>
        ${esc(p.label)}
      </a>`).join('') : '';

    container.className = 'app-nav';
    container.innerHTML = `
      ${top}
      <div class="app-nav-segments">${segs}</div>
      ${hasSubPages ? `<div class="app-nav-pages">${pages}</div>` : ''}
      <div id="nav-alert-stack" class="nav-alert-stack">
        <div id="sync-warning-slot"></div>
        <div id="ops-digest-slot"></div>
        <div id="apm-match-slot"></div>
        <div id="extract-issue-slot"></div>
      </div>
    `;

    renderAdminSlot();
    renderAssigneeSlot();
  }

  // ─── 배치 Git 동기화 실패 경고 배너 ──────────────────────
  function fmtSyncTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  // 현재 경고 목록의 시그니처(레포명 정렬 결합) — 동일 목록이면 닫기 유지, 새 실패가 추가되면 자동으로 다시 표시
  function syncWarnSignature(list) {
    return (list || []).map(w => String(w.repoName || ''))
      .filter(Boolean).sort().join('|');
  }
  const SYNC_WARN_DISMISS_KEY = 'syncWarnDismiss';
  const OPS_DIGEST_DISMISS_KEY = 'opsDigestDismissAt';
  const APM_MATCH_DISMISS_KEY = 'apmMatchDismissAt';
  const EXTRACT_ISSUE_DISMISS_KEY = 'extractIssueDismissAt';

  function renderSyncWarnings(list) {
    const slot = document.getElementById('sync-warning-slot');
    if (!slot) return;
    if (!Array.isArray(list) || list.length === 0) {
      slot.innerHTML = '';
      return;
    }
    // 사용자가 이전에 닫은 동일 목록이면 표시하지 않음
    try {
      const dismissed = sessionStorage.getItem(SYNC_WARN_DISMISS_KEY);
      if (dismissed && dismissed === syncWarnSignature(list)) {
        slot.innerHTML = '';
        return;
      }
    } catch(e) {}

    const items = list.map(w => `
      <li>
        <strong>${esc(w.repoName)}</strong>
        <span class="sync-warning-time">(${esc(fmtSyncTime(w.lastSyncAt))})</span>
        <span class="sync-warning-msg">— ${esc(w.message || '사유 미상')}</span>
      </li>`).join('');
    slot.innerHTML = `
      <div class="sync-warning-banner" role="alert">
        <div class="sync-warning-summary">
          <span class="sync-warning-icon" aria-hidden="true">⚠</span>
          <span class="sync-warning-text">
            최근 배치 Git 동기화 실패: 레포 <strong>${list.length}개</strong> — 최신 소스 미반영 가능
          </span>
          <button type="button" class="sync-warning-toggle" aria-expanded="false">자세히 ▼</button>
          <button type="button" class="sync-warning-close" aria-label="알림 닫기" title="알림 닫기">✕</button>
        </div>
        <ul class="sync-warning-details" hidden>${items}</ul>
      </div>`;

    const btn = slot.querySelector('.sync-warning-toggle');
    const details = slot.querySelector('.sync-warning-details');
    if (btn && details) {
      btn.addEventListener('click', () => {
        const open = !details.hasAttribute('hidden');
        if (open) {
          details.setAttribute('hidden', '');
          btn.setAttribute('aria-expanded', 'false');
          btn.textContent = '자세히 ▼';
        } else {
          details.removeAttribute('hidden');
          btn.setAttribute('aria-expanded', 'true');
          btn.textContent = '접기 ▲';
        }
      });
    }
    const closeBtn = slot.querySelector('.sync-warning-close');
    if (closeBtn) {
      closeBtn.addEventListener('click', () => {
        try { sessionStorage.setItem(SYNC_WARN_DISMISS_KEY, syncWarnSignature(list)); } catch(e) {}
        slot.innerHTML = '';
      });
    }
  }

  function loadSyncWarnings() {
    fetch('/api/config/repos/sync-warnings', { credentials: 'same-origin' })
      .then(r => r.ok ? r.json() : [])
      .then(list => renderSyncWarnings(list))
      .catch(() => {});
  }

  function fmtDigestTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso).replace('T', ' ').substring(0, 19);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function renderOpsDigestBanner(text, atIso) {
    const slot = document.getElementById('ops-digest-slot');
    if (!slot || !text || !String(text).trim()) {
      if (slot) slot.innerHTML = '';
      return;
    }
    const body = String(text).trim();
    const preview = body.length > 200 ? body.slice(0, 200) + '…' : body;
    const timeStr = fmtDigestTime(atIso);
    slot.innerHTML = `
      <div class="sync-warning-banner ops-digest-banner" role="status">
        <div class="sync-warning-summary">
          <span class="sync-warning-icon" aria-hidden="true">💡</span>
          <div class="sync-warning-text ops-digest-text-wrap">
            <div><strong>운영·배치 요약</strong> <span class="ops-digest-badge">AI</span></div>
            ${timeStr ? `<div class="ops-digest-time">갱신: ${esc(timeStr)}</div>` : ''}
            <div class="ops-digest-preview">${esc(preview)}</div>
          </div>
          <button type="button" class="sync-warning-toggle" aria-expanded="false">자세히 ▼</button>
          <button type="button" class="sync-warning-close" aria-label="이 요약 닫기 (다음 갱신 시 다시 표시)" title="닫기">✕</button>
        </div>
        <div class="sync-warning-details ops-digest-details" hidden>
          <pre class="ops-digest-pre">${esc(body)}</pre>
        </div>
      </div>`;

    const root = slot.querySelector('.ops-digest-banner');
    const btn = root.querySelector('.sync-warning-toggle');
    const details = root.querySelector('.ops-digest-details');
    if (btn && details) {
      btn.addEventListener('click', () => {
        const open = !details.hasAttribute('hidden');
        if (open) {
          details.setAttribute('hidden', '');
          btn.setAttribute('aria-expanded', 'false');
          btn.textContent = '자세히 ▼';
        } else {
          details.removeAttribute('hidden');
          btn.setAttribute('aria-expanded', 'true');
          btn.textContent = '접기 ▲';
        }
      });
    }
    const closeBtn = root.querySelector('.sync-warning-close');
    if (closeBtn) {
      closeBtn.addEventListener('click', () => {
        try { sessionStorage.setItem(OPS_DIGEST_DISMISS_KEY, atIso || '1'); } catch (e) {}
        slot.innerHTML = '';
      });
    }
  }

  function loadOpsDigestBanner() {
    fetch('/api/config/ops-digest-summary', { credentials: 'same-origin' })
      .then(r => (r.ok ? r.json() : null))
      .then(data => {
        const slot = document.getElementById('ops-digest-slot');
        if (!slot) return;
        if (!data || !data.text || !String(data.text).trim()) {
          slot.innerHTML = '';
          return;
        }
        const at = data.at || '';
        try {
          const dismissed = sessionStorage.getItem(OPS_DIGEST_DISMISS_KEY);
          if (dismissed && at && dismissed === at) {
            slot.innerHTML = '';
            return;
          }
        } catch (e) {}
        renderOpsDigestBanner(data.text, at);
      })
      .catch(() => {});
  }

  // ─── APM 매칭 진단 배너 ────────────────────────────────
  function renderApmMatchBanner(data) {
    const slot = document.getElementById('apm-match-slot');
    if (!slot) return;
    if (!data || data.ok === false) { slot.innerHTML = ''; return; }
    const mismatchRepos = Number(data.mismatchRepoCount || 0);
    const mismatchPaths = Number(data.totalMismatchPaths || 0);
    const at = data.at || '';
    const days = Number(data.periodDays || 365);
    if (!mismatchRepos || mismatchRepos <= 0 || !mismatchPaths || mismatchPaths <= 0) {
      slot.innerHTML = '';
      return;
    }
    // 사용자가 닫은 동일 at 이면 숨김
    try {
      const dismissed = sessionStorage.getItem(APM_MATCH_DISMISS_KEY);
      if (dismissed && at && dismissed === at) { slot.innerHTML = ''; return; }
    } catch(e) {}

    const timeStr = fmtDigestTime(at);
    const loggedIn = window.AuthState && window.AuthState.loggedIn;
    const link = loggedIn
      ? `<a href="/settings/apm-match-report.html" class="sync-warning-toggle" style="text-decoration:none;">상세 보기 ▶</a>`
      : '';
    slot.innerHTML = `
      <div class="sync-warning-banner ops-digest-banner" role="alert">
        <div class="sync-warning-summary">
          <span class="sync-warning-icon" aria-hidden="true">🧪</span>
          <div class="sync-warning-text ops-digest-text-wrap">
            <div><strong>APM↔URL 매칭 경고</strong></div>
            ${timeStr ? `<div class="ops-digest-time">갱신: ${esc(timeStr)}</div>` : ''}
            <div class="ops-digest-preview">
              미매칭 레포 <strong>${esc(mismatchRepos)}</strong>개 · 미매칭 URL <strong>${esc(mismatchPaths)}</strong>건 (최근 ${esc(days)}일)
            </div>
          </div>
          ${link}
          <button type="button" class="sync-warning-close" aria-label="이 알림 닫기 (다음 갱신 시 다시 표시)" title="닫기">✕</button>
        </div>
      </div>`;
    const root = slot.querySelector('.ops-digest-banner');
    const closeBtn = root && root.querySelector('.sync-warning-close');
    if (closeBtn) {
      closeBtn.addEventListener('click', () => {
        try { sessionStorage.setItem(APM_MATCH_DISMISS_KEY, at || '1'); } catch (e) {}
        slot.innerHTML = '';
      });
    }
  }

  function loadApmMatchBanner() {
    fetch('/api/config/apm-match-summary', { credentials: 'same-origin' })
      .then(r => (r.ok ? r.json() : null))
      .then(data => renderApmMatchBanner(data))
      .catch(() => {});
  }

  // ─── URL 분석(Extract) 문제파일 요약 배너 ─────────────────
  function renderExtractIssueBanner(data) {
    const slot = document.getElementById('extract-issue-slot');
    if (!slot) return;
    if (!data || data.ok === false) { slot.innerHTML = ''; return; }
    const err = Number(data.errorFileCount || 0);
    const zero = Number(data.zeroFileCount || 0);
    const warn = Number(data.warnFileCount || 0);
    const at = data.at || '';
    if (err <= 0 && zero <= 0 && warn <= 0) { slot.innerHTML = ''; return; }
    try {
      const dismissed = sessionStorage.getItem(EXTRACT_ISSUE_DISMISS_KEY);
      if (dismissed && at && dismissed === at) { slot.innerHTML = ''; return; }
    } catch(e) {}

    const timeStr = fmtDigestTime(at);
    const loggedIn = window.AuthState && window.AuthState.loggedIn;
    const link = loggedIn
      ? `<a href="/settings/#extract" class="sync-warning-toggle" style="text-decoration:none;">설정에서 보기 ▶</a>`
      : '';
    const parts = [];
    if (err > 0) parts.push(`에러파일 <strong>${esc(err)}</strong>`);
    if (zero > 0) parts.push(`0개추출 <strong>${esc(zero)}</strong>`);
    if (warn > 0) parts.push(`WARN <strong>${esc(warn)}</strong>`);
    slot.innerHTML = `
      <div class="sync-warning-banner ops-digest-banner" role="alert">
        <div class="sync-warning-summary">
          <span class="sync-warning-icon" aria-hidden="true">🧩</span>
          <div class="sync-warning-text ops-digest-text-wrap">
            <div><strong>URL 분석 오류 요약</strong></div>
            ${timeStr ? `<div class="ops-digest-time">갱신: ${esc(timeStr)}</div>` : ''}
            <div class="ops-digest-preview">${parts.join(' · ')}</div>
          </div>
          ${link}
          <button type="button" class="sync-warning-close" aria-label="이 알림 닫기 (다음 갱신 시 다시 표시)" title="닫기">✕</button>
        </div>
      </div>`;
    const root = slot.querySelector('.ops-digest-banner');
    const closeBtn = root && root.querySelector('.sync-warning-close');
    if (closeBtn) {
      closeBtn.addEventListener('click', () => {
        try { sessionStorage.setItem(EXTRACT_ISSUE_DISMISS_KEY, at || '1'); } catch (e) {}
        slot.innerHTML = '';
      });
    }
  }

  function loadExtractIssueBanner() {
    fetch('/api/config/extract-issue-summary', { credentials: 'same-origin' })
      .then(r => (r.ok ? r.json() : null))
      .then(data => renderExtractIssueBanner(data))
      .catch(() => {});
  }

  const NAV_PORTAL_DISMISS_KEY = 'navPortalNoticeDismissAt';
  let _navAssigneeSelectInst = null;

  function upgradeNavOverlaysIfNeeded() {
    const msgBody = document.querySelector('#navMsgToAdminModal .nav-modal-body');
    if (msgBody && !document.getElementById('navMsgToAdminReplyToId')) {
      msgBody.insertAdjacentHTML('afterbegin', '<input type="hidden" id="navMsgToAdminReplyToId" value="">');
    }
    if (!document.getElementById('navReplyFromAdminModal')) {
      document.body.insertAdjacentHTML('beforeend', `
<div id="navReplyFromAdminModal" class="nav-modal-overlay" style="display:none;">
  <div class="nav-modal-box" onclick="event.stopPropagation()">
    <div class="nav-modal-head"><strong>담당자에게 답장</strong><button type="button" class="nav-modal-x" onclick="window.AppNav.closeReplyFromAdminModal && window.AppNav.closeReplyFromAdminModal()">✕</button></div>
    <div class="nav-modal-body">
      <input type="hidden" id="navReplyAssigneeMessageId" value="">
      <label class="nav-modal-label">내용</label>
      <textarea id="navReplyFromAdminBody" class="nav-modal-textarea" rows="5" placeholder="답장 내용"></textarea>
      <div class="nav-modal-actions">
        <button type="button" class="nav-btn nav-btn-primary" onclick="window.AppNav.submitReplyFromAdmin && window.AppNav.submitReplyFromAdmin()">보내기</button>
        <button type="button" class="nav-btn" onclick="window.AppNav.closeReplyFromAdminModal && window.AppNav.closeReplyFromAdminModal()">취소</button>
      </div>
    </div>
  </div>
</div>`);
      const rpl = document.getElementById('navReplyFromAdminModal');
      if (rpl) {
        rpl.addEventListener('click', (e) => {
          if (e.target && e.target.id === 'navReplyFromAdminModal') closeReplyFromAdminModal();
        });
      }
    }
  }

  function ensureNavOverlays() {
    if (document.getElementById('nav-inbox-backdrop')) {
      upgradeNavOverlaysIfNeeded();
      return;
    }
    document.body.insertAdjacentHTML('beforeend', `
<div id="nav-inbox-backdrop" class="nav-inbox-backdrop" style="display:none;" aria-hidden="true"></div>
<div id="nav-editor-inbox" class="nav-inbox-popover" style="display:none;" role="dialog" aria-label="담당자 쪽지함"></div>
<div id="nav-admin-inbox" class="nav-inbox-popover" style="display:none;" role="dialog" aria-label="관리자 쪽지함"></div>
<div id="navSendNoticeModal" class="nav-modal-overlay" style="display:none;">
  <div class="nav-modal-box" onclick="event.stopPropagation()">
    <div class="nav-modal-head"><strong>쪽지 발송</strong><button type="button" class="nav-modal-x" onclick="window.AppNav.closeSendNoticeModal && window.AppNav.closeSendNoticeModal()">✕</button></div>
    <div class="nav-modal-body">
      <label class="nav-radio-row"><input type="radio" name="navSendScope" value="whole" checked> 전체 (로그인 사용자)</label>
      <label class="nav-radio-row"><input type="radio" name="navSendScope" value="selected"> 선택 담당자</label>
      <div id="navNoticeAssigneeWrap" style="display:none;margin:8px 0;">
        <div class="nav-muted" style="font-size:11px;margin-bottom:4px;">담당자 검색·다중 선택 (아래 목록에서 적용)</div>
        <div id="navNoticeAssigneeMount"></div>
      </div>
      <label class="nav-modal-label">내용</label>
      <textarea id="navNoticeBody" class="nav-modal-textarea" rows="5" placeholder="쪽지 내용"></textarea>
      <div class="nav-modal-actions">
        <button type="button" class="nav-btn nav-btn-primary" onclick="window.AppNav.submitSendNotice && window.AppNav.submitSendNotice()">보내기</button>
        <button type="button" class="nav-btn" onclick="window.AppNav.closeSendNoticeModal && window.AppNav.closeSendNoticeModal()">취소</button>
      </div>
    </div>
  </div>
</div>
<div id="navMsgToAdminModal" class="nav-modal-overlay" style="display:none;">
  <div class="nav-modal-box" onclick="event.stopPropagation()">
    <div class="nav-modal-head"><strong>관리자에게 쪽지</strong><button type="button" class="nav-modal-x" onclick="window.AppNav.closeMsgToAdminModal && window.AppNav.closeMsgToAdminModal()">✕</button></div>
    <div class="nav-modal-body">
      <input type="hidden" id="navMsgToAdminReplyToId" value="">
      <textarea id="navMsgToAdminBody" class="nav-modal-textarea" rows="5" placeholder="내용을 입력하세요"></textarea>
      <div class="nav-modal-actions">
        <button type="button" class="nav-btn nav-btn-primary" onclick="window.AppNav.submitMsgToAdmin && window.AppNav.submitMsgToAdmin()">보내기</button>
        <button type="button" class="nav-btn" onclick="window.AppNav.closeMsgToAdminModal && window.AppNav.closeMsgToAdminModal()">취소</button>
      </div>
    </div>
  </div>
</div>
<div id="navReplyFromAdminModal" class="nav-modal-overlay" style="display:none;">
  <div class="nav-modal-box" onclick="event.stopPropagation()">
    <div class="nav-modal-head"><strong>담당자에게 답장</strong><button type="button" class="nav-modal-x" onclick="window.AppNav.closeReplyFromAdminModal && window.AppNav.closeReplyFromAdminModal()">✕</button></div>
    <div class="nav-modal-body">
      <input type="hidden" id="navReplyAssigneeMessageId" value="">
      <label class="nav-modal-label">내용</label>
      <textarea id="navReplyFromAdminBody" class="nav-modal-textarea" rows="5" placeholder="답장 내용"></textarea>
      <div class="nav-modal-actions">
        <button type="button" class="nav-btn nav-btn-primary" onclick="window.AppNav.submitReplyFromAdmin && window.AppNav.submitReplyFromAdmin()">보내기</button>
        <button type="button" class="nav-btn" onclick="window.AppNav.closeReplyFromAdminModal && window.AppNav.closeReplyFromAdminModal()">취소</button>
      </div>
    </div>
  </div>
</div>`);
    document.getElementById('nav-inbox-backdrop').addEventListener('click', () => closeInboxPopovers());
    document.querySelectorAll('input[name="navSendScope"]').forEach(r => {
      r.addEventListener('change', () => {
        const w = document.getElementById('navNoticeAssigneeWrap');
        if (w) w.style.display = r.value === 'selected' && r.checked ? 'block' : 'none';
        if (r.value === 'selected' && r.checked) loadAssigneesForNoticePicker();
      });
    });
    document.getElementById('navSendNoticeModal').addEventListener('click', (e) => {
      if (e.target && e.target.id === 'navSendNoticeModal') closeSendNoticeModal();
    });
    document.getElementById('navMsgToAdminModal').addEventListener('click', (e) => {
      if (e.target && e.target.id === 'navMsgToAdminModal') closeMsgToAdminModal();
    });
    const rpl = document.getElementById('navReplyFromAdminModal');
    if (rpl) {
      rpl.addEventListener('click', (e) => {
        if (e.target && e.target.id === 'navReplyFromAdminModal') closeReplyFromAdminModal();
      });
    }
  }

  function positionPopover(pop, anchor) {
    if (!pop || !anchor) return;
    const r = anchor.getBoundingClientRect();
    const w = Math.min(380, window.innerWidth - 16);
    pop.style.position = 'fixed';
    pop.style.zIndex = '10001';
    pop.style.width = w + 'px';
    pop.style.top = (r.bottom + 6) + 'px';
    let left = r.right - w;
    if (left < 8) left = 8;
    if (left + w > window.innerWidth - 8) left = Math.max(8, window.innerWidth - 8 - w);
    pop.style.left = left + 'px';
  }

  function closeInboxPopovers() {
    const b = document.getElementById('nav-inbox-backdrop');
    const e = document.getElementById('nav-editor-inbox');
    const a = document.getElementById('nav-admin-inbox');
    if (b) b.style.display = 'none';
    if (e) e.style.display = 'none';
    if (a) a.style.display = 'none';
  }

  function toastNav(msg, type) {
    if (typeof window.showToast === 'function') window.showToast(msg, type || 'info');
  }

  async function refreshEditorInboxBadge() {
    const tok = window.getEditorToken ? window.getEditorToken() : '';
    const badge = document.getElementById('navEditorInboxBadge');
    if (!tok || !badge) return;
    try {
      const res = await fetch('/api/assignee/inbox-summary', { headers: { 'X-Editor-Token': tok } });
      if (!res.ok) return;
      const d = await res.json();
      let n = (Number(d.rejectOpenCount) || 0) + (Number(d.adminNoticeOpenCount) || 0);
      const pn = d.portalNotice || {};
      if (pn.text && String(pn.text).trim()) {
        try {
          const dis = sessionStorage.getItem(NAV_PORTAL_DISMISS_KEY);
          if (!dis || dis !== String(pn.at || '')) n++;
        } catch (e2) { n++; }
      }
      if (n > 0) {
        badge.textContent = n > 99 ? '99+' : String(n);
        badge.style.display = 'inline-block';
      } else badge.style.display = 'none';
    } catch (e) { /* ignore */ }
  }

  async function refreshAdminInboxBadge() {
    const badge = document.getElementById('navAdminInboxBadge');
    if (!badge || !window.AuthState || !window.AuthState.loggedIn) return;
    if (!window.adminFetch) return;
    try {
      const res = await window.adminFetch('/api/config/admin-inbox-summary');
      if (!res.ok) return;
      const d = await res.json();
      let n = Number(d.fromAssigneesOpenCount) || 0;
      const pn = d.portalNotice || {};
      if (pn.text && String(pn.text).trim()) {
        try {
          const dis = sessionStorage.getItem(NAV_PORTAL_DISMISS_KEY);
          if (!dis || dis !== String(pn.at || '')) n++;
        } catch (e2) { n++; }
      }
      if (n > 0) {
        badge.textContent = n > 99 ? '99+' : String(n);
        badge.style.display = 'inline-block';
      } else badge.style.display = 'none';
    } catch (e) { /* ignore */ }
  }

  function renderEditorInboxHtml(d) {
    const parts = [];
    const pn = d.portalNotice || {};
    if (pn.text && String(pn.text).trim()) {
      try {
        const dis = sessionStorage.getItem(NAV_PORTAL_DISMISS_KEY);
        if (!dis || dis !== String(pn.at || '')) {
          parts.push(`<div class="nav-inbox-section"><div class="nav-inbox-h">전체 쪽지</div><div class="nav-inbox-p">${esc(pn.text)}</div>
            <button type="button" class="nav-btn nav-btn-sm" data-nav-dismiss-portal="${esc(pn.at || '')}">확인</button></div>`);
        }
      } catch (e) {
        parts.push(`<div class="nav-inbox-section"><div class="nav-inbox-h">전체 쪽지</div><div class="nav-inbox-p">${esc(pn.text)}</div>
            <button type="button" class="nav-btn nav-btn-sm" data-nav-dismiss-portal="${esc(pn.at || '')}">확인</button></div>`);
      }
    }
    const rejects = (d.rejectEvents || []).filter(x => !x.dismissedAt);
    if (rejects.length) {
      parts.push('<div class="nav-inbox-section"><div class="nav-inbox-h">상태변경 반려</div>');
      rejects.forEach(x => {
        if (x.kind === 'rejectBatch') {
          const summary = esc(x.summaryLine || '');
          const reason = esc(x.reason || '');
          const bid = esc(x.batchId || '');
          parts.push(`<div class="nav-inbox-item"><div class="nav-inbox-p">${summary}</div>
            <div class="nav-inbox-meta" style="margin-top:6px;">사유: ${reason}</div>
            <button type="button" class="nav-btn nav-btn-sm" style="margin-top:6px;" data-nav-dismiss-reject-batch="${bid}">확인</button></div>`);
        } else {
          const path = [x.repositoryName, x.apiPath, x.httpMethod].filter(Boolean).join(' · ');
          parts.push(`<div class="nav-inbox-item"><div class="nav-inbox-meta">${esc(path || 'record #' + x.recordId)}</div><div class="nav-inbox-p">${esc(x.reason)}</div>
            <button type="button" class="nav-btn nav-btn-sm" data-nav-dismiss-reject="${x.id}">확인</button></div>`);
        }
      });
      parts.push('</div>');
    }
    const notices = (d.adminNotices || []).filter(x => !x.dismissedAt);
    if (notices.length) {
      parts.push('<div class="nav-inbox-section"><div class="nav-inbox-h">관리자 쪽지</div>');
      notices.forEach(x => {
        parts.push(`<div class="nav-inbox-item"><div class="nav-inbox-p">${esc(x.body)}</div>
          <div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:8px;">
            <button type="button" class="nav-btn nav-btn-sm nav-btn-primary" data-nav-reply-admin="${x.id}">답장</button>
            <button type="button" class="nav-btn nav-btn-sm" data-nav-dismiss-admin="${x.id}">확인</button>
          </div></div>`);
      });
      parts.push('</div>');
    }
    parts.push(`<div style="margin-top:10px;"><button type="button" class="nav-btn nav-btn-primary nav-btn-sm" id="navOpenMsgToAdmin">관리자에게 쪽지</button></div>`);
    return parts.join('') || '<div class="nav-muted">새 쪽지가 없습니다.</div>';
  }

  function bindEditorInboxClicks(container) {
    container.querySelectorAll('[data-nav-dismiss-portal]').forEach(btn => {
      btn.addEventListener('click', () => {
        try { sessionStorage.setItem(NAV_PORTAL_DISMISS_KEY, btn.getAttribute('data-nav-dismiss-portal') || '1'); } catch (e) {}
        reloadEditorInboxPanel();
        refreshEditorInboxBadge();
      });
    });
    container.querySelectorAll('[data-nav-dismiss-reject]').forEach(btn => {
      btn.addEventListener('click', async () => {
        const id = btn.getAttribute('data-nav-dismiss-reject');
        const tok = window.getEditorToken ? window.getEditorToken() : '';
        try {
          const res = await fetch('/api/assignee/reject-events/' + id + '/dismiss', {
            method: 'POST', headers: { 'X-Editor-Token': tok, 'Content-Type': 'application/json' }
          });
          const data = await res.json().catch(() => ({}));
          if (!res.ok) throw new Error(data.error || '실패');
          reloadEditorInboxPanel();
          refreshEditorInboxBadge();
        } catch (e) { toastNav(e.message || '실패', 'error'); }
      });
    });
    container.querySelectorAll('[data-nav-dismiss-reject-batch]').forEach(btn => {
      btn.addEventListener('click', async () => {
        const batchId = btn.getAttribute('data-nav-dismiss-reject-batch') || '';
        const tok = window.getEditorToken ? window.getEditorToken() : '';
        try {
          const res = await fetch('/api/assignee/reject-events/dismiss-batch', {
            method: 'POST',
            headers: { 'X-Editor-Token': tok, 'Content-Type': 'application/json' },
            body: JSON.stringify({ batchId })
          });
          const data = await res.json().catch(() => ({}));
          if (!res.ok) throw new Error(data.error || '실패');
          reloadEditorInboxPanel();
          refreshEditorInboxBadge();
        } catch (e) { toastNav(e.message || '실패', 'error'); }
      });
    });
    container.querySelectorAll('[data-nav-reply-admin]').forEach(btn => {
      btn.addEventListener('click', () => {
        const nid = btn.getAttribute('data-nav-reply-admin');
        closeInboxPopovers();
        openMsgToAdminModal(nid);
      });
    });
    container.querySelectorAll('[data-nav-dismiss-admin]').forEach(btn => {
      btn.addEventListener('click', async () => {
        const id = btn.getAttribute('data-nav-dismiss-admin');
        const tok = window.getEditorToken ? window.getEditorToken() : '';
        try {
          const res = await fetch('/api/assignee/admin-notices/' + id + '/dismiss', {
            method: 'POST', headers: { 'X-Editor-Token': tok, 'Content-Type': 'application/json' }
          });
          const data = await res.json().catch(() => ({}));
          if (!res.ok) throw new Error(data.error || '실패');
          reloadEditorInboxPanel();
          refreshEditorInboxBadge();
        } catch (e) { toastNav(e.message || '실패', 'error'); }
      });
    });
    const o = container.querySelector('#navOpenMsgToAdmin');
    if (o) o.addEventListener('click', () => { closeInboxPopovers(); openMsgToAdminModal(); });
  }

  async function reloadEditorInboxPanel() {
    const panel = document.getElementById('nav-editor-inbox');
    const tok = window.getEditorToken ? window.getEditorToken() : '';
    if (!panel || !tok) return;
    try {
      const res = await fetch('/api/assignee/inbox-summary', { headers: { 'X-Editor-Token': tok } });
      const d = await res.json();
      panel.innerHTML = '<div class="nav-inbox-inner">' + renderEditorInboxHtml(d) + '</div>';
      bindEditorInboxClicks(panel);
    } catch (e) {
      panel.innerHTML = '<div class="nav-muted">불러오기 실패</div>';
    }
  }

  function toggleEditorInbox(ev) {
    ensureNavOverlays();
    const panel = document.getElementById('nav-editor-inbox');
    const back = document.getElementById('nav-inbox-backdrop');
    if (!panel) return;
    if (panel.style.display === 'block') {
      closeInboxPopovers();
      return;
    }
    closeInboxPopovers();
    panel.style.display = 'block';
    if (back) back.style.display = 'block';
    positionPopover(panel, ev && ev.currentTarget ? ev.currentTarget : document.getElementById('navEditorInboxBtn'));
    reloadEditorInboxPanel();
  }

  function renderAdminInboxHtml(d) {
    const parts = [];
    const pn = d.portalNotice || {};
    if (pn.text && String(pn.text).trim()) {
      try {
        const dis = sessionStorage.getItem(NAV_PORTAL_DISMISS_KEY);
        if (!dis || dis !== String(pn.at || '')) {
          parts.push(`<div class="nav-inbox-section"><div class="nav-inbox-h">전체 쪽지</div><div class="nav-inbox-p">${esc(pn.text)}</div>
            <button type="button" class="nav-btn nav-btn-sm" data-nav-dismiss-portal="${esc(pn.at || '')}">확인</button></div>`);
        }
      } catch (e) {
        parts.push(`<div class="nav-inbox-section"><div class="nav-inbox-h">전체 쪽지</div><div class="nav-inbox-p">${esc(pn.text)}</div>
            <button type="button" class="nav-btn nav-btn-sm" data-nav-dismiss-portal="${esc(pn.at || '')}">확인</button></div>`);
      }
    }
    const msgs = d.fromAssignees || [];
    if (msgs.length) {
      parts.push('<div class="nav-inbox-section"><div class="nav-inbox-h">담당자 메시지</div>');
      msgs.forEach(x => {
        const who = esc((x.senderTeamName || '') + ' / ' + (x.senderAssigneeName || ''));
        parts.push(`<div class="nav-inbox-item"><div class="nav-inbox-meta">${who}</div><div class="nav-inbox-p">${esc(x.body)}</div>
          <div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:8px;">
            <button type="button" class="nav-btn nav-btn-sm nav-btn-primary" data-nav-reply-to-assignee="${x.id}">답장</button>
            <button type="button" class="nav-btn nav-btn-sm" data-nav-dismiss-msg="${x.id}">확인</button>
          </div></div>`);
      });
      parts.push('</div>');
    }
    return parts.join('') || '<div class="nav-muted">새 쪽지가 없습니다.</div>';
  }

  async function reloadAdminInboxPanel() {
    const panel = document.getElementById('nav-admin-inbox');
    if (!panel || !window.adminFetch) return;
    try {
      const res = await window.adminFetch('/api/config/admin-inbox-summary');
      const d = await res.json();
      panel.innerHTML = '<div class="nav-inbox-inner">' + renderAdminInboxHtml(d) + '</div>';
      panel.querySelectorAll('[data-nav-dismiss-portal]').forEach(btn => {
        btn.addEventListener('click', () => {
          try { sessionStorage.setItem(NAV_PORTAL_DISMISS_KEY, btn.getAttribute('data-nav-dismiss-portal') || '1'); } catch (e) {}
          reloadAdminInboxPanel();
          refreshAdminInboxBadge();
        });
      });
      panel.querySelectorAll('[data-nav-dismiss-msg]').forEach(btn => {
        btn.addEventListener('click', async () => {
          const id = btn.getAttribute('data-nav-dismiss-msg');
          try {
            const res2 = await window.adminFetch('/api/config/messages-from-assignees/' + id + '/dismiss', { method: 'POST', body: '{}' });
            const data = await res2.json().catch(() => ({}));
            if (!res2.ok) throw new Error(data.error || '실패');
            reloadAdminInboxPanel();
            refreshAdminInboxBadge();
          } catch (e) { toastNav(e.message || '실패', 'error'); }
        });
      });
      panel.querySelectorAll('[data-nav-reply-to-assignee]').forEach(btn => {
        btn.addEventListener('click', () => {
          const id = btn.getAttribute('data-nav-reply-to-assignee');
          closeInboxPopovers();
          openReplyFromAdminModal(id);
        });
      });
    } catch (e) {
      panel.innerHTML = '<div class="nav-muted">불러오기 실패</div>';
    }
  }

  function toggleAdminInbox(ev) {
    ensureNavOverlays();
    const panel = document.getElementById('nav-admin-inbox');
    const back = document.getElementById('nav-inbox-backdrop');
    if (!panel) return;
    if (panel.style.display === 'block') {
      closeInboxPopovers();
      return;
    }
    closeInboxPopovers();
    panel.style.display = 'block';
    if (back) back.style.display = 'block';
    positionPopover(panel, ev && ev.currentTarget ? ev.currentTarget : document.getElementById('navAdminInboxBtn'));
    reloadAdminInboxPanel();
  }

  async function loadAssigneesForNoticePicker() {
    const mount = document.getElementById('navNoticeAssigneeMount');
    if (!mount || !window.adminFetch || !window.RepoSelect) {
      if (!window.RepoSelect) toastNav('repo-select.js 로드 필요', 'error');
      return;
    }
    try {
      const res = await window.adminFetch('/api/config/it-assignees?page=0&size=500&sortBy=teamName&sortDir=asc');
      const data = await res.json();
      const items = (data.assignees || []).map(a => ({
        value: String(a.id),
        label: (a.teamName || '') + ' / ' + (a.assigneeName || '')
      }));
      if (_navAssigneeSelectInst) {
        try { _navAssigneeSelectInst.destroy(); } catch (e) {}
        _navAssigneeSelectInst = null;
      }
      mount.innerHTML = '';
      _navAssigneeSelectInst = window.RepoSelect.mountLarge(mount, { items, placeholder: '담당자' });
    } catch (e) {
      toastNav('담당자 목록 로드 실패', 'error');
    }
  }

  function openSendNoticeModal() {
    ensureNavOverlays();
    const m = document.getElementById('navSendNoticeModal');
    if (!m) return;
    const ta = document.getElementById('navNoticeBody');
    if (ta) ta.value = '';
    const w = document.getElementById('navNoticeAssigneeWrap');
    if (w) w.style.display = 'none';
    const r0 = document.querySelector('input[name="navSendScope"][value="whole"]');
    if (r0) r0.checked = true;
    m.style.display = 'flex';
  }

  function closeSendNoticeModal() {
    const m = document.getElementById('navSendNoticeModal');
    if (m) m.style.display = 'none';
  }

  async function submitSendNotice() {
    if (!window.adminFetch) return;
    const ta = document.getElementById('navNoticeBody');
    const text = ta ? String(ta.value || '').trim() : '';
    if (!text) { toastNav('내용을 입력하세요.', 'error'); return; }
    const scope = (document.querySelector('input[name="navSendScope"]:checked') || {}).value || 'whole';
    try {
      if (scope === 'whole') {
        const res = await window.adminFetch('/api/config/portal-notice', {
          method: 'POST', body: JSON.stringify({ text })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || '실패');
        toastNav('전체 쪽지가 갱신되었습니다.', 'success');
      } else {
        if (!_navAssigneeSelectInst) { toastNav('담당자를 선택하세요.', 'error'); return; }
        const sel = _navAssigneeSelectInst.getSelected().map(s => parseInt(s, 10)).filter(n => !isNaN(n));
        if (!sel.length) { toastNav('담당자를 한 명 이상 선택하세요.', 'error'); return; }
        const res = await window.adminFetch('/api/config/assignee-notices', {
          method: 'POST', body: JSON.stringify({ assigneeIds: sel, text })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || '실패');
        toastNav('발송 ' + (data.sent != null ? data.sent : '') + '건', 'success');
      }
      closeSendNoticeModal();
    } catch (e) { toastNav(e.message || '실패', 'error'); }
  }

  function openMsgToAdminModal(replyToAdminNoticeId) {
    ensureNavOverlays();
    const m = document.getElementById('navMsgToAdminModal');
    const ta = document.getElementById('navMsgToAdminBody');
    if (ta) ta.value = '';
    const hid = document.getElementById('navMsgToAdminReplyToId');
    const rid = replyToAdminNoticeId != null && String(replyToAdminNoticeId).trim() !== ''
      ? String(replyToAdminNoticeId).trim()
      : '';
    if (hid) hid.value = rid;
    const headEl = document.querySelector('#navMsgToAdminModal .nav-modal-head strong');
    if (headEl) {
      headEl.textContent = rid ? '관리자 쪽지에 답장' : '관리자에게 쪽지';
    }
    if (m) m.style.display = 'flex';
  }

  function closeMsgToAdminModal() {
    const m = document.getElementById('navMsgToAdminModal');
    if (m) m.style.display = 'none';
    const hid = document.getElementById('navMsgToAdminReplyToId');
    if (hid) hid.value = '';
    const headEl = document.querySelector('#navMsgToAdminModal .nav-modal-head strong');
    if (headEl) headEl.textContent = '관리자에게 쪽지';
  }

  function openReplyFromAdminModal(assigneeMessageId) {
    ensureNavOverlays();
    const m = document.getElementById('navReplyFromAdminModal');
    const hid = document.getElementById('navReplyAssigneeMessageId');
    const ta = document.getElementById('navReplyFromAdminBody');
    if (hid) hid.value = assigneeMessageId != null ? String(assigneeMessageId) : '';
    if (ta) ta.value = '';
    if (m) m.style.display = 'flex';
  }

  function closeReplyFromAdminModal() {
    const m = document.getElementById('navReplyFromAdminModal');
    if (m) m.style.display = 'none';
    const hid = document.getElementById('navReplyAssigneeMessageId');
    if (hid) hid.value = '';
    const ta = document.getElementById('navReplyFromAdminBody');
    if (ta) ta.value = '';
  }

  async function submitReplyFromAdmin() {
    if (!window.adminFetch) return;
    const hid = document.getElementById('navReplyAssigneeMessageId');
    const ta = document.getElementById('navReplyFromAdminBody');
    const mid = hid ? parseInt(hid.value || '0', 10) : 0;
    const text = ta ? String(ta.value || '').trim() : '';
    if (!mid || isNaN(mid)) { toastNav('메시지를 찾을 수 없습니다.', 'error'); return; }
    if (!text) { toastNav('내용을 입력하세요.', 'error'); return; }
    try {
      const res = await window.adminFetch('/api/config/reply-to-assignee-message', {
        method: 'POST',
        body: JSON.stringify({ assigneeMessageId: mid, text })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || '실패');
      toastNav('답장을 보냈습니다.', 'success');
      closeReplyFromAdminModal();
      reloadAdminInboxPanel();
      refreshAdminInboxBadge();
    } catch (e) { toastNav(e.message || '실패', 'error'); }
  }

  async function submitMsgToAdmin() {
    const tok = window.getEditorToken ? window.getEditorToken() : '';
    const ta = document.getElementById('navMsgToAdminBody');
    const text = ta ? String(ta.value || '').trim() : '';
    if (!text) { toastNav('내용을 입력하세요.', 'error'); return; }
    const hid = document.getElementById('navMsgToAdminReplyToId');
    const ridStr = hid ? String(hid.value || '').trim() : '';
    const payload = { text };
    if (ridStr) {
      const n = parseInt(ridStr, 10);
      if (!isNaN(n)) payload.replyToAdminNoticeId = n;
    }
    try {
      const res = await fetch('/api/assignee/message-to-admin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Editor-Token': tok },
        body: JSON.stringify(payload)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || '실패');
      toastNav('전송되었습니다.', 'success');
      closeMsgToAdminModal();
    } catch (e) { toastNav(e.message || '실패', 'error'); }
  }

  // ─── 일반사용자(IT담당자) 로그인 슬롯 (전 페이지) ─────────────────────────
  function renderAssigneeSlot() {
    const slot = document.getElementById('nav-assignee-slot');
    if (!slot) return;
    if (window.AuthState && window.AuthState.loggedIn) {
      slot.innerHTML = '';
      return;
    }
    let ed = false;
    try { ed = window.isEditorLoggedIn && window.isEditorLoggedIn(); } catch (e) {}
    if (ed) {
      slot.innerHTML = `
        <span class="nav-assignee-indicator" id="navAssigneeLabel" title="일반 사용자 세션">👤 …</span>
        <button type="button" class="nav-btn" id="navEditorInboxBtn" title="쪽지함">📝<span class="nav-inbox-badge" id="navEditorInboxBadge" style="display:none;"></span></button>
        <button type="button" class="nav-btn" onclick="window.assigneeLogout && window.assigneeLogout()">일반 로그아웃</button>`;
      const btn = document.getElementById('navEditorInboxBtn');
      if (btn) btn.addEventListener('click', (ev) => toggleEditorInbox(ev));
      const tok = window.getEditorToken ? window.getEditorToken() : '';
      if (tok) {
        fetch('/api/assignee/auth/check', { headers: { 'X-Editor-Token': tok } })
          .then(r => (r.ok ? r.json() : null))
          .then(d => {
            const el = document.getElementById('navAssigneeLabel');
            if (el && d && d.valid && (d.teamName || d.assigneeName)) {
              el.textContent = '👤 ' + esc(d.teamName || '') + ' / ' + esc(d.assigneeName || '');
            }
          })
          .catch(() => {});
      }
      setTimeout(() => { refreshEditorInboxBadge(); }, 0);
    } else {
      slot.innerHTML =
        '<button type="button" class="nav-btn" onclick="window.openAssigneeLoginModal && window.openAssigneeLoginModal()">👤 일반사용자 로그인</button>';
    }
  }

  // ─── 관리자 인디케이터/버튼 렌더 ─────────────────────────
  function renderAdminSlot() {
    const slot = document.getElementById('nav-admin-slot');
    if (!slot) return;
    const A = window.AuthState;
    if (A && A.loggedIn) {
      slot.innerHTML = `
        <button type="button" class="nav-btn" onclick="window.AppNav.openSendNoticeModal && window.AppNav.openSendNoticeModal()">📨 쪽지 발송</button>
        <button type="button" class="nav-btn" id="navAdminInboxBtn" title="수신함">📝<span class="nav-inbox-badge" id="navAdminInboxBadge" style="display:none;"></span></button>
        <span class="admin-indicator" title="관리자 세션">👤 관리자 <small>· 남은 ${esc(A.fmtRemaining())}</small></span>
        <button class="nav-btn" onclick="AuthState.logout()">로그아웃</button>
      `;
      const ib = document.getElementById('navAdminInboxBtn');
      if (ib) ib.addEventListener('click', (ev) => toggleAdminInbox(ev));
      setTimeout(() => { refreshAdminInboxBadge(); }, 0);
    } else {
      slot.innerHTML = `
        <button class="nav-btn nav-btn-primary" onclick="adminLogin && adminLogin()">🔑 관리자 로그인</button>
      `;
    }
  }

  // ─── data-admin-only 가시성 관리 ─────────────────────────
  function applyAdminVisibility() {
    const loggedIn = window.AuthState && window.AuthState.loggedIn;
    document.querySelectorAll('[data-admin-only]').forEach(el => {
      el.style.display = loggedIn ? '' : 'none';
    });
  }

  // ─── 부팅 ───────────────────────────────────────────────
  function boot() {
    ensureNavOverlays();
    render();
    applyAdminVisibility();
    loadSyncWarnings();
    loadOpsDigestBanner();
    loadApmMatchBanner();
    loadExtractIssueBanner();
    window.addEventListener('auth:change', () => {
      renderAdminSlot();
      renderAssigneeSlot();
      applyAdminVisibility();
      loadApmMatchBanner();
      loadExtractIssueBanner();
    });
    window.addEventListener('editor-auth:change', () => {
      renderAssigneeSlot();
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  // 전역 노출 (필요 시 페이지가 재렌더 호출 가능)
  window.AppNav = {
    SEGMENTS,
    render,
    renderAdminSlot,
    renderAssigneeSlot,
    loadSyncWarnings,
    renderSyncWarnings,
    loadOpsDigestBanner,
    renderOpsDigestBanner,
    openSendNoticeModal,
    closeSendNoticeModal,
    submitSendNotice,
    toggleEditorInbox,
    toggleAdminInbox,
    closeInboxPopovers,
    openMsgToAdminModal,
    closeMsgToAdminModal,
    submitMsgToAdmin,
    openReplyFromAdminModal,
    closeReplyFromAdminModal,
    submitReplyFromAdmin,
    /** URL현황 등에서 반려 일괄 확인 후 배지·쪽지 패널 동기화 */
    refreshEditorInbox: async () => {
      await refreshEditorInboxBadge();
      await reloadEditorInboxPanel();
    }
  };
})();
