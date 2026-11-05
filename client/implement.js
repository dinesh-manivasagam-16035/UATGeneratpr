// Implementation generator — the Build tab.
//
// Pipeline (matches the server):
//   1. Extract  POST /implement          BRD → implementation spec
//   2. Review   in this tab              untick, edit, answer questions, approve
//   3. Apply    POST /implement/apply    dry run first, then a real run (approved spec,
//                                         org id confirmed, sandbox unless confirmed)
//   4. Verify   POST /implement/verify   read the org back, diff against the spec
//   5. UAT      app.js generate()         UAT cases from a BRD rendered from the spec
// The plan section can also be pushed to Zoho Projects through the existing /push.
(function () {
  const L = window.LiftOff;
  if (!L) return;
  const esc = L.escapeHtml;
  const STORAGE_KEY = "liftoff_impl_v1";

  const SECTIONS = [
    ["open_questions",   "Open questions"],
    ["modules",          "Modules"],
    ["fields",           "Fields"],
    ["layouts",          "Layouts"],
    ["validation_rules", "Validation rules"],
    ["roles",            "Roles"],
    ["workflows",        "Automation"],
    ["functions",        "Deluge functions"],
    ["plan",             "Delivery plan"],
  ];
  const EXECUTOR_LABEL = { api: "API", partial: "Partly manual", rpa: "Manual", review: "Review", projects: "Projects", none: "—" };
  const FILTERS = [["all", "All items"], ["attention", "Needs attention"], ["new", "New"], ["exists", "Already in org"]];
  const STATUS_LABEL = { new: "New", exists: "Already in org", needs_update: "Needs update", conflict: "Conflict",
    not_compared: "Not compared", created: "Created", open: "Open" };
  const API_FIELD_TYPES = ["text", "textarea", "email", "phone", "website", "picklist", "multiselectpicklist",
    "date", "datetime", "integer", "bigint", "decimal", "currency", "percent", "boolean", "lookup"];

  const impl = {
    meta: null,      // full /implement response minus spec
    spec: null,
    dryRun: null,    // last dry-run response
    applied: null,   // last real apply response
    verify: null,    // last verify response
    build: null,     // the step-by-step build run (see renderBuild)
    filter: "all",   // blueprint filter (FILTERS)
  };
  let running = false;   // a build run is in progress

  const $ = (id) => document.getElementById(id);
  const els = {
    meta: $("impl-meta"), org: $("impl-org"), warnings: $("impl-warnings"), spec: $("impl-spec"), toolbar: $("impl-toolbar"),
    reviewBar: $("impl-review-bar"), approve: $("impl-approve"), approveNote: $("impl-approve-note"),
    back: $("impl-back"), dry: $("impl-dry-run"), apply: $("impl-apply"), verify: $("impl-verify"),
    pushPlan: $("impl-push-plan"), toUat: $("impl-to-uat"), download: $("impl-download"),
    downloadRpa: $("impl-download-rpa"), run: $("impl-run"),
  };

  // ---- persistence (per-browser convenience only) ----
  function save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ meta: impl.meta, spec: impl.spec, dryRun: impl.dryRun, build: impl.build }));
    } catch (e) {}
  }
  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (saved && saved.spec) {
        impl.spec = saved.spec; impl.meta = saved.meta || {};
        impl.dryRun = saved.dryRun || null; impl.build = saved.build || null;
        if (impl.build) impl.build.steps.forEach((st) => { if (st.status === "running") st.status = "waiting"; });
      }
    } catch (e) {}
  }

  // ---- helpers ----
  function describe(section, o) {
    switch (section) {
      case "modules":          return `${o.plural_label} (${o.kind})`;
      case "fields":           return `${o.module} · ${o.label}`;
      case "layouts":          return `${o.module} · ${o.name}`;
      case "validation_rules": return `${o.module} · ${o.name}`;
      case "workflows":        return `${o.type}: ${o.module ? o.module + " · " : ""}${o.name}`;
      case "roles":            return o.name;
      case "functions":        return o.name;
      case "open_questions":   return o.question;
      case "plan":             return o.title;
      default:                 return o.id;
    }
  }

  function details(section, o) {
    const parts = [];
    switch (section) {
      case "modules": if (o.purpose) parts.push(o.purpose); break;
      case "fields":
        parts.push(o.data_type + (o.length ? `(${o.length})` : ""));
        if (o.picklist_values && o.picklist_values.length) parts.push("values: " + o.picklist_values.join(" | "));
        if (o.lookup_module) parts.push("→ " + o.lookup_module);
        if (o.required) parts.push("required");
        if (o.unique) parts.push("unique");
        if (o.note) parts.push("⚠ " + o.note);
        break;
      case "layouts":
        (o.sections || []).forEach((s) => parts.push(`${s.name}: ${(s.fields || []).join(", ")}`));
        if (o.profiles && o.profiles.length) parts.push("profiles: " + o.profiles.join(", "));
        break;
      case "validation_rules":
        parts.push("when " + o.condition);
        if (o.error_message) parts.push(`“${o.error_message}”`);
        break;
      case "workflows":
        if (o.trigger) parts.push(o.trigger);
        if (o.criteria) parts.push("if " + o.criteria);
        (o.actions || []).forEach((a) => parts.push("→ " + a));
        if (o.states && o.states.length) parts.push("states: " + o.states.join(" → "));
        if (o.details) parts.push(o.details);
        break;
      case "roles":
        if (o.reports_to) parts.push("reports to " + o.reports_to);
        if (o.profile) parts.push("profile " + o.profile);
        if (o.permissions && o.permissions.length) parts.push(o.permissions.join(", "));
        break;
      case "functions":
        if (o.trigger) parts.push(o.trigger);
        if (o.purpose) parts.push(o.purpose);
        break;
      case "open_questions":
        if (o.context) parts.push(o.context);
        break;
      case "plan":
        parts.push(`${o.phase} · ${o.owner_role || "—"} · ${o.effort_hours || 0}h`);
        if (o.description) parts.push(o.description);
        if (o.spec_refs && o.spec_refs.length) parts.push("builds " + o.spec_refs.join(", "));
        break;
    }
    return parts;
  }

  function findItem(id) {
    for (const [s] of SECTIONS) {
      const arr = impl.spec[s] || [];
      const i = arr.findIndex((o) => o.id === id);
      if (i >= 0) return { section: s, index: i, item: arr[i] };
    }
    return null;
  }

  function executorFor(section, o) {
    if (section === "modules") return o.kind === "standard" || o.status === "exists" ? "none" : "api";
    if (o.status === "exists") return "none";
    if (section === "fields") return !API_FIELD_TYPES.includes(o.data_type) ? "rpa" : o.required ? "partial" : "api";
    if (section === "roles") return (o.permissions || []).length ? "partial" : "api";
    if (section === "open_questions") return "review";
    if (section === "plan") return "projects";
    return "rpa";
  }

  function blockingOpen() {
    return (impl.spec.open_questions || []).filter((q) => q.include !== false && q.blocking && !(q.answer || "").trim());
  }

  function setApproved(v) {
    impl.spec.approved = !!v;
    els.approve.checked = !!v;
    refreshButtons();
    save();
  }

  function download(name, data) {
    const text = typeof data === "string" ? data : JSON.stringify(data, null, 2);
    const blob = new Blob([text], { type: typeof data === "string" ? "text/plain" : "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  async function post(path, body, ms, label) {
    const res = await L.fetchWithTimeout(L.FUNCTION_BASE + path, {
      method: "POST", credentials: "include",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    }, ms, label);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `${label} failed (HTTP ${res.status})`);
    return data;
  }

  // ---- rendering ----
  function render() {
    if (!impl.spec) {
      els.spec.innerHTML = "";
      els.toolbar.hidden = true;
      els.toolbar.innerHTML = "";
      els.reviewBar.hidden = true;
      els.org.hidden = true;
      els.warnings.innerHTML = "";
      els.meta.textContent = "";
      refreshButtons();
      return;
    }
    const m = impl.meta || {};
    const counts = SECTIONS.map(([s, label]) => [label, (impl.spec[s] || []).length]).filter(([, n]) => n);
    els.meta.textContent = counts.map(([l, n]) => `${n} ${l.toLowerCase()}`).join(" · ")
      + (m.provider ? ` — via ${m.provider}` : "");

    const org = m.org || {};
    els.org.hidden = false;
    els.org.innerHTML = org.connected
      ? `<strong>${esc(org.name || "Connected org")}</strong> <span class="muted">${esc(org.id || "")}</span>
         <span class="impl-badge ${org.sandbox ? "ok" : "warn"}">${org.sandbox ? "sandbox" : esc(org.type || "production?")}</span>
         <span class="muted">Items are checked against this org.</span>`
      : `<span class="impl-badge warn">not connected</span>
         <span class="muted">Connect CRM on the Brief tab to check items against your org and to apply.</span>`;

    const warnings = m.warnings || [];
    const cov = m.coverage;
    let wh = "";
    if (cov && cov.requirements) {
      wh += `<p class="muted">Requirements covered: <strong>${cov.covered}/${cov.requirements}</strong>`
        + (cov.uncovered && cov.uncovered.length ? ` — not covered: ${esc(cov.uncovered.join(", "))}` : "") + "</p>";
    }
    if (warnings.length) {
      wh += `<details class="impl-warn"><summary>${warnings.length} note(s) from generation</summary><ul>`
        + warnings.map((w) => `<li>${esc(w)}</li>`).join("") + "</ul></details>";
    }
    if (impl.spec.summary) wh = `<p class="impl-summary">${esc(impl.spec.summary)}</p>` + wh;
    els.warnings.innerHTML = wh;

    els.toolbar.hidden = false;
    els.toolbar.innerHTML = toolbarHtml();
    const html = SECTIONS.map(([s, label]) => sectionHtml(s, label)).join("");
    els.spec.innerHTML = html || `<p class="muted impl-empty">Nothing matches this filter.</p>`;
    els.reviewBar.hidden = false;
    els.approve.checked = !!impl.spec.approved;
    refreshButtons();
  }

  function needsAttention(section, o) {
    if (["conflict", "needs_update", "not_compared"].includes(o.status)) return true;
    if (o.note) return true;
    if (section === "open_questions") return o.include !== false && !(o.answer || "").trim();
    const run = impl.dryRun && (impl.dryRun.results || []).find((r) => r.id === o.id);
    return !!(run && (run.result === "failed" || run.result === "conflict"));
  }

  function matchesFilter(section, o) {
    switch (impl.filter) {
      case "attention": return needsAttention(section, o);
      case "new":       return o.status === "new";
      case "exists":    return o.status === "exists";
      default:          return true;
    }
  }

  function sectionHtml(section, label) {
    const items = impl.spec[section] || [];
    if (!items.length) return "";
    const shown = items.filter((o) => matchesFilter(section, o));
    if (!shown.length) return "";
    const included = items.filter((o) => o.include !== false).length;
    let body;
    if (section === "fields") {
      // Grouped by module, as consultants read a field list.
      const byModule = {};
      shown.forEach((o) => { (byModule[o.module] = byModule[o.module] || []).push(o); });
      body = Object.entries(byModule).map(([m, list]) =>
        `<div class="impl-group"><h4>${esc(m)}</h4>${list.map((o) => itemHtml(section, o)).join("")}</div>`).join("");
    } else {
      body = shown.map((o) => itemHtml(section, o)).join("");
    }
    return `<details class="impl-section" open data-section="${section}">
      <summary><span>${esc(label)}</span><span class="muted">${included}/${items.length} included</span></summary>
      ${body}
    </details>`;
  }

  // Summary line, filters and the review actions above the blueprint.
  function toolbarHtml() {
    const all = [];
    for (const [s] of SECTIONS) if (s !== "plan") (impl.spec[s] || []).forEach((o) => all.push([s, o]));
    const build = all.filter(([s]) => s !== "open_questions");
    const count = (f) => build.filter(([, o]) => f(o)).length;
    const included = build.filter(([, o]) => o.include !== false);
    const byExec = (e) => included.filter(([, o]) => o.executor === e).length;
    const doable = byExec("api") + byExec("partial") + byExec("rpa");
    const pct = doable ? Math.round(100 * byExec("api") / doable) : 0;
    const openQs = (impl.spec.open_questions || []).filter((q) => q.include !== false);
    const answered = openQs.filter((q) => (q.answer || "").trim()).length;
    const stat = (n, label, cls) => n ? `<span class="impl-stat ${cls || ""}">${n} ${label}</span>` : "";
    return `<div class="impl-stats">
        <span class="impl-stat strong">${included.length} of ${build.length} items included</span>
        ${stat(count((o) => o.status === "conflict"), "conflicts", "err")}
        ${stat(count((o) => o.status === "needs_update"), "need update", "warn")}
        ${stat(count((o) => o.status === "new"), "new", "new")}
        ${stat(count((o) => o.status === "exists"), "already in org")}
        ${stat(count((o) => o.status === "not_compared"), "not compared")}
      </div>
      <div class="impl-stats">
        <span class="impl-stat strong">${pct}% by API</span>
        ${stat(byExec("partial"), "partly manual", "warn")}
        ${stat(byExec("rpa"), "manual")}
        ${openQs.length ? `<span class="impl-stat">${openQs.length} open question(s) · ${answered} answered</span>` : ""}
      </div>
      <div class="impl-toolbar-row">
        <div class="impl-filters" role="group" aria-label="Filter the blueprint">
          ${FILTERS.map(([k, l]) => `<button type="button" class="mode-btn${impl.filter === k ? " active" : ""}" data-filter="${k}" aria-pressed="${impl.filter === k}">${l}</button>`).join("")}
        </div>
        <div class="impl-toolbar-actions">
          ${openQs.length ? `<button type="button" class="ghost small" data-tool="copy-questions">Copy questions for email</button>` : ""}
          ${answered ? `<button type="button" class="ghost small" data-tool="regen-answers">Apply answers and regenerate</button>` : ""}
          <button type="button" class="ghost small" data-tool="regen">Regenerate</button>
        </div>
      </div>`;
  }

  function itemHtml(section, o) {
    const off = o.include === false;
    const ver = impl.verify && (impl.verify.results || []).find((r) => r.id === o.id);
    const run = (impl.applied || impl.dryRun) && ((impl.applied || impl.dryRun).results || []).find((r) => r.id === o.id);
    const reqs = (o.req_ids || []).length ? `<span class="impl-req">${esc(o.req_ids.join(", "))}</span>` : "";
    const badges = [
      `<span class="impl-badge exec-${esc(o.executor)}">${esc(EXECUTOR_LABEL[o.executor] || o.executor)}</span>`,
      o.status && !["todo"].includes(o.status) ? `<span class="impl-badge st-${esc(o.status)}">${esc(STATUS_LABEL[o.status] || o.status)}</span>` : "",
      run ? `<span class="impl-badge run-${esc(run.result)}" title="${esc(run.detail || "")}">${esc(run.result.replace(/_/g, " "))}</span>` : "",
      ver ? `<span class="impl-badge ver-${esc(ver.verdict)}" title="${esc(ver.detail || "")}">${esc(ver.verdict)}</span>` : "",
      section === "open_questions" && o.blocking ? `<span class="impl-badge warn">blocking</span>` : "",
      section === "plan" ? `<span class="badge ${esc((o.priority || "P1").toLowerCase())}">${esc(o.priority || "P1")}</span>` : "",
    ].join("");
    const det = details(section, o).map((d) => `<li>${esc(d)}</li>`).join("");
    const extra = [];
    if (section === "functions" && o.code) extra.push(`<pre class="impl-code">${esc(o.code)}</pre>`);
    if (section === "open_questions") {
      extra.push(`<textarea class="impl-answer" data-id="${esc(o.id)}" rows="2" placeholder="Answer (from the client)">${esc(o.answer || "")}</textarea>`);
    }
    if (run && run.detail) extra.push(`<p class="muted impl-run-detail">${esc(run.detail)}${run.follow_up ? " — " + esc(run.follow_up) : ""}</p>`);
    if (ver && ver.detail) extra.push(`<p class="muted impl-run-detail">Verify: ${esc(ver.detail)}</p>`);
    return `<div class="impl-item${off ? " off" : ""}" data-id="${esc(o.id)}">
      <div class="impl-item-head">
        <label class="impl-include"><input type="checkbox" data-act="include" data-id="${esc(o.id)}" ${off ? "" : "checked"} />
          <span class="impl-id">${esc(o.id)}</span></label>
        <span class="impl-title">${esc(describe(section, o))}</span>
        ${reqs}
        <span class="impl-badges">${badges}</span>
        <button class="ghost small" data-act="edit" data-id="${esc(o.id)}" type="button">Edit</button>
      </div>
      ${det ? `<ul class="impl-details">${det}</ul>` : ""}
      ${extra.join("")}
      <div class="impl-editor" data-editor="${esc(o.id)}" hidden>
        <textarea rows="10" spellcheck="false">${esc(JSON.stringify(o, null, 2))}</textarea>
        <div class="actions inline tight">
          <button class="primary small" data-act="save" data-id="${esc(o.id)}" type="button">Save</button>
          <button class="ghost small" data-act="cancel" data-id="${esc(o.id)}" type="button">Cancel</button>
        </div>
      </div>
    </div>`;
  }

  function renderRun(title, data, kind) {
    const counts = Object.entries(data.counts || {}).map(([k, n]) => `${n} ${k.replace(/_/g, " ")}`).join(" · ");
    const rows = (data.results || []).map((r) => `<tr>
        <td>${esc(r.id)}</td><td>${esc(r.label)}</td>
        <td><span class="impl-badge ${kind === "verify" ? "ver-" + esc(r.verdict) : "run-" + esc(r.result)}">${esc((kind === "verify" ? r.verdict : r.result || "").replace(/_/g, " "))}</span></td>
        <td class="muted">${esc([r.detail, r.follow_up].filter(Boolean).join(" — "))}</td></tr>`).join("");
    const rpa = (data.rpa_queue || []).length
      ? `<p class="muted">${data.rpa_queue.length} item(s) need UI work (RPA bot or consultant) — use “Download RPA queue”.</p>` : "";
    const warn = (data.warnings || []).length ? `<p class="status warn">${esc(data.warnings.join(" • "))}</p>` : "";
    els.run.innerHTML = `<div class="impl-run">
      <h3>${esc(title)}</h3><p class="muted">${esc(counts)}</p>${warn}${rpa}
      <div class="impl-table-wrap"><table class="impl-table"><thead><tr><th>Item</th><th>What</th><th>Result</th><th>Detail</th></tr></thead>
      <tbody>${rows}</tbody></table></div></div>`;
  }

  function refreshButtons() {
    const have = !!impl.spec;
    const connected = !!(impl.meta && impl.meta.org && impl.meta.org.connected) || !!L.state.crmAuthorized;
    const blocking = have ? blockingOpen() : [];
    els.approve.disabled = !have || blocking.length > 0;
    els.approveNote.textContent = !have ? ""
      : blocking.length ? `Answer ${blocking.length} blocking question(s) (${blocking.map((q) => q.id).join(", ")}) or untick them to approve.`
      : impl.spec.approved ? "Approved." : "Untick anything you don't want built, edit what's wrong, then approve.";
    els.dry.disabled = !have;
    const sandbox = !!(impl.dryRun && impl.dryRun.org && impl.dryRun.org.sandbox);
    els.apply.disabled = running || !(have && impl.spec.approved && impl.dryRun && impl.dryRun.org && connected && sandbox);
    els.apply.title = !have ? "" : !impl.spec.approved ? "Approve the spec first"
      : !impl.dryRun ? "Run a dry run first" : !connected ? "Connect CRM first"
      : !sandbox ? "Apply only writes to sandbox orgs" : "";
    els.verify.disabled = !(have && connected);
    els.pushPlan.disabled = !(have && (impl.spec.plan || []).some((t) => t.include !== false));
    els.toUat.disabled = !(have && impl.spec.approved);
    els.download.disabled = !have;
    els.downloadRpa.disabled = !(impl.applied || impl.dryRun);
    document.querySelectorAll(".impl-steps li").forEach((li) => {
      const s = li.dataset.step;
      const done = { extract: have, review: have && impl.spec.approved, apply: !!(impl.build && impl.build.finishedAt),
                     verify: !!impl.verify, uat: false }[s];
      li.classList.toggle("done", !!done);
    });
  }

  // Any edit after approval needs a fresh approval and a fresh dry run.
  function changed() {
    if (impl.spec.approved) {
      setApproved(false);
      L.setStatus("build", "Spec changed — review and approve again.", "warn");
    }
    if (!running) { impl.dryRun = null; impl.build = null; }
    save();
  }

  // ---- actions ----
  async function generate(opts) {
    const answers = (opts && opts.answers) || [];
    L.setTabState("build", { locked: false });
    L.showTab("build");
    L.setStatus("build", "");
    L.showLoader("build", "Extracting the implementation spec… this can take a couple of minutes.");
    impl.spec = null; impl.meta = null; impl.dryRun = null; impl.applied = null; impl.verify = null; impl.build = null;
    els.run.innerHTML = "";
    render();
    try {
      const data = await post("/implement", { brd: L.state.brd, modules: L.state.modulesSelected || [], answers },
        190_000, "Implementation spec");
      impl.spec = data.spec;
      // Keep the client's answers: on questions asked again, and as a record on the blueprint.
      if (answers.length) {
        const byText = {};
        answers.forEach((a) => { byText[a.question.toLowerCase().trim()] = a.answer; });
        (impl.spec.open_questions || []).forEach((q) => {
          const a = byText[(q.question || "").toLowerCase().trim()];
          if (a) q.answer = a;
        });
        impl.spec.decisions = answers;
      }
      const meta = Object.assign({}, data);
      delete meta.spec;
      impl.meta = meta;
      save();
      render();
      L.setTabState("build", { done: true });
      const w = (data.warnings || []).length;
      L.setStatus("build", w ? `Spec ready with ${w} note(s). Review it, then approve.` : "Spec ready. Review it, then approve.", w ? "warn" : "ok");
    } catch (e) {
      L.setStatus("build", "Spec extraction failed: " + e.message, "err");
      L.showToast("Implementation spec failed: " + String(e.message).slice(0, 80), "err", 6000);
    } finally {
      L.hideLoader("build");
    }
  }

  async function dryRun() {
    L.setStatus("build", "Dry run…");
    try {
      impl.dryRun = await post("/implement/apply", { spec: impl.spec, dry_run: true }, 90_000, "Dry run");
      impl.applied = null;
      impl.build = null;
      save();
      renderRun("Dry run — nothing was changed", impl.dryRun, "apply");
      render();
      const o = impl.dryRun.org;
      L.setStatus("build", o && !o.sandbox
        ? `Dry run against ${o.name || o.id}. It isn't a sandbox, so Apply stays off — connect a sandbox org to build.`
        : o
        ? `Dry run against ${o.name || o.id}. Check the requests, then Apply.`
        : "Dry run done (no CRM connected — connect CRM to apply).", "ok");
    } catch (e) {
      L.setStatus("build", "Dry run failed: " + e.message, "err");
    }
  }

  // ---- Build: ordered steps, one API call each, stop on the first problem ----
  // impl.build = { org, steps: [{id, label, method, path, status, detail, at}], log: [] }
  // status: waiting | running | done | failed | conflict | skipped
  function buildSteps() {
    return (impl.dryRun.results || [])
      .filter((r) => (r.executor === "api" || r.executor === "partial")
        && ["would_create", "would_update", "conflict", "failed"].includes(r.result))
      .map((r) => ({
        id: r.id, label: r.label,
        method: r.request ? r.request.method : "", path: r.request ? r.request.path.replace(/\?.*$/, "") : "",
        status: "waiting", detail: r.result === "failed" || r.result === "conflict" ? r.detail : "",
      }));
  }

  async function apply() {
    const org = impl.dryRun && impl.dryRun.org;
    if (!org) return;
    if (!org.sandbox) {
      L.setStatus("build", `"${org.name}" isn't a sandbox. LiftOff only builds in sandbox orgs.`, "warn");
      return;
    }
    const steps = buildSteps();
    if (!steps.length) { L.setStatus("build", "Nothing for the API to build — see Configure by hand.", "ok"); renderBuild(); return; }
    if (!window.confirm(`Run ${steps.length} build step(s) in sandbox "${org.name}" (${org.id})?\n\n`
        + "Steps run one at a time and stop on the first problem.")) return;
    impl.build = { org, steps, log: [], startedAt: new Date().toISOString() };
    save();
    await runFrom(0);
  }

  async function runFrom(start) {
    if (running || !impl.build) return;
    running = true;
    const b = impl.build;
    try {
      for (let i = start; i < b.steps.length; i++) {
        const st = b.steps[i];
        if (st.status === "done" || st.status === "skipped") continue;
        st.status = "running"; st.detail = "";
        renderBuild();
        L.setStatus("build", `Step ${i + 1} of ${b.steps.length}: ${st.label}`);
        let r;
        try {
          const data = await post("/implement/apply", {
            spec: impl.spec, dry_run: false, confirm_org_id: b.org.id, item_ids: [st.id],
          }, 90_000, "Build step");
          r = (data.results || [])[0] || { result: "failed", detail: "No result returned." };
        } catch (e) {
          r = { result: "failed", detail: e.message };
        }
        st.at = new Date().toISOString();
        st.detail = [r.detail, r.follow_up].filter(Boolean).join(" — ");
        st.status = ["created", "updated", "skipped"].includes(r.result) ? "done" : r.result === "conflict" ? "conflict" : "failed";
        b.log.push({ at: st.at, id: st.id, label: st.label, result: r.result, detail: st.detail, request: r.request || null });
        if (st.status === "done") {
          const f = findItem(st.id);
          if (f && r.result !== "skipped") f.item.status = "created";
        }
        save();
        renderBuild();
        if (st.status !== "done") {
          L.setStatus("build", `Stopped at step ${i + 1}: ${st.label}. Retry, skip, or fix it.`, "warn");
          return;
        }
      }
      b.finishedAt = new Date().toISOString();
      save();
      renderBuild();
      render();
      L.setStatus("build", "Build steps finished. Tick off the hand-configured items, then Verify.", "ok");
    } finally {
      running = false;
      refreshButtons();
    }
  }

  function blockedBy(item) {
    const ids = new Set(item.req_ids || []);
    return blockingOpen().filter((q) => (q.req_ids || []).some((r) => ids.has(r))).map((q) => q.id);
  }

  function renderBuild() {
    const b = impl.build;
    const queue = (impl.dryRun && impl.dryRun.rpa_queue) || [];
    let html = "";
    if (b) {
      const n = (s) => b.steps.filter((x) => x.status === s).length;
      const tiles = [["Done", n("done") + n("skipped"), "ok"], ["Running", n("running"), "run"],
                     ["Failed", n("failed") + n("conflict"), "err"], ["Waiting", n("waiting"), "wait"]];
      html += `<div class="impl-run"><h3>Build steps, in order</h3>
        <p class="muted">Sandbox "${esc(b.org.name)}" · modules first, then fields and roles · each step stops on error.</p>
        <div class="impl-tiles">${tiles.map(([l, v, k]) => `<div class="impl-tile ${k}"><span>${l}</span><strong>${v}</strong></div>`).join("")}</div>
        <div class="impl-table-wrap"><table class="impl-table"><thead><tr><th>Step</th><th>What</th><th>API call</th><th>Status</th></tr></thead><tbody>`
        + b.steps.map((st, i) => {
          const stuck = st.status === "failed" || st.status === "conflict";
          const f = findItem(st.id);
          const actions = stuck ? `<div class="impl-step-actions">
              <button class="ghost small" type="button" data-step-act="retry" data-i="${i}">Retry</button>
              ${st.status === "conflict" && f && f.section === "fields" ? `<button class="ghost small" type="button" data-step-act="newfield" data-i="${i}">Create new field instead</button>` : ""}
              <button class="ghost small" type="button" data-step-act="skip" data-i="${i}">Skip</button></div>` : "";
          return `<tr class="${stuck ? "impl-row-err" : ""}"><td class="muted">${i + 1}</td>
            <td><strong>${esc(st.label)}</strong><div class="muted">${esc(st.id)}${f && (f.item.req_ids || []).length ? " · " + esc(f.item.req_ids.join(", ")) : ""}</div>
              ${st.detail ? `<div class="${stuck ? "impl-err-text" : "muted"}">${esc(st.detail)}</div>` : ""}</td>
            <td class="impl-mono">${esc(st.method + " " + st.path)}</td>
            <td><span class="impl-badge step-${esc(st.status)}">${esc(st.status)}</span>${actions}</td></tr>`;
        }).join("")
        + `</tbody></table></div>
        <p><button class="ghost small" type="button" data-step-act="log">Download run log</button></p></div>`;
    }
    if (queue.length) {
      const doneCount = queue.filter((q) => { const f = findItem(q.id); return f && f.item.manual_done; }).length;
      html += `<div class="impl-run"><h3>Configure by hand</h3>
        <p class="muted">${queue.length} item(s) the APIs can't fully do · ${doneCount} done. Tick each one when it's set up.</p>
        <ul class="impl-manual">` + queue.map((q) => {
          const f = findItem(q.id);
          const item = f ? f.item : {};
          const blocked = blockedBy(item);
          const done = item.manual_done;
          return `<li>
            <input type="checkbox" data-manual="${esc(q.id)}" ${done ? "checked" : ""} ${blocked.length && !done ? "disabled" : ""} aria-label="${esc(q.label)} done" />
            <div class="impl-manual-body"><strong>${esc(q.label)}</strong>
              <details><summary class="muted">${esc((q.steps || [])[0] || "Steps")}</summary><ol>${(q.steps || []).map((x) => `<li>${esc(x)}</li>`).join("")}</ol></details></div>
            ${done ? `<span class="impl-done-by">Done by ${esc(done.by)}</span>`
              : blocked.length ? `<span class="impl-blocked">Blocked by ${esc(blocked.join(", "))}</span>`
              : `<button class="ghost small" type="button" data-copy="${esc(q.id)}">Copy steps</button>`}
          </li>`;
        }).join("") + "</ul></div>";
    }
    els.run.innerHTML = html;
    // Keep item badges in step with the run.
    impl.applied = b ? { org: b.org, rpa_queue: queue,
      results: b.steps.map((st) => ({ id: st.id, result: { done: "created", skipped: "skipped", running: "running",
        waiting: "waiting", failed: "failed", conflict: "conflict" }[st.status], detail: st.detail })) } : null;
  }

  els.run.addEventListener("click", async (e) => {
    const copy = e.target.closest("button[data-copy]");
    if (copy) {
      const q = ((impl.dryRun && impl.dryRun.rpa_queue) || []).find((x) => x.id === copy.dataset.copy);
      if (q) {
        try { await navigator.clipboard.writeText(q.label + "\n" + (q.steps || []).map((x, i) => `${i + 1}. ${x}`).join("\n")); L.showToast("Steps copied", "ok", 2000); }
        catch (err) { L.showToast("Couldn't copy — open the steps and copy by hand", "warn", 4000); }
      }
      return;
    }
    const t = e.target.closest("button[data-step-act]");
    if (!t || !impl.build) return;
    const act = t.dataset.stepAct;
    if (act === "log") {
      download("build-run-log.json", { org: impl.build.org, started_at: impl.build.startedAt,
        finished_at: impl.build.finishedAt || null, steps: impl.build.steps, log: impl.build.log });
      return;
    }
    const i = Number(t.dataset.i);
    const st = impl.build.steps[i];
    if (!st) return;
    if (act === "retry") return runFrom(i);
    if (act === "skip") {
      st.status = "skipped";
      impl.build.log.push({ at: new Date().toISOString(), id: st.id, label: st.label, result: "skipped_by_user" });
      save();
      return runFrom(i + 1);
    }
    if (act === "newfield") {
      const f = findItem(st.id);
      if (!f) return;
      const label = window.prompt("Label for the new field (the existing one stays as it is):",
        `${f.item.label} (${f.item.data_type})`);
      if (!label || !label.trim()) return;
      f.item.label = label.trim();
      f.item.api_name = label.trim().replace(/[^A-Za-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 50);
      ["live_id", "live_api_name", "live_type", "missing_values"].forEach((k) => delete f.item[k]);
      f.item.note = "New field created instead of changing the existing one's type.";
      f.item.status = "new";
      st.label = `field ${f.item.module}.${f.item.label} (${f.item.data_type})`;
      st.method = "POST"; st.path = st.path.replace(/\/fields\/[^/]+$/, "/fields");
      save();
      render();
      return runFrom(i);
    }
  });

  els.run.addEventListener("change", (e) => {
    const id = e.target.dataset && e.target.dataset.manual;
    if (!id) return;
    const f = findItem(id);
    if (!f) return;
    f.item.manual_done = e.target.checked
      ? { by: L.state.authFullName || L.state.authUser || "you", at: new Date().toISOString() } : null;
    save();
    renderBuild();
  });

  async function verify() {
    L.setStatus("build", "Reading the org back…");
    try {
      impl.verify = await post("/implement/verify", { spec: impl.spec }, 90_000, "Verify");
      renderRun("Verify — org vs spec", impl.verify, "verify");
      render();
      const c = impl.verify.counts || {};
      L.setStatus("build", `${c.present || 0} present, ${c.mismatch || 0} mismatched, ${c.missing || 0} missing, `
        + `${c.unverifiable || 0} to check by hand.`, (c.missing || c.mismatch) ? "warn" : "ok");
    } catch (e) {
      L.setStatus("build", "Verify failed: " + e.message, "err");
    }
  }

  async function pushPlan() {
    const tasks = (impl.spec.plan || []).filter((t) => t.include !== false);
    const cases = tasks.map((t) => ({
      title: `${t.phase ? t.phase + " — " : ""}${t.title}`,
      priority: t.priority || "P1",
      req_ids: t.req_ids || [],
      spec_ref: (t.spec_refs || []).join(", "),
      steps: [{ action: t.description || t.title, expected: (t.spec_refs || []).length ? "Builds " + t.spec_refs.join(", ") : "Done" }],
      acceptance: `Owner: ${t.owner_role || "—"} · Effort: ${t.effort_hours || 0}h`
        + ((t.depends_on || []).length ? ` · Depends on: ${t.depends_on.join("; ")}` : ""),
    }));
    const base = (L.state.fileName || "BRD").replace(/\.[^.]+$/, "");
    const projectName = `Implementation — ${base} — ${new Date().toISOString().slice(0, 10)}`;
    L.setStatus("build", `Pushing ${cases.length} task(s) to Zoho Projects…`);
    try {
      const data = await post("/push", { cases, project_name: projectName }, 120_000, "Push");
      const link = data.project_url ? ` <a href="${esc(data.project_url)}" target="_blank" rel="noopener">Open project</a>` : "";
      const el = document.getElementById("status-build");
      el.className = "status " + (data.tasks_failed ? "warn" : "ok");
      el.innerHTML = esc(`${data.tasks_created || 0} task(s) created${data.tasks_failed ? `, ${data.tasks_failed} failed` : ""}`
        + (data.project_name ? ` in "${data.project_name}"` : "") + (data.mock ? " (mock)" : "") + ".") + link;
    } catch (e) {
      L.setStatus("build", "Push failed: " + e.message, "err");
    }
  }

  // Step 5: a BRD rendered from the approved spec, grouped by the original requirement ids,
  // so UAT cases trace back to the same requirements.
  function specToBrd() {
    const reqTitles = {};
    ((impl.meta && impl.meta.requirements) || []).forEach((r) => { reqTitles[r.id] = r.title; });
    const groups = {};
    const order = [];
    let other = 900;
    for (const [s] of SECTIONS) {
      if (s === "plan" || s === "open_questions") continue;
      for (const o of impl.spec[s] || []) {
        if (o.include === false && o.status !== "exists") continue;
        const ids = (o.req_ids || []).length ? o.req_ids : [`REQ-${other}`];
        ids.forEach((id) => {
          if (!groups[id]) { groups[id] = []; order.push(id); }
          groups[id].push(`- ${describe(s, o)} — ${details(s, o).join("; ")}`);
        });
      }
    }
    const answered = (impl.spec.open_questions || []).filter((q) => (q.answer || "").trim());
    let md = `# Implementation spec\n\n${impl.spec.summary || ""}\n\n## Functional requirements\n\n`;
    order.forEach((id) => {
      md += `### ${id}: ${reqTitles[id] || (id.startsWith("REQ-9") ? "Other configuration" : id)}\n${groups[id].join("\n")}\n\n`;
    });
    if (answered.length) {
      md += "## Decisions\n\n" + answered.map((q) => `- ${q.question} → ${q.answer}`).join("\n") + "\n";
    }
    return md;
  }

  function toUat() {
    const modules = [];
    (impl.spec.modules_in_scope || []).forEach((m) => { if (!modules.includes(m)) modules.push(m); });
    (impl.spec.modules || []).forEach((m) => { if (m.include !== false && !modules.includes(m.api_name)) modules.push(m.api_name); });
    (impl.spec.fields || []).forEach((f) => { if (!modules.includes(f.module)) modules.push(f.module); });
    if (!modules.length) { L.setStatus("build", "The spec names no modules to test.", "warn"); return; }
    L.generateUat(specToBrd(), modules);
  }

  function rpaQueue() {
    const run = impl.applied || impl.dryRun;
    download("rpa-queue.json", { org: run.org || null, generated_at: new Date().toISOString(), items: run.rpa_queue || [] });
  }

  // ---- events ----
  els.spec.addEventListener("change", (e) => {
    const t = e.target;
    if (t.dataset.act === "include") {
      const f = findItem(t.dataset.id);
      if (!f) return;
      f.item.include = t.checked;
      changed();
      render();
    }
  });
  els.spec.addEventListener("input", (e) => {
    const t = e.target;
    if (t.classList.contains("impl-answer")) {
      const f = findItem(t.dataset.id);
      if (!f) return;
      f.item.answer = t.value;
      if (impl.spec.approved) changed();
      save();
      els.toolbar.innerHTML = toolbarHtml();   // answered count + "Apply answers" (not the textarea: keeps focus)
      refreshButtons();
    }
  });
  els.spec.addEventListener("click", (e) => {
    const t = e.target.closest("button[data-act]");
    if (!t) return;
    const id = t.dataset.id;
    const ed = els.spec.querySelector(`[data-editor="${CSS.escape(id)}"]`);
    if (t.dataset.act === "edit" || t.dataset.act === "cancel") {
      if (ed) ed.hidden = t.dataset.act === "cancel" ? true : !ed.hidden;
      return;
    }
    if (t.dataset.act === "save") {
      const f = findItem(id);
      try {
        const next = JSON.parse(ed.querySelector("textarea").value);
        next.id = id;   // ids are stable references (plan spec_refs, apply item_ids)
        next.executor = executorFor(f.section, next);
        impl.spec[f.section][f.index] = next;
        changed();
        render();
        L.setStatus("build", `${id} updated.`, "ok");
      } catch (err) {
        L.setStatus("build", `${id}: not valid JSON — ${err.message}`, "err");
      }
    }
  });
  els.approve.addEventListener("change", () => {
    if (els.approve.checked && blockingOpen().length) { els.approve.checked = false; return; }
    setApproved(els.approve.checked);
    L.setStatus("build", els.approve.checked ? "Approved. Run a dry run, then Apply." : "", "ok");
  });
  els.back.addEventListener("click", () => L.showTab("input"));
  els.dry.addEventListener("click", dryRun);
  els.apply.addEventListener("click", apply);
  els.verify.addEventListener("click", verify);
  els.pushPlan.addEventListener("click", pushPlan);
  els.toUat.addEventListener("click", toUat);
  els.download.addEventListener("click", () => download("implementation-spec.json", Object.assign({}, impl.meta, { spec: impl.spec })));
  els.downloadRpa.addEventListener("click", rpaQueue);

  function answeredQuestions() {
    const now = (impl.spec.open_questions || []).filter((q) => (q.answer || "").trim())
      .map((q) => ({ question: q.question, answer: q.answer.trim() }));
    const seen = new Set(now.map((a) => a.question.toLowerCase().trim()));
    (impl.spec.decisions || []).forEach((a) => { if (!seen.has(a.question.toLowerCase().trim())) now.push(a); });
    return now;
  }

  els.toolbar.addEventListener("click", async (e) => {
    const f = e.target.closest("button[data-filter]");
    if (f) { impl.filter = f.dataset.filter; render(); return; }
    const t = e.target.closest("button[data-tool]");
    if (!t) return;
    if (t.dataset.tool === "copy-questions") {
      const qs = (impl.spec.open_questions || []).filter((q) => q.include !== false && !(q.answer || "").trim());
      const base = (L.state.fileName || "the BRD").replace(/\.[^.]+$/, "");
      const text = `Questions on ${base}\n\n` + qs.map((q, i) =>
        `${i + 1}. ${q.question}${q.blocking ? " (needed before we can build this)" : ""}`
        + (q.context ? `\n   Context: ${q.context}` : "")
        + ((q.req_ids || []).length ? `\n   Requirement: ${q.req_ids.join(", ")}` : "")).join("\n\n");
      try { await navigator.clipboard.writeText(text); L.showToast(`${qs.length} question(s) copied`, "ok", 2500); }
      catch (err) { download("open-questions.txt", text); }
      return;
    }
    if (t.dataset.tool === "regen" || t.dataset.tool === "regen-answers") {
      const answers = t.dataset.tool === "regen-answers" ? answeredQuestions() : (impl.spec.decisions || []);
      if (!window.confirm("Regenerate the blueprint from the BRD" + (answers.length ? ` with ${answers.length} client answer(s)` : "")
          + "?\n\nYour edits, ticks and the dry run are replaced.")) return;
      return generate({ answers });
    }
  });

  window.LiftOffImpl = {
    generate,
    reset() {
      impl.spec = null; impl.meta = null; impl.dryRun = null; impl.applied = null; impl.verify = null; impl.build = null;
      try { localStorage.removeItem(STORAGE_KEY); } catch (e) {}
      els.run.innerHTML = "";
      L.setStatus("build", "");
      L.setTabState("build", { locked: true, done: false });
      render();
    },
  };

  load();
  if (impl.spec) {
    L.setTabState("build", { locked: false, done: true });
    if (impl.build || impl.dryRun) renderBuild();
    render();
  } else {
    refreshButtons();
  }
})();
