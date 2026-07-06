(function () {
  const isLocal = ["localhost", "127.0.0.1"].includes(location.hostname);
  // In production the client and function are on the same Catalyst domain —
  // use a relative path so it works for any environment without hardcoding.
  const FUNCTION_BASE = isLocal
    ? "http://localhost:9000/uat_generator"
    : "/server/uat_generator";

  const TABS = ["input", "cases", "execute", "push"];

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
    creatorOwner: "",
    creatorApp: "",
    creatorForms: [],
    creatorAppDisplayName: "",
    creatorAppLinkName: "",
    creatorFormDisplayName: "",
    creatorFormLinkName: "",
    creatorFields: [],
  };

  const MAX_MODULES = 5;

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
        modulesSelected:       state.modulesSelected,
        platform:              state.platform,
        creatorOwner:          state.creatorOwner,
        creatorApp:            state.creatorApp,
        creatorAppDisplayName: state.creatorAppDisplayName,
        creatorAppLinkName:    state.creatorAppLinkName,
        creatorFormDisplayName:state.creatorFormDisplayName,
        creatorFormLinkName:   state.creatorFormLinkName,
        creatorFields:         state.creatorFields,
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
      if (saved.platform) state.platform = saved.platform;
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
    const loaderEl = document.querySelector('[data-panel="execute"] .loader');
    if (loaderEl) loaderEl.classList.remove("fullscreen");
    clearInterval(_execMsgTimer);
    clearInterval(_execProgressTimer);
    _execMsgTimer = null; _execProgressTimer = null;
    const fillEl = document.getElementById("exec-bar-fill");
    if (fillEl) { fillEl.style.transition = "width 0.3s ease"; fillEl.style.width = "100%"; }
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
  }
  function hideLoader(panelName) {
    const panel = getPanel(panelName);
    const loader = panel.querySelector(".loader");
    loader.hidden = true;
    setTabState(panelName, { running: false });
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
  function detectKind(file) {
    const name = (file.name || "").toLowerCase();
    if (name.endsWith(".pdf") || file.type === "application/pdf") return "pdf";
    if (name.endsWith(".docx") ||
        file.type === "application/vnd.openxmlformats-officedocument.wordprocessingml.document") return "docx";
    if (name.endsWith(".md") || name.endsWith(".markdown")) return "md";
    if (name.endsWith(".txt") || (file.type || "").startsWith("text/")) return "txt";
    return null;
  }

  async function handleFile(file) {
    if (!file) return;
    const kind = detectKind(file);
    if (!kind) {
      setStatus("input", "Unsupported file type. Use .txt, .md, .pdf, or .docx.", "err");
      els.fileMeta.textContent = "";
      state.brd = "";
      state.fileName = "";
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
      text = (text || "").trim();
      if (!text) throw new Error("File parsed but no text was extracted.");
      state.brd = text;
      state.fileName = file.name;
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
    const tags = (tc.tags || []).map((t) => `<span class="tag">${escapeHtml(t)}</span>`).join("");
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
    const sim = exec.simulated ? " (sim)" : "";
    const cls = status === "pass" ? "pass" : status === "fail" ? "fail" : "skip";
    return `<span class="exec-badge ${cls}">${escapeHtml(status.toUpperCase() + sim)}</span>`;
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
    return `<details class="trace" ${exec.status === "fail" ? "open" : ""}><summary>Execution trace</summary><ol class="trace-list">${rows}</ol></details>`;
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

  // ---- Actions ----

  async function generate() {
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
        brd: state.brd,
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
      const res = await fetchWithTimeout(
        FUNCTION_BASE + "/generate",
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body) },
        150_000, "Case generation"
      );
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Generation failed");

      const aiCases = Array.isArray(data.cases) ? data.cases : [];
      const defaultTests = buildDefaultApiTests(state.modulesSelected);
      state.cases = [...defaultTests, ...aiCases];
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

      const have = state.cases.length > 0;
      els.toExecute.disabled = !have;
      els.download.disabled = !have;
      if (els.exportCsvBtn) els.exportCsvBtn.disabled = !have;
      if (have) markStepDone("cases");
      setStatus("cases", have ? "Review the cases, then click Execute." : "No cases were returned.",
                have ? "ok" : "warn");
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

  async function checkAuthStatus() {
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
      const result = await window.catalyst.auth.isUserAuthenticated();
      const u = result.content;
      state.authEmailId  = u.email_id || "";
      state.authFullName = ((u.first_name || "") + " " + (u.last_name || "")).trim();
      state.authUser = state.authEmailId || state.authFullName || "Zoho User";
      await checkCrmStatus();
      // Also pull the org list so the picker is ready without an extra round-trip.
      await fetchOrgList();
    } catch {
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
      if (els.authSwitchOrgBtn) els.authSwitchOrgBtn.hidden = true;
      if (els.authLogoutBtn)    els.authLogoutBtn.hidden    = true;
    } else {
      if (els.authOrgName) els.authOrgName.textContent = state.crmOrgName || "Zoho CRM";
      if (els.authEmail)   els.authEmail.textContent   = state.crmEmail   || state.authUser || "";
      if (els.authLoginArea) els.authLoginArea.hidden = state.crmAuthorized;
      if (els.authUserInfo)  els.authUserInfo.hidden  = !state.crmAuthorized;
      if (els.authSwitchOrgBtn) els.authSwitchOrgBtn.hidden = state.crmOrgList.length <= 1;
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

  function renderOrgList() {
    const listEl = document.getElementById("org-modal-list");
    if (!listEl) return;

    if (!state.crmOrgList.length) {
      listEl.innerHTML = '<p class="muted" style="padding:1rem 0">No organisations found. Try connecting first.</p>';
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
    }).join("");

    listEl.querySelectorAll(".org-item:not(.org-item--current)").forEach((btn) => {
      btn.addEventListener("click", () => {
        closeOrgPicker();
        // Re-trigger OAuth with prompt=select_account so the user can switch org.
        window.location.href = FUNCTION_BASE + "/crm/auth?switch=true";
      });
    });
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
      }
    } catch (e) {
      showToast("CRM connection failed: " + e.message, "err");
    }
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

    // "Switch org" — open org picker (orgs were loaded in checkAuthStatus).
    if (els.authSwitchOrgBtn) {
      els.authSwitchOrgBtn.addEventListener("click", async () => {
        // Refresh org list in case it's stale.
        if (!state.crmOrgList.length) await fetchOrgList();
        openOrgPicker();
      });
    }

    // Disconnect CRM (clears CRM token only, does not sign out of Catalyst)
    if (els.authLogoutBtn) {
      els.authLogoutBtn.addEventListener("click", async () => {
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
        updateAuthUI();
        renderModuleChips();
        if (els.modulesStatus) els.modulesStatus.textContent = "";
      });
    }

    initOrgPicker();
  }

  // ---- Module picker ----

  function renderModuleChips() {
    if (!els.moduleChips) return;
    els.moduleChips.innerHTML = "";
    const list = state.modulesAvailable.length ? state.modulesAvailable : DEFAULT_MODULES;
    const max = MAX_MODULES;
    // Sort: suggested first (preserving suggestion order), then alphabetical.
    const suggestedSet = new Set(state.suggestedModules);
    const sortedList = list.slice().sort((a, b) => {
      const aSugg = state.suggestedModules.indexOf(a.api_name);
      const bSugg = state.suggestedModules.indexOf(b.api_name);
      const aIn = aSugg >= 0, bIn = bSugg >= 0;
      if (aIn && bIn) return aSugg - bSugg;
      if (aIn) return -1;
      if (bIn) return 1;
      return (a.plural_label || a.api_name).localeCompare(b.plural_label || b.api_name);
    });
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
    counter.textContent = sortedList.length + " of " + sortedList.length;
    toolbar.appendChild(search);
    toolbar.appendChild(counter);
    els.moduleChips.appendChild(toolbar);
    const chipWrap = document.createElement("div");
    chipWrap.className = "module-chip-wrap";
    els.moduleChips.appendChild(chipWrap);
    search.addEventListener("input", () => {
      const q = search.value.trim().toLowerCase();
      let visible = 0;
      chipWrap.querySelectorAll(".module-chip").forEach((c) => {
        const match = !q || c.textContent.toLowerCase().includes(q);
        c.style.display = match ? "" : "none";
        if (match) visible++;
      });
      counter.textContent = visible + " of " + sortedList.length;
    });
    sortedList.forEach((m) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "module-chip";
      const isSelected = state.modulesSelected.includes(m.api_name);
      const isSuggested = suggestedSet.has(m.api_name);
      if (isSelected) btn.classList.add("selected");
      if (isSuggested && !isSelected) btn.classList.add("suggested");
      if (!isSelected && state.modulesSelected.length >= max) btn.classList.add("disabled");
      btn.textContent = m.plural_label || m.api_name;
      btn.dataset.apiName = m.api_name;
      btn.title = isSuggested ? "Suggested based on the spec content" : "";
      btn.addEventListener("click", () => toggleModule(m.api_name));
      chipWrap.appendChild(btn);
    });
    renderSelectedSummary();
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
    if (state.platform === "creator") {
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
          body: JSON.stringify({ brd: state.brd, modules: list }) },
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

  function switchPlatform(platform) {
    if (state.platform === platform) return;
    state.platform = platform;

    // Update platform selector button active states
    document.querySelectorAll(".platform-btn").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.platform === platform);
    });

    // Sync auth panel to selected platform
    const platformLabel = document.getElementById("auth-platform-label");
    if (platformLabel) platformLabel.textContent = platform === "creator" ? "Zoho Creator" : "Zoho CRM";
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
      if (els.authSwitchOrgBtn) els.authSwitchOrgBtn.hidden = state.crmOrgList.length <= 1;
      if (els.authLogoutBtn)    els.authLogoutBtn.hidden    = false;
      if (els.authOrgName)      els.authOrgName.textContent = state.crmOrgName || "Zoho CRM";
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
      pickerLabel.textContent = platform === "creator" ? "Target forms" : "Target modules";
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
      const execBody = { cases: state.cases, platform: state.platform };
      if (state.platform === "creator") {
        execBody.creator_owner = state.creatorOwner;
        execBody.creator_app   = state.creatorApp;
      }
      const res = await fetchWithTimeout(
        FUNCTION_BASE + "/execute",
        { method: "POST", headers: { "Content-Type": "application/json" },
          credentials: "include", body: JSON.stringify(execBody) },
        180_000, "Execution"
      );
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Execute failed");

      state.cases = Array.isArray(data.cases) ? data.cases : state.cases;
      state.executed = true;
      renderCasesList(els.execResults, { showExec: true, showPush: false });

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
      "Module", "Execution Status",
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

  // Platform selector
  document.querySelectorAll(".platform-btn").forEach((btn) => {
    btn.addEventListener("click", () => switchPlatform(btn.dataset.platform));
  });

  // Restore persisted state (before auth so UI is pre-populated)
  loadStateFromStorage();
  // Sync platform UI to restored state
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

  // Auth
  initAuth();

  // First-paint
  renderModuleChips();

  if (isLocal) {
    console.info("UAT Generator running in local mode. API base: " + FUNCTION_BASE);
  }
})();
