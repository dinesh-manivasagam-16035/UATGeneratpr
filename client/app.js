(function () {
  const isLocal = ["localhost", "127.0.0.1"].includes(location.hostname);
  const APPSAIL_BASE = "https://uatgenerator-50042610479.development.catalystappsail.in";
  const FUNCTION_BASE = isLocal
    ? "http://localhost:9000/uat_generator"
    : APPSAIL_BASE + "/uat_generator";

  const TABS = ["input", "cases", "execute", "push"];

  const els = {
    tabs: document.querySelectorAll(".tab"),
    panels: document.querySelectorAll(".tab-panel"),

    module:    document.getElementById("module"),
    brdFile:   document.getElementById("brd-file"),
    fileDrop:  document.getElementById("file-drop"),
    fileMeta:  document.getElementById("file-meta"),
    generate:  document.getElementById("generate-btn"),
    statusInput: document.getElementById("status-input"),

    casesMeta: document.getElementById("cases-meta"),
    cases:     document.getElementById("cases"),
    backToInput: document.getElementById("back-to-input"),
    toExecute: document.getElementById("to-execute-btn"),
    download:  document.getElementById("download-btn"),
    statusCases: document.getElementById("status-cases"),

    crmOrg:    document.getElementById("crm_org_id"),
    crmApi:    document.getElementById("crm_api_base"),
    crmClientId: document.getElementById("crm_client_id"),
    crmClientSecret: document.getElementById("crm_client_secret"),
    crmRefresh: document.getElementById("crm_refresh_token"),
    execSummary: document.getElementById("exec-summary"),
    execResults: document.getElementById("exec-results"),
    execute:   document.getElementById("execute-btn"),
    backToCases: document.getElementById("back-to-cases"),
    toPush:    document.getElementById("to-push-btn"),
    statusExecute: document.getElementById("status-execute"),
    execLoaderText: document.getElementById("execute-loader-text"),

    portal:    document.getElementById("portal_id"),
    project:   document.getElementById("project_id"),
    pushSummary: document.getElementById("push-summary"),
    pushResults: document.getElementById("push-results"),
    backToExecute: document.getElementById("back-to-execute"),
    push:      document.getElementById("push-btn"),
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
    executed: false,
    pushed: false,
    stepDone: { input: false, cases: false, execute: false, push: false },
  };

  if (window.pdfjsLib) {
    window.pdfjsLib.GlobalWorkerOptions.workerSrc =
      "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
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
    els.tabs.forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    els.panels.forEach((p) => {
      const active = p.dataset.panel === name;
      p.classList.toggle("active", active);
      p.hidden = !active;
    });
  }
  function markStepDone(name) {
    state.stepDone[name] = true;
    setTabState(name, { done: true, running: false });
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
      els.generate.disabled = false;
      markStepDone("input");
      setStatus("input", "BRD parsed. Click Generate UAT cases.", "ok");
    } catch (err) {
      state.brd = "";
      state.fileName = "";
      els.fileMeta.textContent = "";
      els.generate.disabled = true;
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
      container.innerHTML = '<p class="muted">No cases yet.</p>';
      return;
    }
    container.innerHTML = list.map((tc, i) => caseCardHtml(tc, i, opts)).join("");
  }

  // ---- Actions ----

  async function generate() {
    if (!state.brd) { setStatus("input", "Attach a BRD file first.", "warn"); return; }
    const module = els.module.value;

    showTab("cases");
    setStatus("cases", "");
    showLoader("cases", "Generating UAT cases via LLM... (10–30s)");
    els.toExecute.disabled = true;
    els.download.disabled = true;
    els.casesMeta.textContent = "";
    els.cases.innerHTML = "";

    try {
      const res = await fetch(FUNCTION_BASE + "/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ brd: state.brd, module, project_key: "" }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Generation failed");

      state.cases = Array.isArray(data.cases) ? data.cases : [];
      state.payload = data.projects_payload || null;
      state.executed = false;
      state.pushed = false;

      els.casesMeta.textContent = `${state.cases.length} case(s) generated via ${data.provider}.`;
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

  function readCrmCreds() {
    return {
      org_id:        els.crmOrg.value.trim(),
      api_base:      els.crmApi.value.trim(),
      client_id:     els.crmClientId.value.trim(),
      client_secret: els.crmClientSecret.value.trim(),
      refresh_token: els.crmRefresh.value.trim(),
    };
  }

  async function executeInCrm() {
    if (!state.cases.length) {
      setStatus("execute", "Generate cases first.", "warn");
      return;
    }
    const crm = readCrmCreds();
    const live = crm.client_id && crm.client_secret && crm.refresh_token;

    showLoader("execute", live
      ? "Executing test cases against Zoho CRM..."
      : "Simulating execution (no CRM credentials provided)...");
    els.execute.disabled = true;
    els.toPush.disabled = true;
    els.execSummary.textContent = "";
    els.execResults.innerHTML = "";
    setStatus("execute", "");

    try {
      const res = await fetch(FUNCTION_BASE + "/execute", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cases: state.cases, crm }),
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
        data.failed ? "Some failures — review the trace, then move to Push."
                    : "All cases passed. Move to Push.", kind);
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
      // Portal and project come from project-defaults.properties on the server.
      // We can optionally override them from the UI fields (hidden by default).
      const portal_id = els.portal && els.portal.value.trim();
      const project_id = els.project && els.project.value.trim();
      const payload = { cases: state.cases };
      if (portal_id) payload.portal_id = portal_id;
      if (project_id) payload.project_id = project_id;
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
      els.pushSummary.textContent =
        `Push complete: ${data.tasks_created} task(s), ${data.bugs_created} bug(s) created${mock}.`;
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
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "uat-cases.json";
    a.click();
    URL.revokeObjectURL(url);
  }

  function restart() {
    state.brd = "";
    state.fileName = "";
    state.cases = [];
    state.payload = null;
    state.executed = false;
    state.pushed = false;
    state.stepDone = { input: false, cases: false, execute: false, push: false };

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
  els.backToInput.addEventListener("click", () => showTab("input"));
  els.toExecute.addEventListener("click", () => showTab("execute"));
  els.download.addEventListener("click", downloadJson);

  els.execute.addEventListener("click", executeInCrm);
  els.backToCases.addEventListener("click", () => showTab("cases"));
  els.toPush.addEventListener("click", () => showTab("push"));

  els.push.addEventListener("click", pushToProjects);
  els.backToExecute.addEventListener("click", () => showTab("execute"));
  els.restart.addEventListener("click", restart);

  if (isLocal) {
    console.info("UAT Generator running in local mode. API base: " + FUNCTION_BASE);
  }
})();
