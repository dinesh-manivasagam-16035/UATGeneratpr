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
  const FILTERS = [["all", "All items"], ["attention", "Needs attention"], ["new", "New"], ["exists", "Already in org"],
    ["api", "Builds via API"], ["manual", "Needs UI work"]];
  const GROUPS = [
    ["data_model", "Data model", "modules, fields, layouts, validation rules, roles", ["modules", "fields", "layouts", "validation_rules", "roles"]],
    ["automation", "Automation", "workflows, approvals, Deluge functions", ["workflows", "functions"]],
  ];
  const KIND_LABEL = { modules: "module", fields: "field", layouts: "layout", validation_rules: "validation rule", roles: "role",
    workflows: "workflow", functions: "Deluge function" };
  const STATUS_LABEL = { new: "New", exists: "Already in org", needs_update: "Needs update", conflict: "Conflict",
    not_compared: "Not compared", created: "Created", open: "Open" };
  const API_TRIGGERS = ["create", "edit", "create_or_edit", "field_update", "delete"];   // ImplSpec.API_TRIGGERS
  const API_FIELD_TYPES = ["text", "textarea", "email", "phone", "website", "picklist", "multiselectpicklist",
    "date", "datetime", "integer", "bigint", "decimal", "currency", "percent", "boolean", "lookup"];

  const impl = {
    meta: null,      // full /implement response minus spec
    spec: null,
    dryRun: null,    // last dry-run response
    applied: null,   // last real apply response
    verify: null,    // last verify response
    build: null,     // the step-by-step build run (see renderBuild)
    uat: null,       // UAT run inside Verify: { cases, live, ranAt }
    filter: "all",   // blueprint filter (FILTERS)
  };
  let running = false;   // a build run is in progress

  const $ = (id) => document.getElementById(id);
  const els = {
    meta: $("impl-meta"), org: $("impl-org"), warnings: $("impl-warnings"), spec: $("impl-spec"), toolbar: $("impl-toolbar"),
    reviewBar: $("impl-review-bar"), approve: $("impl-approve"), approveNote: $("impl-approve-note"),
    back: $("impl-back"), dry: $("impl-dry-run"), apply: $("impl-apply"), verify: $("impl-verify"),
    pushPlan: $("impl-push-plan"), verifyArea: $("impl-verify-area"), download: $("impl-download"),
    downloadRpa: $("impl-download-rpa"), run: $("impl-run"),
    toBuild: $("impl-to-build"), toVerify: $("impl-to-verify"),
    backBlueprint: $("impl-back-blueprint"), backBuild: $("impl-back-build"),
  };

  // ---- persistence (per-browser convenience only) ----
  function save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ meta: impl.meta, spec: impl.spec, dryRun: impl.dryRun, build: impl.build,
        verify: impl.verify, uat: impl.uat }));
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
        impl.verify = saved.verify || null; impl.uat = saved.uat || null;
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
        (o.field_updates || []).forEach((f) => parts.push(`set ${f.field} = ${Array.isArray(f.value) ? f.value.join(", ") : f.value}`));
        (o.tasks || []).forEach((t) => parts.push(`task "${t.subject}" due in ${t.due_in_days || 0} day(s)`));
        (o.other_actions || []).forEach((a) => parts.push("by hand: " + a));
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
    if (section === "fields") return API_FIELD_TYPES.includes(o.data_type) ? "api" : "rpa";
    if (section === "roles") return (o.permissions || []).length ? "partial" : "api";
    if (section === "workflows") {
      const apiRule = o.type === "workflow" && API_TRIGGERS.includes(o.trigger_type || "")
        && ((o.field_updates || []).length || (o.tasks || []).length);
      if (!apiRule) return "rpa";
      return (o.other_actions || []).length ? "partial" : "api";
    }
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
         <span class="impl-badge ${org.sandbox ? "ok" : "warn"}">${org.sandbox ? (org.allow_listed ? "test org (allow-listed)" : "sandbox") : esc(org.type || "production?")}</span>
         <span class="muted">Items are checked against this org.</span>`
      : `<span class="impl-badge warn">not connected</span>
         <span class="muted">Connect CRM on the Brief tab to check items against your org and to apply.</span>`;

    const warnings = m.warnings || [];
    let wh = "";
    if (warnings.length) {
      wh += `<details class="impl-warn"><summary>${warnings.length} note(s) from generation</summary><ul>`
        + warnings.map((w) => `<li>${esc(w)}</li>`).join("") + "</ul></details>";
    }
    if (impl.spec.summary) wh = `<p class="impl-summary">${esc(impl.spec.summary)}</p>` + wh;
    els.warnings.innerHTML = wh;

    els.toolbar.hidden = false;
    els.toolbar.innerHTML = toolbarHtml();
    const html = GROUPS.map((g) => groupTableHtml(g)).join("") + questionsTableHtml() + planTableHtml();
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
      case "api":       return o.executor === "api" || o.executor === "partial";
      case "manual":    return o.executor === "rpa" || o.executor === "partial";
      default:          return true;
    }
  }

  // ---- Blueprint as tables ----

  function pill(cls, text, title) {
    return `<span class="impl-badge ${esc(cls)}"${title ? ` title="${esc(title)}"` : ""}>${esc(text)}</span>`;
  }
  function statusPills(section, o) {
    const ver = impl.verify && (impl.verify.results || []).find((r) => r.id === o.id);
    const run = (impl.applied || impl.dryRun) && ((impl.applied || impl.dryRun).results || []).find((r) => r.id === o.id);
    return [
      o.status && o.status !== "todo" ? pill("st-" + o.status, STATUS_LABEL[o.status] || o.status) : "",
      run ? pill("run-" + run.result, run.result.replace(/_/g, " "), run.detail || "") : "",
      ver ? pill("ver-" + ver.verdict, ver.verdict, ver.detail || "") : "",
    ].filter(Boolean).join(" ");
  }
  function reqCells(o) {
    return (o.req_ids || []).map((r) => `<span class="impl-req">${esc(r)}</span>`).join("") || `<span class="muted">—</span>`;
  }
  function includeCell(o, disabled) {
    return `<td><input type="checkbox" data-act="include" data-id="${esc(o.id)}" aria-label="Include ${esc(o.id)}" ${o.include === false ? "" : "checked"} ${disabled ? "disabled" : ""} /></td>`;
  }
  function editorRow(o, cols) {
    return `<tr class="row-editor" data-editor="${esc(o.id)}" hidden><td colspan="${cols}">
      <textarea rows="10" spellcheck="false" aria-label="Edit ${esc(o.id)} as JSON">${esc(JSON.stringify(o, null, 2))}</textarea>
      <div class="cell-actions" style="margin-top:8px">
        <button class="primary small" data-act="save" data-id="${esc(o.id)}" type="button">Save</button>
        <button class="ghost small" data-act="cancel" data-id="${esc(o.id)}" type="button">Cancel</button>
      </div></td></tr>`;
  }
  function nameCell(section, o) {
    return `<td class="cell-main">${esc(describe(section, o).replace(/^[^·]*·\s*/, "")) || esc(o.id)}<span class="muted">${esc(KIND_LABEL[section] || section)}</span>
      ${o.note ? `<div class="muted" style="color:var(--warn)">${esc(o.note)}</div>` : ""}</td>`;
  }

  // Data model / Automation: every section of the group in one table, filtered.
  function groupTableHtml([key, label, sub, sections]) {
    const rows = [];
    sections.forEach((section) => (impl.spec[section] || []).forEach((o) => { if (matchesFilter(section, o)) rows.push([section, o]); }));
    const total = sections.reduce((n, sct) => n + (impl.spec[sct] || []).length, 0);
    if (!total) return "";
    const included = sections.reduce((n, sct) => n + (impl.spec[sct] || []).filter((o) => o.include !== false).length, 0);
    const head = `<div class="card-head"><h3>${esc(label)} <span class="muted">· ${esc(sub)}</span></h3>
        <span class="pill">${included} of ${total} included</span></div>`;
    if (!rows.length) return `<section class="card" data-group="${key}">${head}<p class="muted" style="padding:14px 18px;margin:0">Nothing in this group matches the filter.</p></section>`;
    const auto = key === "automation";
    const cols = auto ? 9 : 8;
    const thead = auto
      ? `<tr><th class="col-check"></th><th class="col-narrow">ID</th><th>Item</th><th>Trigger</th><th>Criteria</th><th>Actions</th><th>In org</th><th>Build via</th><th>Req</th><th></th></tr>`
      : `<tr><th class="col-check"></th><th class="col-narrow">ID</th><th>Item</th><th>Module</th><th>Details</th><th>In org</th><th>Build via</th><th>Req</th><th></th></tr>`;
    const body = rows.map(([section, o]) => {
      const off = o.include === false;
      const exec = pill("exec-" + o.executor, EXECUTOR_LABEL[o.executor] || o.executor);
      const edit = `<td><div class="cell-actions"><button class="ghost small" data-act="edit" data-id="${esc(o.id)}" type="button">Edit</button></div></td>`;
      let mid;
      if (auto) {
        if (section === "workflows") {
          const crit = o.criteria || (o.conditions || []).map((c) => `${c.field} ${c.comparator} ${c.value || ""}`).join(" and ") || "all records";
          const acts = [].concat(
            (o.field_updates || []).map((f) => `Set <code>${esc(f.field)}</code> = ${esc(Array.isArray(f.value) ? f.value.join(", ") : f.value)}`),
            (o.tasks || []).map((t) => `Task “${esc(t.subject)}” · ${esc(String(t.due_in_days || 0))} day(s) · ${esc(t.priority || "Normal")}`),
            (o.other_actions || []).map((a) => `<span class="muted">by hand:</span> ${esc(a)}`),
            (!(o.field_updates || []).length && !(o.tasks || []).length && !(o.other_actions || []).length ? (o.actions || []).map(esc) : []),
            (o.states || []).length ? [`<span class="muted">states:</span> ${esc(o.states.join(" → "))}`] : []);
          mid = `<td>${esc(o.trigger || "")}${o.trigger_type ? `<div><code>${esc(o.trigger_type)}${(o.trigger_fields || []).length ? ": " + esc(o.trigger_fields.join(", ")) : ""}</code></div>` : ""}</td>
            <td>${esc(crit)}</td><td>${acts.join("<br>") || `<span class="muted">—</span>`}${o.details ? `<div class="muted">${esc(o.details)}</div>` : ""}</td>`;
        } else {
          mid = `<td>${esc(o.trigger || "")}</td><td><span class="muted">—</span></td>
            <td>${esc(o.purpose || "")}${(o.arguments || []).length ? `<div class="muted">args: ${esc(o.arguments.map((a) => a.name).join(", "))}</div>` : ""}
              ${o.code ? `<details><summary class="muted">Deluge code</summary><pre class="impl-code">${esc(o.code)}</pre></details>` : ""}</td>`;
        }
      } else {
        const det = details(section, o).map(esc).join("<br>") || `<span class="muted">—</span>`;
        mid = `<td>${esc(o.module || (section === "modules" ? o.api_name || "" : ""))}</td><td>${det}</td>`;
      }
      return `<tr class="${off ? "is-off" : ""}${o.status === "conflict" ? " is-err" : ""}" data-id="${esc(o.id)}">
        ${includeCell(o, o.status === "exists")}<td><strong>${esc(o.id)}</strong></td>${nameCell(section, o)}${mid}
        <td>${statusPills(section, o) || `<span class="muted">—</span>`}</td><td>${exec}</td><td>${reqCells(o)}</td>${edit}</tr>` + editorRow(o, cols);
    }).join("");
    return `<section class="card" data-group="${key}">${head}<div class="table-wrap"><table class="lo-table"><thead>${thead}</thead><tbody>${body}</tbody></table></div></section>`;
  }

  function questionsTableHtml() {
    const qs = (impl.spec.open_questions || []).filter((o) => matchesFilter("open_questions", o));
    const all = impl.spec.open_questions || [];
    if (!all.length) return "";
    const open = all.filter((q) => q.include !== false && !(q.answer || "").trim()).length;
    const head = `<div class="card-head ${open ? "warn" : ""}"><h3>Open questions <span class="muted">· answers feed the next regeneration</span></h3>
        <span class="pill ${open ? "warn" : "ok"}">${open ? open + " open" : "all answered"}</span></div>`;
    if (!qs.length) return `<section class="card ${open ? "warn" : ""}">${head}</section>`;
    const body = qs.map((o) => `<tr class="${o.include === false ? "is-off" : ""}" data-id="${esc(o.id)}">
        ${includeCell(o)}<td><strong>${esc(o.id)}</strong></td>
        <td>${esc(o.question || "")}</td><td class="muted">${esc(o.context || "")}</td>
        <td><textarea class="impl-answer" data-id="${esc(o.id)}" rows="2" placeholder="Answer (from the client)">${esc(o.answer || "")}</textarea></td>
        <td>${o.blocking ? pill("warn", "blocking") : pill("", "no")}</td>
        <td>${reqCells(o)}</td></tr>`).join("");
    return `<section class="card ${open ? "warn" : ""}">${head}<div class="table-wrap"><table class="lo-table">
      <thead><tr><th class="col-check"></th><th class="col-narrow">ID</th><th style="width:30%">Question</th><th>What the BRD says</th><th style="width:28%">Your answer</th><th>Blocking</th><th>Req</th></tr></thead>
      <tbody>${body}</tbody></table></div></section>`;
  }

  function planTableHtml() {
    const all = impl.spec.plan || [];
    if (!all.length) return "";
    const rows = all.filter((o) => matchesFilter("plan", o));
    const hours = all.filter((o) => o.include !== false).reduce((n, t) => n + (Number(t.effort_hours) || 0), 0);
    const head = `<div class="card-head"><h3>Delivery plan</h3><span class="pill">${all.filter((o) => o.include !== false).length} task(s) · ${hours} h</span></div>`;
    const body = rows.map((o) => `<tr class="${o.include === false ? "is-off" : ""}" data-id="${esc(o.id)}">
        ${includeCell(o)}<td>${esc(o.phase || "")}</td><td class="cell-main">${esc(o.title || "")}${o.description ? `<div class="muted" style="font-weight:400">${esc(o.description)}</div>` : ""}</td>
        <td>${esc(o.owner_role || "—")}</td><td>${esc(String(o.effort_hours || 0))} h</td>
        <td>${pill(o.priority === "P0" ? "err" : o.priority === "P2" ? "" : "warn", o.priority || "P1")}</td>
        <td>${(o.spec_refs || []).map((r) => `<code>${esc(r)}</code>`).join(" ") || `<span class="muted">—</span>`}</td>
        <td><div class="cell-actions"><button class="ghost small" data-act="edit" data-id="${esc(o.id)}" type="button">Edit</button></div></td></tr>` + editorRow(o, 8)).join("");
    return `<section class="card">${head}<div class="table-wrap"><table class="lo-table">
      <thead><tr><th class="col-check"></th><th>Phase</th><th>Task</th><th>Owner</th><th>Effort</th><th>Priority</th><th>Builds</th><th></th></tr></thead>
      <tbody>${body}</tbody></table></div></section>`;
  }

  // Summary tiles, filters and the review actions above the blueprint.
  function toolbarHtml() {
    const all = [];
    for (const [s] of SECTIONS) if (s !== "plan") (impl.spec[s] || []).forEach((o) => all.push([s, o]));
    const build = all.filter(([s]) => s !== "open_questions");
    const included = build.filter(([, o]) => o.include !== false);
    const byExec = (e) => included.filter(([, o]) => o.executor === e).length;
    const openQs = (impl.spec.open_questions || []).filter((q) => q.include !== false);
    const answered = openQs.filter((q) => (q.answer || "").trim()).length;
    const blocking = openQs.filter((q) => q.blocking && !(q.answer || "").trim()).length;
    const conflicts = build.filter(([, o]) => o.status === "conflict").length;
    const cov = (impl.meta && impl.meta.coverage) || null;
    const tile = (cls, label, value, sub, bar) => `<div class="tile ${cls}"><p class="tile-label">${label}</p><div class="tile-value">${value}</div>${sub ? `<div class="tile-sub">${sub}</div>` : ""}${bar || ""}</div>`;
    const pct = cov && cov.requirements ? Math.round(100 * cov.covered / cov.requirements) : 0;
    const count = (sct) => (impl.spec[sct] || []).filter((o) => o.include !== false).length;
    const breakdown = [["fields", "field"], ["workflows", "rule"], ["functions", "function"], ["validation_rules", "validation"], ["roles", "role"], ["layouts", "layout"]]
      .map(([sct, l]) => [count(sct), l]).filter(([n]) => n).map(([n, l]) => `${n} ${l}${n === 1 ? "" : "s"}`).join(" · ");
    const tiles = `<div class="tiles tiles-5">
      ${cov && cov.requirements ? tile(pct === 100 ? "ok" : "warn", "Requirements covered", `${cov.covered} <span class="unit">/ ${cov.requirements}</span>`,
          cov.uncovered && cov.uncovered.length ? "not covered: " + esc(cov.uncovered.join(", ")) : "every requirement has an item",
          `<div class="tile-bar"><span style="width:${pct}%"></span></div>`) : tile("", "Requirements", "—", "no requirement ids in the BRD")}
      ${tile("", "Items", `${included.length} <span class="unit">/ ${build.length}</span>`, esc(breakdown || "nothing to build"))}
      ${tile("info", "Builds via API", String(byExec("api") + byExec("partial")), byExec("partial") ? `${byExec("partial")} finished by hand` : "created by Build")}
      ${tile(byExec("rpa") ? "warn" : "", "Needs UI work", String(byExec("rpa")), "no create API · click-path steps")}
      ${tile(openQs.length - answered ? "warn" : openQs.length ? "ok" : "", "Open questions", String(openQs.length - answered),
          openQs.length ? `${answered} answered · ${blocking} blocking` : "none")}
    </div>`;
    const chips = `<div class="chips" role="group" aria-label="Filter the blueprint"><span class="chip-label">Show</span>
        ${FILTERS.map(([k, l]) => `<button type="button" class="mode-btn${impl.filter === k ? " active" : ""}" data-filter="${k}" aria-pressed="${impl.filter === k}">${l}</button>`).join("")}
        ${conflicts ? `<span class="pill err">${conflicts} conflict(s)</span>` : ""}
        <span class="spacer"></span>
        <div class="impl-toolbar-actions">
          ${openQs.length ? `<button type="button" class="ghost small" data-tool="copy-questions">Copy questions for email</button>` : ""}
          ${answered ? `<button type="button" class="ghost small" data-tool="regen-answers">Apply answers and regenerate</button>` : ""}
          <button type="button" class="ghost small" data-tool="regen">Regenerate</button>
        </div></div>`;
    return tiles + chips;
  }

  // Build tab: summary tiles + the plan table from a dry run (or the verify table).
  function renderRun(title, data, kind) {
    const results = data.results || [];
    const rpa = data.rpa_queue || [];
    const n = (f) => results.filter(f).length;
    const api = n((r) => ["would_create", "would_update", "created", "updated"].includes(r.result));
    const conflicts = n((r) => r.result === "conflict" || r.result === "failed");
    const calls = results.reduce((t, r) => t + ((r.requests || []).length || (r.request ? 1 : 0)), 0);
    const org = data.org || {};
    const tile = (cls, label, value, sub) => `<div class="tile ${cls}"><p class="tile-label">${label}</p><div class="tile-value">${value}</div>${sub ? `<div class="tile-sub">${sub}</div>` : ""}</div>`;
    const orgKind = !org.id ? "not connected" : org.sandbox
      ? (org.production_allowed ? (org.type || "production") + " · IMPL_ALLOW_PRODUCTION" : org.allow_listed ? "test org · allow-listed" : "sandbox")
      : (org.type || "production") + " · not a sandbox";
    const idsNote = `id <code>${esc(org.id || "")}</code>${org.zgid ? ` · zgid <code>${esc(org.zgid)}</code>` : ""}`;
    const orgTile = !org.id
      ? tile("warn", "Target org", "None", "connect CRM on the Brief step to apply")
      : org.sandbox
        ? tile(org.production_allowed ? "warn" : "ok", "Target org", esc(org.name || org.id), `${esc(orgKind)} · Apply allowed · ${idsNote}`)
        : tile("warn", "Target org", esc(org.name || org.id), `${esc(orgKind)} · Apply is off · ${idsNote}. Add either id to IMPL_BUILD_ORG_IDS on uat_generator (or set IMPL_ALLOW_PRODUCTION=1), redeploy the function, then dry-run again.`);
    const tiles = `<div class="tiles tiles-4">
      ${orgTile}
      ${tile("info", data.dry_run ? "Will create via API" : "Created via API", String(api), `${calls} API call(s), in order`)}
      ${tile(rpa.length ? "warn" : "", "Queued for UI work", String(rpa.length), rpa.length ? `${rpa.reduce((t, q) => t + (q.steps || []).length, 0)} click-path steps · RPA bot or consultant` : "nothing needs Setup")}
      ${tile(conflicts ? "err" : "ok", "Conflicts / failures", String(conflicts), conflicts ? "see the rows marked in red" : "nothing clashes with the org")}
    </div>`;
    let step = 0;
    const rows = results.map((r) => {
      const reqs = r.requests || (r.request ? [r.request] : []);
      const isApi = ["would_create", "would_update", "created", "updated", "conflict", "failed"].includes(r.result) && (r.executor === "api" || r.executor === "partial");
      const num = isApi ? String(++step) : "—";
      const f = findItem(r.id);
      const what = `<strong>${esc(r.label.replace(/^(field|workflow|module|role|validation rule|Deluge function|layout)\s+/i, ""))}</strong><span class="muted"> ${esc((r.label.match(/^(field|workflow|module|role|validation rule|Deluge function|layout)/i) || [""])[0].toLowerCase())}</span>`;
      const call = reqs.length
        ? reqs.map((q) => `<code>${esc((q.method || "") + " " + (q.path || "").replace(/^\/crm\/v\d+/, "").replace(/\?.*$/, ""))}</code>`).join("<br>")
        : `<span class="muted">${r.executor === "rpa" ? "no create API" : "—"}</span>`;
      const cls = r.result === "conflict" || r.result === "failed" ? "is-err" : (f && f.item.include === false) ? "is-off" : "";
      return `<tr class="${cls}"><td class="muted">${num}</td><td><strong>${esc(r.id)}</strong></td><td>${what}</td><td>${call}</td>
        <td>${pill(kind === "verify" ? "ver-" + r.verdict : "run-" + r.result, (kind === "verify" ? r.verdict : r.result || "").replace(/_/g, " "))}</td>
        <td class="muted">${esc([r.detail, r.follow_up].filter(Boolean).join(" — "))}</td></tr>`;
    }).join("");
    const warn = (data.warnings || []).length ? `<p class="status warn" style="margin:0 18px 12px">${esc(data.warnings.join(" • "))}</p>` : "";
    els.run.innerHTML = tiles + `<section class="card impl-plan"><div class="card-head"><h3>${esc(title)}</h3><span class="muted">Order: modules → fields → roles → rules</span></div>
      <div class="table-wrap"><table class="lo-table"><thead><tr><th class="col-narrow">#</th><th class="col-narrow">Item</th><th>What</th><th>API call</th><th>Result</th><th>Detail</th></tr></thead>
      <tbody>${rows}</tbody></table></div>${warn}</section>` + rpaQueueHtml(rpa);
  }

  // The click-path queue for items without a create API, with the "done" tick per item.
  function rpaQueueHtml(queue) {
    if (!queue.length) return "";
    const doneCount = queue.filter((q) => { const f = findItem(q.id); return f && f.item.manual_done; }).length;
    return `<section class="card" id="impl-rpa"><div class="card-head"><h3>UI work queue <span class="muted">· no create API</span></h3>
        <div class="impl-toolbar-actions"><span class="pill ${doneCount === queue.length ? "ok" : "warn"}">${doneCount} of ${queue.length} done</span></div></div>
      <ul class="impl-manual">` + queue.map((q) => {
        const f = findItem(q.id);
        const item = f ? f.item : {};
        const blocked = blockedBy(item);
        const done = item.manual_done;
        return `<li>
          <input type="checkbox" data-manual="${esc(q.id)}" ${done ? "checked" : ""} ${blocked.length && !done ? "disabled" : ""} aria-label="${esc(q.label)} done" />
          <div class="impl-manual-body"><strong>${esc(q.label)}</strong> <span class="pill warn">${(q.steps || []).length} steps</span>
            <ol>${(q.steps || []).map((x) => `<li>${esc(x)}</li>`).join("")}</ol></div>
          ${done ? `<span class="impl-done-by">Done by ${esc(done.by)}</span>`
            : blocked.length ? `<span class="impl-blocked">Blocked by ${esc(blocked.join(", "))}</span>`
            : `<button class="ghost small" type="button" data-copy="${esc(q.id)}">Copy steps</button>`}
        </li>`;
      }).join("") + "</ul></section>";
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
    els.apply.title = !have ? "" : !impl.spec.approved ? "Approve the blueprint first"
      : !impl.dryRun ? "Run a dry run first" : !connected ? "Connect CRM first"
      : !sandbox ? "Apply only writes to sandbox orgs" : "";
    els.verify.disabled = !(have && connected);
    els.pushPlan.disabled = !(have && (impl.spec.plan || []).some((t) => t.include !== false));
    els.download.disabled = !have;
    els.downloadRpa.disabled = !(impl.applied || impl.dryRun);
    // Tabs follow the pipeline: Blueprint once drafted, Build once approved, Verify once dry-run.
    const approved = have && !!impl.spec.approved;
    const dryDone = approved && !!impl.dryRun;
    const buildDone = !!(impl.build && impl.build.finishedAt) || (dryDone && !buildSteps().length);
    L.setTabState("blueprint", { locked: !have, done: approved });
    L.setTabState("build", { locked: !approved, done: buildDone });
    L.setTabState("verify", { locked: !dryDone, done: checksOk() && uatOk() });
    els.toBuild.disabled = !approved;
    els.toVerify.disabled = !dryDone || running;
  }

  // Any edit after approval needs a fresh approval and a fresh dry run.
  function changed() {
    if (impl.spec.approved) {
      setApproved(false);
      L.setStatus("blueprint", "Blueprint changed — review and approve again.", "warn");
    }
    if (!running) { impl.dryRun = null; impl.build = null; }
    save();
  }

  // ---- actions ----
  async function generate(opts) {
    const answers = (opts && opts.answers) || [];
    L.setTabState("blueprint", { locked: false });
    L.showTab("blueprint");
    L.setStatus("blueprint", "");
    progressView.begin();
    impl.spec = null; impl.meta = null; impl.dryRun = null; impl.applied = null; impl.verify = null; impl.build = null; impl.uat = null;
    els.run.innerHTML = "";
    els.verifyArea.innerHTML = "";
    render();
    try {
      const data = await generateStaged(answers);
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
      const w = (data.warnings || []).length;
      L.setStatus("blueprint", w ? `Blueprint ready with ${w} note(s). Review it, then approve.` : "Blueprint ready. Review it, then approve.", w ? "warn" : "ok");
    } catch (e) {
      L.setStatus("blueprint", "Blueprint generation failed: " + e.message, "err");
      L.showToast("Blueprint failed: " + String(e.message).slice(0, 80), "err", 6000);
    } finally {
      progressView.end();
    }
  }

  // Blueprint generation in short requests (POST /implement/stage): Catalyst stops a function
  // call after ~30 s, and a whole blueprint takes several LLM calls. start → parts (two at a
  // time, one LLM call each) → finish → plan.
  async function generateStaged(answers) {
    const brd = L.plainBrd ? L.plainBrd(L.state.brd) : L.state.brd;
    const modules = L.state.modulesSelected || [];
    const stage = (body, label) => post("/implement/stage", body, 60_000, label);
    const progress = (t) => progressView.phase(t);

    progress("Reading the BRD and your org");
    const start = await stage({ stage: "start", brd, modules, answers }, "Blueprint start");
    const jobs = [];
    start.parts.forEach((p) => p.groups.forEach((g) => jobs.push({ part: p.part, label: p.label, group: g })));
    progressView.plan(jobs);

    // Background job (impl_worker): agent replies can take minutes; the browser only polls.
    let sub = null, subErr = null;
    try {
      sub = await stage({ stage: "submit", brd, modules, answers, org_text: start.org_text, jobs }, "Queue generation");
    } catch (e) { subErr = e; }
    let fin;
    if (sub && sub.job_key) {
      await pollJob(sub.job_key);
      progress("Merging and checking against your org");
      fin = await stage({ stage: "finish", brd, modules, job_key: sub.job_key, warnings: start.warnings || [] }, "Blueprint finish");
    } else {
      // Background jobs not set up: one request per call, fine for fast providers.
      console.warn("Background generation unavailable, running in the browser:", subErr && subErr.message);
      fin = await generateInBrowser(brd, modules, answers, start, jobs, stage, progress);
      fin.warnings = ["Ran without a background job (" + (subErr ? subErr.message : "not set up")
        + "). Long agent replies may time out; see agent/ZIA_GENERATOR.md to enable background generation."].concat(fin.warnings || []);
    }
    if (fin.needs_plan) {
      progress("Writing the delivery plan");
      try {
        const pl = await stage({ stage: "plan", spec: fin.spec, requirements: fin.requirements || [] }, "Plan");
        fin.spec = pl.spec;
        fin.warnings = (fin.warnings || []).concat(pl.warnings || []);
        fin.counts = Object.assign({}, fin.counts, { plan: (pl.spec.plan || []).length });
      } catch (e) {
        fin.warnings = (fin.warnings || []).concat(["Plan step failed (" + e.message + "); Regenerate to try again."]);
      }
    }
    fin.org = start.org;
    fin.provider = start.provider;
    return fin;
  }

  // Polls the job and shows what it is working on, step by step.
  async function pollJob(key) {
    const t0 = Date.now();
    const LIMIT_MS = 16 * 60 * 1000;      // a job function may run 15 minutes
    let last = null;
    while (Date.now() - t0 < LIMIT_MS) {
      try {
        last = await post("/implement/stage", { stage: "status", job_key: key }, 30_000, "Status");
      } catch (e) {
        last = last || { state: "queued" };   // a missed poll isn't fatal
      }
      progressView.job(last);
      if (last.state === "done") return last;
      if (last.state === "failed") throw new Error(last.error || "The background job failed.");
      if (last.state === "queued" && Date.now() - t0 > 3 * 60 * 1000) {
        throw new Error("The background job didn't start within 3 minutes. Check that the impl_worker job function "
          + "is deployed and the 'liftoffjobs' job pool exists.");
      }
      await new Promise((r) => setTimeout(r, 4000));
    }
    throw new Error("Generation is taking longer than 16 minutes; it was stopped. Try a shorter BRD or fewer modules.");
  }

  function fmtTime(ms) {
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, "0")}s`;
  }

  // Generation progress, full width in the Blueprint page: phase, overall bar, one row per step.
  const progressView = (() => {
    const el = () => document.getElementById("impl-progress");
    const DETAIL = { data_model: "Modules, fields, layouts, rules, roles", automation: "Workflows, approvals, Deluge",
                     mock: "Offline sample" };
    const NAME = { data_model: "Data model", automation: "Automation", mock: "Sample" };
    let t0 = 0, timer = null, phaseText = "", steps = [], job = null;

    const fmt = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
    const stateText = (x) => {
      if (x.state === "done") return `${x.items || 0} item${x.items === 1 ? "" : "s"} · ${x.seconds || 0}s`;
      if (x.state === "working") return x.started_at ? `Working · ${fmt(Date.now() - x.started_at)}` : "Working";
      if (x.state === "failed") return "Failed";
      if (x.state === "skipped") return "Skipped";
      return "Queued";
    };

    function percent() {
      if (job && job.state === "done") return 92;
      if (!steps.length) return 4;
      const done = steps.filter((x) => ["done", "failed", "skipped"].includes(x.state)).length;
      const working = steps.filter((x) => x.state === "working").length;
      return Math.round(8 + 82 * (done + working * 0.35) / steps.length);
    }

    function draw() {
      const box = el();
      if (!box) return;
      const total = steps.length;
      const done = steps.filter((x) => ["done", "failed", "skipped"].includes(x.state)).length;
      const sub = job && job.state === "queued" ? "Waiting for a worker"
        : job && job.state === "running" ? `Step ${Math.min(done + 1, total)} of ${total}`
        : phaseText;
      box.innerHTML = `
        <div class="ip-head">
          <div class="ip-title"><span class="ip-pulse" aria-hidden="true"></span><strong>Generating blueprint</strong>
            <span class="ip-sub">${esc(sub)}</span></div>
          <span class="ip-time" title="Elapsed">${fmt(Date.now() - t0)}</span>
        </div>
        <div class="ip-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${percent()}">
          <span style="width:${percent()}%"></span></div>
        ${total ? `<ol class="ip-steps">${steps.map((x) => `
          <li class="ip-step is-${esc(x.state)}">
            <span class="ip-icon" aria-hidden="true"></span>
            <span class="ip-text"><span class="ip-label">${esc(x.label)}</span><span class="ip-detail">${esc(x.state === "failed" && x.error ? x.error : (x.detail || ""))}</span></span>
            <span class="ip-state">${esc(stateText(x))}</span>
          </li>`).join("")}</ol>` : ""}
        <p class="ip-note">Runs in the background. You can leave this page.</p>`;
    }

    return {
      begin() {
        t0 = Date.now(); phaseText = "Starting"; steps = []; job = null;
        const box = el();
        if (box) box.hidden = false;
        L.setTabState("blueprint", { running: true });
        clearInterval(timer);
        timer = setInterval(draw, 1000);          // elapsed and per-step timers tick between polls
        draw();
      },
      phase(text) { phaseText = text; draw(); },
      plan(jobs) {
        steps = jobs.map((j) => ({ label: `Part ${j.part} · ${NAME[j.group] || j.group}`, detail: DETAIL[j.group] || "", state: "waiting" }))
          .concat([{ label: "Delivery plan", detail: "Phases, tasks, effort", state: "waiting" }]);
        draw();
      },
      job(st) {
        job = st;
        if (st && Array.isArray(st.steps) && st.steps.length) steps = st.steps;
        draw();
      },
      step(i, patch) { if (steps[i]) Object.assign(steps[i], patch); draw(); },
      end() {
        clearInterval(timer); timer = null;
        const box = el();
        if (box) { box.hidden = true; box.innerHTML = ""; }
        L.setTabState("blueprint", { running: false });
      },
    };
  })();

  // Fallback without background jobs: one request per agent call.
  async function generateInBrowser(brd, modules, answers, start, jobs, stage, progress) {
    const results = new Array(jobs.length);
    let next = 0, done = 0;
    const GROUP = { data_model: "data model", automation: "automation", mock: "offline" };
    const worker = async () => {
      while (next < jobs.length) {
        const i = next++;
        const j = jobs[i];
        const body = { stage: "part", brd, modules, answers, part: j.part, group: j.group, org_text: start.org_text };
        progressView.step(i, { state: "working", started_at: Date.now() });
        const began = Date.now();
        let r;
        for (let attempt = 1; attempt <= 2; attempt++) {
          try {
            r = await stage(body, `Part ${j.part} (${GROUP[j.group] || j.group})`);
            if (!r.error || attempt === 2) break;
          } catch (e) {
            r = { part: j.part, group: j.group, error: e.message };
          }
        }
        results[i] = r;
        done++;
        const items = Object.values(r.items || {}).reduce((n, list) => n + (list ? list.length : 0), 0);
        progressView.step(i, r.error ? { state: "failed", error: r.error } : { state: "done", items, seconds: Math.round((Date.now() - began) / 1000) });
      }
    };
    progress(`Drafting ${jobs.length} steps`);
    await Promise.all([worker(), worker()]);
    progress("Merging and checking against your org");
    return stage({ stage: "finish", brd, modules, results, warnings: start.warnings || [] }, "Blueprint finish");
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
        ? `Dry run against ${o.name || o.id}. It isn't a sandbox, so Apply stays off — connect a sandbox org, or add this org's id (${o.id}) to IMPL_BUILD_ORG_IDS on uat_generator if it is a test org.`
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
  function req(r) {
    if (r.request) return r.request;
    const list = r.requests || [];
    return list.length ? list[list.length - 1] : {};
  }

  function buildSteps() {
    return (impl.dryRun.results || [])
      .filter((r) => (r.executor === "api" || r.executor === "partial")
        && ["would_create", "would_update", "conflict", "failed"].includes(r.result))
      .map((r) => ({
        id: r.id, label: r.label,
        // A workflow rule is several calls (its actions, then the rule): show the rule call.
        method: req(r).method || "", path: (req(r).path || "").replace(/\?.*$/, ""),
        calls: (r.requests || []).length || (r.request ? 1 : 0),
        status: "waiting", detail: r.result === "failed" || r.result === "conflict" ? r.detail : "",
      }));
  }

  async function apply() {
    const org = impl.dryRun && impl.dryRun.org;
    if (!org) return;
    if (!org.sandbox) {
      L.setStatus("build", `"${org.name}" isn't a sandbox. LiftOff only builds in sandbox orgs or allow-listed test orgs (IMPL_BUILD_ORG_IDS).`, "warn");
      return;
    }
    const steps = buildSteps();
    if (!steps.length) { L.setStatus("build", "Nothing for the API to build — see Configure by hand.", "ok"); renderBuild(); return; }
    if (org.production_allowed) {
      const typed = window.prompt(`This org is NOT a sandbox. Type its name to build ${steps.length} step(s) in it:\n\n${org.name}`, "");
      if (!typed || typed.trim().toLowerCase() !== String(org.name || "").trim().toLowerCase()) {
        L.setStatus("build", "Apply cancelled — the org name didn't match.", "warn");
        return;
      }
    } else if (!window.confirm(`Run ${steps.length} build step(s) in ${org.allow_listed ? "test org" : "sandbox"} "${org.name}" (${org.id})?\n\n`
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
      const tile = (cls, label, value, sub) => `<div class="tile ${cls}"><p class="tile-label">${label}</p><div class="tile-value">${value}</div>${sub ? `<div class="tile-sub">${sub}</div>` : ""}</div>`;
      const finished = !!b.finishedAt;
      html += `<div class="tiles tiles-4">
        ${tile(finished ? (n("failed") + n("conflict") ? "err" : "ok") : "info", "Apply", finished ? (n("failed") + n("conflict") ? "Stopped" : "Done") : "Running…", `${esc(b.org.name)} · ${b.steps.length} step(s)`)}
        ${tile("ok", "Done", String(n("done") + n("skipped")), n("skipped") ? `${n("skipped")} skipped` : "created in the org")}
        ${tile(n("failed") + n("conflict") ? "err" : "", "Failed / conflict", String(n("failed") + n("conflict")), "stops the run until retried or skipped")}
        ${tile("", "Waiting", String(n("waiting") + n("running")), "modules → fields → roles → rules")}
      </div>`;
      html += `<section class="card"><div class="card-head"><h3>Apply <span class="muted">· live progress, stops on the first problem</span></h3>
          <button class="ghost small" type="button" data-step-act="log">Download run log</button></div>
        <div class="table-wrap"><table class="lo-table"><thead><tr><th class="col-narrow">#</th><th>What</th><th>API call</th><th>Status</th></tr></thead><tbody>`
        + b.steps.map((st, i) => {
          const stuck = st.status === "failed" || st.status === "conflict";
          const f = findItem(st.id);
          const actions = stuck ? `<div class="cell-actions" style="margin-top:6px">
              <button class="ghost small" type="button" data-step-act="retry" data-i="${i}">Retry</button>
              ${st.status === "conflict" && f && f.section === "fields" ? `<button class="ghost small" type="button" data-step-act="newfield" data-i="${i}">Create new field instead</button>` : ""}
              <button class="ghost small" type="button" data-step-act="skip" data-i="${i}">Skip</button></div>` : "";
          return `<tr class="${stuck ? "is-err" : ""}"><td class="muted">${i + 1}</td>
            <td><strong>${esc(st.label)}</strong><div class="muted">${esc(st.id)}${f && (f.item.req_ids || []).length ? " · " + esc(f.item.req_ids.join(", ")) : ""}</div>
              ${st.detail ? `<div class="${stuck ? "impl-err-text" : "muted"}">${esc(st.detail)}</div>` : ""}</td>
            <td><code>${esc(st.method + " " + st.path.replace(/^\/crm\/v\d+/, ""))}</code>${st.calls > 1 ? `<div class="muted">+ ${st.calls - 1} action call(s) first</div>` : ""}</td>
            <td>${pill("step-" + st.status, st.status)}${actions}</td></tr>`;
        }).join("")
        + `</tbody></table></div></section>`;
    }
    html += rpaQueueHtml(queue);
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
    renderVerify();      // "confirmed by hand" feeds the configuration check
    refreshButtons();
  });

  // ---- Verify: configuration check, UAT run, hand-off ----
  async function verify() {
    L.setStatus("verify", "Reading the sandbox back…");
    try {
      impl.verify = await post("/implement/verify", { spec: impl.spec }, 90_000, "Verify");
      save();
      renderVerify();
      render();
      const c = verifyCounts();
      L.setStatus("verify", `${c.match} match, ${c.differ} differ, ${c.missing} missing, ${c.byHand} to confirm by hand.`,
        (c.differ || c.missing) ? "warn" : "ok");
    } catch (e) {
      L.setStatus("verify", "Verify failed: " + e.message, "err");
    }
  }

  // The verdict shown: the server's, adjusted for what happened in the build and by hand.
  function effectiveVerdict(r) {
    const step = impl.build && impl.build.steps.find((s) => s.id === r.id);
    if (step && step.status === "skipped") return "skipped";
    if (r.verdict === "unverifiable") {
      const f = findItem(r.id);
      if (f && f.item.manual_done) return "confirmed";
    }
    return r.verdict;
  }

  function verifyCounts() {
    const c = { match: 0, differ: 0, missing: 0, skipped: 0, byHand: 0 };
    ((impl.verify && impl.verify.results) || []).forEach((r) => {
      const v = effectiveVerdict(r);
      if (v === "present" || v === "confirmed") c.match++;
      else if (v === "mismatch") c.differ++;
      else if (v === "missing") c.missing++;
      else if (v === "skipped") c.skipped++;
      else c.byHand++;
    });
    return c;
  }

  function checksOk() {
    if (!impl.verify) return false;
    const c = verifyCounts();
    return c.differ === 0 && c.missing === 0 && c.byHand === 0;
  }

  function uatCounts() {
    const c = { passed: 0, failed: 0, notRun: 0 };
    ((impl.uat && impl.uat.cases) || []).forEach((tc) => {
      const s = (tc.execution_result || {}).status;
      if (s === "pass") c.passed++; else if (s === "fail") c.failed++; else c.notRun++;
    });
    return c;
  }

  function uatOk() {
    if (!impl.uat || !impl.uat.live) return false;
    const c = uatCounts();
    return c.passed > 0 && c.failed === 0;
  }

  function verifyOrg() {
    return (impl.verify && impl.verify.org) || (impl.build && impl.build.org) || (impl.dryRun && impl.dryRun.org) || null;
  }

  const VERDICT_LABEL = { present: "Matches", confirmed: "Confirmed by hand", mismatch: "Differs", missing: "Missing",
    skipped: "Skipped during build", unverifiable: "Check by hand" };

  function renderVerify() {
    if (!impl.verify && !impl.uat) { els.verifyArea.innerHTML = ""; return; }
    let html = "";
    const org = verifyOrg();
    const tile = (cls, label, value, sub) => `<div class="tile ${cls}"><p class="tile-label">${label}</p><div class="tile-value">${value}</div>${sub ? `<div class="tile-sub">${sub}</div>` : ""}</div>`;
    const c = impl.verify ? verifyCounts() : null;
    const u = uatCounts();
    const total = u.passed + u.failed + u.notRun;
    const ready = checksOk() && uatOk();
    const why = !impl.verify ? "Check the org first." : !checksOk() ? "Every check must match or be confirmed by hand."
      : !impl.uat ? "Run the UAT cases first." : !impl.uat.live ? "Run the UAT cases against the org (not a dry run)."
      : !uatOk() ? "No UAT case may fail." : "";
    const reqIds = new Set(); (impl.uat ? impl.uat.cases : []).forEach((tc) => (tc.req_ids || []).forEach((r) => reqIds.add(r)));
    const covered = [...reqIds].filter((r) => (impl.uat.cases || []).some((tc) => (tc.req_ids || []).includes(r) && (tc.execution_result || {}).status === "pass")).length;

    html += `<div class="tiles tiles-4">
      ${c ? tile(c.match + (c.byHand || 0) === (impl.verify.results || []).length ? "ok" : "warn", "Blueprint checks",
          `${c.match} <span class="unit">/ ${(impl.verify.results || []).length} match</span>`,
          [c.differ ? `${c.differ} differ` : "", c.missing ? `${c.missing} missing` : "", c.byHand ? `${c.byHand} to confirm by hand` : "", c.skipped ? `${c.skipped} skipped` : ""].filter(Boolean).join(" · ") || "everything matches")
        : tile("", "Blueprint checks", "—", "not checked yet")}
      ${impl.uat ? tile(u.failed ? "err" : u.notRun ? "warn" : "ok", "UAT cases", `${u.passed} <span class="unit">/ ${total} passed</span>`,
          [u.failed ? `${u.failed} failed` : "", u.notRun ? `${u.notRun} not run` : "", impl.uat.live ? "" : "dry run"].filter(Boolean).join(" · ") || "all passed")
        : tile("", "UAT cases", "—", "not written yet")}
      ${impl.uat ? tile(covered === reqIds.size ? "ok" : "warn", "Requirements", `${covered} <span class="unit">/ ${reqIds.size}</span>`, "each covered by ≥ 1 passing case") : tile("", "Requirements", "—", "")}
      ${tile(ready ? "ok" : "warn", "Promotion", ready ? "Ready" : "Locked", ready ? "package can be handed over" : esc(why))}
    </div>`;

    if (impl.verify) {
      const rows = (impl.verify.results || []).map((r) => ({ r, v: effectiveVerdict(r) }));
      const order = { mismatch: 0, missing: 1, unverifiable: 2, skipped: 3, confirmed: 4, present: 5 };
      rows.sort((a, b) => (order[a.v] ?? 9) - (order[b.v] ?? 9));
      const rowHtml = ({ r, v }) => {
        const f = findItem(r.id);
        let action = "";
        if ((v === "missing" || v === "mismatch") && r.fixable && org && org.sandbox) {
          action = `<button class="ghost small" type="button" data-fix="${esc(r.id)}">Fix in org</button>`;
        } else if (v === "mismatch" && !r.fixable) {
          action = `<span class="muted">Can't change a field's type — create a new field in Build</span>`;
        } else if (v === "unverifiable") {
          action = `<span class="muted">Tick it in the UI work queue once checked</span>`;
        } else if (v === "confirmed" && f) {
          action = `<span class="impl-done-by">Confirmed by ${esc(f.item.manual_done.by)}</span>`;
        } else action = `<span class="muted">—</span>`;
        return `<tr class="${v === "mismatch" || v === "missing" ? "is-err" : ""}">
          <td><strong>${esc(r.id)}</strong></td>
          <td class="cell-main">${esc(r.label)}${f && (f.item.req_ids || []).length ? `<div class="muted" style="font-weight:400">${(f.item.req_ids).map((x) => `<span class="impl-req">${esc(x)}</span>`).join("")}</div>` : ""}</td>
          <td>${esc(r.expected || "")}</td>
          <td class="${v === "mismatch" || v === "missing" ? "impl-err-text" : ""}">${esc(r.actual || "") || `<span class="muted">${esc(r.detail || "—")}</span>`}${r.actual && r.detail && v !== "present" ? `<div class="muted">${esc(r.detail)}</div>` : ""}</td>
          <td>${pill("ver-" + v, VERDICT_LABEL[v] || v)}</td>
          <td>${action}</td></tr>`;
      };
      html += `<section class="card"><div class="card-head"><h3>Org vs blueprint</h3>
          <div class="impl-toolbar-actions"><span class="muted">${esc(org ? org.name : "")} read back${impl.verify.at ? " " + esc(impl.verify.at) : ""}</span>
          <button class="ghost small" type="button" data-verify-act="recheck">Check again</button></div></div>
        <div class="table-wrap"><table class="lo-table"><thead><tr><th class="col-narrow">ID</th><th>Item</th><th>Expected</th><th>Found in org</th><th>Result</th><th>Action</th></tr></thead>
        <tbody>${rows.map(rowHtml).join("")}</tbody></table></div>
        ${(impl.verify.warnings || []).length ? `<p class="status warn" style="margin:0 18px 12px">${esc(impl.verify.warnings.join(" • "))}</p>` : ""}
      </section>`;
    }

    // UAT cases, written from the same blueprint and run against the org.
    const canRunLive = !!(org && org.sandbox);
    html += `<section class="card"><div class="card-head"><h3>UAT cases <span class="muted">· written from the blueprint, run against the org</span></h3>
        <div class="impl-toolbar-actions">
          ${impl.uat ? `<span class="pill ok">${u.passed} pass</span>${u.failed ? `<span class="pill err">${u.failed} fail</span>` : ""}${u.notRun ? `<span class="pill">${u.notRun} not run</span>` : ""}` : ""}
          ${impl.uat ? `<button class="ghost small" type="button" data-verify-act="uat-download">Download cases</button>` : ""}
          <button class="${impl.uat ? "ghost" : "primary"} small" type="button" data-verify-act="uat-run" ${impl.spec && impl.spec.approved ? "" : "disabled title=\"Approve the blueprint first\""}>
            ${impl.uat ? "Run all cases again" : canRunLive ? "Write and run cases" : "Write cases and dry-run them"}</button>
        </div></div>`;
    if (impl.uat) {
      html += `<div class="table-wrap"><table class="lo-table"><thead><tr><th class="col-narrow">ID</th><th>Case</th><th>Observed</th><th>Result</th><th>Req</th></tr></thead><tbody>`
        + impl.uat.cases.slice().sort((a, b) => rank(a) - rank(b)).map((tc, i) => {
          const er = tc.execution_result || {};
          const s = er.status === "pass" ? "passed" : er.status === "fail" ? "failed" : "not run";
          const why = er.status === "fail" ? (er.first_failure || er.reason || "")
            : er.status === "dry_run" ? (er.plan_ok ? "plan looks runnable" : er.first_issue || "plan needs fixes") : er.reason || "";
          return `<tr class="${s === "failed" ? "is-err" : ""}"><td><strong>${esc(tc.id || "UAT-" + (i + 1))}</strong></td><td class="cell-main">${esc(tc.title)}</td>
            <td class="${s === "failed" ? "impl-err-text" : "muted"}">${esc(typeof why === "string" ? why : JSON.stringify(why)) || (s === "passed" ? "as expected" : "—")}</td>
            <td>${pill("uat-" + s.replace(" ", "_"), s)}</td><td>${(tc.req_ids || []).map((id) => `<span class="impl-req">${esc(id)}</span>`).join("") || `<span class="muted">—</span>`}</td></tr>`;
        }).join("") + "</tbody></table></div>";
    } else {
      html += `<p class="muted" style="padding:14px 18px;margin:0">Cases are written from the approved blueprint's requirements and acceptance criteria, then run against ${esc(org ? org.name : "the org")}.</p>`;
    }
    html += "</section>";

    // Hand-off: Projects, and promotion as a package for Zoho's sandbox deployment.
    html += `<section class="card impl-handoff"><div><h3>Hand off</h3>
        <p class="muted">Push cases and failures to Zoho Projects for the team. Promotion hands over a package for Zoho's
          sandbox deployment; LiftOff never writes to production.</p>
        ${why ? `<p class="muted" style="color:var(--warn)"><strong>Promote is locked:</strong> ${esc(why)}</p>` : ""}</div>
        <div class="impl-toolbar-actions">
          <button class="ghost" type="button" data-verify-act="uat-push" ${impl.uat ? "" : "disabled"}>Push to Zoho Projects</button>
          <button class="primary" type="button" data-verify-act="promote" ${ready ? "" : "disabled"}>Promote to production</button>
        </div>
        <div id="impl-promote" hidden></div></section>`;
    els.verifyArea.innerHTML = html;
  }

  function rank(tc) {
    const s = (tc.execution_result || {}).status;
    return s === "fail" ? 0 : s === "pass" ? 2 : 1;
  }

  function scopeModules() {
    const modules = [];
    (impl.spec.modules_in_scope || []).forEach((m) => { if (!modules.includes(m)) modules.push(m); });
    (impl.spec.modules || []).forEach((m) => { if (m.include !== false && !modules.includes(m.api_name)) modules.push(m.api_name); });
    (impl.spec.fields || []).forEach((f) => { if (!modules.includes(f.module)) modules.push(f.module); });
    return modules.slice(0, 3);   // /generate covers up to 3 modules
  }

  async function runUat() {
    const modules = scopeModules();
    if (!modules.length) { L.setStatus("verify", "The blueprint names no modules to test.", "warn"); return; }
    const org = verifyOrg();
    const live = !!(org && org.sandbox);   // UAT creates and deletes records: only ever live on a sandbox
    try {
      // Background jobs on impl_worker (short requests when jobs aren't set up); progress shows here.
      L.setStatus("verify", "Writing UAT cases from the blueprint…");
      const gen = await L.generateCasesStaged({ brd: specToBrd(), modules, platform: "crm" },
        (text) => L.setStatus("verify", text));
      const cases = gen.cases || [];
      if (!cases.length) throw new Error((gen.warnings || [])[0] || "No cases were written.");
      const where = live ? "against the sandbox" : "as a dry run";
      L.setStatus("verify", `Running ${cases.length} case(s) ${where}…`);
      const ex = await L.executeBatched(cases, { platform: "crm", dry_run: !live },
        (text) => L.setStatus("verify", `${text} (${where})`));
      impl.uat = { cases: ex.cases || cases, live: live && !ex.dry_run, ranAt: new Date().toISOString(), modules };
      save();
      renderVerify();
      refreshButtons();
      const c = uatCounts();
      L.setStatus("verify", impl.uat.live ? `UAT: ${c.passed} passed, ${c.failed} failed, ${c.notRun} not run.`
        : "UAT dry run: plans checked, nothing sent. Connect a sandbox org to run them.", c.failed ? "warn" : "ok");
    } catch (e) {
      L.setStatus("verify", "UAT run failed: " + e.message, "err");
    }
  }

  async function fixInSandbox(id) {
    const org = verifyOrg();
    if (!org || !org.sandbox) return;
    if (!impl.spec.approved) { L.setStatus("verify", "Approve the blueprint before changing the sandbox.", "warn"); return; }
    L.setStatus("verify", `Fixing ${id} in the sandbox…`);
    try {
      const data = await post("/implement/apply", { spec: impl.spec, dry_run: false, confirm_org_id: org.id, item_ids: [id] },
        90_000, "Fix");
      const r = (data.results || [])[0] || {};
      if (!["created", "updated", "skipped"].includes(r.result)) throw new Error(r.detail || r.result || "no result");
      await verify();
    } catch (e) {
      L.setStatus("verify", `Couldn't fix ${id}: ${e.message}`, "err");
    }
  }

  async function pushUat() {
    const base = (L.state.fileName || "BRD").replace(/\.[^.]+$/, "");
    const projectName = `UAT — ${base} — ${new Date().toISOString().slice(0, 10)}`;
    L.setStatus("verify", `Pushing ${impl.uat.cases.length} case(s) to Zoho Projects…`);
    try {
      const data = await post("/push", { cases: impl.uat.cases, project_name: projectName }, 120_000, "Push");
      L.setStatus("verify", `${data.tasks_created || 0} task(s) and ${data.bugs_created || 0} bug(s) created`
        + (data.project_name ? ` in "${data.project_name}"` : "") + (data.mock ? " (mock)" : "") + ".",
        data.tasks_failed || data.bugs_failed ? "warn" : "ok");
    } catch (e) {
      L.setStatus("verify", "Push failed: " + e.message, "err");
    }
  }

  // Promotion package: what was built and proven in the sandbox, for Zoho's own deployment.
  function promotionPackage() {
    const org = verifyOrg();
    const built = [];
    for (const [s, label] of SECTIONS) {
      if (s === "plan" || s === "open_questions") continue;
      (impl.spec[s] || []).filter((o) => o.include !== false).forEach((o) => built.push({ section: s, group: label, item: o }));
    }
    const u = uatCounts();
    const json = {
      kind: "liftoff-promotion-package", generated_at: new Date().toISOString(),
      sandbox: org, approved: !!impl.spec.approved,
      components: built.map((b) => ({ id: b.item.id, section: b.section, what: describe(b.section, b.item),
        executor: b.item.executor, req_ids: b.item.req_ids || [], manual_done: b.item.manual_done || null })),
      decisions: (impl.spec.open_questions || []).filter((q) => (q.answer || "").trim()).map((q) => ({ question: q.question, answer: q.answer })),
      verify: (impl.verify.results || []).map((r) => ({ id: r.id, verdict: effectiveVerdict(r), expected: r.expected, actual: r.actual })),
      build_log: impl.build ? impl.build.log : [],
      uat: { ran_at: impl.uat.ranAt, passed: u.passed, failed: u.failed, not_run: u.notRun,
        cases: impl.uat.cases.map((tc) => ({ title: tc.title, req_ids: tc.req_ids || [], status: (tc.execution_result || {}).status || "not_run" })) },
    };
    const groups = {};
    built.forEach((b) => { (groups[b.group] = groups[b.group] || []).push(b.item); });
    let md = `# Promotion hand-off\n\nSandbox: ${org ? `${org.name} (${org.id})` : "—"} · prepared ${json.generated_at.slice(0, 10)}\n\n`
      + `All ${(impl.verify.results || []).length} configuration checks match the blueprint and UAT passed `
      + `(${u.passed} passed, ${u.failed} failed, ${u.notRun} not run).\n\n`
      + "## Deploy\n\n1. In the **sandbox**, open Zoho CRM's sandbox deployment and select the components below.\n"
      + "2. Deploy them to **production** and review Zoho's deployment report.\n"
      + "3. Repeat the hand-configured items marked *(by hand)* if the deployment doesn't carry them.\n"
      + "4. Smoke-test the P0 UAT cases in production with test records, then delete those records.\n\n## Components\n\n";
    Object.entries(groups).forEach(([g, items]) => {
      md += `### ${g}\n\n` + items.map((o) => `- [ ] ${describe(sectionOf(o), o)}${o.executor === "rpa" || o.executor === "partial" ? " *(by hand)*" : ""}`
        + ((o.req_ids || []).length ? ` — ${o.req_ids.join(", ")}` : "")).join("\n") + "\n\n";
    });
    if (json.decisions.length) md += "## Client decisions\n\n" + json.decisions.map((d) => `- ${d.question} → ${d.answer}`).join("\n") + "\n\n";
    md += "## Evidence\n\nSee the attached promotion-package.json for the verify results, build log and UAT results.\n";
    return { json, md };
  }

  function sectionOf(item) {
    for (const [s] of SECTIONS) if ((impl.spec[s] || []).includes(item)) return s;
    return "";
  }

  els.verifyArea.addEventListener("click", (e) => {
    const fix = e.target.closest("button[data-fix]");
    if (fix) return fixInSandbox(fix.dataset.fix);
    const t = e.target.closest("button[data-verify-act]");
    if (!t) return;
    const act = t.dataset.verifyAct;
    if (act === "recheck") return verify();
    if (act === "uat-run") return runUat();
    if (act === "uat-push") return pushUat();
    if (act === "uat-download") return download("uat-cases.json", impl.uat);
    if (act === "promote") {
      if (!(checksOk() && uatOk())) return;
      const pkg = promotionPackage();
      const box = document.getElementById("impl-promote");
      box.hidden = false;
      box.innerHTML = `<p class="status ok">Ready to promote. Download the hand-off and the evidence, then deploy with Zoho CRM's sandbox deployment.</p>
        <div class="impl-toolbar-actions">
          <button class="primary small" type="button" data-pkg="md">Download hand-off (.md)</button>
          <button class="ghost small" type="button" data-pkg="json">Download evidence (.json)</button></div>`;
      box.querySelector('[data-pkg="md"]').addEventListener("click", () => download("promotion-handoff.md", pkg.md));
      box.querySelector('[data-pkg="json"]').addEventListener("click", () => download("promotion-package.json", pkg.json));
    }
  });

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
    L.setStatus("blueprint", `Pushing ${cases.length} task(s) to Zoho Projects…`);
    try {
      const data = await post("/push", { cases, project_name: projectName }, 120_000, "Push");
      const link = data.project_url ? ` <a href="${esc(data.project_url)}" target="_blank" rel="noopener">Open project</a>` : "";
      const el = document.getElementById("status-blueprint");
      el.className = "status " + (data.tasks_failed ? "warn" : "ok");
      el.innerHTML = esc(`${data.tasks_created || 0} task(s) created${data.tasks_failed ? `, ${data.tasks_failed} failed` : ""}`
        + (data.project_name ? ` in "${data.project_name}"` : "") + (data.mock ? " (mock)" : "") + ".") + link;
    } catch (e) {
      L.setStatus("blueprint", "Push failed: " + e.message, "err");
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
        L.setStatus("blueprint", `${id} updated.`, "ok");
      } catch (err) {
        L.setStatus("blueprint", `${id}: not valid JSON — ${err.message}`, "err");
      }
    }
  });
  els.approve.addEventListener("change", () => {
    if (els.approve.checked && blockingOpen().length) { els.approve.checked = false; return; }
    setApproved(els.approve.checked);
    L.setStatus("blueprint", els.approve.checked ? "Approved. Continue to Build for a dry run." : "", "ok");
  });
  els.back.addEventListener("click", () => L.showTab("input"));
  els.toBuild.addEventListener("click", () => L.showTab("build"));
  els.toVerify.addEventListener("click", () => { L.showTab("verify"); if (!impl.verify) verify(); });
  els.backBlueprint.addEventListener("click", () => L.showTab("blueprint"));
  els.backBuild.addEventListener("click", () => L.showTab("build"));
  els.dry.addEventListener("click", dryRun);
  els.apply.addEventListener("click", apply);
  els.verify.addEventListener("click", verify);
  els.pushPlan.addEventListener("click", pushPlan);
  els.download.addEventListener("click", () => download("implementation-blueprint.json", Object.assign({}, impl.meta, { spec: impl.spec })));
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
      impl.spec = null; impl.meta = null; impl.dryRun = null; impl.applied = null; impl.verify = null; impl.build = null; impl.uat = null;
      try { localStorage.removeItem(STORAGE_KEY); } catch (e) {}
      els.run.innerHTML = "";
      els.verifyArea.innerHTML = "";
      ["blueprint", "build", "verify"].forEach((t) => { L.setStatus(t, ""); L.setTabState(t, { locked: true, done: false }); });
      render();
    },
  };

  load();
  if (impl.spec) {
    if (impl.build) renderBuild();
    else if (impl.dryRun) renderRun("Dry run — nothing was changed", impl.dryRun, "apply");
    renderVerify();
    render();
  } else {
    refreshButtons();
  }
})();
