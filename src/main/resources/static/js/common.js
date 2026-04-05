/* ═══════════════════════════════════════════════════════════════
 * URL Viewer — 공통 JavaScript
 * ═══════════════════════════════════════════════════════════════ */

// ─── HTML 이스케이프 ────────────────────────────────────────
function esc(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

// ─── 숫자 포맷 ──────────────────────────────────────────────
function fmt(n) { return (n == null ? 0 : n).toLocaleString(); }

function fmtBytes(n) {
  if (n == null) return '-';
  const abs = Math.abs(n);
  if (abs < 1024) return n + ' B';
  if (abs < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  if (abs < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
  return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

// ─── 날짜 포맷 ──────────────────────────────────────────────
function fmtDt(s) { if (!s || s === '-') return '-'; return s.replace('T', ' ').substring(0, 16); }

// "MM-DD" / "YYYY-MM-DD" → "M월D일"
function fmtMD(s) {
  if (!s) return '';
  const f = v => { const m = v.match(/(\d{1,2})-(\d{1,2})$/); return m ? `${parseInt(m[1])}월${parseInt(m[2])}일` : v; };
  if (s.includes('~')) { const [a, b] = s.split('~'); return f(a) + '~' + f(b); }
  return f(s);
}

// "YYYY-MM-DD" → { year: "26년", md: "4월5일", dow: "(월)", dowIdx: 1 }
function fmtYearMD(s) {
  if (!s) return { year: '', md: '', dow: '', dowIdx: -1 };
  const m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/);
  if (!m) return { year: '', md: fmtMD(s), dow: '', dowIdx: -1 };
  const d = new Date(parseInt(m[1]), parseInt(m[2]) - 1, parseInt(m[3]));
  const dowIdx = d.getDay();
  const dowNames = ['일', '월', '화', '수', '목', '금', '토'];
  return {
    year: `${m[1].slice(2)}년`,
    md: `${parseInt(m[2])}월${parseInt(m[3])}일`,
    dow: `(${dowNames[dowIdx]})`,
    dowIdx
  };
}

// ─── Toast (스택형) ─────────────────────────────────────────
function showToast(msg, type = 'success', duration = 3000) {
  let stack = document.getElementById('toastStack');
  if (!stack) {
    stack = document.createElement('div');
    stack.id = 'toastStack';
    document.body.appendChild(stack);
  }
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = msg;
  stack.appendChild(el);
  // force reflow then show
  requestAnimationFrame(() => el.classList.add('show'));
  setTimeout(() => {
    el.classList.remove('show');
    setTimeout(() => el.remove(), 300);
  }, duration);
}

// ─── 관리자 인증 관련 ───────────────────────────────────────
function isAdmin() { return sessionStorage.getItem('isAdmin') === 'true'; }
function getAdminToken() { return sessionStorage.getItem('adminToken') || ''; }
function adminHeaders(extra = {}) {
  return Object.assign({
    'Content-Type': 'application/json',
    'X-Admin-Token': getAdminToken()
  }, extra);
}

async function reAuth() {
  const pw = prompt('관리자 토큰이 만료되었습니다. 비밀번호를 다시 입력하세요:');
  if (!pw) return false;
  try {
    const res = await fetch('/api/verify-password', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: pw })
    });
    const d = await res.json();
    if (d.valid) {
      sessionStorage.setItem('adminToken', d.token);
      showToast('재인증 성공', 'success');
      return true;
    }
    showToast('비밀번호가 일치하지 않습니다.', 'error');
    return false;
  } catch (e) { showToast('재인증 실패', 'error'); return false; }
}

// 401 자동 재인증 래퍼
async function adminFetch(url, options = {}) {
  options.headers = Object.assign({
    'Content-Type': 'application/json',
    'X-Admin-Token': getAdminToken()
  }, options.headers || {});
  let res = await fetch(url, options);
  if (res.status === 401) {
    const ok = await reAuth();
    if (!ok) return res;
    options.headers['X-Admin-Token'] = getAdminToken();
    res = await fetch(url, options);
  }
  return res;
}

// ─── 상태 배지 ──────────────────────────────────────────────
function statusBadge(status) {
  const cls = {
    '사용': 'st-use', '차단완료': 'st-done', '삭제': 'st-del',
    '최우선 차단대상': 'bt-top', '후순위 차단대상': 'bt-low', '검토필요 차단대상': 'bt-rev'
  };
  const c = cls[status] || 'st-use';
  return `<span class="status-badge ${c}">${esc(status || '사용')}</span>`;
}
