(function () {
  /**
   * Status Guide (공통)
   * - viewer.html: "상태 구분 안내" 본문은 details summary(바깥 pill)로만 접기
   * - dashboard/index.html: "상태 구분(요약)"을 설명형 문구로 + "상세 보기" 모달
   */
  const GUIDE_ID_THRESHOLD = 'guideReviewThreshold';
  const STORAGE_KEY_DASH_GUIDE_DISMISSED = 'dashboard.statusGuide.dismissed'; // reserved
  const THEME_ATTR = 'data-theme';
  const DEFAULT_THRESHOLD = 3;
  let _cachedThreshold = null;

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  function getThresholdText(n) {
    const nn = Number(n);
    if (!Number.isFinite(nn) || nn <= 0) return String(DEFAULT_THRESHOLD);
    return String(nn);
  }

  async function loadThreshold() {
    if (_cachedThreshold != null) return _cachedThreshold;
    try {
      const r = await fetch('/api/config/global');
      if (!r.ok) throw new Error('global config fetch failed');
      const g = await r.json();
      const n = Number(g && g.reviewThreshold);
      _cachedThreshold = Number.isFinite(n) && n > 0 ? n : DEFAULT_THRESHOLD;
      return _cachedThreshold;
    } catch (e) {
      _cachedThreshold = DEFAULT_THRESHOLD;
      return _cachedThreshold;
    }
  }

  function applyThreshold(root, threshold) {
    if (!root) return;
    const nodes = root.querySelectorAll('#' + GUIDE_ID_THRESHOLD);
    const v = getThresholdText(threshold);
    nodes.forEach(n => { n.textContent = v; });
  }

  function detailInnerHtml() {
    // NOTE: viewer/dashboard 공통으로 쓰기 위해 색상은 inline 유지(다크모드 오버라이드가 이미 프로젝트 전역에 있음)
    // viewer.html 은 카드 summary(details)의 접기만 사용 — 박스 내부 중복 접기 버튼 없음
    return `
      <div>
        <span style="background:#dcfce7;padding:1px 6px;border-radius:3px;">사용</span> 실제 또는 현업검토 결과 사용중인 URL &nbsp;|&nbsp;
        <span style="background:#f1f5f9;padding:1px 6px;border-radius:3px;color:#64748b;">차단완료</span> @Deprecated + javadoc표기 + UnsupportedOperationException
      </div>
      <div style="margin-top:8px;padding-top:8px;border-top:1px dashed #7dd3fc;">
        <strong style="color:#991b1b;">차단대상 잔여</strong><br>
        URL분석현황에서 <strong>①-① 차단대상</strong>과 <strong>①-② 담당자 판단</strong>을 합친 그룹입니다.<br>
        <span style="background:#fee2e2;padding:1px 6px;border-radius:3px;color:#991b1b;">①-① 차단대상</span> 호출 0건 + 소스변경 1년 경과 &nbsp;|&nbsp;
        <span style="background:#ffedd5;padding:1px 6px;border-radius:3px;color:#ea580c;">①-② 담당자 판단</span> IT담당자 검토결과 미사용·업무종료 등 수동 지정
      </div>
      <div style="margin-top:8px;padding-top:8px;border-top:1px dashed #7dd3fc;">
        <strong style="color:#a16207;">차단대상 제외건</strong><br>
        <strong>①-③ 현업요청 제외대상</strong>과 <strong>①-④ 사용으로 변경</strong>을 합친 그룹입니다.<br>
        <span style="background:#f3e8ff;padding:1px 6px;border-radius:3px;color:#7c3aed;">①-③ 현업요청 제외대상</span> 현업요청에 따른 차단 제외 &nbsp;|&nbsp;
        <span style="background:#ecfccb;padding:1px 6px;border-radius:3px;color:#3f6212;">①-④ 사용으로 변경</span> 차단대상이었으나 담당자 판단으로 사용 유지
      </div>
      <div style="margin-top:8px;padding-top:8px;border-top:1px dashed #7dd3fc;">
        <strong style="color:#c2410c;">검토대상</strong><br>
        <strong>②-①</strong>·<strong>②-②</strong>·<strong>②-③</strong> 추가검토대상 leaf 를 합친 그룹입니다.<br>
        <span style="background:#fef3c7;padding:1px 6px;border-radius:3px;color:#92400e;">②-① 호출0건+변경있음</span> 호출 0건 + 소스변경 1년 미만 &nbsp;|&nbsp;
        <span style="background:#ffedd5;padding:1px 6px;border-radius:3px;color:#9a3412;">②-② 호출 N건 이하+변경없음</span> 호출 1~<span id="${GUIDE_ID_THRESHOLD}">${DEFAULT_THRESHOLD}</span>건 + 1년 경과 (N=설정 reviewThreshold) &nbsp;|&nbsp;
        <span style="background:#ecfccb;padding:1px 6px;border-radius:3px;color:#3f6212;">②-③ 사용으로 변경</span> 추가검토대상에서 담당자 판단으로 사용 유지
      </div>
    `;
  }

  function compactHtml(options) {
    const o = options || {};
    const linkText = o.linkText || '상세보기';
    const linkAction = o.linkAction || 'modal';
    const href = o.href || '/url-viewer/viewer.html';
    const open = (linkAction === 'link')
      ? `<a href="${esc(href)}" class="sg-compact-btn" title="상태 구분 안내 확인">${esc(linkText)}</a>`
      : `<button type="button" class="sg-compact-btn" data-sg-open="1" title="상태 구분 안내 보기">${esc(linkText)}</button>`;

    return `
      <div class="sg-compact-head">
        <b class="sg-compact-title">상태구분</b>
        ${open}
      </div>
      <div style="margin-top:6px;"></div>
      <span style="color:#dc2626;font-weight:800;">차단대상 잔여</span>는 <b>차단 대상으로 남아있는 건</b>입니다. (①-① + ①-②)<br>
      <span style="color:#eab308;font-weight:800;">차단대상 제외건</span>은 <b>차단에서 제외/사용으로 유지</b>된 건입니다. (①-③ + ①-④)<br>
      <span style="color:#d97706;font-weight:800;">검토대상</span>은 <b>추가 확인이 필요한 후보군</b>입니다. (②-① + ②-② + ②-③)
    `;
  }

  function ensureModalStyle() {
    if (document.getElementById('sg-modal-style')) return;
    const st = document.createElement('style');
    st.id = 'sg-modal-style';
    st.textContent = `
      .sg-compact-head{display:flex;align-items:center;gap:8px;justify-content:flex-start;flex-wrap:wrap;}
      .sg-compact-title{font-weight:900;color:var(--text,#1e293b);}
      .sg-modal-overlay{position:fixed;inset:0;background:rgba(15,23,42,.55);z-index:9999;display:none;align-items:center;justify-content:center;padding:16px;}
      .sg-modal-overlay.show{display:flex;}
      .sg-modal{width:min(860px, 96vw);max-height:min(82vh, 720px);overflow:auto;background:var(--card,#fff);border:1px solid var(--border,#e2e8f0);border-radius:12px;box-shadow:0 12px 40px rgba(0,0,0,.22);}
      .sg-modal-header{display:flex;align-items:center;gap:10px;padding:12px 14px;border-bottom:1px solid var(--border,#e2e8f0);}
      .sg-modal-title{font-weight:900;color:var(--text,#1e293b);font-size:13px;}
      .sg-modal-close{margin-left:auto;border:1px solid var(--border,#e2e8f0);background:#f8fafc;color:var(--text-muted,#64748b);border-radius:8px;padding:6px 10px;cursor:pointer;font-weight:800;font-size:12px;}
      .sg-modal-close:hover{background:#eff6ff;}
      .sg-modal-body{padding:14px;}
      .sg-guide-box{background:#f0f9ff;border:1px solid #bae6fd;border-radius:10px;padding:12px 14px;font-size:11px;color:#0c4a6e;line-height:1.75;}
      .sg-guide-actions{margin-top:10px;display:flex;justify-content:flex-end;}
      .sg-link-btn{font-size:11px;color:var(--primary,#3b82f6);text-decoration:none;padding:4px 10px;border:1px solid var(--primary,#3b82f6);border-radius:999px;font-weight:800;}
      .sg-link-btn:hover{text-decoration:underline;}
      /* 대시보드 "차단 배포일자"의 range-btn 과 톤/크기 통일 */
      .sg-compact-btn{
        border:1px solid var(--border,#e2e8f0);
        background:#f8fafc;
        color:var(--text-muted,#64748b);
        border-radius:999px;
        padding:3px 10px;
        font-size:11px;
        font-weight:700;
        cursor:pointer;
        white-space:nowrap;
        user-select:none;
        line-height:1.2;
        transition:background .12s,color .12s,border-color .12s,transform .08s ease;
      }
      .sg-compact-btn:hover{
        background:#eff6ff;
        border-color:#bfdbfe;
        color:#1d4ed8;
      }
      .sg-compact-btn:active{transform:translateY(1px);}
      .sg-compact-btn:focus-visible{outline:2px solid rgba(59,130,246,.45);outline-offset:2px;}
      [data-theme="dark"] .sg-compact-btn{
        background:#293548;
        border-color:var(--border,#334155);
        color:var(--text-muted,#94a3b8);
      }
      [data-theme="dark"] .sg-compact-btn:hover{
        background:#1e3a5f;
        border-color:#1d4ed8;
        color:#93c5fd;
      }
    `;
    document.head.appendChild(st);
  }

  function ensureModal() {
    let overlay = document.getElementById('sgModalOverlay');
    if (overlay) return overlay;
    ensureModalStyle();
    overlay = document.createElement('div');
    overlay.id = 'sgModalOverlay';
    overlay.className = 'sg-modal-overlay';
    overlay.innerHTML = `
      <div class="sg-modal" role="dialog" aria-modal="true" aria-label="상태 구분 안내">
        <div class="sg-modal-header">
          <div class="sg-modal-title">상태 구분 안내</div>
          <button type="button" class="sg-modal-close" data-sg-close="1">닫기</button>
        </div>
        <div class="sg-modal-body">
          <div class="sg-guide-box" id="sgModalGuideBox"></div>
          <div class="sg-guide-actions">
            <a class="sg-link-btn" href="/url-viewer/viewer.html">URL분석현황으로 이동 →</a>
          </div>
        </div>
      </div>
    `;
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) closeModal();
    });
    overlay.querySelector('[data-sg-close="1"]').addEventListener('click', closeModal);
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && overlay.classList.contains('show')) closeModal();
    });
    document.body.appendChild(overlay);
    return overlay;
  }

  function openModal() {
    const overlay = ensureModal();
    const box = overlay.querySelector('#sgModalGuideBox');
    if (box) box.innerHTML = detailInnerHtml();
    overlay.classList.add('show');
    // threshold 최신화
    loadThreshold().then(n => applyThreshold(overlay, n));
  }

  function closeModal() {
    const overlay = document.getElementById('sgModalOverlay');
    if (!overlay) return;
    overlay.classList.remove('show');
  }

  function mountCompact(selectorOrEl, options) {
    const el = (typeof selectorOrEl === 'string') ? document.querySelector(selectorOrEl) : selectorOrEl;
    if (!el) return;
    // compact 버튼/헤더 스타일은 모달 오픈 여부와 무관하게 항상 적용돼야 함
    ensureModalStyle();
    el.innerHTML = compactHtml(options);
    el.querySelectorAll('[data-sg-open="1"]').forEach(a => {
      a.addEventListener('click', (e) => {
        e.preventDefault();
        openModal();
      });
    });
  }

  function mountDetailInto(selectorOrEl) {
    const el = (typeof selectorOrEl === 'string') ? document.querySelector(selectorOrEl) : selectorOrEl;
    if (!el) return;
    ensureModalStyle();
    el.innerHTML = detailInnerHtml();
    loadThreshold().then(n => applyThreshold(el, n));
  }

  // expose
  window.StatusGuide = {
    mountCompact,
    mountDetailInto,
    openModal,
    closeModal,
    loadThreshold
  };
})();

