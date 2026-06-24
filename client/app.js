(function () {
  const isLocal = ["localhost", "127.0.0.1"].includes(location.hostname);
  // In production the client and function are on the same Catalyst domain —
  // use a relative path so it works for any environment without hardcoding.
  const FUNCTION_BASE = isLocal
    ? "http://localhost:9000/uat_generator"
    : "/server/uat_generator";

  const TABS = ["input", "cases", "execute", "push", "inspect"];

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
    download:  document.getElementById("download-btn"),
    statusCases: document.getElementById("status-cases"),

    execSummary: document.getElementById("exec-summary"),
    execResults: document.getElementById("exec-results"),
    execute:   document.getElementById("execute-btn"),
    backToCases: document.getElementById("back-to-cases"),
    toPush:    document.getElementById("to-push-btn"),
    statusExecute: document.getElementById("status-execute"),
    execLoaderText: document.getElementById("execute-loader-text"),

    projectName: document.getElementById("project_name"),
    pushSummary: document.getElementById("push-summary"),

    loadFunctionsBtn: document.getElementById("load-functions-btn"),
    runAllFunctionsBtn: document.getElementById("run-all-functions-btn"),
    functionsStatus: document.getElementById("functions-status"),
    functionsList:   document.getElementById("functions-list"),
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
    crmAuthorized: false,
    oauthAvailable: false,
    crmEmail: null,
    crmOrgName: null,
    crmOrgList: [],
    stepDone: { input: false, cases: false, execute: false, push: false },
  };

  const MAX_MODULES = 5;

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
  function getTheme() {
    try { return localStorage.getItem("testpilot_theme") || "light"; }
    catch (e) { return "light"; }
  }
  const THEMES = [
    { id: "light",  label: "Light",  swatchBg: "radial-gradient(circle at 35% 35%, #fff 0%, #e0e8f4 100%)", swatchBorder: "rgba(0,0,0,0.15)" },
    { id: "dark",   label: "Dark",   swatchBg: "radial-gradient(circle at 35% 35%, #2a3550 0%, #0d1117 100%)", swatchBorder: "" },
    { id: "ocean",  label: "Ocean",  swatchBg: "radial-gradient(circle at 35% 35%, #00c8d4 0%, #020e1a 100%)", swatchBorder: "" },
    { id: "sunset", label: "Sunset", swatchBg: "radial-gradient(circle at 35% 35%, #ff7043 0%, #160b1e 100%)", swatchBorder: "" },
    { id: "forest", label: "Forest", swatchBg: "radial-gradient(circle at 35% 35%, #2ecc71 0%, #050e08 100%)", swatchBorder: "" },
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
    // Only auto-unlock the next tab within the main pilot flow (steps 1-4).
    // The Inspect tab is utility and stays unlocked at boot.
    if (name === "push") return;
    const next = TABS[TABS.indexOf(name) + 1];
    if (next && next !== "inspect") setTabState(next, { locked: false });
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
      // Suggest a Projects project name from the spec filename so the user
      // doesn't have to type it. They can still override on the Land tab.
      if (els.projectName && !els.projectName.value.trim()) {
        const base = file.name.replace(/\.[^.]+$/, "").replace(/[_\-]+/g, " ").trim();
        const today = new Date().toISOString().slice(0, 10);
        els.projectName.value = `Test Plan — ${base} — ${today}`;
      }
      refreshGenerateAvailability();
      if (state.modulesSelected.length) markStepDone("input");
      setStatus("input",
        state.modulesSelected.length
          ? "Spec parsed. Click Generate UAT cases."
          : "Spec parsed. Analyzing for module suggestions...",
        state.modulesSelected.length ? "ok" : "");
      // Run spec analysis to auto-suggest modules. Non-blocking.
      analyzeBrd().then(() => {
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
  }

  // ---- Sample template download ----

  function downloadSampleTemplate() {
    const content = `# UAT Test Cases Document — [Feature Name]

## Overview
**Module:** [e.g., Leads / Contacts / Deals]
**Developer:** [Your Name]
**Date:** [YYYY-MM-DD]
**Version:** 1.0

---

## Feature Description
[Describe the feature being tested. What does it do? What business problem does it solve?]

---

## Fields

| Field API Name | Label           | Type     | Required | Constraints                           | Default |
|----------------|-----------------|----------|----------|---------------------------------------|---------|
| Last_Name      | Last Name       | Text     | Yes      | Max 80 chars                          | —       |
| Email          | Email           | Email    | No       | Valid email format                    | —       |
| Phone          | Phone           | Phone    | No       | Max 30 chars                          | —       |
| Lead_Status    | Status          | Picklist | No       | New, Contacted, Qualified, Unqualified| New     |

---

## Use Cases

### UC-1: Create a new record
**Description:** A user creates a new record with all required fields filled.

**Preconditions:** User is logged in with Create permission.

**Required fields:** Last_Name
**Optional fields:** Email, Phone, Lead_Status

**Expected result:** Record is created and appears in the list view.

---

### UC-2: Edit an existing record
**Description:** A user edits an existing record's details.

**Preconditions:** A record exists. User has Edit permission.

**Fields to edit:** Email, Phone

**Expected result:** Changes are saved and reflected on the detail page.

---

### UC-3: Delete a record
**Description:** A user deletes a record.

**Preconditions:** A record exists. User has Delete permission.

**Expected result:** Record moves to Recycle Bin. No longer visible in list view.

---

### UC-4: Validation — missing required field
**Description:** Submitting a form without the required Last_Name field.

**Input:** Leave Last_Name blank, fill all other optional fields.

**Expected result:** Validation error is shown. Record is NOT created.

---

### UC-5: Validation — invalid email format
**Description:** Entering an invalid value in the Email field.

**Input:** Email = "not-an-email"

**Expected result:** Field-level validation error shown. Record is NOT created.

---

### UC-6: Picklist — invalid value
**Description:** Sending an unlisted picklist value via API.

**Input:** Lead_Status = "Unknown_Value"

**Expected result:** API returns INVALID_DATA error.

---

### UC-7: Duplicate detection
**Description:** Creating a record with the same Email as an existing one.

**Input:** Email = already existing record's email

**Expected result:** Duplicate alert shown (or API returns DUPLICATE_DATA).

---

## Notes
- Prefix all test record names with **UAT-Smoke-** (e.g. "UAT-Smoke-Lead-001").
- Delete test records after each run to keep the org clean.
- Use zohoapis.in for India DC, zohoapis.com for Global DC.
`;
    triggerDownload(
      new Blob([content], { type: "text/markdown;charset=utf-8" }),
      "test-cases-template.md"
    );
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
    if (!state.modulesSelected.length) {
      setStatus("input", "Pick at least one target module.", "warn");
      return;
    }

    showTab("cases");
    setStatus("cases", "");
    const count = state.modulesSelected.length;
    showLoader("cases",
      `Generating UAT cases for ${count} module${count === 1 ? "" : "s"}... (10–30s per module)`);
    els.toExecute.disabled = true;
    els.download.disabled = true;
    els.casesMeta.textContent = "";
    els.cases.innerHTML = "";

    try {
      const res = await fetch(FUNCTION_BASE + "/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          brd: state.brd,
          modules: state.modulesSelected,
          project_key: "",
        }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Generation failed");

      const aiCases = Array.isArray(data.cases) ? data.cases : [];
      const defaultTests = buildDefaultApiTests(state.modulesSelected);
      state.cases = [...defaultTests, ...aiCases];
      state.payload = data.projects_payload || null;
      state.executed = false;
      state.pushed = false;

      const breakdown = data.counts
        ? Object.entries(data.counts).map(([m, n]) => `${m}: ${n}`).join("  •  ")
        : "";
      els.casesMeta.innerHTML =
        `<span class="cases-count" id="cases-count">0</span>` +
        ` case(s) — ${defaultTests.length} API smoke + ${aiCases.length} via ${escapeHtml(data.provider)}` +
        (breakdown ? `  •  ${escapeHtml(breakdown)}` : "");
      animateCountUp(document.getElementById("cases-count"), state.cases.length);
      renderCasesList(els.cases, { showExec: false, showPush: false });

      const have = state.cases.length > 0;
      els.toExecute.disabled = !have;
      els.download.disabled = !have;
      if (have) markStepDone("cases");
      setStatus("cases", have ? "Review the cases, then click Execute." : "No cases were returned.",
                have ? "ok" : "warn");
    } catch (e) {
      setStatus("cases", "Error: " + e.message, "err");
    } finally {
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
      state.crmAuthorized = true;
      state.crmOrgName    = "Local Dev Org";
      updateAuthUI();
      return;
    }
    try {
      const result = await window.catalyst.auth.isUserAuthenticated();
      const u = result.content;
      state.authUser = u.email_id
        || ((u.first_name || "") + " " + (u.last_name || "")).trim()
        || "Zoho User";
      await checkCrmStatus();
      // Also pull the org list so the picker is ready without an extra round-trip.
      await fetchOrgList();
    } catch {
      // Not authenticated — hide spinner and show styled sign-in button.
      state.authUser      = null;
      state.crmAuthorized = false;
      const loader = document.getElementById("login-auth-loader");
      if (loader) loader.hidden = true;
      const actionArea = document.getElementById("login-action-area");
      if (actionArea) actionArea.hidden = false;
      document.getElementById("zoho-signin-btn")?.addEventListener("click", () => {
        // Full-page redirect to Zoho Accounts (no embedded iframe).
        window.catalyst.auth.signIn(null, { service_url: "/app/index.html" });
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
      // Auto-load modules on first successful CRM connection.
      if (!wasAuthorized) loadModules();
    } catch {
      // Non-fatal — leave existing state.
    }
  }

  function updateAuthUI() {
    const loggedIn  = !!state.authUser;
    const loginScreen = document.getElementById("login-screen");
    const tabsNav     = document.querySelector("nav.tabs");
    const mainEl      = document.querySelector("main");
    const footerEl    = document.querySelector("footer");

    if (!loggedIn) {
      if (loginScreen) loginScreen.hidden = false;
      if (tabsNav)     tabsNav.hidden     = true;
      if (mainEl)      mainEl.hidden      = true;
      if (footerEl)    footerEl.hidden    = true;
      return;
    }

    // Logged in → show main app
    if (loginScreen) loginScreen.hidden = true;
    if (tabsNav)     tabsNav.hidden     = false;
    if (mainEl)      mainEl.hidden      = false;
    if (footerEl)    footerEl.hidden    = false;

    // Brief tab: show CRM connection state
    if (els.authOrgName) els.authOrgName.textContent = state.crmOrgName || "Zoho CRM";
    if (els.authEmail)   els.authEmail.textContent   = state.crmEmail   || state.authUser || "";
    if (els.authLoginArea) els.authLoginArea.hidden = state.crmAuthorized;
    if (els.authUserInfo)  els.authUserInfo.hidden  = !state.crmAuthorized;
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
    handleOAuthCallback().then(() => checkAuthStatus());

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

    // Sign-out
    if (els.authLogoutBtn) {
      els.authLogoutBtn.addEventListener("click", () => {
        if (isLocal) { state.authUser = null; updateAuthUI(); return; }
        window.catalyst.auth.signOut("/app/index.html");
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
    renderModuleChips();
    refreshGenerateAvailability();
    if (state.brd && state.modulesSelected.length) markStepDone("input");
  }

  function refreshGenerateAvailability() {
    els.generate.disabled = !(state.brd && state.modulesSelected.length);
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
      const res = await fetch(FUNCTION_BASE + "/analyze", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ brd: state.brd, modules: list }),
      });
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

  // ---- Inspect tab: CRM functions ----

  const inspectState = { functions: [], results: {} };

  async function loadCrmFunctions() {
    if (!state.authUser) {
      setFnStatus("Sign in first.", "err");
      return;
    }
    setFnStatus("Loading functions from CRM...");
    showLoader("inspect", "Loading CRM functions...");
    els.loadFunctionsBtn.disabled = true;
    try {
      const res = await fetch(FUNCTION_BASE + "/functions/list", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({}),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to load functions");
      inspectState.functions = Array.isArray(data.functions) ? data.functions : [];
      inspectState.results = {};
      renderFunctionsList();
      els.runAllFunctionsBtn.disabled = inspectState.functions.length === 0;
      setFnStatus(
        inspectState.functions.length
          ? `${inspectState.functions.length} function(s) loaded.`
          : "No standalone functions found on this CRM org.",
        inspectState.functions.length ? "ok" : "warn"
      );
    } catch (e) {
      setFnStatus("Error: " + e.message, "err");
      showToast("Failed to load CRM functions: " + e.message, "err");
    } finally {
      hideLoader("inspect");
      els.loadFunctionsBtn.disabled = false;
    }
  }

  function setFnStatus(msg, kind) {
    if (!els.functionsStatus) return;
    els.functionsStatus.textContent = msg || "";
    els.functionsStatus.className = "status " + (kind || "");
  }

  function renderFunctionsList() {
    if (!els.functionsList) return;
    if (!inspectState.functions.length) {
      els.functionsList.innerHTML = '<div class="empty-state"><div class="empty-icon">\u2699\uFE0F</div><div class="empty-title">No CRM functions loaded</div><div class="empty-hint">Click "Load CRM Functions" to fetch the list.</div></div>';
      return;
    }
    els.functionsList.innerHTML = inspectState.functions.map((f, i) => functionRowHtml(f, i)).join("");
    els.functionsList.querySelectorAll(".fn-run").forEach((b) =>
      b.addEventListener("click", () => runOneFunction(parseInt(b.dataset.idx, 10)))
    );
  }

  function functionRowHtml(fn, idx) {
    const r = inspectState.results[fn.name];
    const executable = fn.executable !== false;     // default true if missing
    const statusPill = !r
      ? `<span class="fn-pill idle">Not run</span>`
      : r.status === "ok"
        ? `<span class="fn-pill pass">PASS ${r.status_code} · ${r.duration_ms}ms</span>`
        : `<span class="fn-pill fail">FAIL ${r.status_code} · ${r.duration_ms}ms</span>`;
    const logs = r ? renderFunctionLogs(r) : "";
    const desc = fn.description
      ? `<p class="fn-desc">${escapeHtml(fn.description)}</p>`
      : "";
    const meta = [
      fn.type ? `<span class="fn-type-tag">${escapeHtml(fn.type)}</span>` : "",
      fn.language ? `<code>${escapeHtml(fn.language)}</code>` : "",
      fn.category ? escapeHtml(fn.category) : "",
      fn.modified_time ? `modified ${escapeHtml(fn.modified_time)}` : "",
    ].filter(Boolean).join(" · ");
    const runButton = executable
      ? `<button class="primary fn-run" data-idx="${idx}">Run</button>`
      : `<button class="primary" disabled title="Only standalone (org-type) functions can be executed via API">Run</button>`;
    return `
      <div class="fn-row" data-idx="${idx}">
        <div class="fn-head">
          <div class="fn-id">
            <h3>${escapeHtml(fn.display_name || fn.name)}</h3>
            <code class="fn-name">${escapeHtml(fn.name)}</code>
          </div>
          <div class="fn-controls">
            ${statusPill}
            ${runButton}
          </div>
        </div>
        ${meta ? `<div class="fn-meta">${meta}</div>` : ""}
        ${desc}
        ${logs}
      </div>
    `;
  }

  function renderFunctionLogs(r) {
    const sections = [];
    if (r.error) {
      sections.push(`<div class="fn-log-err"><strong>Error:</strong> ${escapeHtml(r.error)}</div>`);
    }
    const responseJson = r.response
      ? JSON.stringify(r.response, null, 2)
      : (r.raw_response || "");
    if (responseJson) {
      sections.push(
        `<details class="fn-log-section" ${r.status === "error" ? "open" : ""}>` +
        `<summary>Response body</summary>` +
        `<pre>${escapeHtml(responseJson)}</pre>` +
        `</details>`
      );
    }
    if (r.output && Object.keys(r.output).length) {
      sections.push(
        `<details class="fn-log-section">` +
        `<summary>Function output</summary>` +
        `<pre>${escapeHtml(JSON.stringify(r.output, null, 2))}</pre>` +
        `</details>`
      );
    }
    return sections.length ? `<div class="fn-logs">${sections.join("")}</div>` : "";
  }

  async function runOneFunction(idx) {
    const fn = inspectState.functions[idx];
    if (!fn) return;
    // Show "running" state inline
    inspectState.results[fn.name] = { status: "running", status_code: 0, duration_ms: 0 };
    const row = els.functionsList.querySelector(`.fn-row[data-idx="${idx}"]`);
    if (row) {
      const pill = row.querySelector(".fn-pill");
      if (pill) { pill.className = "fn-pill running"; pill.textContent = "Running..."; }
    }
    try {
      const res = await fetch(FUNCTION_BASE + "/functions/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ name: fn.name, arguments: {} }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Run failed");
      inspectState.results[fn.name] = data;
    } catch (e) {
      inspectState.results[fn.name] = {
        status: "error", status_code: 0, duration_ms: 0,
        error: e.message,
      };
    }
    renderFunctionsList();
  }

  async function runAllFunctions() {
    if (!inspectState.functions.length) return;
    els.runAllFunctionsBtn.disabled = true;
    // Only execute org-type (standalone) functions; others can't be invoked via API.
    const runnable = inspectState.functions
      .map((f, i) => ({ f, i }))
      .filter(({ f }) => f.executable !== false);
    setFnStatus(`Running ${runnable.length} executable function(s) sequentially...`);
    for (const { i } of runnable) {
      await runOneFunction(i);
    }
    const passed = runnable.filter(({ f }) => (inspectState.results[f.name] || {}).status === "ok").length;
    const failed = runnable.length - passed;
    setFnStatus(`Done. ${passed} passed, ${failed} failed.`, failed ? "warn" : "ok");
    els.runAllFunctionsBtn.disabled = false;
  }

  async function loadModules() {
    if (els.modulesStatus) {
      els.modulesStatus.textContent = "Loading modules...";
      els.modulesStatus.className = "status";
    }
    if (els.loadModulesBtn) els.loadModulesBtn.disabled = true;
    try {
      const res = await fetch(FUNCTION_BASE + "/modules", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({}),
      });
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

  async function executeInCrm() {
    if (!state.cases.length) {
      setStatus("execute", "Generate cases first.", "warn");
      return;
    }
    showLoader("execute", "Executing test cases against Zoho CRM...");
    els.execute.disabled = true;
    els.toPush.disabled = true;
    els.execSummary.textContent = "";
    els.execResults.innerHTML = "";
    setStatus("execute", "");

    try {
      const res = await fetch(FUNCTION_BASE + "/execute", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ cases: state.cases }),
      });
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
      hideLoader("execute");
      els.execute.disabled = false;
    }
  }

  async function pushToProjects() {
    if (!state.executed) {
      setStatus("push", "Execute the cases first.", "warn");
      return;
    }

    showLoader("push", `Pushing ${state.cases.length} case(s) to Zoho Projects...`);
    els.push.disabled = true;
    els.pushSummary.textContent = "";
    els.pushResults.innerHTML = "";

    try {
      // The server creates (or reuses) a Projects project of this name and
      // lands the tasks inside it. Portal stays from project-defaults.properties.
      const payload = { cases: state.cases };
      const projectName = els.projectName && els.projectName.value.trim();
      if (projectName) payload.project_name = projectName;
      const res = await fetch(FUNCTION_BASE + "/push", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
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

      markStepDone("push");
      setStatus("push", "Push complete.", kind);
    } catch (e) {
      setStatus("push", "Error: " + e.message, "err");
    } finally {
      hideLoader("push");
      els.push.disabled = false;
    }
  }

  function downloadJson() {
    const blob = new Blob([JSON.stringify({ cases: state.cases, projects_payload: state.payload }, null, 2)],
                         { type: "application/json" });
    triggerDownload(blob, "uat-cases.json");
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
    els.casesMeta.textContent = "";
    els.cases.innerHTML = "";
    els.execSummary.textContent = "";
    els.execResults.innerHTML = "";
    els.pushSummary.textContent = "";
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
  if (els.downloadSampleBtn) els.downloadSampleBtn.addEventListener("click", downloadSampleTemplate);
  els.backToInput.addEventListener("click", () => showTab("input"));
  els.toExecute.addEventListener("click", () => showTab("execute"));
  els.download.addEventListener("click", downloadJson);

  els.execute.addEventListener("click", executeInCrm);
  els.backToCases.addEventListener("click", () => showTab("cases"));
  els.toPush.addEventListener("click", () => showTab("push"));

  els.push.addEventListener("click", pushToProjects);
  els.backToExecute.addEventListener("click", () => showTab("execute"));
  if (els.downloadCsv) els.downloadCsv.addEventListener("click", downloadCsv);
  els.restart.addEventListener("click", restart);

  // Inspect tab wiring
  if (els.loadFunctionsBtn) els.loadFunctionsBtn.addEventListener("click", loadCrmFunctions);
  if (els.runAllFunctionsBtn) els.runAllFunctionsBtn.addEventListener("click", runAllFunctions);
  if (els.loadModulesBtn) els.loadModulesBtn.addEventListener("click", loadModules);

  // Auth
  initAuth();

  // First-paint
  renderModuleChips();

  if (isLocal) {
    console.info("UAT Generator running in local mode. API base: " + FUNCTION_BASE);
  }
})();
