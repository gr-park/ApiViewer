/**
 * LOCAi · ops_digest AI 출력용: 허용 인라인 태그만 유지하고 나머지는 이스케이프합니다.
 * (화이트리스트 — 외부 sanitizer 없음)
 *
 * 허용: strong, em, mark, b, i — 속성이 있으면 태그는 버리고 이스케이프(텍스트만 유지되는 효과는 아님: 전각을 이스케이프).
 */
(function (global) {
  'use strict';

  const ALLOWED = new Set(['strong', 'em', 'mark', 'b', 'i']);

  const OPEN_ALLOWED = /^<\s*(strong|em|mark|b|i)(\s[^>]*)?\s*>/i;
  const CLOSE_ALLOWED = /^<\s*\/\s*(strong|em|mark|b|i)\s*>/i;

  function escapeHtmlChars(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  /**
   * @param {*} raw
   * @returns {string} 안전한 HTML 조각(innerHTML)
   */
  function sanitizeLocAiRichHtml(raw) {
    let s = String(raw == null ? '' : raw);
    if (!s) return '';
    try {
      s = s.replace(/<!--[\s\S]*?-->/g, '');
      s = s.replace(/<\s*style\b[\s\S]*?<\/style\s*>/gi, '');
      s = s.replace(/<\s*script\b[\s\S]*?<\/script\s*>/gi, '');
      s = s.replace(/\r\n|\r/g, '\n');
    } catch (_) { /* noop */ }

    let out = '';
    let pos = 0;
    while (pos < s.length) {
      const lt = s.indexOf('<', pos);
      if (lt === -1) {
        out += escapeHtmlChars(s.slice(pos));
        break;
      }
      out += escapeHtmlChars(s.slice(pos, lt));

      const rest = s.slice(lt);

      let m = CLOSE_ALLOWED.exec(rest);
      if (m) {
        const name = String(m[1]).toLowerCase();
        pos = lt + m[0].length;
        out += '</' + name + '>';
        continue;
      }

      m = OPEN_ALLOWED.exec(rest);
      if (m) {
        const name = String(m[1]).toLowerCase();
        pos = lt + m[0].length;
        if (m[2] != null && String(m[2]).trim().length > 0) {
        out += escapeHtmlChars(m[0]);
      } else {
        out += '<' + name + '>';
      }
        continue;
      }

      const unkGt = rest.indexOf('>');
      if (unkGt !== -1) {
        out += escapeHtmlChars(rest.slice(0, unkGt + 1));
        pos = lt + unkGt + 1;
      } else {
        out += escapeHtmlChars(rest);
        pos = s.length;
      }
    }

    return out;
  }

  /**
   * 미리보기: 전체 문자 수가 maxLen 이내면 rich HTML 유지,
   * 넘치면 플레인 텍스트만 truncate(브라우저 표시 간단·안전).
   * @param {*} raw
   * @param {number} maxLen
   * @returns {{ html: string, truncated: boolean }}
   */
  function richPreviewSnippet(raw, maxLen) {
    const n = Math.max(40, Number(maxLen) || 200);
    const safe = sanitizeLocAiRichHtml(raw);
    if (typeof document === 'undefined') {
      const t = safe.replace(/<[^>]+>/g, '').length;
      return t <= n ? { html: safe, truncated: false }
        : { html: escapeHtmlChars(safe.replace(/<[^>]+>/g, '').slice(0, n)) + '…', truncated: true };
    }
    const holder = document.createElement('div');
    holder.innerHTML = safe;
    const text = holder.textContent || '';
    if (text.length <= n) return { html: safe, truncated: false };
    return { html: escapeHtmlChars(text.slice(0, n)) + '…', truncated: true };
  }

  global.LocAiRichText = { sanitizeLocAiRichHtml, escapeHtmlChars, richPreviewSnippet };
})(typeof window !== 'undefined' ? window : this);
