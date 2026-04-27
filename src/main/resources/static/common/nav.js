/* ═══════════════════════════════════════════════════════════════
 * URL Viewer — 공통 네비게이션 (선언적 렌더링)
 *
 * 사용법: 페이지 <head> 에 아래 2개 meta 태그 추가, <body> 상단에
 *   <div id="nav-container"></div> 배치.
 *     <meta name="nav-segment" content="url-viewer">
 *     <meta name="nav-page"    content="viewer">
 *   (메타 없으면 현재 URL 기준 자동 매칭)
 *
 * 페이지 추가/변경: 아래 SEGMENTS 배열만 수정.
 * adminOnly: true 항목은 AuthState 구독해 자동 숨김/표시.
 * ═══════════════════════════════════════════════════════════════ */
(function () {
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
        { id: 'extract',       label: '🔍 URL 분석',         href: '/url-viewer/extract.html', adminOnly: true },
        { id: 'workflow',      label: '🗺️ 업무 흐름',       href: '/url-viewer/workflow.html' }
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
        { id: 'settings-home', label: '⚙️ 설정 관리',        href: '/settings/' }
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
        <a class="brand" href="/dashboard/"><h1>IT소스 관리포털 <small>— IT Source Management Portal</small></h1></a>
        <span class="brand-sub">${esc(brandSub)}</span>
        <div class="utils">
          <button class="dark-toggle" onclick="toggleDarkMode && toggleDarkMode()">🌙 다크모드</button>
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
      <div id="sync-warning-container"></div>
    `;

    renderAdminSlot();
  }

  // ─── 배치 Git 동기화 실패 경고 배너 ──────────────────────
  function fmtSyncTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function renderSyncWarnings(list) {
    const slot = document.getElementById('sync-warning-container');
    if (!slot) return;
    if (!Array.isArray(list) || list.length === 0) {
      slot.innerHTML = '';
      return;
    }
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
  }

  function loadSyncWarnings() {
    fetch('/api/config/repos/sync-warnings', { credentials: 'same-origin' })
      .then(r => r.ok ? r.json() : [])
      .then(list => renderSyncWarnings(list))
      .catch(() => {});
  }

  // ─── 관리자 인디케이터/버튼 렌더 ─────────────────────────
  function renderAdminSlot() {
    const slot = document.getElementById('nav-admin-slot');
    if (!slot) return;
    const A = window.AuthState;
    if (A && A.loggedIn) {
      slot.innerHTML = `
        <span class="admin-indicator" title="관리자 세션">👤 관리자 <small>· 남은 ${esc(A.fmtRemaining())}</small></span>
        <button class="nav-btn" onclick="AuthState.logout()">로그아웃</button>
      `;
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
    render();
    applyAdminVisibility();
    loadSyncWarnings();
    window.addEventListener('auth:change', () => {
      renderAdminSlot();
      applyAdminVisibility();
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  // 전역 노출 (필요 시 페이지가 재렌더 호출 가능)
  window.AppNav = { SEGMENTS, render, renderAdminSlot, loadSyncWarnings, renderSyncWarnings };
})();
