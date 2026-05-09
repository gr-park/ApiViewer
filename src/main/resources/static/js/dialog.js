/* ═══════════════════════════════════════════════════════════════
 * 커스텀 다이얼로그 — prompt/confirm/alert 대체
 * 웹격리(RBI) 시스템에서 네이티브 팝업 차단 문제 해결
 *
 * 사용법:
 *   const pw = await customPrompt('비밀번호를 입력하세요:', {type:'password'});
 *   const ok = await customConfirm('삭제하시겠습니까?');
 *   await customAlert('완료되었습니다.');
 * ═══════════════════════════════════════════════════════════════ */

(function() {
  // 스타일 주입 (1회)
  const style = document.createElement('style');
  style.textContent = `
    .cdlg-overlay { position:fixed; inset:0; background:rgba(15,23,42,.5); z-index:10000; display:flex; align-items:center; justify-content:center; opacity:0; transition:opacity .15s; }
    .cdlg-overlay.show { opacity:1; }
    .cdlg-box { background:#fff; border-radius:12px; width:400px; max-width:90vw; box-shadow:0 8px 32px rgba(0,0,0,.25); overflow:hidden; transform:scale(.95); transition:transform .15s; }
    .cdlg-overlay.show .cdlg-box { transform:scale(1); }
    .cdlg-header { padding:16px 20px 0; font-size:15px; font-weight:700; color:#1e293b; }
    .cdlg-body { padding:14px 20px; font-size:13px; color:#334155; line-height:1.6; white-space:pre-wrap; }
    .cdlg-input { width:100%; padding:9px 12px; border:1px solid #e2e8f0; border-radius:6px; font-size:13px; outline:none; margin-top:8px; font-family:inherit; }
    .cdlg-input:focus { border-color:#3b82f6; box-shadow:0 0 0 2px rgba(59,130,246,.15); }
    .cdlg-footer { padding:12px 20px; display:flex; justify-content:flex-end; gap:8px; border-top:1px solid #e2e8f0; }
    .cdlg-btn { padding:8px 20px; border:none; border-radius:6px; font-size:13px; font-weight:600; cursor:pointer; font-family:inherit; transition:background .12s; }
    .cdlg-btn-cancel { background:#475569; color:#fff; }
    .cdlg-btn-cancel:hover { background:#334155; }
    .cdlg-btn-ok { background:#3b82f6; color:#fff; }
    .cdlg-btn-ok:hover { background:#2563eb; }
    .cdlg-btn-danger { background:#ef4444; color:#fff; }
    .cdlg-btn-danger:hover { background:#dc2626; }
  `;
  document.head.appendChild(style);

  function createDialog(type, message, options = {}) {
    return new Promise(resolve => {
      const overlay = document.createElement('div');
      overlay.className = 'cdlg-overlay';

      const title = options.title || (type === 'prompt' ? '입력' : type === 'confirm' ? '확인' : '알림');
      const okLabel = options.okLabel || '확인';
      const cancelLabel = options.cancelLabel || '취소';
      const inputType = options.type || 'text';
      const defaultValue = options.defaultValue || '';
      const danger = options.danger || false;
      const multiline = !!options.multiline;

      let inputHtml = '';
      if (type === 'prompt') {
        if (multiline) {
          const esc = (s) => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');
          inputHtml = `<textarea class="cdlg-input" id="cdlgInput" rows="4" placeholder="${esc(options.placeholder || '')}" autocomplete="off" style="resize:vertical;min-height:88px;">${esc(defaultValue)}</textarea>`;
        } else {
          inputHtml = `<input class="cdlg-input" id="cdlgInput" type="${inputType}" value="${defaultValue}" placeholder="${options.placeholder || ''}" autocomplete="off">`;
        }
      }

      let footerHtml = '';
      if (type === 'alert') {
        footerHtml = `<button class="cdlg-btn cdlg-btn-ok" id="cdlgOk">${okLabel}</button>`;
      } else {
        footerHtml = `
          <button class="cdlg-btn cdlg-btn-cancel" id="cdlgCancel">${cancelLabel}</button>
          <button class="cdlg-btn ${danger ? 'cdlg-btn-danger' : 'cdlg-btn-ok'}" id="cdlgOk">${okLabel}</button>`;
      }

      overlay.innerHTML = `
        <div class="cdlg-box">
          <div class="cdlg-header">${title}</div>
          <div class="cdlg-body">${message}${inputHtml}</div>
          <div class="cdlg-footer">${footerHtml}</div>
        </div>`;

      document.body.appendChild(overlay);
      requestAnimationFrame(() => overlay.classList.add('show'));

      const input = overlay.querySelector('#cdlgInput');
      const okBtn = overlay.querySelector('#cdlgOk');
      const cancelBtn = overlay.querySelector('#cdlgCancel');

      function close(result) {
        overlay.classList.remove('show');
        setTimeout(() => overlay.remove(), 150);
        resolve(result);
      }

      okBtn.addEventListener('click', () => {
        if (type === 'prompt') close(input ? input.value : '');
        else if (type === 'confirm') close(true);
        else close(true);
      });

      if (cancelBtn) {
        cancelBtn.addEventListener('click', () => {
          if (type === 'prompt') close(null);
          else close(false);
        });
      }

      // Enter/Escape 키 처리 (multiline prompt: Enter는 줄바꿈)
      overlay.addEventListener('keydown', e => {
        if (e.key === 'Enter' && type === 'prompt' && multiline && e.target === input) return;
        if (e.key === 'Enter') { e.preventDefault(); okBtn.click(); }
        if (e.key === 'Escape') { e.preventDefault(); if (cancelBtn) cancelBtn.click(); else okBtn.click(); }
      });

      // 포커스
      if (input) { setTimeout(() => input.focus(), 100); }
      else { setTimeout(() => okBtn.focus(), 100); }
    });
  }

  // 글로벌 함수 등록
  window.customPrompt = function(message, options) {
    return createDialog('prompt', message, options);
  };
  window.customConfirm = function(message, options) {
    return createDialog('confirm', message, options);
  };
  window.customAlert = function(message, options) {
    return createDialog('alert', message, options);
  };
})();
