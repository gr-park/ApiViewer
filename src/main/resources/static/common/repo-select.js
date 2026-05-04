/* ═══════════════════════════════════════════════════════════════
 * URL Viewer — 공용 레포지토리 선택 컴포넌트
 *
 * 사용 예 (컴팩트):
 *   <div id="fRepo"></div>
 *   const sel = RepoSelect.mountCompact('#fRepo', {
 *     selected: [],                  // 초기 선택 (빈 배열 = 전체)
 *     onChange: (arr) => doSearch(), // 변경 콜백, arr = 선택된 repoName[]
 *     placeholder: '(전체 활성 레포)'
 *   });
 *   sel.getSelected();               // 현재 선택 배열 (0개면 전체 의미)
 *
 * 대형 버전(관리자):
 *   RepoSelect.mountLarge('#mountEl', { singleMode: true, ... });
 *
 * 라벨 포맷: RepoSelect.formatLabel({repoName, businessName})
 *   - "업무명 | 레포명"   (업무명 있을 때)
 *   - "레포명"            (업무명 없을 때)
 * ═══════════════════════════════════════════════════════════════ */
(function () {
  let _reposCache = null;
  let _reposPromise = null;

  // 레포 영문명(repoName) 표시 시 소문자 통일 — 데이터/식별자는 원본 유지, UI 표기만 소문자
  function lowerName(name) { return (name || '').toLowerCase(); }

  function formatLabel(repo) {
    if (!repo) return '';
    // itemMode: repoName=value, businessName=label, _sublabel=sublabel
    if (repo._sublabel !== undefined) {
      const base = repo.businessName || lowerName(repo.repoName) || '';
      return repo._sublabel ? `${base} · ${repo._sublabel}` : base;
    }
    let biz  = (repo.businessName || '').trim();
    const rawName = (repo.repoName || '');
    const name = lowerName(rawName);

    // 일부 페이지/데이터에서 businessName 자체에 "업무명 | REPO-NAME" 형태로 들어오는 경우가 있다.
    // 이 경우 우측 REPO 표기만 소문자로 정규화해서 일관된 UI를 유지한다.
    // - repoName 과 우측 토큰이 동일(대소문자 무시)할 때만 적용한다(업무명에 파이프가 실제 의미로 쓰이는 케이스 보호).
    if (biz && biz.includes('|') && rawName) {
      const parts = biz.split('|');
      if (parts.length >= 2) {
        const left = parts.slice(0, -1).join('|').trim();
        const right = parts[parts.length - 1].trim();
        if (right && right.toLowerCase() === String(rawName).trim().toLowerCase()) {
          biz = left;
        }
      }
    }
    return biz ? `${biz} | ${name}` : name;
  }

  function esc(s) {
    return (s == null ? '' : String(s))
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /** 업무명·레포명·표시 라벨·부라벨 부분일치 (대소문자 무시) */
  function _repoMatchesKeyword(r, rawQuery) {
    if (rawQuery == null || !String(rawQuery).trim()) return true;
    const q = String(rawQuery).trim().toLowerCase();
    const label = formatLabel(r).toLowerCase();
    const name = lowerName(r.repoName || '');
    const biz = (r.businessName || '').trim().toLowerCase();
    const sub = String(r._sublabel || '').toLowerCase();
    return label.includes(q) || name.includes(q) || biz.includes(q) || sub.includes(q);
  }

  function _visibleRepos(repos, state) {
    return (repos || []).filter(r => _repoMatchesKeyword(r, state.filterQuery));
  }

  /** 현재 키워드에 맞는 항목만 선택(단일 모드는 첫 항목만). 목록 갱신·트리거/요약은 호출 측에서 */
  function _selectVisibleOnly(state, repos) {
    const visible = _visibleRepos(repos, state);
    state.selected.clear();
    if (state.singleMode) {
      if (visible[0]) state.selected.add(visible[0].repoName);
    } else {
      visible.forEach(r => state.selected.add(r.repoName));
    }
    _fire(state);
  }

  async function loadRepos(force) {
    if (_reposCache && !force) return _reposCache;
    if (_reposPromise && !force) return _reposPromise;
    _reposPromise = fetch('/api/config/repos')
      .then(r => r.ok ? r.json() : [])
      .then(list => { _reposCache = Array.isArray(list) ? list : []; _reposPromise = null; return _reposCache; })
      .catch(e => { _reposPromise = null; console.warn('[RepoSelect] 레포 로드 실패', e); return []; });
    return _reposPromise;
  }

  function _resolveEl(target) {
    if (typeof target === 'string') return document.querySelector(target);
    return target;
  }

  function _fire(state) {
    try { state.onChange(Array.from(state.selected)); }
    catch (e) { console.warn('[RepoSelect] onChange error', e); }
  }

  function _mount(container, opts, variant) {
    container = _resolveEl(container);
    if (!container) throw new Error('RepoSelect: container not found');

    // 일반 items 지원 ({value, label, sublabel}) — jobType 등 레포 아닌 목록에도 재사용
    const useItems = Array.isArray(opts.items);
    const toRepoShape = useItems
      ? (opts.items || []).map(it => ({
          repoName: it.value,
          businessName: it.label || '',
          _sublabel: it.sublabel || ''
        }))
      : null;

    const state = {
      repos: null,
      selected: new Set(opts.selected || []),
      onChange: typeof opts.onChange === 'function' ? opts.onChange : function () {},
      placeholder: opts.placeholder || (useItems ? '선택' : '(전체 활성 레포)'),
      variant,
      // singleMode는 명시적 true만 허용 (문자열 등 truthy 오염 방어)
      singleMode: opts.singleMode === true,
      itemMode: useItems,
      open: false,
      _outsideHandler: null,
      /** compact/large 패널 키워드 필터 (표시 목록만 좁힘, 전체/해제는 전체 목록 기준) */
      filterQuery: typeof opts.filterQuery === 'string' ? opts.filterQuery : '',
    };

    container.classList.add('repo-select', 'repo-select--' + variant);

    const render = () => (variant === 'compact' ? _renderCompact(container, state) : _renderLarge(container, state));

    if (useItems) {
      state.repos = toRepoShape;
      render();
    } else if (Array.isArray(opts.repos)) {
      state.repos = opts.repos.slice();
      render();
    } else {
      container.innerHTML = '<span style="font-size:11px;color:var(--text-muted);">로딩 중…</span>';
      loadRepos().then(list => {
        state.repos = list;
        render();
      });
    }

    const instance = {
      getSelected() { return Array.from(state.selected); },
      setSelected(arr) { state.selected = new Set(arr || []); render(); },
      getRepos() { return state.repos ? state.repos.slice() : []; },
      refresh() {
        return loadRepos(true).then(list => { state.repos = list; render(); });
      },
      destroy() {
        if (state._outsideHandler) document.removeEventListener('click', state._outsideHandler);
        container.innerHTML = '';
        container.classList.remove('repo-select', 'repo-select--' + variant);
      }
    };
    container._repoSelectInstance = instance;
    return instance;
  }

  function _bindCompactItemHandlers(container, state) {
    container.querySelectorAll('.rs-item').forEach(cb => {
      cb.addEventListener('change', () => {
        if (state.singleMode) {
          state.selected.clear();
          if (cb.checked) state.selected.add(cb.dataset.repo);
          container.querySelectorAll('.rs-item').forEach(other => { if (other !== cb) other.checked = false; });
        } else {
          if (cb.checked) state.selected.add(cb.dataset.repo);
          else state.selected.delete(cb.dataset.repo);
        }
        _fire(state);
        _updateCompactTrigger(container, state);
      });
    });
  }

  /** 목록 영역만 갱신 — 키워드 입력 시 포커스 유지 */
  function _renderCompactListBody(listEl, container, state, repos) {
    if (!repos.length) {
      listEl.innerHTML = '<div class="rs-empty">등록된 레포가 없습니다.</div>';
      return;
    }
    const visible = repos.filter(r => _repoMatchesKeyword(r, state.filterQuery));
    if (!visible.length) {
      listEl.innerHTML = '<div class="rs-empty">키워드에 맞는 항목이 없습니다.</div>';
      return;
    }
    listEl.innerHTML = visible.map(r => {
      const checked = state.selected.has(r.repoName) ? 'checked' : '';
      return `<label class="rs-list-item">
        <input type="checkbox" class="rs-item" data-repo="${esc(r.repoName)}" ${checked}>
        <span class="rs-label" title="${esc(formatLabel(r))}">${esc(formatLabel(r))}</span>
      </label>`;
    }).join('');
    _bindCompactItemHandlers(container, state);
  }

  // ── Compact ────────────────────────────────────────────────────
  function _renderCompact(container, state) {
    const repos = state.repos || [];
    const total = repos.length;
    const sel = state.selected.size;
    const allSelected = sel === 0;
    const triggerText = allSelected ? state.placeholder : `${sel}개 선택`;

    container.innerHTML = `
      <button type="button" class="rs-trigger">
        <span class="rs-trigger-text">${esc(triggerText)}</span>
        <span class="rs-trigger-caret">▾</span>
      </button>
      <div class="rs-panel">
        <div class="rs-panel-actions">
          <button type="button" class="rs-all">전체</button>
          <button type="button" class="rs-none">해제</button>
          <input type="search" class="rs-filter" placeholder="키워드 검색" value="${esc(state.filterQuery)}" autocomplete="off" aria-label="레포 검색">
          <button type="button" class="rs-apply" title="현재 키워드에 맞는 항목만 선택">적용</button>
        </div>
        <div class="rs-list"></div>
      </div>
    `;

    const listEl = container.querySelector('.rs-list');
    _renderCompactListBody(listEl, container, state, repos);

    const trigger = container.querySelector('.rs-trigger');
    const panel   = container.querySelector('.rs-panel');

    trigger.addEventListener('click', (e) => {
      e.stopPropagation();
      state.open = !state.open;
      panel.classList.toggle('is-open', state.open);
    });

    if (state._outsideHandler) document.removeEventListener('click', state._outsideHandler);
    state._outsideHandler = (e) => {
      if (!container.contains(e.target)) {
        state.open = false;
        panel.classList.remove('is-open');
      }
    };
    document.addEventListener('click', state._outsideHandler);

    const filterInput = container.querySelector('.rs-filter');
    if (filterInput) {
      filterInput.addEventListener('click', (e) => e.stopPropagation());
      filterInput.addEventListener('keydown', (e) => e.stopPropagation());
      filterInput.addEventListener('input', (e) => {
        e.stopPropagation();
        state.filterQuery = e.target.value;
        _renderCompactListBody(listEl, container, state, repos);
      });
    }
    const applyBtn = container.querySelector('.rs-apply');
    if (applyBtn) {
      applyBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        _selectVisibleOnly(state, repos);
        _renderCompactListBody(listEl, container, state, repos);
        _updateCompactTrigger(container, state);
      });
    }

    container.querySelector('.rs-all').addEventListener('click', () => {
      state.selected.clear();
      repos.forEach(r => state.selected.add(r.repoName));
      _fire(state);
      _renderCompact(container, state);
      container.querySelector('.rs-panel').classList.add('is-open');
      state.open = true;
    });
    container.querySelector('.rs-none').addEventListener('click', () => {
      state.selected.clear();
      _fire(state);
      _renderCompact(container, state);
      container.querySelector('.rs-panel').classList.add('is-open');
      state.open = true;
    });
  }

  function _updateCompactTrigger(container, state) {
    const total = (state.repos || []).length;
    const sel = state.selected.size;
    const txt = sel === 0 ? state.placeholder : `${sel}개 선택`;
    const el = container.querySelector('.rs-trigger-text');
    if (el) el.textContent = txt;
  }

  function _bindLargeItemHandlers(container, state) {
    container.querySelectorAll('.rs-item').forEach(cb => {
      cb.addEventListener('change', () => {
        if (state.singleMode) {
          state.selected.clear();
          if (cb.checked) state.selected.add(cb.dataset.repo);
          container.querySelectorAll('.rs-item').forEach(other => { if (other !== cb) other.checked = false; });
        } else {
          if (cb.checked) state.selected.add(cb.dataset.repo);
          else state.selected.delete(cb.dataset.repo);
        }
        _fire(state);
        _updateLargeSummary(container, state);
      });
    });
  }

  function _renderLargeGridBody(grid, container, state, repos) {
    if (!repos.length) {
      grid.innerHTML = '<div class="rs-empty">등록된 레포가 없습니다.</div>';
      return;
    }
    const visible = repos.filter(r => _repoMatchesKeyword(r, state.filterQuery));
    if (!visible.length) {
      grid.innerHTML = '<div class="rs-empty">키워드에 맞는 항목이 없습니다.</div>';
      return;
    }
    grid.innerHTML = visible.map(r => {
      const checked = state.selected.has(r.repoName) ? 'checked' : '';
      return `<label class="rs-grid-item">
        <input type="checkbox" class="rs-item" data-repo="${esc(r.repoName)}" ${checked}>
        <span class="rs-label" title="${esc(formatLabel(r))}">${esc(formatLabel(r))}</span>
      </label>`;
    }).join('');
    _bindLargeItemHandlers(container, state);
  }

  // ── Large ──────────────────────────────────────────────────────
  function _renderLarge(container, state) {
    const repos = state.repos || [];
    container.innerHTML = `
      <div class="rs-large-header">
        <span class="rs-summary"></span>
        <button type="button" class="rs-all">전체선택</button>
        <button type="button" class="rs-none">전체해제</button>
        <input type="search" class="rs-filter rs-filter--large" placeholder="키워드 검색" value="${esc(state.filterQuery)}" autocomplete="off" aria-label="레포 검색">
        <button type="button" class="rs-apply rs-apply--large" title="현재 키워드에 맞는 항목만 선택">적용</button>
      </div>
      <div class="rs-grid"></div>
    `;

    const grid = container.querySelector('.rs-grid');
    _renderLargeGridBody(grid, container, state, repos);
    _updateLargeSummary(container, state);

    const filterInput = container.querySelector('.rs-filter');
    if (filterInput) {
      filterInput.addEventListener('input', (e) => {
        state.filterQuery = e.target.value;
        _renderLargeGridBody(grid, container, state, repos);
        _updateLargeSummary(container, state);
      });
    }
    const applyBtn = container.querySelector('.rs-apply');
    if (applyBtn) {
      applyBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        _selectVisibleOnly(state, repos);
        _renderLargeGridBody(grid, container, state, repos);
        _updateLargeSummary(container, state);
      });
    }

    container.querySelector('.rs-all').addEventListener('click', () => {
      state.selected.clear();
      repos.forEach(r => state.selected.add(r.repoName));
      _fire(state);
      _renderLarge(container, state);
    });
    container.querySelector('.rs-none').addEventListener('click', () => {
      state.selected.clear();
      _fire(state);
      _renderLarge(container, state);
    });
  }

  function _updateLargeSummary(container, state) {
    const el = container.querySelector('.rs-summary');
    if (!el) return;
    const total = (state.repos || []).length;
    const sel = state.selected.size;
    el.textContent = sel === 0
      ? `전체 ${total}개 — 선택 없음 (= 전체 조회)`
      : `${sel}/${total} 선택`;
  }

  window.RepoSelect = {
    formatLabel,
    loadRepos,
    mountCompact(el, opts) { return _mount(el, opts || {}, 'compact'); },
    mountLarge(el, opts)   { return _mount(el, opts || {}, 'large'); }
  };

  // 일반 items 기반 multi-select alias — jobType/상태 등에 재사용
  window.MultiSelect = {
    mountCompact(el, opts) { return _mount(el, opts || {}, 'compact'); },
    mountLarge(el, opts)   { return _mount(el, opts || {}, 'large'); }
  };
})();
