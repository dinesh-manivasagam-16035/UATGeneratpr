(function () {
  const isLocal = ["localhost", "127.0.0.1"].includes(location.hostname);
  // In production the client and function are on the same Catalyst domain —
  // use a relative path so it works for any environment without hardcoding.
  const FUNCTION_BASE = isLocal
    ? "http://localhost:9000/uat_generator"
    : "/server/uat_generator";

  const TABS = ["input", "cases", "execute", "push", "blueprint", "build", "verify"];
  const IMPL_TABS = ["blueprint", "build", "verify"];   // Implementation mode (implement.js)

  const els = {
    tabs: document.querySelectorAll(".tab"),
    panels: document.querySelectorAll(".tab-panel"),

    brdFile:   document.getElementById("brd-file"),
    fileDrop:  document.getElementById("file-drop"),
    fileMeta:  document.getElementById("file-meta"),
    generate:  document.getElementById("generate-btn"),
    downloadSampleBtn: document.getElementById("download-sample-btn"),
    statusInput: document.getElementById("status-input"),

    moduleChips:        document.getElementById("module-chips"),
    moduleSelected:     document.getElementById("module-selected"),
    moduleSelectedList: document.getElementById("module-selected-list"),
    modulePickerEmpty:  document.getElementById("module-picker-empty"),
    analysisSummary:    document.getElementById("analysis-summary"),
    modulePickerEmpty:  document.getElementById("module-picker-empty"),
    loadModulesBtn:     document.getElementById("load-modules-btn"),
    themeSwatchPreview: document.getElementById("theme-swatch-preview"),
    themePickerLabel:   document.getElementById("theme-picker-label"),
    modulesStatus:      document.getElementById("modules-status"),
    headerUserChip: document.getElementById("header-user-chip"),
    hucAvatar:      document.getElementById("huc-avatar"),
    hucOrg:         document.getElementById("huc-org"),
    hucEmail:       document.getElementById("huc-email"),
    hucDot:         document.getElementById("huc-dot"),

    authLoginBtn:    document.getElementById("auth-login-btn"),
    authLoginArea:   document.getElementById("auth-login-area"),
    authUserInfo:    document.getElementById("auth-user-info"),
    authEmail:       document.getElementById("auth-email"),
    authOrgName:     document.getElementById("auth-org-name"),
    authSwitchOrgBtn:document.getElementById("auth-switch-org-btn"),
    authLogoutBtn:   document.getElementById("auth-logout-btn"),
    authStatusText:  document.getElementById("auth-status-text"),
    ssoFrame:        document.getElementById("sso-frame"),

    casesMeta: document.getElementById("cases-meta"),
    cases:     document.getElementById("cases"),
    backToInput: document.getElementById("back-to-input"),
    toExecute: document.getElementById("to-execute-btn"),
    exportCsvBtn: document.getElementById("export-csv-btn"),
    download:  document.getElementById("download-btn"),
    statusCases: document.getElementById("status-cases"),

    execSummary: document.getElementById("exec-summary"),
    execQuote:   document.getElementById("exec-quote"),
    execResults: document.getElementById("exec-results"),
    execute:   document.getElementById("execute-btn"),
    backToCases: document.getElementById("back-to-cases"),
    toPush:    document.getElementById("to-push-btn"),
    statusExecute: document.getElementById("status-execute"),
    execLoaderText: document.getElementById("execute-loader-text"),

    projectName: document.getElementById("project_name"),
    pushSummary: document.getElementById("push-summary"),
    pushLink:    document.getElementById("push-link"),

    pushResults: document.getElementById("push-results"),
    backToExecute: document.getElementById("back-to-execute"),
    push:      document.getElementById("push-btn"),
    downloadCsv: document.getElementById("download-csv-btn"),
    restart:   document.getElementById("restart-btn"),
    statusPush: document.getElementById("status-push"),
    pushLoaderText: document.getElementById("push-loader-text"),
  };

  const state = {
    activeTab: "input",
    brd: "",
    fileName: "",
    cases: [],
    requirements: [],   // [{id, title, part, derived, cases, in_run}] from /generate
    payload: null,
    modulesAvailable: [],
    modulesSelected: [],
    suggestedModules: [],
    analysisText: "",
    executed: false,
    pushed: false,
    authUser: null,
    authFullName: "",
    authEmailId: "",
    crmAuthorized: false,
    oauthAvailable: false,
    crmEmail: null,
    crmOrgName: null,
    crmOrgList: [],
    stepDone: { input: false, cases: false, execute: false, push: false },
    // Creator
    platform: "crm",
    mode: "uat",        // "uat" (test cases) or "impl" (implementation spec, see implement.js)
    creatorOwner: "",
    creatorApp: "",
    creatorForms: [],
    creatorAppDisplayName: "",
    creatorAppLinkName: "",
    creatorFormDisplayName: "",
    creatorFormLinkName: "",
    creatorFields: [],
  };

  const MAX_MODULES = 3; // must match MAX_MODULES in GenerateServlet

  // ---- Timeout-aware fetch ----
  // ms: abort after this many milliseconds; label used in error message
  async function fetchWithTimeout(url, options, ms, label) {
    const ctrl = new AbortController();
    const tid = setTimeout(() => ctrl.abort(), ms);
    try {
      const res = await fetch(url, { ...options, signal: ctrl.signal });
      clearTimeout(tid);
      return res;
    } catch (e) {
      clearTimeout(tid);
      if (e.name === "AbortError") {
        throw new Error(`${label || "Request"} timed out after ${Math.round(ms / 1000)}s. The server may be busy — please try again.`);
      }
      throw e;
    }
  }

  // ---- LocalStorage persistence ----
  const STORAGE_KEY = "liftoff_state_v2";

  function saveStateToStorage() {
    try {
      const toSave = {
        cases:                 state.cases,
        requirements:          state.requirements,
        modulesSelected:       state.modulesSelected,
        platform:              state.platform,
        platformManual:        !!state.platformManual,
        mode:                  state.mode,
        creatorOwner:          state.creatorOwner,
        creatorApp:            state.creatorApp,
        creatorAppDisplayName: state.creatorAppDisplayName,
        creatorAppLinkName:    state.creatorAppLinkName,
        creatorFormDisplayName:state.creatorFormDisplayName,
        creatorFormLinkName:   state.creatorFormLinkName,
        creatorFields:         state.creatorFields,
        orgChosen:             !!state.orgChosen,
      };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(toSave));
    } catch (e) { /* storage full or private mode */ }
  }

  function loadStateFromStorage() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (Array.isArray(saved.modulesSelected) && saved.modulesSelected.length) {
        state.modulesSelected = saved.modulesSelected;
      }
      if (Array.isArray(saved.cases) && saved.cases.length) {
        state.cases    = saved.cases;
        state.executed = saved.cases.some((c) => c.execution_result);
        state.pushed   = saved.cases.some((c) => c.push_result);
      }
      if (Array.isArray(saved.requirements)) state.requirements = saved.requirements;
      if (saved.platform) state.platform = saved.platform;
      if (saved.platformManual) state.platformManual = true;
      if (saved.orgChosen) state.orgChosen = true;
      if (saved.mode === "impl" || saved.mode === "uat") state.mode = saved.mode;
      if (saved.creatorOwner)           state.creatorOwner           = saved.creatorOwner;
      if (saved.creatorApp)             state.creatorApp             = saved.creatorApp;
      if (saved.creatorAppDisplayName)  state.creatorAppDisplayName  = saved.creatorAppDisplayName;
      if (saved.creatorAppLinkName)     state.creatorAppLinkName     = saved.creatorAppLinkName;
      if (saved.creatorFormDisplayName) state.creatorFormDisplayName = saved.creatorFormDisplayName;
      if (saved.creatorFormLinkName)    state.creatorFormLinkName    = saved.creatorFormLinkName;
      if (Array.isArray(saved.creatorFields)) state.creatorFields    = saved.creatorFields;
    } catch (e) { /* corrupt data */ }
  }

  const DEFAULT_MODULES = [
    { api_name: "Leads",    plural_label: "Leads" },
    { api_name: "Contacts", plural_label: "Contacts" },
    { api_name: "Accounts", plural_label: "Accounts" },
    { api_name: "Deals",    plural_label: "Deals" },
    { api_name: "Tasks",    plural_label: "Tasks" },
    { api_name: "Cases",    plural_label: "Cases" },
    { api_name: "Products", plural_label: "Products" },
    { api_name: "Quotes",   plural_label: "Quotes" },
  ];

  if (window.pdfjsLib) {
    window.pdfjsLib.GlobalWorkerOptions.workerSrc =
      "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
  }

  // ---- Theme toggle ----
  const VALID_THEMES = new Set(["light", "dark"]);
  function getTheme() {
    try {
      const t = localStorage.getItem("testpilot_theme") || "light";
      return VALID_THEMES.has(t) ? t : "light";
    } catch (e) { return "light"; }
  }
  const THEMES = [
    { id: "light", label: "Light", swatchBg: "radial-gradient(circle at 35% 35%, #ffffff 0%, #d6e2f8 100%)", swatchBorder: "rgba(21,89,208,0.20)" },
    { id: "dark",  label: "Night", swatchBg: "radial-gradient(circle at 35% 35%, #22222E 0%, #0A0A10 100%)", swatchBorder: "rgba(124,133,247,0.25)" },
  ];
  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem("testpilot_theme", theme); } catch (e) {}
    const meta = THEMES.find(t => t.id === theme) || THEMES[0];
    if (els.themeSwatchPreview) {
      els.themeSwatchPreview.style.background = meta.swatchBg;
      if (meta.swatchBorder) els.themeSwatchPreview.style.borderColor = meta.swatchBorder;
    }
    if (els.themePickerLabel) els.themePickerLabel.textContent = meta.label;
    // update active state in dropdown
    document.querySelectorAll(".theme-option").forEach(opt => {
      opt.classList.toggle("active", opt.dataset.themePick === theme);
    });
  }
  const themePickerBtn = document.getElementById("theme-picker-btn");
  const themeDropdown  = document.getElementById("theme-dropdown");
  if (themePickerBtn && themeDropdown) {
    themePickerBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      themeDropdown.hidden = !themeDropdown.hidden;
    });
    themeDropdown.addEventListener("click", (e) => {
      const opt = e.target.closest(".theme-option");
      if (!opt) return;
      const picked = opt.dataset.themePick;
      if (picked) { setTheme(picked); themeDropdown.hidden = true; }
    });
    document.addEventListener("click", () => { themeDropdown.hidden = true; });
  }
  // Apply saved theme on load (updates picker label + swatch).
  setTheme(getTheme());

  // ---- Generation loader: cycling messages + fake progress bar ----

  const GEN_MESSAGES = [
    "Analyzing your spec…",
    "Sketching test scenarios…",
    "Writing execution plans…",
    "Applying edge cases & boundaries…",
    "Checking for coverage gaps…",
    "Polishing the details…",
    "Still working — large specs take up to 2 min…",
    "Almost ready…",
  ];
  let _genMsgTimer = null, _genProgressTimer = null, _genProgressVal = 0;

  // ---- Busy overlay: one blocking progress panel for every long action ----
  // start(title, detail) → update({title?, detail?, fraction?, steps?}) → end().
  // While shown, the app is inert (no clicks, no keyboard) so a job can't be disturbed.
  // Orbit loader: a progress ring around a pulsing core with two satellites.
  // orbitHtml(size) → markup; orbitSet(el, fraction | null | "done") → state.
  const RING_C = 150.8; // 2π · r24 in the 56-unit viewBox
  function orbitHtml(size) {
    return `<span class="lo-orbit indeterminate" style="--sz:${size || 56}px" aria-hidden="true">
      <svg class="ring" viewBox="0 0 56 56"><circle class="track" cx="28" cy="28" r="24"/><circle class="prog" cx="28" cy="28" r="24"/></svg>
      <span class="halo"></span><span class="core"></span><span class="sat"></span><span class="sat s2"></span><span class="pct"></span></span>`;
  }
  function orbitSet(el, v) {
    if (!el) return;
    el.classList.remove("is-done");
    if (v === "done") { el.classList.remove("indeterminate", "has-pct"); el.classList.add("is-done"); return; }
    if (typeof v === "number") {
      const f = Math.max(0, Math.min(1, v));
      el.classList.remove("indeterminate"); el.classList.add("has-pct");
      el.querySelector(".prog").style.strokeDashoffset = String(RING_C * (1 - f));
      el.querySelector(".pct").textContent = Math.round(f * 100) + "%";
    } else { el.classList.add("indeterminate"); el.classList.remove("has-pct"); el.querySelector(".prog").style.strokeDashoffset = ""; }
  }

  const busy = (() => {
    let el = null, t0 = 0, timer = null, depth = 0;
    const fmt = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
    function ensure() {
      if (el) return el;
      el = document.createElement("div");
      el.id = "busy-overlay";
      el.setAttribute("role", "dialog");
      el.setAttribute("aria-modal", "true");
      el.setAttribute("aria-live", "polite");
      el.hidden = true;
      el.innerHTML = `<div class="busy-card">
          <div class="busy-head">${orbitHtml(64)}
            <div><h3 class="busy-title"></h3><p class="busy-detail"></p></div>
            <span class="busy-time" title="Elapsed">0:00</span></div>
          <div class="busy-barrow"><div class="busy-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100"><span></span></div><span class="busy-pct"></span></div>
          <ol class="busy-steps" hidden></ol>
          <p class="busy-note">Working in the background. Other controls are locked until this finishes.</p>
        </div>`;
      document.body.appendChild(el);
      return el;
    }
    function render(o) {
      const b = ensure();
      if (o.title !== undefined) b.querySelector(".busy-title").textContent = o.title;
      if (o.detail !== undefined) {
        const d = b.querySelector(".busy-detail");
        if (d.textContent !== (o.detail || "")) { d.classList.add("swap"); setTimeout(() => { d.textContent = o.detail || ""; d.classList.remove("swap"); }, 160); }
      }
      const bar = b.querySelector(".busy-bar"), orbit = b.querySelector(".lo-orbit"), pct = b.querySelector(".busy-pct");
      if (typeof o.fraction === "number") {
        bar.classList.remove("indeterminate");
        const p = Math.round(o.fraction * 100);
        bar.setAttribute("aria-valuenow", String(p));
        bar.firstElementChild.style.width = Math.max(3, Math.min(100, p)) + "%";
        pct.textContent = p + "%";
        orbitSet(orbit, o.fraction);
      } else if (o.fraction === null) {
        bar.classList.add("indeterminate");
        bar.removeAttribute("aria-valuenow");
        bar.firstElementChild.style.width = "";
        pct.textContent = "";
        orbitSet(orbit, null);
      }
      if (o.steps !== undefined) {
        const list = b.querySelector(".busy-steps");
        const steps = Array.isArray(o.steps) ? o.steps : [];
        list.hidden = !steps.length;
        list.innerHTML = steps.map((st) => {
          const state = st.state || st.status || "waiting";
          const right = state === "done" ? (st.items != null ? `${st.items} item(s)` : st.result || "done")
            : state === "working" || state === "running" ? "working" : state === "failed" ? "failed" : state === "skipped" ? "skipped" : "queued";
          return `<li class="is-${escapeHtml(state)}"><span class="lo-dot is-${escapeHtml(state)}"></span><span class="busy-step-label">${escapeHtml(st.label || st.title || "")}</span>
            <span class="busy-step-state">${escapeHtml(right)}</span>${st.error ? `<span class="busy-step-err">${escapeHtml(st.error)}</span>` : ""}</li>`;
        }).join("");
        const done = steps.filter((st) => ["done", "failed", "skipped"].includes(st.state || st.status)).length;
        if (steps.length && typeof o.fraction !== "number") render({ fraction: (done + 0.3 * steps.filter((st) => ["working", "running"].includes(st.state || st.status)).length) / steps.length });
      }
    }
    return {
      start(title, detail) {
        depth++;
        const b = ensure();
        if (depth === 1) {
          t0 = Date.now();
          b.querySelector(".busy-steps").hidden = true;
          b.querySelector(".busy-steps").innerHTML = "";
          render({ title, detail: detail || "Starting…", fraction: null });
          b.hidden = false;
          document.body.classList.add("is-busy");
          const app = document.getElementById("app");
          if (app) app.setAttribute("inert", "");
          clearInterval(timer);
          timer = setInterval(() => { b.querySelector(".busy-time").textContent = fmt(Date.now() - t0); }, 1000);
          b.querySelector(".busy-time").textContent = "0:00";
        } else {
          render({ title, detail });
        }
      },
      update(o) { if (!el || el.hidden) return; render(typeof o === "string" ? { detail: o } : o || {}); },
      end() {
        depth = Math.max(0, depth - 1);
        if (depth || !el) return;
        clearInterval(timer); timer = null;
        el.hidden = true;
        document.body.classList.remove("is-busy");
        const app = document.getElementById("app");
        if (app) app.removeAttribute("inert");
      },
      active() { return !!(el && !el.hidden); },
    };
  })();

  // ---- Guide: one "Now" card, exceptions-only "Needs you", plain-language lists, sidebar sub-status ----
  // Buttons inside guide cards proxy the real (hidden) controls: data-proxy="<element id>".
  const guide = {
    now(panel, o) {
      const el = document.getElementById("now-" + panel);
      if (!el) return;
      if (!o) { el.hidden = true; el.innerHTML = ""; return; }
      el.hidden = false;
      el.className = "now tone-" + (o.tone || "act");
      const btn = (b, primary) => !b ? "" : `<button type="button" class="btn-now ${primary ? "primary" : "ghost"}" ${b.proxy ? `data-proxy="${escapeHtml(b.proxy)}"` : ""} ${b.act ? `data-guide-act="${escapeHtml(b.act)}"` : ""} ${b.href ? `data-tab="${escapeHtml(b.href)}"` : ""} ${b.disabled ? "disabled" : ""} ${b.title ? `title="${escapeHtml(b.title)}"` : ""}>${escapeHtml(b.label)}</button>`;
      el.innerHTML = `<div class="now-text"><div class="now-k">${escapeHtml(o.kicker || "Now")}</div><h2>${escapeHtml(o.title || "")}</h2>${o.text ? `<p>${o.text}</p>` : ""}</div>
        <div class="now-actions">${btn(o.primary, true)}${btn(o.secondary, false)}${o.note ? `<span class="now-note">${escapeHtml(o.note)}</span>` : ""}</div>`;
    },
    needs(panel, items, opts) {
      const el = document.getElementById("needs-" + panel);
      if (!el) return;
      if (!items || !items.length) { el.hidden = true; el.innerHTML = ""; return; }
      el.hidden = false;
      const tone = (opts && opts.tone) || "warn";
      el.innerHTML = `<div class="needs tone-${tone}"><div class="needs-hd"><span class="pill ${tone}">${items.length}</span> ${escapeHtml((opts && opts.title) || "Needs you")}</div>
        ${items.map((it) => `<div class="needs-row"><div class="needs-q"><strong>${escapeHtml(it.title)}</strong>${it.text ? `<span>${it.text}</span>` : ""}${it.steps ? `<ol>${it.steps.map((x) => `<li>${escapeHtml(x)}</li>`).join("")}</ol>` : ""}</div><div class="needs-act">${it.html || ""}</div></div>`).join("")}</div>`;
    },
    readiness(panel, checks) {
      const el = document.getElementById("ready-" + panel);
      if (!el) return;
      el.innerHTML = checks.map((c) => `<div class="check"><span class="tick ${c.ok ? "ok" : "todo"}">${c.ok ? "✓" : ""}</span><span class="check-text"><strong>${escapeHtml(c.label)}</strong>${c.text ? " — " + c.text : ""}</span>${c.action ? `<button type="button" class="ghost small" data-proxy="${escapeHtml(c.action.proxy || "")}" data-open-fold="${escapeHtml(c.action.fold || "")}">${escapeHtml(c.action.label)}</button>` : ""}</div>`).join("");
    },
    plain(panel, rows, title, badge) {
      const el = document.getElementById("plain-" + panel);
      if (!el) return;
      if (!rows || !rows.length) { el.hidden = true; el.innerHTML = ""; return; }
      el.hidden = false;
      el.innerHTML = `<div class="card"><div class="card-head"><h3>${escapeHtml(title)}</h3>${badge ? `<span class="pill ${badge.tone || "mute"}">${escapeHtml(badge.text)}</span>` : ""}</div>
        <ul class="plain">${rows.map((r) => `<li class="${r.off ? "is-off" : ""}"><span class="pill ${r.tone || "mute"}">${escapeHtml(r.tag)}</span><div class="what"><strong>${escapeHtml(r.title)}</strong>${r.sub ? `<span>${escapeHtml(r.sub)}</span>` : ""}</div>${r.right ? `<span class="pill mute">${escapeHtml(r.right)}</span>` : ""}</li>`).join("")}</ul></div>`;
    },
    sub(tab, text) { const el = document.getElementById("tab-sub-" + tab); if (el) el.textContent = text || ""; },
  };
  // Proxy clicks from guide cards to the real controls; "Show details" links open the fold.
  document.addEventListener("click", (e) => {
    const t = e.target.closest("[data-proxy], [data-open-fold], [data-tab]");
    if (!t || t.closest(".tab")) return;
    if (t.dataset.openFold) { const d = document.getElementById(t.dataset.openFold); if (d) { d.open = true; d.scrollIntoView({ behavior: "smooth", block: "start" }); } }
    if (t.dataset.proxy) { const real = document.getElementById(t.dataset.proxy); if (real && !real.disabled) real.click(); }
    if (t.dataset.tab && !t.dataset.proxy) showTab(t.dataset.tab);
  });

  // Brief: readiness + the one thing to do.
  function renderGuideBrief() {
    const crmOk = !!state.crmAuthorized;
    const brdOk = !!state.brd;
    const impl = state.mode === "impl";
    const scopeOk = impl ? true : state.platform === "creator" ? !!getCreatorFormLink() : state.modulesSelected.length > 0;
    const reqs = brdOk ? extractRequirements(state.brd).filter((r) => r.id !== "ACCEPT") : [];
    guide.readiness("input", [
      { ok: crmOk, label: crmOk ? `${state.platform === "desk" ? "Zoho account connected" : "CRM connected"}` : `${state.platform === "desk" ? "Zoho account" : "CRM"} not connected`,
        text: crmOk ? `${escapeHtml(state.crmOrgName || productLabel())}${state.platform === "desk" ? " · Desk org is read when the blueprint is generated" : state.modulesAvailable.length ? ` · ${state.modulesAvailable.length} modules loaded` : ""}` : "needed to compare with the org and to build",
        action: crmOk ? null : { label: "Connect", proxy: "auth-login-btn" } },
      { ok: brdOk, label: brdOk ? "BRD loaded" : "No BRD yet",
        text: brdOk ? `${escapeHtml(state.fileName || "document")}${reqs.length ? " · " + reqs.slice(0, 3).map((r) => escapeHtml(r.id + (r.title ? " " + r.title : ""))).join(" · ") + (reqs.length > 3 ? ` · +${reqs.length - 3} more` : "") : ""}` : "drop a .txt, .md, .rtf, .pdf or .docx below",
        action: { label: brdOk ? "Replace" : "Choose file", proxy: "brd-file" } },
      { ok: scopeOk, label: "Scope",
        text: (state.modulesSelected.length ? escapeHtml(state.modulesSelected.join(", ")) : impl ? "modules inferred from the BRD" : "pick up to 5 modules") + ` · Output: ${impl ? "Implementation blueprint" : "UAT cases"}`,
        action: { label: "Edit", fold: "fold-input" } },
    ]);
    const ready = brdOk && scopeOk && !els.generate.disabled;
    guide.now("input", {
      tone: ready ? "act" : "wait",
      kicker: ready ? "Now" : "First",
      title: ready ? (impl ? `Generate the blueprint from ${state.fileName || "the BRD"}` : `Write UAT cases from ${state.fileName || "the BRD"}`)
        : !brdOk ? "Load the requirements document" : !crmOk && impl ? "Connect CRM, then generate the blueprint" : "Pick the modules in scope",
      text: ready ? (impl
          ? `LiftOff reads ${reqs.length ? reqs.length + " requirement(s)" : "the BRD"}, asks the agent for the ${productLabel()} data model and automation, and checks every item against ${escapeHtml(state.crmOrgName || "the org")}. About a minute. Nothing is written to ${productLabel()}.`
          : `Cases are written per module from the BRD's requirements and acceptance criteria. Nothing is sent to CRM until you run them.`)
        : !brdOk ? "Drop the BRD file into the box below, or build one with the template builder." : "",
      primary: ready ? { label: impl ? "Generate blueprint →" : "Generate UAT cases →", proxy: "generate-btn" } : (!brdOk ? { label: "Choose file", proxy: "brd-file" } : { label: "Show options", act: "", disabled: false, proxy: "", title: "" }),
      note: ready && !crmOk && impl ? "CRM isn't connected: items won't be compared with the org and nothing can be built." : "",
    });
    if (!ready && brdOk) { const b = document.querySelector('#now-input .btn-now.primary'); if (b) b.dataset.openFold = "fold-input"; }
    guide.sub("input", brdOk ? `${state.fileName || "BRD loaded"} · ${productLabel()}` : productLabel());
  }

  // ---- Guided layer for UAT mode: Cases → Run → Hand off ----
  const uatLast = { exec: null, push: null };   // last /execute and /push responses, for the Now cards
  function renderGuideUat() {
    if (state.mode === "impl") return;
    const cases = Array.isArray(state.cases) ? state.cases : [];
    const n = cases.length;
    const reqs = Array.isArray(state.requirements) ? state.requirements : [];
    const covered = reqs.filter((r) => cases.some((c) => (c.req_ids || []).includes(r.id))).length;
    const uncovered = reqs.filter((r) => r.in_run !== false && !cases.some((c) => (c.req_ids || []).includes(r.id)));
    const untraced = cases.filter((c) => !(c.req_ids || []).length && c.spec_ref !== "Default API smoke test");
    const byP = (p) => cases.filter((c) => (c.priority || "P1") === p).length;
    const st = (c) => c.execution_result && c.execution_result.status;
    const passed = cases.filter((c) => st(c) === "pass").length, failed = cases.filter((c) => st(c) === "fail").length;
    const notRun = cases.filter((c) => !st(c) || !["pass", "fail"].includes(st(c))).length;
    const target = productLabel();
    const org = state.crmOrgName || target;
    const tile = (v, label, color) => `<div class="s"><b style="color:${color || "inherit"}">${escapeHtml(String(v))}</b><span>${escapeHtml(label)}</span></div>`;
    const caseRow = (c) => ({ tag: c.priority || "P1", tone: (c.priority || "P1").toLowerCase(), title: c.title || "", sub: `${c.module || ""}${c.spec_ref ? " · " + c.spec_ref : ""}`, right: (c.req_ids || []).join(", ") });

    // ---- Cases ----
    const scoreC = document.getElementById("score-cases");
    if (!n) {
      guide.now("cases", { tone: "wait", kicker: "First", title: "Generate UAT cases from the BRD", text: "Cases are written per module from the requirements and acceptance criteria.", primary: { label: "← Brief", href: "input" } });
      if (scoreC) scoreC.innerHTML = ""; guide.needs("cases", []); guide.plain("cases", []); guide.sub("cases", "");
    } else {
      guide.now("cases", { tone: state.executed ? "ok" : "act", kicker: state.executed ? "Done" : "Now",
        title: state.executed ? `${n} case(s) written and run` : `Review ${n} case(s), then run them in ${org}`,
        text: state.executed ? "Results are on the Run step." : `${covered} of ${reqs.length || "?"} requirement(s) covered · ${byP("P0")} P0, ${byP("P1")} P1, ${byP("P2")} P2. Each case creates its own test record${state.crmAuthorized ? "" : " — connect first, or run a dry run that sends nothing"}.`,
        primary: { label: state.executed ? "See results →" : state.crmAuthorized ? `Run ${n} case(s) →` : "Dry run (sends nothing) →", proxy: state.executed ? "" : "to-execute-btn", href: state.executed ? "execute" : "" },
        secondary: { label: "Download JSON", proxy: "download-btn" } });
      if (scoreC) scoreC.innerHTML = `<div class="score score-4">${tile(n, "test cases")}${tile(`${covered} / ${reqs.length}`, "requirements covered", reqs.length && covered < reqs.length ? "var(--warn)" : "var(--ok)")}${tile(byP("P0"), "P0 — core acceptance", "var(--err)")}${tile(byP("P1") + byP("P2"), "P1 + P2 — rules and edges")}</div>`;
      const needs = [];
      uncovered.slice(0, 6).forEach((r) => needs.push({ title: `${r.id} has no test case`, text: escapeHtml(r.title || "Regenerate with this requirement in scope, or accept the gap."), html: `<button type="button" class="ghost small" data-open-fold="fold-cases">See coverage</button>` }));
      if (untraced.length) needs.push({ title: `${untraced.length} case(s) aren't traced to a requirement`, text: "They still run; they just won't count toward coverage.", html: `<button type="button" class="ghost small" data-open-fold="fold-cases">Open the cases</button>` });
      guide.needs("cases", needs);
      const rows = cases.slice(0, 8).map(caseRow);
      if (n > 8) rows.push({ tag: "…", tone: "mute", title: `and ${n - 8} more`, sub: "open Show details for the full list" });
      guide.plain("cases", rows, "What will be tested · in plain words", { text: `${covered} of ${reqs.length} requirement(s)`, tone: reqs.length && covered < reqs.length ? "warn" : "ok" });
      guide.sub("cases", `${n} case(s)${state.executed ? " · run" : ""}`);
    }

    // ---- Run ----
    const scoreE = document.getElementById("score-execute");
    const ex = uatLast.exec;
    if (!n) {
      guide.now("execute", { tone: "wait", kicker: "First", title: "Write the cases first", primary: { label: "← Cases", href: "cases" } });
      if (scoreE) scoreE.innerHTML = ""; guide.needs("execute", []); guide.plain("execute", []); guide.sub("execute", "");
    } else if (!state.executed) {
      guide.now("execute", { tone: "act", kicker: "Now", title: state.crmAuthorized ? `Run ${n} case(s) in ${org}` : `Dry-run ${n} case(s)`,
        text: state.crmAuthorized ? `Each case creates its test records through the ${target} API, checks every assertion and deletes what it created. You'll see pass or fail per case.` : `${target} isn't connected: the plans are checked for mistakes but nothing is sent.`,
        primary: { label: state.crmAuthorized ? "Run cases →" : "Dry run →", proxy: "execute-btn" } });
      if (scoreE) scoreE.innerHTML = ""; guide.needs("execute", []); guide.plain("execute", []); guide.sub("execute", "ready");
    } else {
      const dry = !!(ex && ex.dry_run);
      guide.now("execute", { tone: failed ? "fail" : "ok", kicker: dry ? "Dry run done" : "Done",
        title: dry ? `${ex.plans_ok || 0} plan(s) runnable, ${ex.plans_with_issues || 0} need fixes` : failed ? `${failed} of ${n} case(s) failed` : `All ${passed} case(s) passed`,
        text: dry ? "Nothing was sent. Connect to run for real." : failed ? "Open the failures below, fix the org or the case, and re-run. Hand-off creates a bug for each failure." : "Every acceptance check held. Hand off to Zoho Projects for the record.",
        primary: { label: "Hand off →", proxy: "to-push-btn" }, secondary: { label: "Run again", proxy: "execute-btn" } });
      if (scoreE) scoreE.innerHTML = `<div class="score">${tile(`${passed} / ${n}`, "passed", failed ? "var(--err)" : "var(--ok)")}${tile(failed, "failed", failed ? "var(--err)" : "inherit")}${tile(notRun, "not run / skipped", notRun ? "var(--warn)" : "inherit")}</div>`;
      const fails = cases.filter((c) => st(c) === "fail");
      guide.needs("execute", fails.slice(0, 8).map((c) => ({ title: c.title || "case", text: escapeHtml((c.execution_result && (c.execution_result.first_failure || c.execution_result.reason)) || "an assertion failed"), html: `<button type="button" class="ghost small" data-open-fold="fold-execute">See the trace</button>` })), { title: "Failed", tone: "err" });
      const okRows = cases.filter((c) => st(c) === "pass").slice(0, 8).map((c) => Object.assign(caseRow(c), { tag: "pass", tone: "ok" }));
      if (passed > 8) okRows.push({ tag: "…", tone: "mute", title: `and ${passed - 8} more passed` });
      guide.plain("execute", okRows, "What passed", { text: `test records were ${dry ? "not created (dry run)" : "deleted afterwards"}`, tone: "ok" });
      guide.sub("execute", dry ? "dry run" : `${passed} passed${failed ? ` · ${failed} failed` : ""}`);
    }

    // ---- Hand off ----
    const scoreP = document.getElementById("score-push");
    const pu = uatLast.push;
    const project = (els.projectName && els.projectName.value.trim()) || "";
    if (!state.executed) {
      guide.now("push", { tone: "wait", kicker: "First", title: "Run the cases first", text: "Hand-off records each case as a task in Zoho Projects and each failure as a bug.", primary: { label: "← Run", href: "execute" } });
      if (scoreP) scoreP.innerHTML = ""; guide.needs("push", []); guide.sub("push", "");
    } else if (!state.pushed) {
      guide.now("push", { tone: "act", kicker: "Now", title: `Push ${n} case(s) and ${failed} bug(s) to Zoho Projects`,
        text: `Project: ${project ? `“${escapeHtml(project)}” (reused if it exists)` : "type a name in the box above"}. One task per case with its result; one bug per failure with the trace.`,
        primary: { label: "Push to Projects →", proxy: "push-btn" }, secondary: { label: "Download CSV", proxy: "download-csv-btn" } });
      if (scoreP) scoreP.innerHTML = `<div class="score">${tile(n, "tasks to create")}${tile(failed, "bugs to create", failed ? "var(--err)" : "inherit")}${tile(project ? "set" : "—", "project name", project ? "var(--ok)" : "var(--warn)")}</div>`;
      guide.needs("push", project ? [] : [{ title: "Name the Zoho Projects project", text: "An existing project with this name is reused; otherwise one is created.", html: `<button type="button" class="ghost small" data-proxy="project_name">Type a name</button>` }]);
      guide.sub("push", "ready");
    } else {
      const tf = pu ? (pu.tasks_failed || 0) + (pu.bugs_failed || 0) : 0;
      guide.now("push", { tone: tf ? "fail" : "ok", kicker: "Done", title: pu ? `${pu.tasks_created || 0} task(s) and ${pu.bugs_created || 0} bug(s) in Zoho Projects` : "Pushed to Zoho Projects",
        text: pu && pu.project_name ? `Project “${escapeHtml(pu.project_name)}”${pu.mock ? " (mock push)" : ""}.` : "", primary: pu && pu.project_url && !pu.mock ? { label: "Open in Zoho Projects →", act: "open-url", title: pu.project_url } : null, secondary: { label: "Start over", proxy: "restart-btn" } });
      if (scoreP) scoreP.innerHTML = `<div class="score">${tile(pu ? pu.tasks_created || 0 : n, "tasks created", "var(--ok)")}${tile(pu ? pu.bugs_created || 0 : failed, "bugs created", failed ? "var(--err)" : "inherit")}${tile(tf, "failed to create", tf ? "var(--err)" : "inherit")}</div>`;
      guide.needs("push", tf ? [{ title: `${tf} item(s) weren't created in Projects`, text: "See which ones in the details and push again; existing ones are reused.", html: `<button type="button" class="ghost small" data-open-fold="fold-push">See details</button>` }] : []);
      guide.sub("push", "done");
    }
  }
  document.addEventListener("click", (e) => {
    const t = e.target.closest('[data-guide-act="open-url"]');
    if (t && t.title) window.open(t.title, "_blank", "noopener");
  });
  document.addEventListener("input", (e) => { if (e.target && e.target.id === "project_name") renderGuideUat(); });

  const PANEL_BUSY_TITLE = {
    input: "Reading the document", cases: "Writing UAT cases", execute: "Running cases in Zoho CRM",
    push: "Pushing to Zoho Projects", blueprint: "Drafting the implementation blueprint", build: "Building", verify: "Verifying",
  };

  function startGenLoader() {
    const loaderEl = document.querySelector('[data-panel="cases"] .loader');
    if (loaderEl) loaderEl.classList.add("fullscreen");
    const msgEl  = document.getElementById("gen-message");
    const fillEl = document.getElementById("gen-bar-fill");
    if (!msgEl) return;
    let idx = 0;
    _genProgressVal = 0;
    if (fillEl) { fillEl.style.transition = "none"; fillEl.style.width = "0%"; }
    msgEl.classList.remove("fading");
    msgEl.textContent = GEN_MESSAGES[0];
    setTimeout(() => { if (fillEl) fillEl.style.transition = "width 0.6s ease"; }, 60);

    _genMsgTimer = setInterval(() => {
      const el = document.getElementById("gen-message");
      if (!el) return;
      el.classList.add("fading");
      setTimeout(() => {
        idx = (idx + 1) % GEN_MESSAGES.length;
        el.textContent = GEN_MESSAGES[idx];
        el.classList.remove("fading");
        busy.update({ detail: GEN_MESSAGES[idx] });
      }, 350);
    }, 3200);

    // Creep to 82% over ~25s then stall until done
    _genProgressTimer = setInterval(() => {
      _genProgressVal = Math.min(_genProgressVal + 2.8, 82);
      const f = document.getElementById("gen-bar-fill");
      if (f) f.style.width = _genProgressVal + "%";
      if (_genProgressVal >= 82) { clearInterval(_genProgressTimer); _genProgressTimer = null; }
    }, 700);
  }

  function stopGenLoader() {
    document.getElementById("gen-job-steps")?.remove();
    const loaderEl = document.querySelector('[data-panel="cases"] .loader');
    if (loaderEl) loaderEl.classList.remove("fullscreen");
    clearInterval(_genMsgTimer);
    clearInterval(_genProgressTimer);
    _genMsgTimer = null;
    _genProgressTimer = null;
    const fillEl = document.getElementById("gen-bar-fill");
    if (fillEl) { fillEl.style.transition = "width 0.3s ease"; fillEl.style.width = "100%"; }
  }

  // ---- Execution animated loader ----

  const EXEC_MESSAGES = [
    "Connecting to Zoho CRM…",
    "Running test steps…",
    "Evaluating assertions…",
    "Checking response data…",
    "Processing results…",
    "Still running — complex cases take time…",
    "Wrapping up execution…",
    "Almost done…",
  ];
  let _execMsgTimer = null, _execProgressTimer = null, _execProgressVal = 0;

  const EXEC_QUOTES_PASS = [
    "Houston, we have no problems.",
    "All systems nominal. Cleared for production.",
    "Zero defects. The spec and the system agree perfectly.",
    "Clean sweep. Your CRM does exactly what it promises.",
    "Mission accomplished. The flight plan held.",
    "Green across the board. Ship it.",
  ];
  const EXEC_QUOTES_FAIL = [
    "The test suite caught them before your users did. That's the job.",
    "Every failure is a finding. Every finding is progress.",
    "Cracks found and mapped. Now you know exactly where to look.",
    "The gap between spec and implementation has been measured.",
    "Good catch. Fix, re-run, repeat.",
  ];

  function startExecLoader() {
    const loaderEl = document.querySelector('[data-panel="execute"] .loader');
    if (loaderEl) loaderEl.classList.add("fullscreen");
    const msgEl  = document.getElementById("exec-gen-message");
    const fillEl = document.getElementById("exec-bar-fill");
    if (!msgEl) return;
    let idx = 0;
    _execProgressVal = 0;
    if (fillEl) { fillEl.style.transition = "none"; fillEl.style.width = "0%"; }
    msgEl.classList.remove("fading");
    msgEl.textContent = EXEC_MESSAGES[0];
    setTimeout(() => { if (fillEl) fillEl.style.transition = "width 0.6s ease"; }, 60);
    _execMsgTimer = setInterval(() => {
      const el = document.getElementById("exec-gen-message");
      if (!el) return;
      el.classList.add("fading");
      setTimeout(() => {
        idx = (idx + 1) % EXEC_MESSAGES.length;
        el.textContent = EXEC_MESSAGES[idx];
        el.classList.remove("fading");
      }, 350);
    }, 4000);
    _execProgressTimer = setInterval(() => {
      _execProgressVal = Math.min(_execProgressVal + 1.2, 78);
      const f = document.getElementById("exec-bar-fill");
      if (f) f.style.width = _execProgressVal + "%";
      if (_execProgressVal >= 78) { clearInterval(_execProgressTimer); _execProgressTimer = null; }
    }, 700);
  }

  function stopExecLoader() {
    document.getElementById("exec-job-steps")?.remove();
    const loaderEl = document.querySelector('[data-panel="execute"] .loader');
    if (loaderEl) loaderEl.classList.remove("fullscreen");
    clearInterval(_execMsgTimer);
    clearInterval(_execProgressTimer);
    _execMsgTimer = null; _execProgressTimer = null;
    const fillEl = document.getElementById("exec-bar-fill");
    if (fillEl) { fillEl.style.transition = "width 0.3s ease"; fillEl.style.width = "100%"; }
  }

  // Real progress replaces the cycling messages and the creeping bar once a staged run reports it.
  // jobStatus (background jobs) adds the step list; hovering a step shows what it covers.
  function loaderProgress(kind, text, fraction, jobStatus) {
    const ids = kind === "exec"
      ? { msg: "exec-gen-message", fill: "exec-bar-fill" }
      : { msg: "gen-message", fill: "gen-bar-fill" };
    if (kind === "exec") {
      clearInterval(_execMsgTimer); clearInterval(_execProgressTimer);
      _execMsgTimer = null; _execProgressTimer = null;
    } else {
      clearInterval(_genMsgTimer); clearInterval(_genProgressTimer);
      _genMsgTimer = null; _genProgressTimer = null;
    }
    const msgEl = document.getElementById(ids.msg);
    if (msgEl) { msgEl.classList.remove("fading"); msgEl.textContent = text; }
    const fillEl = document.getElementById(ids.fill);
    if (fillEl && typeof fraction === "number") fillEl.style.width = Math.min(95, Math.round(fraction * 100)) + "%";
    renderJobSteps(kind, msgEl, jobStatus);
    busy.update({ detail: text, fraction: typeof fraction === "number" ? fraction : undefined,
      steps: jobStatus && Array.isArray(jobStatus.steps) ? jobStatus.steps : undefined });
  }

  const RESULT_TEXT = { pass: "Passed", fail: "Failed", dry_run: "Plan checked", skipped: "Skipped" };

  // Step rows for a background UAT job, styled like the Blueprint progress (.ip-* in styles.css).
  function renderJobSteps(kind, msgEl, st) {
    const id = kind + "-job-steps";
    let box = document.getElementById(id);
    if (!st || !Array.isArray(st.steps) || !st.steps.length || !msgEl) { if (box) box.remove(); return; }
    if (!box) {
      box = document.createElement("div");
      box.id = id;
      box.className = "ip ip-inline uat-job";
      msgEl.insertAdjacentElement("afterend", box);
    }
    let steps = st.steps;
    let summary = "";
    if (kind === "exec") {
      // One step per case: the counts, the case running now and the last few finished.
      const n = {};
      steps.forEach((x) => { if (x.result) n[x.result] = (n[x.result] || 0) + 1; });
      summary = Object.entries(n).map(([k, v]) => `${v} ${(RESULT_TEXT[k] || k).toLowerCase()}`).join(" · ");
      const working = steps.filter((x) => x.state === "working");
      const finished = steps.filter((x) => x.state === "done" || x.state === "failed").slice(-4);
      const waiting = steps.filter((x) => x.state === "waiting").length;
      steps = finished.concat(working);
      if (waiting) steps = steps.concat([{ label: `${waiting} more case${waiting === 1 ? "" : "s"}`, state: "waiting" }]);
    }
    const fmt = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
    const note = (x) => x.result ? (RESULT_TEXT[x.result] || x.result) + (x.seconds ? ` · ${x.seconds}s` : "")
      : x.state === "done" ? `${x.items || 0} case${x.items === 1 ? "" : "s"} · ${x.seconds || 0}s`
      : x.state === "failed" ? "Failed"
      : x.state === "working" ? (x.started_at ? `Working · ${fmt(Date.now() - x.started_at)}` : "Working")
      : "Queued";
    box.innerHTML = (summary ? `<p class="ip-sub">${escapeHtml(summary)}</p>` : "")
      + `<ol class="ip-steps">${steps.map((x) => `
          <li class="ip-step is-${escapeHtml(x.state || "waiting")}">
            <span class="ip-icon" aria-hidden="true"></span>
            <span class="ip-text"><span class="ip-label">${escapeHtml(x.label || "")}</span>${(x.state === "failed" && x.error) || x.detail ? `<span class="ip-detail">${escapeHtml(x.state === "failed" && x.error ? x.error : x.detail)}</span>` : ""}</span>
            <span class="ip-state">${escapeHtml(note(x))}</span>
          </li>`).join("")}</ol>`
      + `<p class="ip-note">Runs in the background. You can leave this page.</p>`;
  }

  // ---- Push animated loader ----

  const PUSH_MESSAGES = [
    "Creating tasks in Zoho Projects…",
    "Filing bugs for failed cases…",
    "Linking tasks to project…",
    "Finalising records…",
    "Almost done…",
  ];
  let _pushMsgTimer = null, _pushProgressTimer = null, _pushProgressVal = 0;

  function startPushLoader() {
    const loaderEl = document.querySelector('[data-panel="push"] .loader');
    if (loaderEl) loaderEl.classList.add("fullscreen");
    const msgEl  = document.getElementById("push-gen-message");
    const fillEl = document.getElementById("push-bar-fill");
    if (!msgEl) return;
    let idx = 0;
    _pushProgressVal = 0;
    if (fillEl) { fillEl.style.transition = "none"; fillEl.style.width = "0%"; }
    msgEl.classList.remove("fading");
    msgEl.textContent = PUSH_MESSAGES[0];
    setTimeout(() => { if (fillEl) fillEl.style.transition = "width 0.6s ease"; }, 60);
    _pushMsgTimer = setInterval(() => {
      const el = document.getElementById("push-gen-message");
      if (!el) return;
      el.classList.add("fading");
      setTimeout(() => {
        idx = (idx + 1) % PUSH_MESSAGES.length;
        el.textContent = PUSH_MESSAGES[idx];
        el.classList.remove("fading");
      }, 350);
    }, 4500);
    _pushProgressTimer = setInterval(() => {
      _pushProgressVal = Math.min(_pushProgressVal + 2.5, 80);
      const f = document.getElementById("push-bar-fill");
      if (f) f.style.width = _pushProgressVal + "%";
      if (_pushProgressVal >= 80) { clearInterval(_pushProgressTimer); _pushProgressTimer = null; }
    }, 700);
  }

  function stopPushLoader() {
    const loaderEl = document.querySelector('[data-panel="push"] .loader');
    if (loaderEl) loaderEl.classList.remove("fullscreen");
    clearInterval(_pushMsgTimer);
    clearInterval(_pushProgressTimer);
    _pushMsgTimer = null; _pushProgressTimer = null;
    const fillEl = document.getElementById("push-bar-fill");
    if (fillEl) { fillEl.style.transition = "width 0.3s ease"; fillEl.style.width = "100%"; }
  }

  // ---- Count-up animation on numeric content of an element ----
  function animateCountUp(el, to, duration = 800) {
    if (!el) return;
    const from = parseInt(el.dataset.lastCount || "0", 10) || 0;
    el.dataset.lastCount = String(to);
    if (from === to) { el.textContent = String(to); return; }
    const t0 = performance.now();
    const step = (t) => {
      const p = Math.min(1, (t - t0) / duration);
      const ease = 1 - Math.pow(1 - p, 3);
      const val = Math.round(from + (to - from) * ease);
      el.textContent = String(val);
      if (p < 1) requestAnimationFrame(step);
      else {
        el.textContent = String(to);
        el.classList.remove("count-flash");
        void el.offsetWidth;
        el.classList.add("count-flash");
      }
    };
    requestAnimationFrame(step);
  }

  // ---- Confetti burst for all-pass celebration ----
  function celebrateAllPass() {
    const colors = ["#0052CC", "#F26C01", "#00875A", "#FF8B00", "#4C9AFF"];
    const host = document.createElement("div");
    host.className = "confetti-host";
    document.body.appendChild(host);
    const N = 80;
    for (let i = 0; i < N; i++) {
      const p = document.createElement("span");
      p.className = "confetti-piece";
      const startX = Math.random() * 100;
      const driftX = (Math.random() - 0.5) * 200;
      p.style.left = startX + "vw";
      p.style.setProperty("--cx", driftX + "px");
      p.style.background = colors[Math.floor(Math.random() * colors.length)];
      p.style.transform = `rotate(${Math.random() * 360}deg)`;
      p.style.animationDelay = Math.random() * 400 + "ms";
      p.style.animationDuration = 1400 + Math.random() * 800 + "ms";
      host.appendChild(p);
    }
    setTimeout(() => host.remove(), 3000);
  }

  // ---- Toast notifications ----
  function showToast(msg, kind, timeout) {
    const host = document.getElementById("toast-host");
    if (!host) return;
    const t = document.createElement("div");
    t.className = "toast " + (kind || "info");
    t.textContent = msg;
    host.appendChild(t);
    const ms = typeof timeout === "number" ? timeout : 3500;
    setTimeout(() => {
      t.classList.add("fade-out");
      setTimeout(() => t.remove(), 400);
    }, ms);
  }

  // ---- Tab management ----

  function getTabBtn(name) {
    return Array.from(els.tabs).find((b) => b.dataset.tab === name);
  }
  function getPanel(name) {
    return Array.from(els.panels).find((p) => p.dataset.panel === name);
  }
  // Step icons: one per tab, inside the number badge (the number moves to a small corner tag).
  const TAB_ICONS = {
    input: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
    cases: '<path d="M9 5h6l1 2h3v13H5V7h3z"/><path d="m9 13 2 2 4-4"/>',
    execute: '<path d="M5 4v16l14-8z"/>',
    push: '<path d="M12 19V7M6 13l6-6 6 6"/><path d="M4 3h16"/>',
    blueprint: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M4 10h16M10 10v10M14 4v6"/>',
    build: '<path d="m14.5 6.5 3 3L7 20H4v-3z"/><path d="m15 4 1.5-1.5a2.1 2.1 0 0 1 3 3L18 7"/><path d="M4 20l3-3"/>',
    verify: '<path d="M12 3l7 3v6c0 4.5-3 7.8-7 9-4-1.2-7-4.5-7-9V6z"/><path d="m9 12 2 2 4-4"/>',
  };
  document.querySelectorAll(".sidebar .tab").forEach((t) => {
    const n = t.querySelector(".tab-num"), ic = TAB_ICONS[t.dataset.tab];
    if (!n || !ic) return;
    n.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ic}</svg><i class="tab-step">${escapeHtml(t.dataset.step || "")}</i>`;
    n.classList.add("has-icon");
  });

  // ---- Products: one adapter per Zoho product on the server; the browser only names it ----
  const PRODUCTS = {
    crm:     { label: "Zoho CRM",     modules: ["Leads", "Contacts", "Accounts", "Deals"], entity: "modules" },
    desk:    { label: "Zoho Desk",    modules: ["Tickets", "Contacts", "Accounts", "Tasks"], entity: "modules" },
    creator: { label: "Zoho Creator", modules: [], entity: "forms" },
  };
  function productLabel(p) { return (PRODUCTS[p || state.platform] || PRODUCTS.crm).label; }
  // Which product a BRD is about, from its vocabulary. Returns null when it isn't clear.
  function detectProduct(text) {
    const t = (text || "").slice(0, 20000);
    const score = (res) => res.reduce((n, re) => n + (t.match(re) || []).length, 0);
    const desk = score([/\btickets?\b/gi, /\bagents?\b/gi, /\bSLAs?\b/g, /\bhelp ?desk\b/gi, /\bdepartments?\b/gi, /\bzoho desk\b/gi, /\bmacros?\b/gi]);
    const crm = score([/\bleads?\b/gi, /\bdeals?\b/gi, /\bpipeline\b/gi, /\bzoho crm\b/gi, /\bopportunit(y|ies)\b/gi, /\bcampaigns?\b/gi]);
    const creator = score([/\bzoho creator\b/gi, /\bforms?\b/gi, /\breports?\b/gi]);
    if (desk >= 3 && desk > crm * 1.5 && desk >= creator) return "desk";
    if (crm >= 2 && crm >= desk) return "crm";
    if (creator >= 4 && creator > crm && creator > desk) return "creator";
    return null;
  }
  window.LiftOff = Object.assign(window.LiftOff || {}, { productLabel, PRODUCTS });

  // Sidebar collapse: icons only, remembered per browser.
  (function initSidebarToggle() {
    const app = document.querySelector(".app"), btn = document.getElementById("side-toggle");
    if (!app || !btn) return;
    const apply = (collapsed) => {
      app.classList.toggle("side-collapsed", collapsed);
      btn.setAttribute("aria-expanded", String(!collapsed));
      btn.title = btn.ariaLabel = collapsed ? "Expand sidebar" : "Collapse sidebar";
      document.querySelectorAll(".sidebar .tab").forEach((t) => {
        const lbl = t.querySelector(".tab-label");
        const name = lbl ? (lbl.childNodes[0] && lbl.childNodes[0].textContent || "").trim() : "";
        if (collapsed && name) t.title = name; else t.removeAttribute("title");
      });
      try { localStorage.setItem("liftoff.sideCollapsed", collapsed ? "1" : "0"); } catch (e) { /* ignore */ }
    };
    let saved = false;
    try { saved = localStorage.getItem("liftoff.sideCollapsed") === "1"; } catch (e) { /* ignore */ }
    apply(saved);
    btn.addEventListener("click", () => apply(!app.classList.contains("side-collapsed")));
  })();

  function setTabState(name, opts) {
    const btn = getTabBtn(name);
    if (!btn) return;
    if (opts.locked !== undefined) btn.classList.toggle("locked", opts.locked);
    if (opts.done   !== undefined) btn.classList.toggle("done",   opts.done);
    if (opts.running !== undefined) btn.classList.toggle("running", opts.running);
  }
  function showTab(name) {
    if (!TABS.includes(name)) return;
    const btn = getTabBtn(name);
    if (btn && btn.classList.contains("locked")) return;
    state.activeTab = name;
    if (["cases", "execute", "push"].includes(name)) setTimeout(renderGuideUat, 0);
    els.tabs.forEach((b) => {
      const isActive = b.dataset.tab === name;
      b.classList.toggle("active", isActive);
      b.setAttribute("aria-selected", String(isActive));
    });
    els.panels.forEach((p) => {
      const active = p.dataset.panel === name;
      p.classList.toggle("active", active);
      p.hidden = !active;
      if (active) {
        // Restart the panel-enter animation so each tab switch animates in.
        p.style.animation = "none";
        void p.offsetWidth;
        p.style.animation = "";
        // Restart all scene-banner child animations (tab-anim elements).
        p.querySelectorAll(".tab-anim").forEach((el) => {
          el.style.animation = "none";
          void el.offsetWidth;
          el.style.animation = "";
        });
      }
    });
  }
  function markStepDone(name) {
    state.stepDone[name] = true;
    setTabState(name, { done: true, running: false });
    if (name === "push") return;
    const next = TABS[TABS.indexOf(name) + 1];
    if (next) setTabState(next, { locked: false });
  }
  function showLoader(panelName, text) {
    const panel = getPanel(panelName);
    const loader = panel.querySelector(".loader");
    const textEl = loader.querySelector(".loader-text");
    if (text && textEl) textEl.textContent = text;
    loader.hidden = false;
    setTabState(panelName, { running: true });
    busy.start(PANEL_BUSY_TITLE[panelName] || "Working", text || "");
  }
  function hideLoader(panelName) {
    const panel = getPanel(panelName);
    const loader = panel.querySelector(".loader");
    loader.hidden = true;
    setTabState(panelName, { running: false });
    busy.end();
  }

  els.tabs.forEach((btn) => {
    btn.addEventListener("click", () => showTab(btn.dataset.tab));
  });

  // ---- Status helpers ----

  function setStatus(panelName, msg, kind) {
    const el = {
      input: els.statusInput,
      cases: els.statusCases,
      execute: els.statusExecute,
      push: els.statusPush,
      blueprint: document.getElementById("status-blueprint"),
      build: document.getElementById("status-build"),
      verify: document.getElementById("status-verify"),
    }[panelName];
    if (!el) return;
    el.textContent = msg || "";
    el.className = "status" + (kind ? " " + kind : "");
  }

  // ---- File parsing ----

  async function readFileAsArrayBuffer(file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = () => reject(r.error);
      r.readAsArrayBuffer(file);
    });
  }
  async function readFileAsText(file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = () => reject(r.error);
      r.readAsText(file);
    });
  }
  async function parsePdf(file) {
    if (!window.pdfjsLib) throw new Error("PDF parser failed to load.");
    const buf = await readFileAsArrayBuffer(file);
    const pdf = await window.pdfjsLib.getDocument({ data: buf }).promise;
    const pages = [];
    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i);
      const content = await page.getTextContent();
      pages.push(content.items.map((it) => it.str).join(" "));
    }
    return pages.join("\n\n");
  }
  async function parseDocx(file) {
    if (!window.mammoth) throw new Error("DOCX parser failed to load.");
    const buf = await readFileAsArrayBuffer(file);
    const result = await window.mammoth.extractRawText({ arrayBuffer: buf });
    return result.value || "";
  }
  // A .txt saved by TextEdit in rich-text mode (or a .rtf) is RTF markup: font and list tables,
  // control words and \'hh escapes. Sent as is, that markup crowds out the BRD and the Zia Agent
  // rejects it, so keep only the document's text.
  function rtfToText(rtf) {
    const SKIP = /^(fonttbl|colortbl|expandedcolortbl|stylesheet|listtable|listoverridetable|info|pict|header|footer|headerl|headerr|footerl|footerr|themedata|colorschememapping|latentstyles|datastore|xmlnstbl|rsidtbl|generator|filetbl|revtbl|object|fldinst)$/;
    const CP1252 = { 0x80: 8364, 0x82: 8218, 0x83: 402, 0x84: 8222, 0x85: 8230, 0x86: 8224, 0x87: 8225, 0x88: 710,
      0x89: 8240, 0x8a: 352, 0x8b: 8249, 0x8c: 338, 0x8e: 381, 0x91: 8216, 0x92: 8217, 0x93: 8220, 0x94: 8221,
      0x95: 8226, 0x96: 8211, 0x97: 8212, 0x98: 732, 0x99: 8482, 0x9a: 353, 0x9b: 8250, 0x9c: 339, 0x9e: 382, 0x9f: 376 };
    const stack = [];
    let skip = false, uc = 1, pendingSkip = 0, out = "", i = 0;
    const emit = (t) => {
      if (skip) return;
      if (pendingSkip > 0) { pendingSkip -= t.length; return; }   // fallback chars after \uN
      out += t;
    };
    while (i < rtf.length) {
      const c = rtf[i];
      if (c === "{") {
        stack.push({ skip, uc });
        if (rtf.startsWith("{\\*", i)) skip = true;                 // unknown destination
        i++;
      } else if (c === "}") {
        const st = stack.pop() || { skip: false, uc: 1 };
        skip = st.skip; uc = st.uc; i++;
      } else if (c === "\\") {
        const n = rtf[i + 1];
        if (n === "\\" || n === "{" || n === "}") { emit(n); i += 2; }
        else if (n === "\n" || n === "\r") { emit("\n"); i += 2; }
        else if (n === "~") { emit(" "); i += 2; }
        else if (n === "-" || n === "_") { emit(n === "_" ? "-" : ""); i += 2; }
        else if (n === "'") {
          const code = parseInt(rtf.substr(i + 2, 2), 16);
          if (!isNaN(code)) emit(String.fromCharCode(code === 0xa0 ? 32 : (CP1252[code] || code)));
          i += 4;
        } else {
          const m = /^([a-zA-Z]+)(-?\d+)? ?/.exec(rtf.slice(i + 1, i + 40));
          if (!m) { i += 2; continue; }
          i += 1 + m[0].length;
          const w = m[1], arg = m[2] === undefined ? null : parseInt(m[2], 10);
          if (SKIP.test(w)) skip = true;
          else if (w === "par" || w === "line" || w === "row") emit("\n");
          else if (w === "tab" || w === "cell") emit("\t");
          else if (w === "uc" && arg !== null) uc = arg;
          else if (w === "u" && arg !== null) { emit(String.fromCharCode(arg < 0 ? arg + 65536 : arg)); pendingSkip = uc; }
          else if (w === "bullet") emit("•");
          else if (w === "emdash") emit("—");
          else if (w === "endash") emit("–");
          else if (w === "lquote" || w === "rquote") emit("'");
          else if (w === "ldblquote" || w === "rdblquote") emit('"');
        }
      } else {
        if (c !== "\n" && c !== "\r") emit(c);
        i++;
      }
    }
    return out.replace(/ /g, " ").replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  }
  /** The BRD as plain text: a BRD loaded before RTF support may still be RTF markup. */
  function plainBrd(text) {
    return /^\s*\{\\rtf/.test(text || "") ? rtfToText(text) : (text || "");
  }
  function detectKind(file) {
    const name = (file.name || "").toLowerCase();
    if (name.endsWith(".pdf") || file.type === "application/pdf") return "pdf";
    if (name.endsWith(".docx") ||
        file.type === "application/vnd.openxmlformats-officedocument.wordprocessingml.document") return "docx";
    if (name.endsWith(".md") || name.endsWith(".markdown")) return "md";
    if (name.endsWith(".txt") || name.endsWith(".rtf") || (file.type || "").startsWith("text/")
        || file.type === "application/rtf") return "txt";
    return null;
  }

  async function handleFile(file) {
    if (!file) return;
    const kind = detectKind(file);
    if (!kind) {
      setStatus("input", "Unsupported file type. Use .txt, .md, .rtf, .pdf, or .docx.", "err");
      els.fileMeta.textContent = "";
      state.brd = "";
    renderRequirementsTable();
      state.fileName = "";
      renderRequirementsTable();
      els.generate.disabled = true;
      setTabState("input", { done: false });
      return;
    }
    showLoader("input", `Parsing ${file.name}...`);
    els.fileMeta.textContent = `Reading ${file.name}...`;
    try {
      let text = "";
      if (kind === "pdf") text = await parsePdf(file);
      else if (kind === "docx") text = await parseDocx(file);
      else text = await readFileAsText(file);
      if (/^\s*\{\\rtf/.test(text || "")) text = rtfToText(text);
      text = (text || "").trim();
      if (!text) throw new Error("File parsed but no text was extracted.");
      state.brd = text;
      state.fileName = file.name;
      if (!state.platformManual) {
        const p = detectProduct(text);
        if (p && p !== state.platform) { switchPlatform(p, { auto: true }); showToast(`${productLabel(p)} detected from the BRD — change it in Brief → details if that's wrong`, "info", 5000); }
      }
      renderRequirementsTable();
      els.fileMeta.textContent =
        `${file.name} — ${kind.toUpperCase()}, ${text.length.toLocaleString()} chars`;
      saveStateToStorage();
      // Suggest a Projects project name from the spec filename so the user
      // doesn't have to type it. They can still override on the Land tab.
      if (els.projectName && !els.projectName.value.trim()) {
        const base = file.name.replace(/\.[^.]+$/, "").replace(/[_\-]+/g, " ").trim();
        const today = new Date().toISOString().slice(0, 10);
        els.projectName.value = `Test Plan — ${base} — ${today}`;
      }
      refreshGenerateAvailability();
      const readyForCreator = state.platform === "creator" && !!getCreatorFormLink();
      if (state.modulesSelected.length || readyForCreator) markStepDone("input");
      setStatus("input",
        (state.modulesSelected.length || readyForCreator)
          ? "Spec parsed. Click Generate UAT cases."
          : state.platform === "creator"
            ? "Spec parsed. Enter the form name above to continue."
            : "Spec parsed. Analyzing for module suggestions...",
        (state.modulesSelected.length || readyForCreator) ? "ok" : "");
      // Run spec analysis to auto-suggest modules (CRM only). Non-blocking.
      if (state.platform !== "creator") analyzeBrd().then(() => {
        if (!state.modulesSelected.length) {
          setStatus("input", "Spec parsed. Now pick up to 5 target modules.", "");
        } else if (state.suggestedModules.length) {
          setStatus("input",
            `Spec parsed. ${state.suggestedModules.length} module(s) suggested — review and click Generate.`,
            "ok");
        }
      });
    } catch (err) {
      state.brd = "";
      state.fileName = "";
      renderRequirementsTable();
      els.fileMeta.textContent = "";
      refreshGenerateAvailability();
      setStatus("input", "Parse error: " + err.message, "err");
    } finally {
      hideLoader("input");
    }
  }

  // ---- Cases rendering ----

  function escapeHtml(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
  }

  function caseCardHtml(tc, idx, { showExec = false, showPush = false } = {}) {
    const priority = (tc.priority || "P1").toLowerCase();
    const reqChips = (Array.isArray(tc.req_ids) ? tc.req_ids : [])
      .map((id) => `<span class="tag tag-req" title="Requirement">${escapeHtml(id)}</span>`).join("");
    const tags = reqChips + (tc.tags || []).map((t) => `<span class="tag">${escapeHtml(t)}</span>`).join("");
    const execBadge = showExec ? renderExecBadge(tc.execution_result) : "";
    const planSummary = renderPlanSummary(tc.execution_plan);
    const execDetails = showExec ? renderExecDetails(tc.execution_result) : "";
    const pushInfo = showPush ? renderPushInfo(tc) : "";
    return `
      <div class="case" data-idx="${idx}">
        <h3>
          <span>${escapeHtml(tc.title || "Untitled case")}</span>
          <span class="header-right">
            ${execBadge}
            <span class="badge ${priority}">${escapeHtml(tc.priority || "P1")}</span>
          </span>
        </h3>
        <div class="tags">${tags}</div>
        <pre class="gherkin">${escapeHtml(tc.gherkin || "")}</pre>
        ${tc.acceptance ? `<p><strong>Acceptance:</strong> ${escapeHtml(tc.acceptance)}</p>` : ""}
        ${planSummary}
        ${execDetails}
        ${pushInfo}
      </div>
    `;
  }

  function renderExecBadge(exec) {
    if (!exec) return "";
    const status = exec.status || "";
    if (status === "dry_run") {
      const label = exec.plan_ok ? "DRY RUN · PLAN OK" : `DRY RUN · ${exec.issue_count || 0} ISSUE(S)`;
      return `<span class="exec-badge ${exec.plan_ok ? "skip" : "fail"}" title="Not executed against CRM">${escapeHtml(label)}</span>`;
    }
    const cls = status === "pass" ? "pass" : status === "fail" ? "fail" : "skip";
    return `<span class="exec-badge ${cls}">${escapeHtml(status.toUpperCase())}</span>`;
  }

  function renderPlanSummary(plan) {
    if (!Array.isArray(plan) || !plan.length) return "";
    const rows = plan
      .map(
        (s) =>
          `<li><code>${escapeHtml(s.method || "?")}</code> ${escapeHtml(
            s.path || ""
          )} <span class="muted">— ${escapeHtml(s.description || "")}</span></li>`
      )
      .join("");
    return `<details class="plan"><summary>Execution plan (${plan.length} step${plan.length === 1 ? "" : "s"})</summary><ul class="plan-list">${rows}</ul></details>`;
  }

  function renderExecDetails(exec) {
    if (!exec) return "";
    if (!Array.isArray(exec.trace) || !exec.trace.length) {
      const reason = exec.reason ? ` — ${escapeHtml(exec.reason)}` : "";
      return `<p class="exec-skip">No trace${reason}</p>`;
    }
    const rows = exec.trace
      .map((step) => {
        if (step.executed === false) {
          const issues = (step.issues || []).map((t) => `<li class="assertion fail">${escapeHtml(t)}</li>`).join("");
          const notes  = (step.notes  || []).map((t) => `<li class="assertion">${escapeHtml(t)}</li>`).join("");
          const cls = step.ok ? "ok" : "fail";
          return `
          <li class="trace-step ${cls}">
            <div class="trace-head">
              <span class="trace-method">${escapeHtml(step.method || "?")}</span>
              <code>${escapeHtml(step.path || "")}</code>
              <span class="trace-pill ${cls}">${step.ok ? "NOT RUN" : "PLAN ISSUE"}</span>
            </div>
            <div class="trace-desc">${escapeHtml(step.description || "")}</div>
            ${issues || notes ? `<ul class="assertions">${issues}${notes}</ul>` : ""}
          </li>`;
        }
        const ok = step.ok ? "ok" : "fail";
        const assertions = Array.isArray(step.assertions)
          ? step.assertions
              .map(
                (a) =>
                  `<li class="assertion ${a.ok ? "ok" : "fail"}"><code>${escapeHtml(
                    a.path || ""
                  )}</code> → actual <code>${escapeHtml(
                    String(a.actual)
                  )}</code>${
                    a.expected !== undefined
                      ? `, expected <code>${escapeHtml(String(a.expected))}</code>`
                      : a.expected_in
                      ? `, expected in <code>${escapeHtml(JSON.stringify(a.expected_in))}</code>`
                      : ""
                  }</li>`
              )
              .join("")
          : "";
        return `
          <li class="trace-step ${ok}">
            <div class="trace-head">
              <span class="trace-method">${escapeHtml(step.method || "?")}</span>
              <code>${escapeHtml(step.path || "")}</code>
              <span class="trace-status">${escapeHtml(String(step.status_code || ""))}</span>
              <span class="trace-pill ${ok}">${ok.toUpperCase()}</span>
            </div>
            <div class="trace-desc">${escapeHtml(step.description || "")}</div>
            ${assertions ? `<ul class="assertions">${assertions}</ul>` : ""}
          </li>
        `;
      })
      .join("");
    const open = exec.status === "fail" || (exec.status === "dry_run" && !exec.plan_ok);
    const title = exec.status === "dry_run" ? "Plan check (dry run — not executed)" : "Execution trace";
    return `<details class="trace" ${open ? "open" : ""}><summary>${title}</summary><ol class="trace-list">${rows}</ol></details>`;
  }

  function renderPushInfo(tc) {
    if (!tc.push_result) return "";
    const r = tc.push_result;
    const parts = [];
    if (r.task_id) {
      const cls = r.task_status === "mock" ? "muted" : "ok";
      parts.push(`<span class="push-pill ${cls}">Task ${escapeHtml(r.task_id)}</span>`);
    }
    if (r.bug_id) {
      const cls = r.bug_status === "mock" ? "muted" : "err";
      parts.push(`<span class="push-pill ${cls}">Bug ${escapeHtml(r.bug_id)}</span>`);
    }
    if (r.task_error) parts.push(`<span class="push-pill err">Task error: ${escapeHtml(r.task_error)}</span>`);
    if (r.bug_error) parts.push(`<span class="push-pill err">Bug error: ${escapeHtml(r.bug_error)}</span>`);
    return parts.length ? `<div class="push-info">${parts.join(" ")}</div>` : "";
  }

  // Stagger the caseEnter animation across ALL cards so they reveal one-by-one,
  // giving a "streaming" feel even though the response arrives all at once.
  function applyStreamReveal(container) {
    container.querySelectorAll(".case").forEach((el, i) => {
      el.style.animationDelay = Math.min(i * 45, 1600) + "ms";
    });
  }

  function renderCasesList(container, opts) {
    renderCasesListInner(container, opts);
    if (container === els.cases) renderCoverage(document.getElementById("coverage-cases"));
    if (container === els.execResults) renderCoverage(document.getElementById("coverage-exec"));
  }

  // Requirement → cases → results matrix. Counts come from state.cases, so it
  // stays correct after execution or when cases are edited.
  function renderCoverage(container) {
    if (!container) return;
    const reqs = Array.isArray(state.requirements) ? state.requirements : [];
    if (!reqs.length) { container.innerHTML = ""; return; }
    const byReq = new Map(reqs.map((r) => [r.id, { total: 0, pass: 0, fail: 0 }]));
    let untraced = 0;
    state.cases.forEach((tc) => {
      const ids = Array.isArray(tc.req_ids) ? tc.req_ids : [];
      if (!ids.length) {
        if (tc.spec_ref !== "Default API smoke test") untraced++;
        return;
      }
      const s = tc.execution_result && tc.execution_result.status;
      ids.forEach((id) => {
        const b = byReq.get(id);
        if (!b) return;
        b.total++;
        if (s === "pass") b.pass++;
        else if (s === "fail") b.fail++;
      });
    });
    const covered = reqs.filter((r) => byReq.get(r.id).total > 0).length;
    const rows = reqs.map((r) => {
      const b = byReq.get(r.id);
      let cls, label;
      if (!b.total)                { cls = r.in_run === false ? "skipped" : "gap";
                                     label = r.in_run === false ? "Not in this run" : "No cases"; }
      else if (b.fail)             { cls = "fail";    label = `${b.fail} failing`; }
      else if (b.pass === b.total) { cls = "pass";    label = "All passing"; }
      else if (b.pass)             { cls = "partial"; label = `${b.pass}/${b.total} passed`; }
      else                         { cls = "pending"; label = "Not run"; }
      return `<tr class="cov-${cls}"><td class="cov-id">${escapeHtml(r.id)}</td>` +
             `<td>${escapeHtml(r.title || "")}</td><td class="cov-num">${b.total}</td>` +
             `<td><span class="cov-status">${escapeHtml(label)}</span></td></tr>`;
    }).join("");
    const derivedNote = reqs.some((r) => r.derived)
      ? `<p class="cov-note">This BRD has no requirement IDs, so IDs were derived from its section headings.</p>` : "";
    container.innerHTML =
      `<details class="coverage"${covered < reqs.length ? " open" : ""}>` +
      `<summary>Requirement coverage: <strong>${covered} of ${reqs.length}</strong> covered` +
      `${untraced ? ` &bull; ${untraced} case(s) untraced` : ""}</summary>` +
      derivedNote +
      `<div class="cov-scroll"><table class="cov-table"><thead><tr>` +
      `<th>ID</th><th>Requirement</th><th>Cases</th><th>Status</th></tr></thead>` +
      `<tbody>${rows}</tbody></table></div></details>`;
  }

  function renderCasesListInner(container, opts) {
    const list = state.cases;
    if (!list.length) {
      container.innerHTML = '<div class="empty-state"><div class="empty-icon">\uD83D\uDCCB</div><div class="empty-title">No test cases yet</div><div class="empty-hint">Upload a BRD and click Generate to create cases.</div></div>';
      return;
    }
    // Group by module, preserving original order
    const groups = new Map();
    list.forEach((tc, i) => {
      const key = tc.module || "Other";
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push({ tc, i });
    });
    if (groups.size <= 1) {
      container.innerHTML = list.map((tc, i) => caseCardHtml(tc, i, opts)).join("");
      if (opts.streamReveal) applyStreamReveal(container);
      return;
    }
    const html = [];
    for (const [moduleName, items] of groups) {
      html.push(
        `<div class="module-group-head">${escapeHtml(moduleName)}` +
        ` <span class="module-group-count">${items.length} case${items.length === 1 ? "" : "s"}</span>` +
        `</div>`
      );
      items.forEach(({ tc, i }) => html.push(caseCardHtml(tc, i, opts)));
    }
    container.innerHTML = html.join("");
    if (opts.streamReveal) applyStreamReveal(container);
  }

  // ---- Sample template download ----

  // ---- BRD Template Builder ----

  const BRD_TYPES = ["String","Number","Boolean","Date","DateTime","Email","Phone","Picklist","Lookup","MultiSelect","Currency","Textarea"];

  function brdEl(tag, attrs, text) {
    var el = document.createElement(tag);
    for (var k in attrs) {
      if (k === "class") el.className = attrs[k];
      else el.setAttribute(k, attrs[k]);
    }
    if (text !== undefined) el.textContent = text;
    return el;
  }

  function brdInput(placeholder, value) {
    var inp = brdEl("input", { "class": "brd-in", type: "text", placeholder: placeholder });
    inp.value = value || "";
    return inp;
  }

  function brdDelBtn() {
    var btn = brdEl("button", { "class": "brd-del", type: "button", title: "Remove" }, "✕");
    return btn;
  }

  function brdTd(child) {
    var td = document.createElement("td");
    td.appendChild(child);
    return td;
  }

  function openBrdModal() {
    var overlay = document.getElementById("brd-modal-overlay");
    if (overlay) overlay.hidden = false;
    var tbody = document.getElementById("brd-fields-body");
    if (tbody && !tbody.children.length) {
      addBrdFieldRow(); addBrdFieldRow(); addBrdFieldRow();
    }
  }

  function closeBrdModal() {
    var overlay = document.getElementById("brd-modal-overlay");
    if (overlay) overlay.hidden = true;
  }

  function addBrdFieldRow() {
    var tbody = document.getElementById("brd-fields-body");
    if (!tbody) return;
    var tr = document.createElement("tr");

    var nameInp = brdInput("Field_Name", "");

    var typeSelect = brdEl("select", { "class": "brd-in" });
    BRD_TYPES.forEach(function(t) {
      var opt = brdEl("option", {}, t);
      if (t === "String") opt.selected = true;
      typeSelect.appendChild(opt);
    });

    var reqSelect = brdEl("select", { "class": "brd-in" });
    var optOpt = brdEl("option", {}, "Optional");
    optOpt.selected = true;
    reqSelect.appendChild(brdEl("option", {}, "Required"));
    reqSelect.appendChild(optOpt);

    var notesInp = brdInput("Notes / constraints", "");
    var delBtn   = brdDelBtn();

    tr.appendChild(brdTd(nameInp));
    tr.appendChild(brdTd(typeSelect));
    tr.appendChild(brdTd(reqSelect));
    tr.appendChild(brdTd(notesInp));
    var delTd = document.createElement("td");
    delTd.appendChild(delBtn);
    tr.appendChild(delTd);

    delBtn.addEventListener("click", function() { tr.parentNode && tr.parentNode.removeChild(tr); });
    tbody.appendChild(tr);
  }

  function addBrdFilterRow() {
    var tbody = document.getElementById("brd-filters-body");
    if (!tbody) return;
    var tr = document.createElement("tr");

    var fieldInp = brdInput("Field_Name", "");

    var typeSelect = brdEl("select", { "class": "brd-in" });
    ["is", "isn't", "contains", "not contains"].forEach(function(t) {
      typeSelect.appendChild(brdEl("option", {}, t));
    });

    var valueInp = brdInput("value", "");
    var delBtn   = brdDelBtn();

    tr.appendChild(brdTd(fieldInp));
    tr.appendChild(brdTd(typeSelect));
    tr.appendChild(brdTd(valueInp));
    var delTd = document.createElement("td");
    delTd.appendChild(delBtn);
    tr.appendChild(delTd);

    delBtn.addEventListener("click", function() { tr.parentNode && tr.parentNode.removeChild(tr); });
    tbody.appendChild(tr);
  }

  function getFieldNamesForRel() {
    var names = [];
    var rows = document.querySelectorAll("#brd-fields-body tr");
    for (var i = 0; i < rows.length; i++) {
      var inp = rows[i].querySelector("input");
      var v = inp ? inp.value.trim() : "";
      if (v) names.push(v);
    }
    return names;
  }

  function addBrdRelRow() {
    var tbody = document.getElementById("brd-rels-body");
    if (!tbody) return;
    var tr = document.createElement("tr");

    var modInp = brdInput("Accounts", "");

    var relSelect = brdEl("select", { "class": "brd-in" });
    ["1-1", "1-M", "M-1", "M-N"].forEach(function(t) {
      relSelect.appendChild(brdEl("option", {}, t));
    });

    // Field name: populate from existing field names as datalist suggestions
    var fieldNames = getFieldNamesForRel();
    var listId = "rel-fields-dl-" + Date.now();
    var fldInp = brdInput(fieldNames.length ? "pick or type" : "Account_Name", "");
    fldInp.setAttribute("list", listId);
    var dl = document.createElement("datalist");
    dl.id = listId;
    fieldNames.forEach(function(n) {
      var opt = document.createElement("option");
      opt.value = n;
      dl.appendChild(opt);
    });

    var delBtn = brdDelBtn();

    tr.appendChild(brdTd(modInp));
    tr.appendChild(brdTd(relSelect));
    var fldTd = document.createElement("td");
    fldTd.appendChild(fldInp);
    fldTd.appendChild(dl);
    tr.appendChild(fldTd);
    var delTd = document.createElement("td");
    delTd.appendChild(delBtn);
    tr.appendChild(delTd);

    delBtn.addEventListener("click", function() { tr.parentNode && tr.parentNode.removeChild(tr); });
    tbody.appendChild(tr);
  }

  // ---- Auto-fill default CRM fields by module name ----
  var CRM_DEFAULT_FIELDS = {
    Leads:    [
      { name: "Last_Name",      type: "String",   req: "Required", notes: "Contact last name" },
      { name: "First_Name",     type: "String",   req: "Optional", notes: "" },
      { name: "Email",          type: "Email",    req: "Optional", notes: "Must be unique" },
      { name: "Phone",          type: "Phone",    req: "Optional", notes: "" },
      { name: "Company",        type: "String",   req: "Optional", notes: "" },
      { name: "Lead_Status",    type: "Picklist", req: "Optional", notes: "Default: New" },
      { name: "Lead_Source",    type: "Picklist", req: "Optional", notes: "" },
      { name: "Annual_Revenue", type: "Currency", req: "Optional", notes: "" },
    ],
    Contacts: [
      { name: "Last_Name",   type: "String",   req: "Required", notes: "" },
      { name: "First_Name",  type: "String",   req: "Optional", notes: "" },
      { name: "Email",       type: "Email",    req: "Optional", notes: "" },
      { name: "Phone",       type: "Phone",    req: "Optional", notes: "" },
      { name: "Account_Name",type: "Lookup",   req: "Optional", notes: "Links to Accounts" },
      { name: "Title",       type: "String",   req: "Optional", notes: "" },
      { name: "Department",  type: "String",   req: "Optional", notes: "" },
    ],
    Accounts: [
      { name: "Account_Name",   type: "String",   req: "Required", notes: "" },
      { name: "Phone",          type: "Phone",    req: "Optional", notes: "" },
      { name: "Website",        type: "String",   req: "Optional", notes: "" },
      { name: "Industry",       type: "Picklist", req: "Optional", notes: "" },
      { name: "Annual_Revenue", type: "Currency", req: "Optional", notes: "" },
      { name: "Rating",         type: "Picklist", req: "Optional", notes: "" },
    ],
    Deals: [
      { name: "Deal_Name",    type: "String",   req: "Required", notes: "" },
      { name: "Stage",        type: "Picklist", req: "Required", notes: "e.g. Qualification" },
      { name: "Closing_Date", type: "Date",     req: "Required", notes: "YYYY-MM-DD" },
      { name: "Amount",       type: "Currency", req: "Optional", notes: "" },
      { name: "Account_Name", type: "Lookup",   req: "Optional", notes: "" },
      { name: "Probability",  type: "Number",   req: "Optional", notes: "" },
    ],
    Tasks: [
      { name: "Subject",     type: "String",   req: "Required", notes: "" },
      { name: "Due_Date",    type: "Date",     req: "Optional", notes: "" },
      { name: "Status",      type: "Picklist", req: "Optional", notes: "Default: Not Started" },
      { name: "Priority",    type: "Picklist", req: "Optional", notes: "" },
      { name: "Description", type: "Textarea", req: "Optional", notes: "" },
    ],
    Cases: [
      { name: "Subject",     type: "String",   req: "Required", notes: "" },
      { name: "Status",      type: "Picklist", req: "Optional", notes: "" },
      { name: "Priority",    type: "Picklist", req: "Optional", notes: "" },
      { name: "Description", type: "Textarea", req: "Optional", notes: "" },
    ],
    Products: [
      { name: "Product_Name", type: "String",   req: "Required", notes: "" },
      { name: "Unit_Price",   type: "Currency", req: "Required", notes: "" },
      { name: "Product_Code", type: "String",   req: "Optional", notes: "" },
      { name: "Description",  type: "Textarea", req: "Optional", notes: "" },
    ],
  };

  function autoFillDefaultFields(moduleName) {
    var tbody = document.getElementById("brd-fields-body");
    if (!tbody) return;
    var hasData = Array.from(tbody.querySelectorAll("input")).some(function(inp) {
      return inp.value.trim().length > 0;
    });
    if (hasData) return; // don't overwrite manually entered data
    tbody.innerHTML = "";
    var normalized = moduleName.trim();
    var defaults = null;
    Object.keys(CRM_DEFAULT_FIELDS).forEach(function(k) {
      if (k.toLowerCase() === normalized.toLowerCase()) defaults = CRM_DEFAULT_FIELDS[k];
    });
    if (!defaults) defaults = [
      { name: "Name",          type: "String",   req: "Required", notes: "" },
      { name: "Created_Time",  type: "DateTime", req: "Optional", notes: "Auto-set by CRM" },
      { name: "Modified_Time", type: "DateTime", req: "Optional", notes: "Auto-set by CRM" },
      { name: "Owner",         type: "Lookup",   req: "Optional", notes: "Assigned user" },
    ];
    defaults.forEach(function(f) {
      addBrdFieldRowWithData(f.name, f.type, f.req, f.notes);
    });
  }

  function addBrdFieldRowWithData(name, type, req, notes) {
    var tbody = document.getElementById("brd-fields-body");
    if (!tbody) return;
    var tr = document.createElement("tr");

    var nameInp = brdInput("Field_Name", name || "");

    var typeSelect = brdEl("select", { "class": "brd-in" });
    BRD_TYPES.forEach(function(t) {
      var opt = brdEl("option", {}, t);
      if (t === (type || "String")) opt.selected = true;
      typeSelect.appendChild(opt);
    });

    var reqSelect = brdEl("select", { "class": "brd-in" });
    var reqOpt = brdEl("option", {}, "Required");
    var optOpt = brdEl("option", {}, "Optional");
    if (req === "Required") reqOpt.selected = true; else optOpt.selected = true;
    reqSelect.appendChild(reqOpt);
    reqSelect.appendChild(optOpt);

    var notesInp = brdInput("Notes / constraints", notes || "");
    var delBtn   = brdDelBtn();

    tr.appendChild(brdTd(nameInp));
    tr.appendChild(brdTd(typeSelect));
    tr.appendChild(brdTd(reqSelect));
    tr.appendChild(brdTd(notesInp));
    var delTd = document.createElement("td");
    delTd.appendChild(delBtn);
    tr.appendChild(delTd);

    delBtn.addEventListener("click", function() { tr.parentNode && tr.parentNode.removeChild(tr); });
    tbody.appendChild(tr);
  }

  // ---- Import fields from CSV ----
  function importFieldsCsv(file) {
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function(e) {
      var lines = (e.target.result || "").split(/\r?\n/).filter(function(l) { return l.trim(); });
      if (!lines.length) return;
      // Skip header row if it looks like a header (Field Name / Data Type)
      var start = /field[\s_]?name/i.test(lines[0]) ? 1 : 0;
      var tbody = document.getElementById("brd-fields-body");
      if (!tbody) return;
      var added = 0;
      for (var i = start; i < lines.length; i++) {
        var cols = lines[i].split(",").map(function(c) { return c.trim().replace(/^"|"$/g, ""); });
        var fname = cols[0] || "";
        if (!fname) continue;
        var ftype = cols[1] || "String";
        if (!BRD_TYPES.includes(ftype)) ftype = "String";
        var freq = /required/i.test(cols[2] || "") ? "Required" : "Optional";
        var fnotes = cols[3] || "";
        addBrdFieldRowWithData(fname, ftype, freq, fnotes);
        added++;
      }
      showToast("Imported " + added + " field(s) from CSV", "ok");
    };
    reader.readAsText(file);
  }

  // ---- Reset BRD modal ----
  function resetBrdModal() {
    var confirmReset = window.confirm("Reset all BRD builder fields?");
    if (!confirmReset) return;
    var setVal = function(id, v) { var el = document.getElementById(id); if (el) el.value = v; };
    setVal("brd-project-name", "");
    setVal("brd-region", "IN");
    setVal("brd-auth", "OAuth 2.0");
    setVal("brd-entity-name", "");
    setVal("brd-module-api", "");
    setVal("brd-rules", "");
    setVal("brd-records-per-page", "200");
    setVal("brd-sort-field", "");
    setVal("brd-sort-order", "DESC");
    setVal("brd-rate-limit", "Retry");
    var tbody = document.getElementById("brd-fields-body");
    if (tbody) tbody.innerHTML = "";
    var fbody = document.getElementById("brd-filters-body");
    if (fbody) fbody.innerHTML = "";
    var rbody = document.getElementById("brd-rels-body");
    if (rbody) rbody.innerHTML = "";
    // Reset action checkboxes to defaults
    var defaultActions = state.platform === "creator"
      ? ["Read (list)", "Create", "Update", "Delete"]
      : ["Read (single)", "Read (list)", "Search", "Create", "Update"];
    document.querySelectorAll("input[name='brd-action']").forEach(function(cb) {
      cb.checked = defaultActions.includes(cb.value);
    });
    addBrdFieldRow(); addBrdFieldRow(); addBrdFieldRow();
  }

  function getElVal(id) {
    var el = document.getElementById(id);
    return el ? (el.value || "") : "";
  }

  function serializeBrd() {
    var proj    = (getElVal("brd-project-name") || "Untitled Project").trim();
    var region  = getElVal("brd-region")  || "IN";
    var auth    = getElVal("brd-auth")    || "OAuth 2.0";

    var isCreator = state.platform === "creator";
    var entity, apiName;
    if (isCreator) {
      var appName  = (getElVal("brd-creator-app-name") || "MyApp").trim();
      var appLink  = (getElVal("brd-creator-app")      || appName).trim();
      var formName = (getElVal("brd-creator-form-name") || "Form").trim();
      var formLink = (getElVal("brd-creator-form")      || formName).trim();
      entity  = formName;
      apiName = formLink;
    } else {
      entity  = (getElVal("brd-entity-name") || "Module").trim();
      apiName = (getElVal("brd-module-api")  || entity).trim();
    }

    var fields = [];
    var fieldRows = document.querySelectorAll("#brd-fields-body tr");
    for (var i = 0; i < fieldRows.length; i++) {
      var cells = fieldRows[i].querySelectorAll("input, select");
      var fname = cells[0] ? cells[0].value.trim() : "";
      if (fname) fields.push({ name: fname, type: cells[1] ? cells[1].value : "String", req: cells[2] ? cells[2].value : "Optional", notes: cells[3] ? cells[3].value.trim() : "" });
    }

    var actions = [];
    var actionCbs = document.querySelectorAll("input[name='brd-action']");
    for (var j = 0; j < actionCbs.length; j++) {
      actions.push({ name: actionCbs[j].value, on: actionCbs[j].checked });
    }

    var filters = [];
    var filterRows = document.querySelectorAll("#brd-filters-body tr");
    for (var k = 0; k < filterRows.length; k++) {
      var fcells = filterRows[k].querySelectorAll("input, select");
      var ff = fcells[0] ? fcells[0].value.trim() : "";
      if (ff) filters.push({
        field: ff,
        type: fcells[1] ? fcells[1].value.trim() : "is",
        value: fcells[2] ? fcells[2].value.trim() : "",
      });
    }

    var rels = [];
    var relRows = document.querySelectorAll("#brd-rels-body tr");
    for (var l = 0; l < relRows.length; l++) {
      var rmod = relRows[l].querySelector("td:nth-child(1) input");
      var rrel = relRows[l].querySelector("td:nth-child(2) select");
      var rfld = relRows[l].querySelector("td:nth-child(3) input");
      var rm = rmod ? rmod.value.trim() : "";
      if (rm) rels.push({
        mod: rm,
        rel: rrel ? rrel.value.trim() : "",
        field: rfld ? rfld.value.trim() : "",
      });
    }

    var rules = (getElVal("brd-rules") || "").trim();
    var rpp = getElVal("brd-records-per-page") || "200";
    var sf  = (getElVal("brd-sort-field") || "Created_Time").trim();
    var so  = getElVal("brd-sort-order") || "DESC";
    var rl  = getElVal("brd-rate-limit") || "Retry";

    var brd = "# BRD: " + proj + "\nRegion: " + region + " | Auth: " + auth + "\n";
    if (isCreator) {
      brd += "Platform: Zoho Creator | App: " + appName + " (link: " + appLink + ")\n";
    }
    brd += "\n";
    if (isCreator) {
      brd += "## Form: " + entity + "\nForm Link Name: " + apiName + "\n\n";
    } else {
      brd += "## Entity: " + entity + "\nModule API Name: " + apiName + "\n\n";
    }

    if (fields.length) {
      brd += "### Fields\n| Field Name | Data Type | Required | Notes |\n|---|---|---|---|\n";
      fields.forEach(function(f) { brd += "| " + f.name + " | " + f.type + " | " + f.req + " | " + (f.notes || "—") + " |\n"; });
      brd += "\n";
    }

    brd += "### Actions\n";
    actions.forEach(function(a) { brd += "- " + a.name + ": " + (a.on ? "YES" : "NO") + "\n"; });
    brd += "\n";

    if (filters.length) {
      brd += "### Search / Filter Fields\n| Field | Filter Type | Value |\n|---|---|---|\n";
      filters.forEach(function(f) { brd += "| " + f.field + " | " + f.type + " | " + (f.value || "—") + " |\n"; });
      brd += "\n";
    }

    if (rels.length) {
      brd += "### Relationships\n| Related Module | Relation Type | Field Name |\n|---|---|---|\n";
      rels.forEach(function(r) { brd += "| " + r.mod + " | " + r.rel + " | " + r.field + " |\n"; });
      brd += "\n";
    }

    if (rules) {
      brd += "### Business Rules\n";
      rules.split("\n").forEach(function(l) { if (l.trim()) brd += "- " + l.trim() + "\n"; });
      brd += "\n";
    }

    brd += "### Global Settings\n";
    brd += "- Records per page: " + rpp + "\n";
    brd += "- Default sort: " + sf + " " + so + "\n";
    brd += "- Rate limit handling: " + rl + "\n";

    return brd;
  }

  // ---- Default API smoke tests injected alongside generated cases ----

  const MODULE_REQUIRED_FIELDS = {
    Leads:    { Last_Name: "UAT-Smoke-Lead" },
    Contacts: { Last_Name: "UAT-Smoke-Contact" },
    Accounts: { Account_Name: "UAT-Smoke-Account" },
    Deals:    { Deal_Name: "UAT-Smoke-Deal", Stage: "Qualification", Closing_Date: "2026-12-31" },
    Tasks:    { Subject: "UAT-Smoke-Task", Due_Date: "2026-12-31", Status: "Not Started" },
    Cases:    { Subject: "UAT-Smoke-Case" },
    Products: { Product_Name: "UAT-Smoke-Product", Unit_Price: 100 },
    Quotes:   { Subject: "UAT-Smoke-Quote" },
  };

  function buildDefaultApiTests(modulesSelected) {
    const tests = [];
    for (const moduleName of modulesSelected) {
      const body = MODULE_REQUIRED_FIELDS[moduleName] || { Last_Name: "UAT-Smoke-Record" };

      tests.push({
        title: `[API Smoke] GET ${moduleName} — list records`,
        priority: "P0",
        category: "api",
        module: moduleName,
        tags: ["api", "smoke", "default"],
        gherkin: `Given the CRM API is reachable\nWhen I GET /crm/v3/${moduleName}\nThen HTTP 200 is returned with a data array`,
        steps: [{ action: `GET /crm/v3/${moduleName}`, expected: "HTTP 200, code=SUCCESS" }],
        acceptance: "List API returns 200 OK",
        spec_ref: "Default API smoke test",
        execution_plan: [
          { description: `List ${moduleName} records`, method: "GET",
            path: `/crm/v3/${moduleName}`,
            assertions: [{ path: "code", equals: "SUCCESS" }] },
        ],
      });

      tests.push({
        title: `[API Smoke] POST ${moduleName} — create & delete`,
        priority: "P0",
        category: "api",
        module: moduleName,
        tags: ["api", "smoke", "default", "crud"],
        gherkin: `Given the CRM API is reachable\nWhen I POST to /crm/v3/${moduleName} with required fields\nThen the record is created (code=SUCCESS) and can be deleted`,
        steps: [
          { action: `POST /crm/v3/${moduleName}`, expected: "code=SUCCESS, ID returned" },
          { action: `DELETE /crm/v3/${moduleName}/{{record_id}}`, expected: "code=SUCCESS" },
        ],
        acceptance: "Create + delete API works end-to-end",
        spec_ref: "Default API smoke test",
        execution_plan: [
          { description: `Create ${moduleName} record`, method: "POST",
            path: `/crm/v3/${moduleName}`,
            body: { data: [body] },
            capture: { record_id: "data[0].details.id" },
            assertions: [{ path: "data[0].code", equals: "SUCCESS" }] },
          { description: `Delete ${moduleName} record`, method: "DELETE",
            path: `/crm/v3/${moduleName}/{{record_id}}`,
            assertions: [{ path: "data[0].code", equals: "SUCCESS" }] },
        ],
      });

      tests.push({
        title: `[API Smoke] Invalid auth — expect 401`,
        priority: "P1",
        category: "security",
        module: moduleName,
        tags: ["api", "smoke", "default", "security"],
        gherkin: `Given I use an invalid Authorization token\nWhen I GET /crm/v3/${moduleName}\nThen HTTP 401 is returned`,
        steps: [{ action: `GET /crm/v3/${moduleName} with bad token`, expected: "HTTP 401 Unauthorized" }],
        acceptance: "API rejects invalid credentials",
        spec_ref: "Default API smoke test",
        execution_plan: [
          { description: `Bad-auth request to ${moduleName}`, method: "GET",
            path: `/crm/v3/${moduleName}`,
            override_auth: "INVALID_TOKEN",
            assertions: [{ path: "httpStatus", equals: 401 }] },
        ],
      });
    }
    return tests;
  }

  // ---- UAT generation and execution: background jobs, short requests as the fallback ----
  // Catalyst stops a function request after ~30 s, and one Zia Agent reply can take longer.
  // Both run as jobs on the impl_worker job function (submit → poll status → finish/result),
  // like the blueprint. When background jobs aren't set up (submit fails), generation makes one
  // LLM call per request (/generate/stage "part", two at a time, retried once) and execution
  // sends a few cases per /execute request (batch: true), resending any case not reached.

  // withCredentials mirrors the one-shot calls: /execute sent cookies cross-origin, /generate didn't.
  async function postJson(path, body, ms, label, withCredentials) {
    const res = await fetchWithTimeout(FUNCTION_BASE + path, {
      method: "POST", ...(withCredentials ? { credentials: "include" } : {}),
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }, ms, label);
    const data = await res.json().catch(() => ({}));
    return { res, data };
  }

  // Polls a background job until it is done; onStatus gets every status ({state, done, total, steps}).
  async function pollJob(path, key, onStatus) {
    const t0 = Date.now();
    const LIMIT_MS = 16 * 60 * 1000;      // a job function may run 15 minutes
    let last = { state: "queued" };
    while (Date.now() - t0 < LIMIT_MS) {
      try {
        const { res, data } = await postJson(path, { stage: "status", job_key: key }, 30_000, "Status", path === "/execute");
        if (res.ok) last = data;
        else if (res.status === 404 || res.status === 400) throw Object.assign(new Error(data.error || "The job was lost."), { fatal: true });
      } catch (e) {
        if (e.fatal) throw e;               // a missed poll isn't fatal
      }
      onStatus(last, Date.now() - t0);
      if (last.state === "done") return last;
      if (last.state === "failed") throw new Error(last.error || "The background job failed.");
      if (last.state === "queued" && Date.now() - t0 > 3 * 60 * 1000) {
        throw new Error("The background job didn't start within 3 minutes. Check that the impl_worker job function "
          + "is deployed and the 'liftoffjobs' job pool exists.");
      }
      await new Promise((r) => setTimeout(r, 3000));
    }
    throw new Error("The background job is taking longer than 16 minutes; it was stopped.");
  }

  function fmtElapsed(ms) {
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, "0")}s`;
  }

  // body: the /generate body. Resolves to the /generate response.
  // onProgress(text, fraction, jobStatus?): jobStatus is set while a background job runs.
  async function generateCasesStaged(body, onProgress) {
    const progress = onProgress || (() => {});
    const stage = async (b, label) => {
      const { res, data } = await postJson("/generate/stage", b, 60_000, label);
      if (!res.ok) throw new Error(data.error || `${label} failed (HTTP ${res.status})`);
      return data;
    };
    const common = { brd: body.brd, platform: body.platform || "crm",
      creator_owner: body.creator_owner || "", creator_app: body.creator_app || "" };

    progress("Reading the spec and your CRM fields…", 0.03);
    const start = await stage({ ...common, modules: body.modules, stage: "start" }, "Case generation start");
    const calls = start.calls || [];
    const finishBody = { ...common, stage: "finish", modules: body.modules, project_key: body.project_key || "",
      warnings: start.warnings || [], schema_source: start.schema_source || {} };

    // Background job (impl_worker): the browser only polls.
    let sub = null, subErr = null;
    try {
      sub = await stage({ ...common, stage: "submit", modules: body.modules, calls,
        schemas: start.schemas || {}, requirements: start.requirements || {} }, "Queue case generation");
    } catch (e) { subErr = e; }
    if (sub && sub.job_key) {
      await pollJob("/generate/stage", sub.job_key, (st, elapsed) => {
        const head = st.state === "queued" ? "Waiting for a worker to pick up the job"
          : `Writing cases — ${st.done || 0} of ${st.total || calls.length} step(s) done`;
        progress(`${head} · ${fmtElapsed(elapsed)}`, 0.05 + 0.85 * (st.done || 0) / Math.max(1, st.total || calls.length), st);
      });
      progress("Merging cases and checking coverage…", 0.93);
      return stage({ ...finishBody, job_key: sub.job_key }, "Case generation finish");
    }
    console.warn("Background generation unavailable, running in the browser:", subErr && subErr.message);
    const fallbackNote = "Ran without a background job (" + (subErr ? subErr.message : "not set up")
      + "). Long agent replies may time out; see agent/ZIA_GENERATOR.md to enable background jobs.";

    const results = new Array(calls.length);
    let next = 0, done = 0;
    const worker = async () => {
      while (next < calls.length) {
        const i = next++;
        const c = calls[i];
        const partBody = { ...common, stage: "part", module: c.module, part: c.part, focus: c.focus, wanted: c.wanted,
          schema: (start.schemas || {})[c.module] || "", requirements: (start.requirements || {})[String(c.part)] || "" };
        let r;
        for (let attempt = 1; attempt <= 2; attempt++) {
          try {
            r = await stage(attempt === 1 ? partBody : { ...partBody, retry: true }, `${c.module} part ${c.part}`);
            if (!r.error) break;
          } catch (e) {
            r = { module: c.module, part: c.part, focus: c.focus, error: e.message };
          }
        }
        results[i] = r;
        done++;
        progress(`Writing cases… ${done} of ${calls.length} done (${c.module}, ${c.label}).`,
          0.05 + 0.85 * done / calls.length);
      }
    };
    progress(`Writing cases… 0 of ${calls.length} done.`, 0.05);
    await Promise.all([worker(), worker()]);

    progress("Merging cases and checking coverage…", 0.93);
    return stage({ ...finishBody, results, warnings: [fallbackNote].concat(start.warnings || []) }, "Case generation finish");
  }

  const EXEC_BATCH_LIVE = 4;     // cases per /execute request against CRM (the server also stops at ~22 s)
  const EXEC_BATCH_DRY = 40;     // a dry run sends nothing to CRM, so batches can be large

  // opts: the /execute body without cases. Resolves to the /execute response for all cases.
  // A 409 (not connected) throws an Error with .status and .data.
  // onProgress(text, fraction, jobStatus?): jobStatus is set while a background job runs.
  async function executeBatched(cases, opts, onProgress) {
    const progress = onProgress || (() => {});

    // Background job (impl_worker) first; the access token it needs is taken from the session server-side.
    progress(`Queuing ${cases.length} case(s)…`, 0.02);
    let sub = null, subErr = null;
    try {
      const { res, data } = await postJson("/execute", { ...opts, cases, stage: "submit" }, 60_000, "Queue execution", true);
      if (res.status === 409) {
        throw Object.assign(new Error(data.error || "Not connected to Zoho CRM."), { status: 409, data });
      }
      if (res.ok && data.job_key) sub = data;
      else subErr = new Error(data.error || `HTTP ${res.status}`);
    } catch (e) {
      if (e.status === 409) throw e;
      subErr = e;
    }
    if (sub) {
      await pollJob("/execute", sub.job_key, (st, elapsed) => {
        const head = st.state === "queued" ? "Waiting for a worker to pick up the run"
          : `Ran ${st.done || 0} of ${st.total || cases.length} case(s)`;
        progress(`${head} · ${fmtElapsed(elapsed)}`, (st.done || 0) / Math.max(1, st.total || cases.length), st);
      });
      const { res, data } = await postJson("/execute", { stage: "result", job_key: sub.job_key }, 60_000, "Execution result", true);
      if (!res.ok) throw new Error(data.error || `Execution result failed (HTTP ${res.status})`);
      return data;
    }
    console.warn("Background execution unavailable, running in batches:", subErr && subErr.message);

    const size = opts.dry_run ? EXEC_BATCH_DRY : EXEC_BATCH_LIVE;
    const out = cases.slice();
    const totals = { passed: 0, failed: 0, skipped: 0, plans_ok: 0, plans_with_issues: 0 };
    let meta = { dry_run: !!opts.dry_run, simulated: !!opts.dry_run };
    let pending = cases.map((_, i) => i);
    let done = 0, failedBatches = 0;
    const notRun = (i, reason) => {
      out[i] = { ...cases[i], execution_result: { status: "skipped", reason } };
      totals.skipped++;
      done++;
    };

    progress(`Running ${cases.length} case(s)…`, 0.02);
    while (pending.length) {
      const idx = pending.slice(0, size);
      let rest = pending.slice(size);
      let res, data;
      try {
        ({ res, data } = await postJson("/execute", { ...opts, cases: idx.map((i) => cases[i]), batch: true },
          60_000, "Execution", true));
      } catch (e) {
        res = null; data = { error: e.message };
      }
      if (res && res.status === 409 && done === 0) {
        const err = new Error(data.error || "Not connected to Zoho CRM.");
        err.status = 409; err.data = data;
        throw err;
      }
      if (!res || !res.ok) {
        // Don't lose the cases that already ran: mark this batch as not run and carry on,
        // unless the server keeps failing.
        const reason = "Not run: " + (data.error || `HTTP ${res && res.status}`);
        idx.forEach((i) => notRun(i, reason));
        if (++failedBatches >= 2) { rest.forEach((i) => notRun(i, "Not run: execution stopped after repeated errors.")); rest = []; }
        pending = rest;
        progress(`Ran ${done} of ${cases.length} case(s)…`, done / cases.length);
        continue;
      }
      failedBatches = 0;
      meta = { dry_run: !!data.dry_run, simulated: !!data.simulated };
      ["passed", "failed", "skipped", "plans_ok", "plans_with_issues"].forEach((k) => { totals[k] += data[k] || 0; });
      const back = [];
      (data.cases || []).forEach((c, j) => {
        if (j >= idx.length) return;
        if (c.execution_result && c.execution_result.not_reached) back.push(idx[j]);
        else { out[idx[j]] = c; done++; }
      });
      if (back.length === idx.length) {          // no progress (shouldn't happen): don't loop forever
        back.forEach((i) => notRun(i, "Not run: the server ran out of time before this case."));
        back.length = 0;
      }
      pending = back.concat(rest);
      progress(`Ran ${done} of ${cases.length} case(s)…`, done / cases.length);
    }
    return { cases: out, ...totals, ...meta };
  }

  // ---- Actions ----

  async function generate() {
    if (state.mode === "impl") {
      if (!state.brd) { setStatus("input", "Attach a BRD first.", "warn"); return; }
      if (window.LiftOffImpl) window.LiftOffImpl.generate();
      return;
    }
    if (!state.brd) { setStatus("input", "Attach a UAT spec file first.", "warn"); return; }
    if (state.platform === "creator") {
      if (!getCreatorFormLink()) {
        setStatus("input", "Enter the Form Name in the Creator spec above first.", "warn");
        document.getElementById("creator-form-display-name")?.focus();
        return;
      }
    } else if (!state.modulesSelected.length) {
      setStatus("input", "Pick at least one target module.", "warn");
      return;
    }

    showTab("cases");
    setStatus("cases", "");
    startGenLoader();
    showLoader("cases", "");
    els.toExecute.disabled = true;
    els.download.disabled = true;
    els.casesMeta.textContent = "";
    els.cases.innerHTML = "";

    try {
      const body = {
        brd: plainBrd(state.brd),
        modules: state.modulesSelected,
        project_key: "",
        platform: state.platform,
      };
      if (state.platform === "creator") {
        const formLink = getCreatorFormLink();
        const appLink  = getCreatorAppLink();
        body.modules        = [formLink];
        body.creator_owner  = state.creatorOwner || ownerFromEmail(state.authEmailId || state.authUser || "");
        body.creator_app    = appLink;
        state.creatorApp    = appLink;
        // Prepend field spec to BRD so LLM knows the form structure
        const fieldSpec = buildCreatorFieldSpec();
        if (fieldSpec) body.brd = fieldSpec + "\n\n---\n\n" + state.brd;
      }
      const data = await generateCasesStaged(body, (text, f, st) => loaderProgress("gen", text, f, st));

      const aiCases = Array.isArray(data.cases) ? data.cases : [];
      const defaultTests = buildDefaultApiTests(state.modulesSelected);
      state.cases = [...defaultTests, ...aiCases];
      state.requirements = Array.isArray(data.requirements) ? data.requirements : [];
      state.payload = data.projects_payload || null;
      state.executed = false;
      state.pushed = false;
      saveStateToStorage();

      const breakdown = data.counts
        ? Object.entries(data.counts).map(([m, n]) => `${m}: ${n}`).join("  •  ")
        : "";
      els.casesMeta.innerHTML =
        `<span class="cases-count" id="cases-count">0</span>` +
        ` case(s) — ${defaultTests.length} API smoke + ${aiCases.length} via ${escapeHtml(data.provider)}` +
        (breakdown ? `  •  ${escapeHtml(breakdown)}` : "");
      animateCountUp(document.getElementById("cases-count"), state.cases.length);
      renderCasesList(els.cases, { showExec: false, showPush: false, streamReveal: true });
      uatLast.exec = null; uatLast.push = null;
      renderGuideUat();

      const have = state.cases.length > 0;
      els.toExecute.disabled = !have;
      els.download.disabled = !have;
      if (els.exportCsvBtn) els.exportCsvBtn.disabled = !have;
      if (have) markStepDone("cases");
      const warnings = Array.isArray(data.warnings) ? data.warnings : [];
      if (warnings.length) {
        console.warn("Generation warnings:", warnings);
        const shown = warnings.slice(0, 3).join(" • ");
        const more  = warnings.length > 3 ? ` (+${warnings.length - 3} more in the console)` : "";
        setStatus("cases", (have ? "Generated with notes: " : "No cases were returned: ") + shown + more, "warn");
      } else {
        setStatus("cases", have ? "Review the cases, then click Execute." : "No cases were returned.",
                  have ? "ok" : "warn");
      }
    } catch (e) {
      const msg = e.message || "Unknown error";
      const isJsonErr = msg.includes("JSON parse error") || msg.includes("Unexpected character");
      const friendly = isJsonErr
        ? "The AI returned malformed JSON. Try generating again — it usually resolves on retry."
        : "Generation failed: " + msg;
      setStatus("cases", friendly, "err");
      showToast(isJsonErr ? "Malformed AI response — retry should fix it" : "Generate failed: " + msg.slice(0, 80), "err", 6000);
    } finally {
      stopGenLoader();
      hideLoader("cases");
    }
  }

  // ---- Catalyst embedded auth + CRM status ----
  // Flow:
  //   1. Page loads → login card shows with spinner while isUserAuthenticated() runs
  //   2. Not authenticated → SDK renders Zoho sign-in form in #loginDivElementId
  //   3. User signs in → SDK reloads page with service_url → step 1 again → succeeds
  //   4. Authenticated → login screen hides, main app (tabs + panels) shows
  //   5. CRM status shown in Brief tab auth panel (Connect / Connected)
  // Local dev bypasses auth entirely.

  // Sign-in page boot sequence: orbit loader + three ticking steps.
  const boot = {
    init() {
      const o = document.getElementById("login-orbit");
      if (o && !o.firstElementChild) { o.innerHTML = orbitHtml(52); }
    },
    step(name, state, note) {
      const li = document.querySelector(`#boot-steps li[data-boot="${name}"]`);
      if (!li) return;
      const dot = li.querySelector(".lo-dot");
      dot.className = "lo-dot" + (state ? " is-" + state : "");
      li.className = state ? "is-" + state : "";
      li.querySelector("small").textContent = note || (state === "working" ? "checking" : state === "done" ? "ok" : state === "failed" ? "failed" : "");
      const done = document.querySelectorAll("#boot-steps li.is-done").length;
      orbitSet(document.querySelector("#login-orbit .lo-orbit"), done >= 3 ? "done" : done ? done / 3 : null);
    },
    msg(t) { const m = document.getElementById("login-msg"); if (m) m.textContent = t; },
  };

  async function checkAuthStatus() {
    boot.init();
    if (isLocal) {
      state.authUser      = "dev@localhost (local)";
      state.authFullName  = "Local Dev";
      state.authEmailId   = "dev@localhost";
      state.crmAuthorized = true;
      state.crmOrgName    = "Local Dev Org";
      updateAuthUI();
      return;
    }
    try {
      boot.step("session", "working"); boot.msg("Checking your session…");
      const result = await window.catalyst.auth.isUserAuthenticated();
      const u = result.content;
      state.authEmailId  = u.email_id || "";
      state.authFullName = ((u.first_name || "") + " " + (u.last_name || "")).trim();
      state.authUser = state.authEmailId || state.authFullName || "Zoho User";
      boot.step("session", "done", state.authEmailId || "signed in");
      boot.step("crm", "working"); boot.msg("Checking the CRM connection…");
      await checkCrmStatus();
      boot.step("crm", "done", state.crmAuthorized ? "connected" : "not connected yet");
      boot.step("org", "working"); boot.msg(state.crmAuthorized ? "Loading your org and modules…" : "Opening LiftOff…");
      // Also pull the org list so the picker is ready without an extra round-trip.
      await fetchOrgList();
      boot.step("org", "done", state.crmOrgName || (state.crmAuthorized ? "ready" : "connect in Brief"));
      boot.msg("Ready");
      await new Promise((r) => setTimeout(r, 350));
    } catch {
      boot.step("session", "failed", "sign in");
      // Not authenticated — hide spinner, show sign-in button.
      state.authUser      = null;
      state.crmAuthorized = false;
      const loader = document.getElementById("login-auth-loader");
      if (loader) loader.hidden = true;
      const actionArea = document.getElementById("login-action-area");
      if (actionArea) actionArea.hidden = false;
      document.getElementById("zoho-signin-btn")?.addEventListener("click", () => {
        // Hide button, show the Catalyst widget div, then render the login widget.
        if (actionArea) actionArea.hidden = true;
        const widgetWrap = document.getElementById("catalyst-widget-wrap");
        if (widgetWrap) widgetWrap.hidden = false;
        window.catalyst.auth.signIn("loginDivElementId", { service_url: "/app/index.html" });
      }, { once: true });
    }
    updateAuthUI();
  }

  async function checkCrmStatus() {
    try {
      const res  = await fetch(FUNCTION_BASE + "/crm/status", { credentials: "include" });
      const data = await res.json();
      state.crmAuthorized  = data.authorized === true;
      state.oauthAvailable = data.oauth_available === true;
      if (data.email && !state.crmEmail) state.crmEmail = data.email;
    } catch {
      state.crmAuthorized  = false;
      state.oauthAvailable = false;
    }
  }

  // Fetch org list from backend and update state.  Called after SSO + after OAuth callback.
  async function fetchOrgList() {
    try {
      const res  = await fetch(FUNCTION_BASE + "/crm/orgs", { credentials: "include" });
      const data = await res.json();
      if (data.needs_auth) {
        state.oauthAvailable = data.oauth_available !== false;
        return;
      }
      const wasAuthorized = state.crmAuthorized;
      state.crmAuthorized = true;
      state.oauthAvailable = true;
      state.crmOrgList = Array.isArray(data.orgs) ? data.orgs : [];
      state.crmOrgsError = data.orgs_error || "";
      if (data.current) {
        state.crmOrgName = data.current.org_name || "";
        if (!state.crmEmail && data.current.email) state.crmEmail = data.current.email;
      }
      // Auto-load modules whenever CRM is authorized (page load or first connect).
      if (state.platform === "creator") {
        loadCreatorForms();
      } else {
        loadModules();
      }
    } catch {
      // Non-fatal — leave existing state.
    }
  }

  function updateAuthUI() {
    setTimeout(renderGuideBrief, 0);
    const loggedIn  = !!state.authUser;
    const loginScreen = document.getElementById("login-screen");
    const tabsNav     = document.querySelector("nav.tabs");
    const mainEl      = document.querySelector("main");

    if (!loggedIn) {
      if (loginScreen) loginScreen.hidden = false;
      if (tabsNav)     tabsNav.hidden     = true;
      if (mainEl)      mainEl.hidden      = true;
      if (els.headerUserChip) els.headerUserChip.hidden = true;
      return;
    }

    // Logged in → show main app
    if (loginScreen) loginScreen.hidden = true;
    if (tabsNav)     tabsNav.hidden     = false;
    if (mainEl)      mainEl.hidden      = false;

    // Header user chip — shows Zoho account as soon as logged in
    if (els.headerUserChip) {
      const displayName = state.authFullName || state.authEmailId || state.authUser || "Zoho";
      const displayEmail = state.authEmailId && state.authEmailId !== state.authFullName
        ? state.authEmailId : "";
      const initial = displayName[0]?.toUpperCase() || "Z";
      els.headerUserChip.hidden = false;
      if (els.hucAvatar) els.hucAvatar.textContent = initial;
      if (els.hucOrg)    els.hucOrg.textContent    = displayName;
      if (els.hucEmail)  els.hucEmail.textContent  = displayEmail;
      if (els.hucDot)    els.hucDot.dataset.connected = "1"; // logged in = connected
    }

    // Auth panel: platform-aware connection state
    if (state.platform === "creator") {
      if (els.authLoginArea)    els.authLoginArea.hidden    = true;
      if (els.authUserInfo)     els.authUserInfo.hidden     = false;
      if (els.authOrgName)      els.authOrgName.textContent = "Zoho Creator";
      if (els.authEmail)        els.authEmail.textContent   = state.authEmailId || state.authUser || "";
      if (els.authLogoutBtn)    els.authLogoutBtn.hidden    = true;
    } else {
      if (els.authOrgName) els.authOrgName.textContent = state.crmOrgName || "Zoho CRM";
      if (els.authEmail)   els.authEmail.textContent   = state.crmEmail   || state.authUser || "";
      if (els.authLoginArea) els.authLoginArea.hidden = state.crmAuthorized;
      if (els.authUserInfo)  els.authUserInfo.hidden  = !state.crmAuthorized;
      if (els.authLogoutBtn)    els.authLogoutBtn.hidden    = false;
    }

    // Gate Execute button: show lock label when CRM not connected
    if (els.toExecute) {
      if (!state.crmAuthorized && !isLocal) {
        els.toExecute.title = "Connect Zoho CRM first to execute test cases";
        els.toExecute.setAttribute("data-crm-locked", "1");
      } else {
        els.toExecute.title = "";
        els.toExecute.removeAttribute("data-crm-locked");
      }
    }
  }

  // ---- Org picker modal ----

  function openOrgPicker() {
    const overlay = document.getElementById("org-modal-overlay");
    if (!overlay) return;
    overlay.hidden = false;
    renderOrgList();
  }

  function closeOrgPicker() {
    const overlay = document.getElementById("org-modal-overlay");
    if (overlay) overlay.hidden = true;
    const errEl = document.getElementById("org-modal-error");
    if (errEl) errEl.textContent = "";
  }

  // Switches the session to an org (by id) and reloads what depends on it.
  async function selectOrg(orgId, orgName, btn) {
    const errEl = document.getElementById("org-modal-error");
    if (btn) btn.disabled = true;
    if (errEl) errEl.textContent = "";
    try {
      const res = await fetch(FUNCTION_BASE + "/crm/select-org", { method: "POST", credentials: "include",
        headers: { "Content-Type": "application/json" }, body: JSON.stringify({ org_id: orgId, org_name: orgName || "" }) });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
      closeOrgPicker();
      state.crmOrgName = data.org_name || orgName || orgId;
      state.orgChosen = true;
      saveStateToStorage();
      setStatus("input", `Switched to ${state.crmOrgName}. Reloading modules…` + (data.note ? " " + data.note : ""), data.note ? "warn" : "ok");
      await checkAuthStatus();
      loadModules();
    } catch (e) {
      if (btn) btn.disabled = false;
      if (errEl) errEl.textContent = "Couldn't switch org: " + e.message;
    }
  }

  function renderOrgList() {
    const listEl = document.getElementById("org-modal-list");
    if (!listEl) return;

    const manual = `
      <div class="org-manual">
        <p class="muted">${state.crmOrgList.length <= 1
          ? "Zoho only told us about the connected org" + (state.crmOrgsError ? " (" + escapeHtml(state.crmOrgsError) + ")" : "") + ". To work in another org of this account, enter its Org ID:"
          : "Not listed? Enter the org's ID:"}
          <span class="muted">CRM → Setup → Company Settings → Company Details → <strong>Org ID</strong>.</span></p>
        <div class="org-manual-row">
          <input type="text" id="org-manual-id" inputmode="numeric" placeholder="e.g. 60012345678" aria-label="CRM org id" />
          <button type="button" class="primary small" id="org-manual-go">Use this org</button>
        </div>
      </div>`;

    if (!state.crmOrgList.length) {
      listEl.innerHTML = '<p class="muted" style="padding:1rem 0">No organisations found. Connect CRM first.</p>' + manual;
      wireManual(listEl);
      return;
    }

    listEl.innerHTML = state.crmOrgList.map((org) => {
      const isCurrent = state.crmOrgName && org.org_name === state.crmOrgName;
      return `
        <button class="org-item${isCurrent ? " org-item--current" : ""}"
                data-org-name="${escapeHtml(org.org_name)}"
                data-org-id="${escapeHtml(org.org_id)}">
          <span class="org-item-icon" aria-hidden="true">
            <span class="bm-grid bm-grid-xs">
              <span class="bm-q" style="background:#0052CC"></span>
              <span class="bm-q" style="background:#F26C01"></span>
              <span class="bm-q" style="background:#00875A"></span>
              <span class="bm-q" style="background:#FF8B00"></span>
            </span>
          </span>
          <span class="org-item-body">
            <strong class="org-item-name">${escapeHtml(org.org_name || "Unnamed org")}</strong>
            ${org.org_domain ? `<span class="org-item-domain muted">${escapeHtml(org.org_domain)}</span>` : ""}
          </span>
          ${isCurrent ? '<span class="org-item-badge">Connected</span>' : '<span class="org-item-arrow">→</span>'}
        </button>`;
    }).join("") + manual;

    listEl.querySelectorAll(".org-item:not(.org-item--current)").forEach((btn) => {
      btn.addEventListener("click", () => selectOrg(btn.dataset.orgId, btn.dataset.orgName, btn));
    });
    wireManual(listEl);
  }

  function wireManual(listEl) {
    const input = listEl.querySelector("#org-manual-id");
    const go = listEl.querySelector("#org-manual-go");
    if (!input || !go) return;
    const run = () => {
      const id = input.value.trim();
      if (!/^\d{6,}$/.test(id)) { const e = document.getElementById("org-modal-error"); if (e) e.textContent = "An Org ID is a number (usually 11 digits)."; return; }
      selectOrg(id, "", go);
    };
    go.addEventListener("click", run);
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") run(); });
  }

  function initOrgPicker() {
    const cancelBtn = document.getElementById("org-modal-cancel");
    const overlay   = document.getElementById("org-modal-overlay");
    if (cancelBtn) cancelBtn.addEventListener("click", closeOrgPicker);
    if (overlay)   overlay.addEventListener("click", (e) => {
      if (e.target === overlay) closeOrgPicker();
    });
  }

  async function handleOAuthCallback() {
    const params = new URLSearchParams(window.location.search);
    const code  = params.get("code");
    const state = params.get("state");
    if (!code || !state) return;

    // Clean up URL so back/refresh doesn't re-trigger the exchange.
    window.history.replaceState({}, "", window.location.pathname);

    const qs = new URLSearchParams({ code, state });
    const accountsServer = params.get("accounts-server");
    if (accountsServer) qs.set("accounts_server", accountsServer);

    try {
      const res  = await fetch(FUNCTION_BASE + "/crm/callback?" + qs.toString(), {
        credentials: "include",
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.success) {
        showToast("CRM connection failed: " + (data.error || res.statusText), "err");
      } else {
        state.orgChosen = false;   // a fresh connection lands in the default org: let the user pick
      }
    } catch (e) {
      showToast("CRM connection failed: " + e.message, "err");
    }
  }

  // A user in several orgs is routed to their default org until they pick one: ask right after connecting.
  function offerOrgPickerIfNeeded() {
    if (state.orgChosen || state.crmOrgList.length <= 1 || state.platform === "creator") return;
    const overlay = document.getElementById("org-modal-overlay");
    if (overlay && !overlay.hidden) return;
    state.orgChosen = true;        // offer once per connection
    renderOrgList();
    openOrgPicker();
    setStatus("input", "You belong to " + state.crmOrgList.length + " CRM organisations — pick the one to work in.", "");
  }

  function initAuth() {
    handleOAuthCallback()
      .then(() => checkAuthStatus())
      .then(() => {
        const resumeExec = sessionStorage.getItem("liftoff_resume_exec");
        if (resumeExec && state.crmAuthorized && state.cases.length) {
          try { sessionStorage.removeItem("liftoff_resume_exec"); } catch (e) {}
          showTab("execute");
          setTimeout(() => executeInCrm(), 400);
        }
      });

    // Brief tab "Connect Zoho CRM" — OAuth redirect (no credential entry needed).
    if (els.authLoginBtn) {
      els.authLoginBtn.addEventListener("click", () => {
        window.location.href = FUNCTION_BASE + "/crm/auth";
      });
    }


    // Disconnect CRM (clears CRM token only, does not sign out of Catalyst)
    async function disconnectCrm() {
      try {
        if (!isLocal) {
          await fetch(FUNCTION_BASE + "/crm/disconnect", { method: "POST", credentials: "include" });
        }
      } catch (e) { /* best-effort */ }
      state.crmAuthorized = false;
      state.crmOrgName = "";
      state.crmEmail = "";
      state.crmOrgList = [];
      state.modulesAvailable = [];
      state.orgChosen = false;
      updateAuthUI();
      renderModuleChips();
      if (els.modulesStatus) els.modulesStatus.textContent = "";
    }
    if (els.authLogoutBtn) els.authLogoutBtn.addEventListener("click", disconnectCrm);

    // Account menu on the sidebar chip: who is connected, change org, disconnect & reconnect.
    if (els.headerUserChip) {
      const chip = els.headerUserChip;
      chip.setAttribute("role", "button"); chip.setAttribute("tabindex", "0"); chip.setAttribute("aria-haspopup", "menu"); chip.setAttribute("aria-expanded", "false");
      const menu = document.createElement("div");
      menu.className = "acct-menu"; menu.hidden = true; menu.setAttribute("role", "menu");
      chip.appendChild(menu);
      const close = () => { menu.hidden = true; chip.setAttribute("aria-expanded", "false"); };
      const open = () => {
        const org = state.crmOrgName || "Zoho CRM";
        const cur = (state.crmOrgList || []).find((o) => o.org_name === state.crmOrgName) || {};
        menu.innerHTML = `<div class="acct-head"><strong>${escapeHtml(org)}</strong><span>${escapeHtml(state.crmEmail || state.authEmailId || state.authUser || "")}</span>${cur.id ? `<span style="margin-top:4px">Org ID <code>${escapeHtml(String(cur.id))}</code>${cur.zgid ? ` · ZGID <code>${escapeHtml(String(cur.zgid))}</code>` : ""}</span>` : ""}</div>
          <button type="button" role="menuitem" data-acct="org"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 21h18M5 21V7l7-4 7 4v14M9 21v-6h6v6"/></svg><span>Change org<small>Enter another Org ID on the same connection</small></span></button>
          <button type="button" role="menuitem" data-acct="reconnect"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 12a9 9 0 1 1-3-6.7M21 3v6h-6"/></svg><span>Disconnect &amp; reconnect<small>Sign in to CRM again and choose the org</small></span></button>
          <button type="button" role="menuitem" class="danger" data-acct="disconnect"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M18.4 5.6a9 9 0 1 1-12.8 0M12 2v10"/></svg><span>Disconnect CRM<small>Clears the CRM token; you stay signed in to LiftOff</small></span></button>`;
        menu.hidden = false; chip.setAttribute("aria-expanded", "true");
      };
      chip.addEventListener("click", async (e) => {
        const item = e.target.closest("[data-acct]");
        if (item) {
          e.stopPropagation(); close();
          const act = item.dataset.acct;
          if (act === "org") {
            if (!state.crmAuthorized) { showToast("Connect CRM first", "warn", 3000); return; }
            renderOrgList(); openOrgPicker(); return;
          }
          if (act === "disconnect") { await disconnectCrm(); showToast("CRM disconnected", "ok", 2500); showTab("input"); return; }
          if (act === "reconnect") {
            busy.start("Reconnecting to Zoho CRM", "Clearing the current connection, then opening Zoho sign-in…");
            await disconnectCrm();
            if (isLocal) { busy.end(); showToast("Local mode: nothing to reconnect", "info", 3000); return; }
            window.location.href = FUNCTION_BASE + "/crm/auth";
            return;
          }
          return;
        }
        if (menu.hidden) open(); else close();
      });
      chip.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); chip.click(); } if (e.key === "Escape") close(); });
      document.addEventListener("click", (e) => { if (!chip.contains(e.target)) close(); });
    }

    initOrgPicker();
  }

  // ---- Module picker ----

  function renderModuleChips() {
    if (!els.moduleChips) return;
    els.moduleChips.innerHTML = "";
    const list = state.modulesAvailable.length ? state.modulesAvailable : DEFAULT_MODULES;
    const max = MAX_MODULES;
    // Sort: selected first, then suggested (in suggestion order), then alphabetical.
    const suggestedSet = new Set(state.suggestedModules);
    const sortedList = list.slice().sort((a, b) => {
      const aSel = state.modulesSelected.includes(a.api_name), bSel = state.modulesSelected.includes(b.api_name);
      if (aSel !== bSel) return aSel ? -1 : 1;
      const aSugg = state.suggestedModules.indexOf(a.api_name);
      const bSugg = state.suggestedModules.indexOf(b.api_name);
      const aIn = aSugg >= 0, bIn = bSugg >= 0;
      if (aIn && bIn) return aSugg - bSugg;
      if (aIn) return -1;
      if (bIn) return 1;
      return (a.plural_label || a.api_name).localeCompare(b.plural_label || b.api_name);
    });
    const live = state.modulesAvailable.length > 0;
    // Toolbar: search + counter
    const toolbar = document.createElement("div");
    toolbar.className = "module-toolbar";
    const search = document.createElement("input");
    search.type = "search";
    search.className = "module-search";
    search.placeholder = "Search modules\u2026";
    search.setAttribute("aria-label", "Search modules");
    const counter = document.createElement("span");
    counter.className = "module-counter";
    counter.textContent = `${state.modulesSelected.length} selected · ${sortedList.length} module(s)` + (live ? "" : " · standard list");
    toolbar.appendChild(search);
    toolbar.appendChild(counter);
    els.moduleChips.appendChild(toolbar);

    // The modules table: tick to include; suggested ones are flagged with the reason.
    const wrap = document.createElement("div");
    wrap.className = "table-wrap";
    const table = document.createElement("table");
    table.className = "lo-table module-table";
    table.innerHTML = `<thead><tr><th class="col-check"></th><th>Module</th><th>API name</th><th>Suggested</th></tr></thead>`;
    const tbody = document.createElement("tbody");
    sortedList.forEach((m) => {
      const isSelected = state.modulesSelected.includes(m.api_name);
      const isSuggested = suggestedSet.has(m.api_name);
      const full = !isSelected && state.modulesSelected.length >= max;
      const tr = document.createElement("tr");
      tr.className = "module-row" + (isSelected ? " is-sel" : "") + (full ? " is-off" : "");
      tr.dataset.apiName = m.api_name;
      tr.dataset.text = ((m.plural_label || "") + " " + m.api_name).toLowerCase();
      const label = m.plural_label || m.api_name;
      tr.innerHTML = `<td><input type="checkbox" aria-label="Include ${escapeHtml(label)}" ${isSelected ? "checked" : ""} ${full ? "disabled" : ""}></td>
        <td class="cell-main">${escapeHtml(label)}${m.singular_label && m.singular_label !== label ? `<span class="muted">${escapeHtml(m.singular_label)}</span>` : ""}</td>
        <td><code>${escapeHtml(m.api_name)}</code></td>
        <td>${isSuggested ? `<span class="pill info" title="Named or implied in the BRD">named in BRD</span>` : `<span class="muted">—</span>`}</td>`;
      tr.addEventListener("click", (e) => {
        if (full) return;
        if (e.target.tagName === "INPUT") { toggleModule(m.api_name); return; }
        toggleModule(m.api_name);
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
    els.moduleChips.appendChild(wrap);
    search.addEventListener("input", () => {
      const q = search.value.trim().toLowerCase();
      let visible = 0;
      tbody.querySelectorAll("tr").forEach((r) => {
        const match = !q || r.dataset.text.includes(q);
        r.style.display = match ? "" : "none";
        if (match) visible++;
      });
      counter.textContent = `${state.modulesSelected.length} selected · ${visible} of ${sortedList.length}`;
    });
    if (els.modulePickerEmpty) els.modulePickerEmpty.hidden = live;
    renderSelectedSummary();
  }

  // ---- Requirements table (client-side parse of the BRD: IDs like FR-1, NFR-2, US-3 and their headings) ----
  function extractRequirements(text) {
    const out = [];
    if (!text) return out;
    const lines = text.split(/\r?\n/);
    let section = "";
    const idRe = /^\s*(?:[#*\-\u2022]+\s*)?((?:FR|NFR|BR|US|REQ|AC|R)[\s-]?\d+(?:\.\d+)*)\s*[:.\-\u2013\u2014)]?\s*(.*)$/i;
    const headRe = /^\s*(?:#{1,3}\s*)?([A-Z][A-Za-z /&-]{2,40})\s*:?\s*$/;
    lines.forEach((raw) => {
      const line = raw.trim();
      if (!line) return;
      const h = headRe.exec(line);
      if (h && !idRe.test(line) && line.length < 48) { section = h[1].trim(); return; }
      const m = idRe.exec(line);
      if (m) {
        const id = m[1].toUpperCase().replace(/\s+/g, "-").replace(/^([A-Z]+)(\d)/, "$1-$2");
        if (!out.some((r) => r.id === id)) out.push({ id, title: (m[2] || "").replace(/[*_`]/g, "").trim(), section });
      }
    });
    // Acceptance criteria written as "A." / "B." under an Acceptance heading.
    let acc = 0;
    lines.forEach((raw) => {
      const line = raw.trim();
      if (/^[A-H][.)]\s+\S/.test(line)) acc++;
    });
    if (acc) out.push({ id: "ACCEPT", title: `${acc} acceptance criteria → UAT cases`, section: "Acceptance" });
    return out;
  }

  function renderRequirementsTable() {
    const card = document.getElementById("brd-requirements-card");
    const table = document.getElementById("brd-requirements");
    const count = document.getElementById("brd-requirements-count");
    if (!card || !table) return;
    const reqs = state.brd ? extractRequirements(state.brd) : [];
    if (!reqs.length) { card.hidden = true; table.innerHTML = ""; return; }
    card.hidden = false;
    if (count) count.textContent = `${reqs.filter((r) => r.id !== "ACCEPT").length} requirement(s) · ${state.brd.length.toLocaleString()} chars`;
    table.innerHTML = `<thead><tr><th class="col-narrow">ID</th><th>Requirement</th><th>Section</th></tr></thead><tbody>`
      + reqs.map((r) => `<tr><td><strong>${escapeHtml(r.id === "ACCEPT" ? "A–" : r.id)}</strong></td>
          <td>${escapeHtml(r.title || "(untitled)")}</td><td class="muted">${escapeHtml(r.section || "—")}</td></tr>`).join("")
      + "</tbody>";
  }

  function renderSelectedSummary() {
    if (!els.moduleSelectedList || !els.moduleSelected) return;
    if (!state.modulesSelected.length) {
      els.moduleSelected.hidden = true;
      els.moduleSelectedList.innerHTML = "";
      return;
    }
    els.moduleSelected.hidden = false;
    els.moduleSelectedList.innerHTML = state.modulesSelected
      .map((m) => `<span class="pill">${escapeHtml(m)}</span>`)
      .join("");
  }

  function toggleModule(apiName) {
    const idx = state.modulesSelected.indexOf(apiName);
    if (idx >= 0) state.modulesSelected.splice(idx, 1);
    else {
      if (state.modulesSelected.length >= MAX_MODULES) return;
      state.modulesSelected.push(apiName);
    }
    // For Creator: update creatorApp from the selected form's app metadata
    if (state.platform === "creator" && state.modulesSelected.length) {
      const first = state.creatorForms.find((f) => f.api_name === state.modulesSelected[0]);
      if (first && first.app) state.creatorApp = first.app;
    }
    renderModuleChips();
    refreshGenerateAvailability();
    if (state.brd && state.modulesSelected.length) markStepDone("input");
  }

  function refreshGenerateAvailability() {
    setTimeout(renderGuideBrief, 0);
    if (state.mode === "impl") {
      els.generate.disabled = !state.brd;   // modules are optional: the spec can propose new ones
    } else if (state.platform === "creator") {
      els.generate.disabled = !(state.brd && getCreatorFormLink());
    } else {
      els.generate.disabled = !(state.brd && state.modulesSelected.length);
    }
  }

  function renderAnalysisSummary() {
    if (!els.analysisSummary) return;
    if (!state.analysisText) {
      els.analysisSummary.hidden = true;
      els.analysisSummary.textContent = "";
      return;
    }
    els.analysisSummary.hidden = false;
    els.analysisSummary.textContent = state.analysisText;
  }

  async function analyzeBrd() {
    if (!state.brd) return;
    const list = state.modulesAvailable.length ? state.modulesAvailable : DEFAULT_MODULES;
    if (!list.length) return;
    try {
      const res = await fetchWithTimeout(
        FUNCTION_BASE + "/analyze",
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ brd: plainBrd(state.brd), modules: list }) },
        30_000, "Spec analysis"
      );
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Analyze failed");
      state.suggestedModules = Array.isArray(data.suggested_modules) ? data.suggested_modules : [];
      state.analysisText = data.analysis || "";
      // Preselect suggested modules if user hasn't picked anything yet.
      if (!state.modulesSelected.length && state.suggestedModules.length) {
        state.modulesSelected = state.suggestedModules.slice(0, MAX_MODULES);
      }
      renderAnalysisSummary();
      renderModuleChips();
      refreshGenerateAvailability();
      if (state.brd && state.modulesSelected.length) markStepDone("input");
    } catch (e) {
      // Non-fatal: just log and continue. User can still pick manually.
      console.warn("Spec analyze failed:", e.message);
    }
  }

  async function loadModules() {
    if (state.platform === "desk") {
      state.modulesAvailable = PRODUCTS.desk.modules.map((m) => ({ api_name: m, plural_label: m, singular_label: m.replace(/s$/, ""), custom: false }));
      renderModuleChips();
      if (els.modulesStatus) { els.modulesStatus.textContent = "Desk modules are fixed: Tickets, Contacts, Accounts, Tasks."; els.modulesStatus.className = "status"; }
      return;
    }
    if (els.modulesStatus) {
      els.modulesStatus.textContent = "Loading modules...";
      els.modulesStatus.className = "status";
    }
    if (els.loadModulesBtn) els.loadModulesBtn.disabled = true;
    try {
      const res = await fetchWithTimeout(
        FUNCTION_BASE + "/modules",
        { method: "POST", headers: { "Content-Type": "application/json" },
          credentials: "include", body: JSON.stringify({}) },
        20_000, "Load modules"
      );
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed");
      state.modulesAvailable = Array.isArray(data.modules) ? data.modules : [];
      if (els.modulePickerEmpty) {
        els.modulePickerEmpty.style.display = state.modulesAvailable.length ? "none" : "";
      }
      renderModuleChips();
      if (els.modulesStatus) {
        const live = data.source === "live";
        els.modulesStatus.textContent = `${state.modulesAvailable.length} module(s) loaded (${live ? "live from CRM" : "default list"}).`;
        els.modulesStatus.className = "status " + (live ? "ok" : "muted");
      }
      // Re-analyze with the new (possibly larger / custom-module) list.
      if (state.brd) analyzeBrd();
    } catch (e) {
      if (els.modulesStatus) {
        els.modulesStatus.textContent = "Error: " + e.message;
        els.modulesStatus.className = "status err";
      }
      showToast("Failed to load modules: " + e.message, "err");
    } finally {
      if (els.loadModulesBtn) els.loadModulesBtn.disabled = false;
    }
  }

  async function loadCreatorForms() {
    if (els.modulesStatus) {
      els.modulesStatus.textContent = "Loading Creator forms...";
      els.modulesStatus.className = "status";
    }
    if (els.loadModulesBtn) els.loadModulesBtn.disabled = true;
    try {
      const res = await fetchWithTimeout(
        FUNCTION_BASE + "/creator/forms",
        { method: "POST", headers: { "Content-Type": "application/json" },
          credentials: "include", body: JSON.stringify({}) },
        20_000, "Load Creator forms"
      );
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to load Creator forms");

      state.creatorOwner = data.owner || "";
      state.creatorForms = Array.isArray(data.forms) ? data.forms : [];

      // Group forms: use api_name as chip key, display_name as label
      state.modulesAvailable = state.creatorForms.map((f) => ({
        api_name:    f.api_name,
        plural_label: f.display_name || f.api_name,
        app:         f.app,
      }));

      // Set creatorApp to first app found (user can override via chip selection)
      if (state.creatorForms.length && !state.creatorApp) {
        state.creatorApp = state.creatorForms[0].app || "";
      }

      renderModuleChips();

      if (els.modulesStatus) {
        const live = data.source === "live";
        els.modulesStatus.textContent =
          `${state.creatorForms.length} form(s) loaded from Creator` +
          (state.creatorOwner ? ` (owner: ${state.creatorOwner})` : "") +
          (!live ? " — no forms found, check connection" : "");
        els.modulesStatus.className = "status " + (state.creatorForms.length ? "ok" : "warn");
      }

      // Pre-populate Creator spec inputs from first loaded form
      if (state.creatorForms.length) {
        const first = state.creatorForms[0];
        const setIfEmpty = (id, val) => {
          const el = document.getElementById(id);
          if (el && !el.value && val) el.value = val;
        };
        setIfEmpty("creator-app-display-name",  first.app_name || first.app || "");
        setIfEmpty("creator-app-link-name",      first.app || "");
        setIfEmpty("creator-form-display-name",  first.display_name || first.api_name || "");
        setIfEmpty("creator-form-link-name",     first.api_name || "");
        refreshGenerateAvailability();
      }
      if (state.brd) analyzeBrd();
    } catch (e) {
      if (els.modulesStatus) {
        // Graceful fallback — user can fill the spec manually
        els.modulesStatus.textContent = "Could not load forms from Creator — fill in the spec above manually.";
        els.modulesStatus.className = "status muted";
      }
    } finally {
      if (els.loadModulesBtn) els.loadModulesBtn.disabled = false;
    }
  }

  // ---- Creator Form Spec helpers ----

  const FIELD_TYPES = [
    "Text", "Number", "Email", "Phone", "Date", "Date-Time",
    "Dropdown", "Multi-select", "Checkbox", "Radio",
    "File Upload", "Image", "Currency", "Percent",
    "URL", "Name", "Address", "Notes", "Formula",
  ];

  function ownerFromEmail(email) {
    if (!email) return "";
    const at = email.indexOf("@");
    return at > 0 ? email.substring(0, at).toLowerCase() : email.toLowerCase();
  }

  function addCreatorField() {
    state.creatorFields.push({ name: "", type: "Text", required: false, notes: "" });
    renderCreatorFields();
    saveStateToStorage();
    // Focus the new row's name input
    const rows = document.querySelectorAll(".creator-field-row");
    if (rows.length) rows[rows.length - 1].querySelector(".cf-name")?.focus();
  }

  function removeCreatorField(idx) {
    state.creatorFields.splice(idx, 1);
    renderCreatorFields();
    saveStateToStorage();
  }

  function renderCreatorFields() {
    const tbody    = document.getElementById("creator-fields-tbody");
    const empty    = document.getElementById("creator-fields-empty");
    const tableWrap = document.getElementById("creator-fields-table-wrap");
    if (!tbody) return;
    tbody.innerHTML = "";
    const hasRows = state.creatorFields.length > 0;
    if (empty)     empty.hidden     = hasRows;
    if (tableWrap) tableWrap.hidden = !hasRows;

    state.creatorFields.forEach((f, i) => {
      const row = document.createElement("div");
      row.className = "creator-field-row";
      const typeOpts = FIELD_TYPES.map(
        (t) => `<option${t === f.type ? " selected" : ""}>${escapeHtml(t)}</option>`
      ).join("");
      row.innerHTML =
        `<input type="text" class="cf-name" placeholder="e.g. Employee Name" value="${escapeHtml(f.name)}" />` +
        `<select class="cf-type">${typeOpts}</select>` +
        `<label class="cf-req-label"><input type="checkbox" class="cf-req"${f.required ? " checked" : ""} /> Req</label>` +
        `<input type="text" class="cf-notes" placeholder="e.g. max 100 chars, options: Yes/No" value="${escapeHtml(f.notes)}" />` +
        `<button type="button" class="cf-remove" aria-label="Remove field">&times;</button>`;
      row.querySelector(".cf-name").addEventListener("input",  (e) => { state.creatorFields[i].name     = e.target.value; saveStateToStorage(); });
      row.querySelector(".cf-type").addEventListener("change", (e) => { state.creatorFields[i].type     = e.target.value; saveStateToStorage(); });
      row.querySelector(".cf-req").addEventListener("change",  (e) => { state.creatorFields[i].required = e.target.checked; saveStateToStorage(); });
      row.querySelector(".cf-notes").addEventListener("input", (e) => { state.creatorFields[i].notes    = e.target.value; saveStateToStorage(); });
      row.querySelector(".cf-remove").addEventListener("click", () => removeCreatorField(i));
      tbody.appendChild(row);
    });
  }

  function buildCreatorFieldSpec() {
    const appName  = (document.getElementById("creator-app-display-name")?.value  || state.creatorAppDisplayName  || "").trim();
    const appLink  = (document.getElementById("creator-app-link-name")?.value      || state.creatorAppLinkName      || appName).trim();
    const formName = (document.getElementById("creator-form-display-name")?.value  || state.creatorFormDisplayName  || "").trim();
    const formLink = (document.getElementById("creator-form-link-name")?.value     || state.creatorFormLinkName     || formName).trim();

    if (!appName && !formName && !state.creatorFields.length) return "";

    let spec = "## Zoho Creator Form Specification\n";
    if (appName)  spec += `App: ${appName}${appLink && appLink !== appName ? " (link: " + appLink + ")" : ""}\n`;
    if (formName) spec += `Form: ${formName}${formLink && formLink !== formName ? " (link: " + formLink + ")" : ""}\n`;

    const activeFields = state.creatorFields.filter((f) => f.name.trim());
    if (activeFields.length) {
      spec += "\n### Form Fields\n| Field Name | Type | Required | Notes |\n|---|---|---|---|\n";
      activeFields.forEach((f) => {
        spec += `| ${f.name} | ${f.type} | ${f.required ? "Yes" : "No"} | ${f.notes || "—"} |\n`;
      });
    }
    return spec;
  }

  function restoreCreatorSpecInputs() {
    const set = (id, val) => { const el = document.getElementById(id); if (el && val) el.value = val; };
    set("creator-app-display-name",  state.creatorAppDisplayName);
    set("creator-app-link-name",     state.creatorAppLinkName);
    set("creator-form-display-name", state.creatorFormDisplayName);
    set("creator-form-link-name",    state.creatorFormLinkName);
  }

  function getCreatorFormLink() {
    return (document.getElementById("creator-form-link-name")?.value
      || document.getElementById("creator-form-display-name")?.value
      || state.creatorFormLinkName || state.creatorFormDisplayName || "").trim();
  }

  function getCreatorAppLink() {
    return (document.getElementById("creator-app-link-name")?.value
      || document.getElementById("creator-app-display-name")?.value
      || state.creatorAppLinkName || state.creatorAppDisplayName || state.creatorApp || "").trim();
  }

  // ---- Generator mode (UAT cases / Implementation) ----
  // Implementation mode is CRM-only and uses Blueprint → Build → Verify (implement.js)
  // instead of Cases → Fly → Land.
  function switchMode(mode) {
    state.mode = mode === "impl" ? "impl" : "uat";
    const impl = state.mode === "impl";
    document.querySelectorAll(".mode-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.mode === state.mode);
    });
    if (impl && state.platform === "creator") switchPlatform("crm");
    const creatorBtn = document.getElementById("platform-creator-btn");
    if (creatorBtn) creatorBtn.hidden = impl;
    const hint = document.getElementById("mode-hint");
    if (hint) hint.hidden = !impl;
    ["cases", "execute", "push"].forEach((t) => { const b = getTabBtn(t); if (b) b.hidden = impl; });
    IMPL_TABS.forEach((t) => { const b = getTabBtn(t); if (b) b.hidden = !impl; });
    els.generate.innerHTML = impl ? "Generate blueprint &rarr;" : "Generate UAT cases &rarr;";
    if (!impl) setTimeout(renderGuideUat, 0);
    if (!TABS.includes(state.activeTab) || (impl && ["cases", "execute", "push"].includes(state.activeTab))
        || (!impl && IMPL_TABS.includes(state.activeTab))) {
      showTab("input");
    }
    refreshGenerateAvailability();
    saveStateToStorage();
  }

  // Hooks for implement.js (kept small on purpose).
  window.LiftOff = {
    orbitHtml, orbitSet,
    plainBrd,
    busy,
    guide,
    FUNCTION_BASE,
    state,
    fetchWithTimeout,
    generateCasesStaged,
    executeBatched,
    showToast: (...a) => showToast(...a),
    escapeHtml: (s) => escapeHtml(s),
    showTab,
    setTabState,
    setStatus,
    showLoader,
    hideLoader,
    switchMode,
    /** Step 5: run the UAT generator on a BRD derived from the approved spec. */
    generateUat(brd, modules) {
      switchMode("uat");
      state.brd = brd;
      state.fileName = "implementation-spec.md";
      state.modulesSelected = modules.slice(0, MAX_MODULES);
      if (els.fileMeta) els.fileMeta.textContent = "Implementation spec — " + brd.length.toLocaleString() + " chars";
      renderModuleChips();
      refreshGenerateAvailability();
      saveStateToStorage();
      setTabState("cases", { locked: false });
      return generate();
    },
  };

  function switchPlatform(platform, opts) {
    if (!(opts && opts.auto)) state.platformManual = true;
    if (state.platform === platform) return;
    state.platform = platform;
    if (platform !== "creator") {
      // Each product has its own module list; CRM's comes from the org, Desk's is fixed.
      state.modulesSelected = state.modulesSelected.filter((m) => (PRODUCTS[platform].modules.length ? PRODUCTS[platform].modules : [m]).includes(m));
      if (platform === "desk") state.modulesAvailable = PRODUCTS.desk.modules.map((m) => ({ api_name: m, plural_label: m, singular_label: m.replace(/s$/, ""), custom: false }));
      else if (state.modulesAvailable.length && state.modulesAvailable.every((m) => PRODUCTS.desk.modules.includes(m.api_name))) state.modulesAvailable = [];
      renderModuleChips();
    }
    saveStateToStorage();
    setTimeout(renderGuideBrief, 0);

    // Update platform selector button active states
    document.querySelectorAll(".platform-btn").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.platform === platform);
    });

    // Sync auth panel to selected platform
    const platformLabel = document.getElementById("auth-platform-label");
    if (platformLabel) platformLabel.textContent = productLabel(platform);
    if (platform === "creator") {
      if (els.authLoginArea)    els.authLoginArea.hidden    = true;
      if (els.authUserInfo)     els.authUserInfo.hidden     = false;
      if (els.authOrgName)      els.authOrgName.textContent = "Zoho Creator";
      if (els.authEmail)        els.authEmail.textContent   = state.authEmailId || state.authUser || "";
      if (els.authSwitchOrgBtn) els.authSwitchOrgBtn.hidden = true;
      if (els.authLogoutBtn)    els.authLogoutBtn.hidden    = true;
    } else {
      if (els.authLoginArea)    els.authLoginArea.hidden    = state.crmAuthorized;
      if (els.authUserInfo)     els.authUserInfo.hidden     = !state.crmAuthorized;
      if (els.authSwitchOrgBtn) els.authSwitchOrgBtn.hidden = false;   // an org id can always be entered by hand
      if (els.authLogoutBtn)    els.authLogoutBtn.hidden    = false;
      if (els.authOrgName)      els.authOrgName.textContent = state.crmOrgName || productLabel(platform);
      if (els.authEmail)        els.authEmail.textContent   = state.crmEmail   || state.authUser || "";
    }

    // Show/hide CRM vs Creator sections in BRD builder
    document.querySelectorAll(".brd-crm-only").forEach((el) => {
      el.style.display = platform === "crm" ? "" : "none";
    });
    document.querySelectorAll(".brd-creator-only").forEach((el) => {
      el.style.display = platform === "creator" ? "" : "none";
    });

    // Update module picker label
    const pickerLabel = document.getElementById("module-picker-label");
    if (pickerLabel) {
      pickerLabel.textContent = platform === "creator" ? "Target forms" : platform === "desk" ? "Desk modules" : "Target modules";
    }

    // Update "Execute in…" button label on Cases tab
    if (els.toExecute) {
      // Keep the arrow, just swap the label text node
      const arrow = " →";
      els.toExecute.textContent = platform === "creator"
        ? "Execute in Zoho Creator" + arrow
        : "Execute in Zoho CRM" + arrow;
    }

    // Show/hide platform-specific sections
    const modulePicker      = document.getElementById("module-picker");
    const modulePickerLabel = document.getElementById("module-picker-label");
    const creatorSpec       = document.getElementById("creator-spec-section");
    if (platform === "creator") {
      if (modulePicker)      modulePicker.hidden      = true;
      if (modulePickerLabel) modulePickerLabel.hidden = true;
      if (creatorSpec)       creatorSpec.hidden       = false;
      if (els.loadModulesBtn) els.loadModulesBtn.textContent = "↻ Load forms from Creator";
      // Restore persisted Creator spec into inputs
      restoreCreatorSpecInputs();
      renderCreatorFields();
    } else {
      if (modulePicker)      modulePicker.hidden      = false;
      if (modulePickerLabel) modulePickerLabel.hidden = false;
      if (creatorSpec)       creatorSpec.hidden       = true;
      if (els.loadModulesBtn) els.loadModulesBtn.textContent = "↻ Reload modules";
    }

    // Clear existing module selection
    state.modulesSelected = [];
    state.modulesAvailable = [];
    state.suggestedModules = [];
    state.creatorForms = [];

    if (platform === "creator") {
      renderModuleChips();
      // Do NOT auto-call loadCreatorForms — user fills spec manually.
      // They can click "Load forms from Creator" to populate from API.
    } else {
      renderModuleChips();
      if (state.crmAuthorized || isLocal) loadModules();
    }

    refreshGenerateAvailability();
    saveStateToStorage();
  }

  function showCrmRequiredModal() {
    const overlay = document.getElementById("crm-req-overlay");
    if (overlay) overlay.hidden = false;
  }

  async function executeInCrm() {
    if (!state.cases.length) {
      setStatus("execute", "Generate cases first.", "warn");
      return;
    }
    if (!state.crmAuthorized && !isLocal) {
      showCrmRequiredModal();
      return;
    }
    startExecLoader();
    showLoader("execute", "");
    els.execute.disabled = true;
    els.toPush.disabled = true;
    els.execSummary.textContent = "";
    if (els.execQuote) { els.execQuote.textContent = ""; els.execQuote.hidden = true; }
    els.execResults.innerHTML = "";
    setStatus("execute", "");

    try {
      // Without a CRM session (local dev), ask for a dry run explicitly: the server
      // checks the plans and sends nothing to CRM. It no longer fakes results.
      const dryRun = !state.crmAuthorized;
      const cases = state.cases;
      const execBody = { platform: state.platform, dry_run: dryRun };
      if (state.platform === "creator") {
        execBody.creator_owner = state.creatorOwner;
        execBody.creator_app   = state.creatorApp;
      }
      const run = () => executeBatched(cases, execBody, (text, f, st) => loaderProgress("exec", text, f, st));
      let data;
      try {
        try {
          data = await run();
        } catch (e) {
          if (e.status !== 409 || !isLocal || execBody.dry_run) throw e;
          // Local mode pretends to be signed in; with no real CRM session, dry-run instead.
          execBody.dry_run = true;
          data = await run();
        }
      } catch (e) {
        if (e.status === 409 && e.data && e.data.needs_auth) {
          state.crmAuthorized = false;
          showCrmRequiredModal();
          setStatus("execute", e.data.error, "warn");
          return;
        }
        throw e;
      }

      state.cases = Array.isArray(data.cases) ? data.cases : state.cases;
      state.executed = true;
      saveStateToStorage();
      renderCasesList(els.execResults, { showExec: true, showPush: false });
      uatLast.exec = data; renderGuideUat();

      if (data.dry_run) {
        const bad = data.plans_with_issues || 0;
        els.execSummary.textContent =
          `Dry run — nothing was sent to Zoho CRM. ${data.plans_ok || 0} plan(s) look runnable, ` +
          `${bad} need fixes, ${data.skipped} have no plan. Connect CRM to run them for real.`;
        els.execSummary.className = "exec-summary warn";
        els.toPush.disabled = false;
        setStatus("execute", bad ? "Fix the flagged plans, or connect CRM and run for real."
                                 : "Plans look runnable. Connect CRM to execute them.", "warn");
        return;
      }

      const mode = data.simulated ? "simulated" : "live";
      const kind = data.failed ? "warn" : "ok";
      els.execSummary.textContent =
        `Execution complete (${mode}): ${data.passed} passed, ${data.failed} failed, ${data.skipped} skipped.`;
      els.execSummary.className = "exec-summary " + kind;
      if (els.execQuote) {
        const pool = data.failed === 0 ? EXEC_QUOTES_PASS : EXEC_QUOTES_FAIL;
        els.execQuote.textContent = "“" + pool[Math.floor(Math.random() * pool.length)] + "”";
        els.execQuote.hidden = false;
      }

      els.toPush.disabled = false;
      markStepDone("execute");
      setStatus("execute",
        data.failed ? "Some cracks — review the trace, then proceed to Land."
                    : "Flawless flight. Cleared to Land.", kind);
      if (!data.failed && data.passed > 0) {
        celebrateAllPass();
      }
    } catch (e) {
      setStatus("execute", "Error: " + e.message, "err");
    } finally {
      stopExecLoader();
      hideLoader("execute");
      els.execute.disabled = false;
    }
  }

  async function pushToProjects() {
    if (!state.executed) {
      setStatus("push", "Execute the cases first.", "warn");
      return;
    }

    startPushLoader();
    showLoader("push", "");
    els.push.disabled = true;
    els.pushSummary.textContent = "";
    if (els.pushLink) { els.pushLink.innerHTML = ""; els.pushLink.hidden = true; }
    els.pushResults.innerHTML = "";

    try {
      // The server creates (or reuses) a Projects project of this name and
      // lands the tasks inside it. Portal stays from project-defaults.properties.
      const payload = { cases: state.cases };
      const projectName = els.projectName && els.projectName.value.trim();
      if (projectName) payload.project_name = projectName;
      const res = await fetchWithTimeout(
        FUNCTION_BASE + "/push",
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload) },
        120_000, "Push to Projects"
      );
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Push failed");

      const byTitle = {};
      (data.results || []).forEach((r) => { byTitle[r.title] = r; });
      state.cases = state.cases.map((tc) =>
        byTitle[tc.title] ? Object.assign({}, tc, { push_result: byTitle[tc.title] }) : tc
      );
      state.pushed = true;
      renderCasesList(els.pushResults, { showExec: true, showPush: true });
      uatLast.push = data; renderGuideUat();

      const mock = data.mock ? " (mock — set ZOHO_REFRESH_TOKEN for live push)" : "";
      const kind = data.tasks_failed || data.bugs_failed ? "warn" : "ok";
      const inProject = data.project_name
        ? ` into project "${data.project_name}"`
        : "";
      els.pushSummary.textContent =
        `Push complete: ${data.tasks_created} task(s), ${data.bugs_created} bug(s) created${inProject}${mock}.`;
      els.pushSummary.className = "exec-summary " + kind;
      if (data.project_url && !data.mock && els.pushLink) {
        els.pushLink.innerHTML =
          `<a href="${escapeHtml(data.project_url)}" target="_blank" rel="noopener" class="project-link-btn">Open in Zoho Projects &rarr;</a>`;
        els.pushLink.hidden = false;
      }

      markStepDone("push");
      setStatus("push", "Push complete.", kind);
    } catch (e) {
      setStatus("push", "Error: " + e.message, "err");
    } finally {
      stopPushLoader();
      hideLoader("push");
      els.push.disabled = false;
    }
  }

  function downloadJson() {
    const blob = new Blob([JSON.stringify({ cases: state.cases, projects_payload: state.payload }, null, 2)],
                         { type: "application/json" });
    triggerDownload(blob, "uat-cases.json");
  }

  function exportCsv() {
    if (!state.cases.length) return;
    const tasksCsv = buildTasksCsv(state.cases);
    triggerDownload(
      new Blob(["﻿" + tasksCsv], { type: "text/csv;charset=utf-8" }),
      "uat-cases.csv"
    );
    const bugsCsv = buildBugsCsv(state.cases);
    if (bugsCsv) {
      const failCount = state.cases.filter(
        (tc) => tc.execution_result && tc.execution_result.status === "fail"
      ).length;
      setTimeout(() => triggerDownload(
        new Blob(["﻿" + bugsCsv], { type: "text/csv;charset=utf-8" }),
        "uat-bugs.csv"
      ), 200);
      showToast(`Exported ${state.cases.length} cases + ${failCount} bugs as CSV`, "ok");
    } else {
      showToast(`Exported ${state.cases.length} cases as CSV`, "ok");
    }
  }

  function triggerDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  // Escapes one CSV cell per RFC 4180. Always quote so embedded commas /
  // newlines / quotes round-trip cleanly into Excel and Projects' import UI.
  function csvCell(v) {
    if (v == null) return '""';
    const s = String(v).replace(/"/g, '""');
    return '"' + s + '"';
  }

  function priorityForProjects(p) {
    switch ((p || "P1").toUpperCase()) {
      case "P0": return "High";
      case "P2": return "Low";
      default:   return "Medium";
    }
  }

  function statusForProjects(tc) {
    const s = tc.execution_result && tc.execution_result.status;
    if (s === "pass") return "Closed";
    if (s === "fail") return "Open";
    return "Open";
  }

  // Builds a Projects-import-friendly CSV. Columns match what Zoho Projects'
  // "Import → Tasks" UI expects. Failures are listed separately as Bug rows
  // in a second CSV (since Projects uses different importers for the two).
  function buildTasksCsv(cases) {
    const header = [
      "Task Name", "Description", "Priority", "Status", "Tags",
      "Module", "Requirements", "Execution Status",
    ];
    const rows = [header.map(csvCell).join(",")];
    cases.forEach((tc) => {
      const desc = [
        tc.gherkin ? "Gherkin:\n" + tc.gherkin : "",
        tc.steps && tc.steps.length
          ? "Steps:\n" + tc.steps.map((s, i) => (i + 1) + ". " + (s.action || "") +
              " -> " + (s.expected || "")).join("\n")
          : "",
        tc.acceptance ? "Acceptance:\n" + tc.acceptance : "",
      ].filter(Boolean).join("\n\n");
      const row = [
        tc.title || "Untitled case",
        desc,
        priorityForProjects(tc.priority),
        statusForProjects(tc),
        (tc.tags || []).concat(["uat"]).join(";"),
        tc.module || "",
        (Array.isArray(tc.req_ids) ? tc.req_ids : []).join(";"),
        (tc.execution_result && tc.execution_result.status) || "",
      ];
      rows.push(row.map(csvCell).join(","));
    });
    return rows.join("\r\n");
  }

  function buildBugsCsv(cases) {
    const failed = cases.filter((tc) =>
      tc.execution_result && tc.execution_result.status === "fail");
    if (!failed.length) return null;
    const header = [
      "Bug Title", "Description", "Severity", "Classification",
      "Steps to Reproduce", "Module",
    ];
    const rows = [header.map(csvCell).join(",")];
    failed.forEach((tc) => {
      const sev = priorityForProjects(tc.priority) === "High" ? "Show Stopper"
                : priorityForProjects(tc.priority) === "Low"  ? "Minor"
                : "Major";
      const repro = (tc.steps || [])
        .map((s, i) => (i + 1) + ". " + (s.action || "") + " -> " + (s.expected || ""))
        .join("\n");
      const firstFail = tc.execution_result && tc.execution_result.first_failure;
      const desc = [
        firstFail ? "First failure: " + firstFail : "",
        tc.gherkin ? "Scenario:\n" + tc.gherkin : "",
        tc.acceptance ? "Acceptance:\n" + tc.acceptance : "",
      ].filter(Boolean).join("\n\n");
      const row = [
        "[UAT FAIL] " + (tc.title || "Untitled case"),
        desc,
        sev,
        "Bug",
        repro,
        tc.module || "",
      ];
      rows.push(row.map(csvCell).join(","));
    });
    return rows.join("\r\n");
  }

  function downloadCsv() {
    if (!state.cases.length) {
      setStatus("push", "Generate cases first.", "warn");
      return;
    }
    const tasksCsv = buildTasksCsv(state.cases);
    triggerDownload(
      new Blob(["﻿" + tasksCsv], { type: "text/csv;charset=utf-8" }),
      "uat-tasks-projects-import.csv"
    );
    const bugsCsv = buildBugsCsv(state.cases);
    if (bugsCsv) {
      // Small delay so both downloads register in browser
      setTimeout(() => {
        triggerDownload(
          new Blob(["﻿" + bugsCsv], { type: "text/csv;charset=utf-8" }),
          "uat-bugs-projects-import.csv"
        );
      }, 250);
    }
    const failedCount = state.cases.filter((tc) =>
      tc.execution_result && tc.execution_result.status === "fail").length;
    setStatus("push",
      `Downloaded ${state.cases.length} task(s)${failedCount ? ` and ${failedCount} bug(s)` : ""}.`,
      "ok");
  }

  function restart() {
    if (window.LiftOffImpl) window.LiftOffImpl.reset();
    try { localStorage.removeItem(STORAGE_KEY); } catch (e) {}
    state.brd = "";
    state.fileName = "";
    state.cases = [];
    state.payload = null;
    state.modulesSelected = [];
    state.suggestedModules = [];
    state.analysisText = "";
    state.executed = false;
    state.pushed = false;
    state.stepDone = { input: false, cases: false, execute: false, push: false };
    renderAnalysisSummary();
    renderModuleChips();

    els.brdFile.value = "";
    els.fileMeta.textContent = "";
    els.generate.disabled = true;
    els.toExecute.disabled = true;
    els.toPush.disabled = true;
    els.download.disabled = true;
    if (els.exportCsvBtn) els.exportCsvBtn.disabled = true;
    els.casesMeta.textContent = "";
    els.cases.innerHTML = "";
    els.execSummary.textContent = "";
    if (els.execQuote) { els.execQuote.textContent = ""; els.execQuote.hidden = true; }
    els.execResults.innerHTML = "";
    els.pushSummary.textContent = "";
    if (els.pushLink) { els.pushLink.innerHTML = ""; els.pushLink.hidden = true; }
    els.pushResults.innerHTML = "";
    ["input", "cases", "execute", "push"].forEach((t) => {
      setStatus(t, "");
      setTabState(t, { done: false, running: false });
    });
    setTabState("cases",   { locked: true });
    setTabState("execute", { locked: true });
    setTabState("push",    { locked: true });
    showTab("input");
  }

  // ---- Wire events ----

  els.brdFile.addEventListener("change", (e) => {
    handleFile(e.target.files && e.target.files[0]);
  });
  ["dragenter", "dragover"].forEach((evt) => {
    els.fileDrop.addEventListener(evt, (e) => {
      e.preventDefault();
      els.fileDrop.classList.add("dragover");
    });
  });
  ["dragleave", "drop"].forEach((evt) => {
    els.fileDrop.addEventListener(evt, (e) => {
      e.preventDefault();
      els.fileDrop.classList.remove("dragover");
    });
  });
  els.fileDrop.addEventListener("drop", (e) => {
    const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) {
      els.brdFile.value = "";
      handleFile(f);
    }
  });

  els.generate.addEventListener("click", generate);
  if (els.downloadSampleBtn) els.downloadSampleBtn.addEventListener("click", openBrdModal);

  // BRD modal wiring
  (function() {
    function wire(id, fn) { var el = document.getElementById(id); if (el) el.addEventListener("click", fn); }

    wire("brd-modal-close",  closeBrdModal);
    wire("brd-modal-cancel", closeBrdModal);
    wire("brd-add-field",    addBrdFieldRow);
    wire("brd-add-filter",   addBrdFilterRow);
    wire("brd-add-rel",      addBrdRelRow);
    wire("brd-reset",        resetBrdModal);

    // Import CSV for fields
    var importBtn  = document.getElementById("brd-import-fields");
    var importFile = document.getElementById("brd-import-fields-file");
    if (importBtn && importFile) {
      importBtn.addEventListener("click", function() { importFile.value = ""; importFile.click(); });
      importFile.addEventListener("change", function() { importFieldsCsv(importFile.files[0]); });
    }

    // Auto-fill defaults when module API name is filled
    var moduleApiInput = document.getElementById("brd-module-api");
    if (moduleApiInput) {
      moduleApiInput.addEventListener("blur", function() {
        var v = moduleApiInput.value.trim();
        if (v) autoFillDefaultFields(v);
      });
    }

    var overlay = document.getElementById("brd-modal-overlay");
    if (overlay) {
      overlay.addEventListener("click", function(e) {
        if (e.target === overlay) closeBrdModal();
      });
    }

    function brdValidateMandatory() {
      var proj = (getElVal("brd-project-name") || "").trim();
      if (!proj) {
        showToast("Please fill in Project Name (marked *)", "warn", 4000);
        return false;
      }
      if (state.platform === "creator") {
        var appName  = (getElVal("brd-creator-app-name")  || "").trim();
        var formName = (getElVal("brd-creator-form-name") || "").trim();
        if (!appName || !formName) {
          showToast("Please fill in App Name and Form Name (marked *)", "warn", 4000);
          return false;
        }
      } else {
        var entity = (getElVal("brd-entity-name") || "").trim();
        var api    = (getElVal("brd-module-api")  || "").trim();
        if (!entity || !api) {
          showToast("Please fill in Entity / Module Name and Module API Name (marked *)", "warn", 4000);
          return false;
        }
      }
      return true;
    }

    wire("brd-download-md", function() {
      if (!brdValidateMandatory()) return;
      triggerDownload(new Blob([serializeBrd()], { type: "text/markdown;charset=utf-8" }), "brd-template.md");
    });

    wire("brd-use-template", function() {
      if (!brdValidateMandatory()) return;
      var text   = serializeBrd();
      var entity = state.platform === "creator"
        ? (getElVal("brd-creator-form-name").trim() || "form")
        : (getElVal("brd-entity-name").trim() || "template");
      state.brd      = text;
      state.fileName = entity.replace(/\s+/g, "-").toLowerCase() + "-brd.md";
      renderRequirementsTable();
      if (els.fileMeta) els.fileMeta.textContent = "✓ BRD loaded from template (" + state.fileName + ")";
      closeBrdModal();
      refreshGenerateAvailability();
      if (state.brd && state.modulesSelected.length) markStepDone("input");
    });
  }());

  // ---- ConvoKraft chatbot FAB toggle ----
  (function() {
    var fab       = document.getElementById("chatbot-fab");
    var container = document.getElementById("chatbot-container");
    if (!fab || !container) return;
    // Ensure hidden state is driven by a class so CSS-defined display doesn't conflict
    container.style.display = "none";
    var chatOpen = false;
    fab.addEventListener("click", function() {
      chatOpen = !chatOpen;
      container.style.display = chatOpen ? "block" : "none";
      fab.classList.toggle("active", chatOpen);
      fab.setAttribute("aria-expanded", String(chatOpen));
    });
  }());
  els.backToInput.addEventListener("click", () => showTab("input"));
  els.toExecute.addEventListener("click", () => {
    if (!state.crmAuthorized && !isLocal) {
      document.getElementById("crm-req-overlay").hidden = false;
      return;
    }
    showTab("execute");
  });
  els.download.addEventListener("click", downloadJson);
  if (els.exportCsvBtn) els.exportCsvBtn.addEventListener("click", exportCsv);

  els.execute.addEventListener("click", executeInCrm);
  els.backToCases.addEventListener("click", () => showTab("cases"));
  els.toPush.addEventListener("click", () => showTab("push"));

  els.push.addEventListener("click", pushToProjects);
  els.backToExecute.addEventListener("click", () => showTab("execute"));
  if (els.downloadCsv) els.downloadCsv.addEventListener("click", downloadCsv);
  els.restart.addEventListener("click", restart);

  if (els.loadModulesBtn) els.loadModulesBtn.addEventListener("click", () => {
    if (state.platform === "creator") loadCreatorForms();
    else loadModules();
  });

  // Creator form spec — live input wiring
  (function () {
    function toSlug(str, sep) {
      return str.trim().replace(/\s+/g, sep).replace(/[^a-zA-Z0-9_\-]/g, "");
    }
    // App display → auto-fill link name
    const appDisplay = document.getElementById("creator-app-display-name");
    const appLink    = document.getElementById("creator-app-link-name");
    if (appDisplay) appDisplay.addEventListener("input", (e) => {
      state.creatorAppDisplayName = e.target.value;
      if (appLink && !appLink.dataset.edited) appLink.value = toSlug(e.target.value, "_").toLowerCase();
      saveStateToStorage();
    });
    if (appLink) appLink.addEventListener("input", (e) => {
      appLink.dataset.edited = "1";
      state.creatorAppLinkName = e.target.value;
      saveStateToStorage();
    });
    // Form display → auto-fill link name + enable generate button
    const formDisplay = document.getElementById("creator-form-display-name");
    const formLink    = document.getElementById("creator-form-link-name");
    if (formDisplay) formDisplay.addEventListener("input", (e) => {
      state.creatorFormDisplayName = e.target.value;
      if (formLink && !formLink.dataset.edited) formLink.value = toSlug(e.target.value, "_");
      refreshGenerateAvailability();
      const ready = !!getCreatorFormLink() && !!state.brd;
      if (ready) markStepDone("input");
      saveStateToStorage();
    });
    if (formLink) formLink.addEventListener("input", (e) => {
      formLink.dataset.edited = "1";
      state.creatorFormLinkName = e.target.value;
      refreshGenerateAvailability();
      saveStateToStorage();
    });
    // Add field button
    const addBtn = document.getElementById("creator-add-field-btn");
    if (addBtn) addBtn.addEventListener("click", addCreatorField);
  }());

  // CRM required modal wiring
  (function () {
    const overlay = document.getElementById("crm-req-overlay");
    const connectBtn = document.getElementById("crm-req-connect");
    const cancelBtn  = document.getElementById("crm-req-cancel");
    if (connectBtn) connectBtn.addEventListener("click", () => {
      // Remember to resume execution after OAuth returns
      try { sessionStorage.setItem("liftoff_resume_exec", "1"); } catch (e) {}
      window.location.href = FUNCTION_BASE + "/crm/auth";
    });
    if (cancelBtn)  cancelBtn.addEventListener("click",  () => { if (overlay) overlay.hidden = true; });
    if (overlay)    overlay.addEventListener("click", (e) => { if (e.target === overlay) overlay.hidden = true; });
  }());

  // Generator mode selector
  document.querySelectorAll(".mode-btn").forEach((btn) => {
    btn.addEventListener("click", () => switchMode(btn.dataset.mode));
  });

  // Platform selector
  document.querySelectorAll(".platform-btn").forEach((btn) => {
    btn.addEventListener("click", () => switchPlatform(btn.dataset.platform));
  });

  // Restore persisted state (before auth so UI is pre-populated)
  loadStateFromStorage();
  // Sync platform UI to restored state
  if (state.platform === "desk") {
    document.querySelectorAll(".platform-btn").forEach((b) => b.classList.toggle("active", b.dataset.platform === "desk"));
    state.modulesAvailable = PRODUCTS.desk.modules.map((m) => ({ api_name: m, plural_label: m, singular_label: m.replace(/s$/, ""), custom: false }));
    const lbl = document.getElementById("module-picker-label"); if (lbl) lbl.textContent = "Desk modules";
    const pl = document.getElementById("auth-platform-label"); if (pl) pl.textContent = "Zoho Desk";
  }
  if (state.platform === "creator") {
    document.querySelectorAll(".platform-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.platform === "creator");
    });
    document.querySelectorAll(".brd-crm-only").forEach((el) => { el.style.display = "none"; });
    document.querySelectorAll(".brd-creator-only").forEach((el) => { el.style.display = ""; });
    const lbl = document.getElementById("module-picker-label");
    if (lbl) lbl.hidden = true;
    const modulePicker = document.getElementById("module-picker");
    if (modulePicker) modulePicker.hidden = true;
    const creatorSpec  = document.getElementById("creator-spec-section");
    if (creatorSpec) creatorSpec.hidden = false;
    if (els.loadModulesBtn) els.loadModulesBtn.textContent = "↻ Load forms from Creator";
    restoreCreatorSpecInputs();
    renderCreatorFields();
  }
  // If we restored cases, unlock tabs and render
  if (state.cases.length) {
    setTabState("cases",   { locked: false, done: true });
    setTabState("execute", { locked: false, done: state.executed });
    setTabState("push",    { locked: false, done: state.pushed });
    state.stepDone.input = !!state.brd;
    state.stepDone.cases = true;
    renderCasesList(els.cases, { showExec: false, showPush: false });
    els.casesMeta.textContent = `${state.cases.length} case(s) — restored from last session`;
    els.toExecute.disabled  = false;
    els.download.disabled   = false;
    if (els.exportCsvBtn) els.exportCsvBtn.disabled = false;
    if (state.brd) setTabState("input", { done: true });
    if (els.fileMeta && state.fileName && !els.fileMeta.textContent) {
      els.fileMeta.textContent = "✓ " + state.fileName + " — restored";
    }
    refreshGenerateAvailability();
  }

  if (state.mode === "impl") switchMode("impl");

  // Auth
  initAuth();

  // First-paint
  renderModuleChips();
  renderGuideBrief();
  renderGuideUat();

  if (isLocal) {
    console.info("UAT Generator running in local mode. API base: " + FUNCTION_BASE);
  }
})();
