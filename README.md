# UAT Script Generator (Catalyst demo)

Generates UAT test cases from a BRD + CRM/Desk module context, and pushes them to
Zoho Projects as tasks. Java AdvancedIO function on Catalyst + a static web client.

## Layout

```
.
├── catalyst.json
├── functions/
│   └── uat_generator/                  # Java AdvancedIO function
│       ├── catalyst-config.json        # main: GenerateServlet (default package)
│       ├── GenerateServlet.java        # Catalyst entry point, routes to servlets below
│       ├── pom.xml
│       └── src/main/java/com/uat/generator/
│           ├── AnalyzeServlet.java     # POST /analyze   — suggest modules for a BRD
│           ├── GenerateServlet.java    # POST /generate  — UAT cases per module (one request)
│           ├── GenerateStageServlet.java # POST /generate/stage — background job, or one LLM call per request
│           ├── UatJob.java             #   UAT generation/execution jobs on impl_worker (with ImplJob, JobQueue, JobStore)
│           ├── ImplementServlet.java   # POST /implement — BRD → implementation spec
│           ├── ImplementApplyServlet.java  # POST /implement/apply  — dry run / create in CRM
│           ├── ImplementVerifyServlet.java # POST /implement/verify — org vs spec diff
│           ├── ImplSpec, ImplPrompts, ImplOrg, ImplPlan, ImplRpa, MockImplClient  # spec helpers
│           ├── ExecuteServlet.java     # POST /execute   — run execution_plan against CRM
│           ├── CrmExecutor.java        #   step runner + assertions
│           ├── PushServlet.java        # POST /push      — cases to Zoho Projects tasks
│           ├── ReportBugServlet.java   # POST /report-bug — failure to Projects bug
│           ├── Modules/Functions/Creator servlets   # CRM + Creator metadata
│           ├── Crm*Servlet.java        # /crm/* OAuth connect, callback, status, orgs
│           ├── ClaudeClient.java       # LLM client (GitHub Models, OpenAI-compatible)
│           ├── Llm.java                # picks the LLM provider for every generation call
│           ├── ZiaAgentClient.java     # Zia Agent provider (Agent API, see agent/ZIA_GENERATOR.md)
│           ├── MockLlmClient.java      # offline mock provider
│           └── ProjectsClient.java, BugsClient.java, RunLogger.java, ...
├── client/                              # Catalyst Web Client (static): index.html, app.js, implement.js, styles.css
└── samples/                             # Demo BRDs + golden output JSON
```

## Prerequisites

- Catalyst CLI (`npm i -g zcatalyst-cli`)
- Java 11 + Maven
- A Catalyst project (`catalyst init` will populate `catalyst.json`)
- For the default LLM provider: a GitHub token with access to GitHub Models
- For Zoho Projects push: a self-client OAuth refresh token with
  `ZohoProjects.tasks.CREATE` scope

## First-time setup

```bash
cd "/Users/dinesh-160385/UAT Generator"

# Bind this folder to a Catalyst project (creates project IDs in catalyst.json)
catalyst init
# When prompted, pick: "Use existing project structure" so the layout above is kept.
```

## Function env vars

Configure these in the Catalyst console under
**Functions → uat_generator → Configuration → Environment Variables**.
Never commit any of these values to the repo.

| Variable | Required for | Notes |
|---|---|---|
| `SESSION_SECRET` | recommended | Long random string (e.g. `openssl rand -base64 48`). Encrypts CRM sign-in sessions into the cookie so they survive restarts and scaling. Without it, sessions are kept in memory and lost on restart. Changing it signs everyone out |
| `LIFTOFF_AGENT_ORG_IDS` | Zia Agent | CRM org IDs allowed to call `/agent/run`. Unset = agent access off |
| `LLM_PROVIDER` | optional | `zia` (Zia Agent), `github` (GitHub Models) or `mock`. Unset: the Zia Agent when its variables are set, else GitHub Models |
| `GITHUB_TOKEN` | GitHub Models | Token for GitHub Models, when not using the Zia Agent |
| `COPILOT_MODEL` | optional | GitHub Models model ID, default `gpt-4o` |
| `COPILOT_ENDPOINT` | optional | Override the inference endpoint |
| `ZIA_AGENT_ORG_ID`, `ZIA_AGENT_ID`, `ZIA_AGENT_VERSION_ID` | Zia Agent | From the agent's Integrate tab; the version ID changes on every redeploy. Setup: [agent/ZIA_GENERATOR.md](agent/ZIA_GENERATOR.md) |
| `ZIA_AGENT_REFRESH_TOKEN`, `ZIA_AGENT_CLIENT_ID`, `ZIA_AGENT_CLIENT_SECRET` | Zia Agent | Self Client refresh token with scope `ZiaAgents.agents.TRIGGER` |
| `ZIA_ACCOUNTS_BASE`, `ZIA_AGENT_URL`, `ZIA_AGENT_TIMEOUT_SEC` | optional | Accounts host (default from `ZOHO_DC`), Agent API endpoint, per-call timeout (120 s) |
| `ZOHO_CLIENT_ID`, `ZOHO_CLIENT_SECRET` | CRM connect, Projects push | OAuth client from api-console.zoho.com |
| `ZOHO_REDIRECT_URI` | CRM connect | Must match the OAuth client's redirect URI |
| `ZOHO_REFRESH_TOKEN` | Projects push | Scope: `ZohoProjects.tasks.CREATE` |
| `ZOHO_DC` | optional | Data center (e.g. `in`, `com`, `eu`) |
| `ZOHO_ACCOUNTS_BASE` | optional | Override the accounts host |
| `ZOHO_CONNECTION_NAME` | optional | Catalyst connection used for auth |
| `ZOHO_PROJECTS_PORTAL_ID`, `ZOHO_PROJECTS_PROJECT_ID` | optional | Fallbacks when the client doesn't send them |
| `CATALYST_RUNS_TABLE` | optional | Datastore table for run logs (default `uat_runs`) |

### Generation tuning (all optional)

Generation splits the BRD into section-aligned chunks and makes several small LLM
calls instead of one large one, so responses fit the provider's output limit and
nothing is silently truncated. Anything skipped is returned in `warnings`.

Catalyst stops an Advanced I/O request at about 30 seconds (`EXECUTION_TIME_EXCEEDED`,
HTTP 408), and one Zia Agent reply can take longer than that, so the app never generates
in a web request: the Cases and Verify tabs run generation as a background job, falling back
to `POST /generate/stage` (one LLM call per request) when jobs aren't set up; see
[Background jobs, staged generation and batched execution](#background-jobs-staged-generation-and-batched-execution).
`GEN_DEADLINE_SEC` applies only to one-shot `/generate` callers; `GEN_CONCURRENCY` to
one-shot calls and background jobs.

| Variable | Default | What it controls |
|---|---|---|
| `GEN_CHUNK_CHARS` | `9000` | Max characters of BRD per call |
| `GEN_MAX_CHUNKS` | `6` | Max chunks per run; extra sections are reported, not dropped silently |
| `MAX_LLM_CALLS` | `8` | Call budget per run (one-shot or staged) |
| `CASES_PER_CALL` | `8` | Case budget per call |
| `TARGET_CASES_PER_MODULE` | `30` | Used to decide how many focused passes each chunk gets |
| `GEN_CONCURRENCY` | `2` | Parallel LLM calls (GitHub Models allows ~2 concurrent on most plans) |
| `GEN_DEADLINE_SEC` | `140` | One-shot `/generate` only: stop and return partial results after this long |
| `GEN_CALL_DEADLINE_SEC` | `26` | Fallback only: time for one part's call (no in-request retry; the browser retries once with fewer cases) |
| `EXEC_BATCH_SEC` | `22` | `/execute` with `"batch": true`: stop starting new cases after this long |
| `LLM_MAX_TOKENS` | `4000` | Output tokens per call |

## Catalyst Datastore: `uat_runs` table

Each `/generate` call appends a row to a Datastore table for analytics. Create
the table once in **Catalyst console → Cloud Scale → Data Store → Create Table**
with the following columns:

| Column | Type | Notes |
|---|---|---|
| `ROWID` | Bigint | Auto, primary key (Catalyst default) |
| `created_at` | DateTime | UTC ISO-8601 |
| `module` | Varchar (64) | e.g. `crm.lead` |
| `provider` | Varchar (32) | `claude` or `zia` |
| `brd_length` | Int | Char count of the BRD |
| `case_count` | Int | 0 on failures |
| `status` | Varchar (16) | `generated` \| `partial` \| `failed` |
| `error` | Text | nullable; first 2KB of the exception message |

The `RunLogger` writes asynchronously and swallows logging errors — a Datastore
outage will not break the user-facing request. Logging is skipped when the
function lacks Catalyst context (e.g. running outside `catalyst serve`).

## Build & deploy

```bash
# Build the function .war
cd functions/uat_generator
mvn clean package
cd ../..

# Deploy everything (function + client)
catalyst deploy

# Or deploy individually
catalyst deploy --only functions
catalyst deploy --only client
```

After deploy, the web client is served from your Catalyst app domain, and the
function is reachable at `/server/uat_generator/{generate,push,health}`.

## Setup work on Zoho RPA

Items the API can't create carry a machine-readable recipe. Build → Needs you → **Send to Zoho RPA**
creates a job (`POST /implement/rpa-job`), pushes it to `RPA_WEBHOOK_URL` (a Zoho Flow webhook that
runs the bot; optional `X-LiftOff-Token: RPA_WEBHOOK_TOKEN`) or downloads it, and the bot reports per
item to `/implement/rpa-result` with the job's token; the browser polls `/implement/rpa-status` and
ticks items done. The bot spec, op by op, is in [docs/ZOHO_RPA_RUNNER.md](docs/ZOHO_RPA_RUNNER.md).
The Playwright runner remains as the no-licence alternative. Set `PUBLIC_FUNCTION_BASE` when the
function's public URL differs from the app's origin.

## Self-check

`functions/uat_generator/selfcheck.sh` compiles the function and runs `SelfCheck` (no test framework
needed): agent-JSON parsing and normalisation, the plain-language → API rule structurer, lanes vs. the
capability matrix for every product adapter, the derived execution plan, duplicate-function exclusion,
failure diagnostics, the runner's recipe parsers and the Desk field mapping. Run it before a deploy;
it exits non-zero on the first regression. See [docs/ADDING_A_PRODUCT.md](docs/ADDING_A_PRODUCT.md)
for the product adapter contract.

## Local dev

```bash
# Run the function locally
cd functions/uat_generator
mvn clean package
catalyst serve

# In another terminal, serve the client (catalyst serve also handles this)
catalyst serve --only client
```

The client calls `/server/uat_generator/...` relative paths, so it works the same
locally and in production.

## API

### `POST /server/uat_generator/generate`

```json
{
  "brd": "Full BRD text here...",
  "module": "crm.lead",
  "project_key": "ACME-UAT"   // optional, embedded into projects_payload
}
```

Response (abridged):

```json
{
  "modules": ["Leads"],
  "provider": "GitHub Models (gpt-4o)",
  "counts": { "Leads": 24 },
  "total": 24,
  "chunks": 1,
  "llm_calls": 3,
  "warnings": [],
  "schema_source": { "Leads": "live" },          // live | builtin | generic
  "requirements": [
    { "id": "FR-1", "title": "Region resolution", "part": 1, "derived": false, "cases": 6, "in_run": true }
  ],
  "coverage": { "requirements": 4, "covered": 4, "uncovered": [], "not_in_run": [], "untraced_cases": 0 },
  "cases": [
    {
      "title": "Create lead with required fields",
      "priority": "P0",
      "tags": ["create", "happy-path"],
      "gherkin": "Given I am a sales user\nWhen I create a lead...\nThen ...",
      "steps": [{ "action": "...", "expected": "..." }],
      "acceptance": "Lead is created and visible in list view."
    }
  ],
  "projects_payload": { "project_key": "ACME-UAT", "tasks": [ ... ] }
}
```

### Background jobs, staged generation and batched execution

Generation and execution run as background jobs on the `impl_worker` job function
(15 minutes per job; setup in [agent/ZIA_GENERATOR.md](agent/ZIA_GENERATOR.md#6-background-generation-one-time-setup)).
The browser queues a job, polls its status every few seconds, and shows each step: one per
planned LLM call for generation, one per case for execution. Hovering a step shows what it covers.
When background jobs aren't set up (no Cache, job pool or worker), the browser falls back to
short requests and adds a note saying so.

`POST /server/uat_generator/generate/stage`. Every stage is stateless (the BRD is re-chunked the
same way each time):

| Stage | Body | Returns | LLM calls |
|---|---|---|---|
| `start` | `brd`, `modules`, `platform`, `creator_owner`, `creator_app` | `calls: [{module, part, label, focus, wanted}]`, `schemas` (field schema text per module, live from CRM when connected), `requirements` (prompt list per part), `schema_source`, `warnings` | 0 |
| `submit` | `brd`, `modules`, `platform`…, `calls`, `schemas`, `requirements` | `{job_key}`: a `uat_generate` job, one step per call; 503 when jobs aren't set up | 0 here |
| `status` | `job_key` | `{state: queued\|running\|done\|failed, done, total, label, error?, steps: [{label, detail, state, seconds, items}]}` | 0 |
| `finish` | `brd`, `modules`, `job_key` **or** `results` (the parts), `warnings`, `schema_source`, `project_key` | the `/generate` response: merged and de-duplicated (module + title) cases, `counts`, `requirements`, `coverage`, `projects_payload` | 0 |
| `part` | `brd`, `module`, `part`, `focus`, `wanted`, `schema`, `requirements`, `retry?` | fallback only: `{module, part, focus, cases, notes, error?}` (always HTTP 200; a failure is in `error`) | 1 |

In the fallback the client runs two parts at a time and sends a failed part once more with
`"retry": true` (fewer cases); a part that still fails becomes a warning. `finish` re-validates
every case it gets back. `/generate` itself still runs the whole thing in one request for
one-shot callers (`/agent/run`, scripts, mock mode).

`POST /server/uat_generator/execute` takes the same body as before plus a `stage`:

| Stage | Body | Returns |
|---|---|---|
| `submit` | `cases`, `platform`, `dry_run` | `{job_key}`: a `uat_execute` job, one step per case; 409 `needs_auth` when not connected and not a dry run |
| `status` | `job_key` | as above; each step has `result` (`pass`, `fail`, `dry_run`, `skipped`) |
| `result` | `job_key` | the `/execute` response for the job's cases, in order |

The worker has no session cookie, so `submit` gives the job a CRM access token valid for at least
20 more minutes (never the refresh token); the worker removes it from the cached input when the
run ends.

Without a stage, `/execute` runs the cases in the request, as before. With `"batch": true` it stops
starting new cases after `EXEC_BATCH_SEC` (always running at least one); cases it didn't reach
come back with `execution_result.not_reached: true`, counted in `not_reached` rather than
`skipped`. The fallback sends 4 cases per request against CRM (40 for a dry run) and resends
unreached cases. A batch that fails marks its cases "not run" and the run continues (it stops
after two failed batches in a row).

### `POST /server/uat_generator/push`

```json
{
  "portal_id": "60012345678",
  "project_id": "78900099112233",
  "cases": [ /* same shape as generate output */ ]
}
```

Creates one Projects task per case. Returns per-case results.

## Implementation generator

Switch the Brief tab to **Implementation** to turn a BRD into an implementation blueprint instead of
test cases. The tabs become **Brief → Blueprint → Build → Verify**; each unlocks when the step
before it is ready (Build once the blueprint is approved, Verify once a dry run is done). The pipeline:

```
BRD ──► 1. Extract  POST /implement          implementation spec (JSON)
    ──► 2. Review   Blueprint tab            untick / edit items, answer open questions, approve
    ──► 3. Apply    Build tab                dry run, then ordered REST steps; UI-only items → Configure by hand
    ──► 4. Verify   Verify tab               read the sandbox back, diff against the blueprint
    ──► 5. UAT      in Verify                UAT cases from the approved blueprint, run on the sandbox
    ──► Promote     hand-off package         for Zoho's sandbox-to-production deployment
```

**Spec sections:** `modules`, `fields` (with picklists and lookups), `layouts`, `validation_rules`,
`workflows` (workflow, blueprint, assignment, approval, scoring, escalation, schedule, cadence,
webhook), `roles`, `functions` (Deluge), `open_questions`, `plan`. Every item carries
`id`, `req_ids`, `include`, `status` and `executor`. Status is checked against the connected org:
`new`, `exists` (unticked), `needs_update` (a picklist lacks values; they're added), `conflict`
(different type or lookup target; CRM can't change a type, so create a new field or skip) or
`not_compared` (org not read). Executors:

| executor | Items | Built by |
|---|---|---|
| `api` | custom modules, custom fields (standard types; required ones are then marked mandatory through the layouts API), picklist value additions, roles, workflow rules with field-update and task actions | `/implement/apply` via `/crm/v8/settings/*` |
| `rpa` (Manual) | layouts, validation rules (no create API), blueprints, approvals, assignment/scoring rules, date-based workflow rules, Deluge functions, formula/autonumber/multi-lookup fields | RPA bot or consultant, from the `rpa_queue` (click paths + values) |
| `partial` (Partly manual) | roles with module permissions; workflow rules that also need email, webhook or function actions | API creates it; the finishing steps go to the `rpa_queue` |
| `review` | open questions | consultant, before approval |
| `projects` | plan tasks | "Push plan to Projects" (`/push`) |

**Workflow rules** are built through the v8 API from the structured form the generator asks for
(`trigger_type`, `trigger_fields`, `conditions`, `match`, `field_updates`, `tasks`, `other_actions`).
A rule references its actions by id, so a build step makes one call per field update
(`/settings/automation/field_updates`) and per task (`/settings/automation/tasks`), then the rule
(`/settings/automation/workflow_rules`). Field and module ids come from the live org; a field that
doesn't exist yet stops the step. Verify checks rules by name (their actions aren't compared).
Request shapes follow Zoho's v8 docs (config-workflow, create-field-update, create-automation-task,
create-custom-field, update-custom-fields, update-custom-layout, create-custom-module-api, create-role).

**Safety:** `/implement/apply` is a dry run unless `dry_run: false`. A real run needs
`spec.approved: true` and `confirm_org_id` equal to the connected org, and it only writes to
**sandbox** orgs; production is read-only (dry runs still work there). Blocking open questions must be answered (or unticked) before the UI
lets you approve. Items already in the org are skipped. "Required" on a field is a layout setting
and is returned as a follow-up, not applied.

**Review** shows a summary (included, conflicts, need update, new, already in org, not compared;
share by API, partly manual, manual), filters (All, Needs attention, New, Already in org) and fields
grouped by module. Open questions can be copied for an email to the client; their answers are sent
back with **Apply answers and regenerate** (`answers` on `/implement`) and override the BRD.

**Build** runs the API items as ordered steps (modules, then fields, then roles), one call per
step (`item_ids`), with live Done / Running / Failed / Waiting counts. A run stops at the first
failure or conflict and offers Retry, Skip, or, for a type conflict, Create new field instead; the run
log downloads as JSON. Items the APIs can't do are listed under **Configure by hand** with their
click paths; tick each one off (who and when is kept), and items tied to an unanswered blocking
question show as blocked.

**Verify** reads the sandbox back and shows, per item, what the blueprint says and what the sandbox
has: `present`, `mismatch` (type, missing picklist values, lookup target), `missing`, or
`unverifiable` (validation rules, workflows and functions aren't read back through the APIs used here).
Missing items and missing picklist values have **Fix in sandbox**; an unverifiable item counts once
it's ticked in Configure by hand. **UAT cases** are then written from the approved blueprint
(a `uat_generate` job) and run against the sandbox (a `uat_execute` job; a dry run when the org isn't a sandbox), and
can be pushed to Zoho Projects with their failures.

**Promote to production** unlocks only when every check matches (or is confirmed by hand) and a live
UAT run has no failures. LiftOff never writes to production: promotion downloads a hand-off
(`promotion-handoff.md`: components to deploy with Zoho CRM's sandbox deployment, hand-configured
items, client decisions) and the evidence (`promotion-package.json`: verify results, build log, UAT
results).

Tunables: `IMPL_MAX_LLM_CALLS` (9: two calls per BRD part plus one for the plan),
`CRM_SETTINGS_API_VERSION` (`v8`), plus the shared `GEN_*` settings. `LLM_PROVIDER=mock` returns a
shallow offline spec. Generation uses the Zia Agent when it's configured (see [agent/ZIA_GENERATOR.md](agent/ZIA_GENERATOR.md)).

## Sessions and execution

- **Sessions.** A CRM sign-in is an AES-256-GCM sealed cookie (`tp_crm_sid`,
  HttpOnly, SameSite=Lax, Secure outside localhost) holding the refresh token and
  connection details, valid for 30 days. Nothing is stored server-side. With OAuth
  sign-in, the server's own client secret is not put in the cookie. Access tokens are
  cached per instance and refreshed about once an hour.
- **Status.** `/crm/status` reports `authorized` only for a CRM user session.
  Server-side Projects credentials appear separately as `projects_server`, and
  `persistent_sessions` says whether `SESSION_SECRET` is set.
- **Execution is never faked.** Without CRM credentials, `/execute` returns 409
  unless the request asks for `"dry_run": true`. A dry run sends nothing to CRM and
  checks each plan instead (HTTP methods, paths, request bodies, variables
  captured before use, assertions). Those cases get status `dry_run` with
  `plan_ok` and a list of issues, never pass/fail.

## Zia Agent

`POST /agent/run` lets a Zia Agent run UAT end to end: generate cases, dry-run or
execute them, and get back a compact summary (coverage per requirement, failures,
plan issues). It authenticates with the agent connection's Zoho CRM token, accepts
only orgs listed in `LIFTOFF_AGENT_ORG_IDS`, and is off until that is set. Setup,
the tool definition and the agent instructions are in [`agent/`](agent/README.md).

## Grounding and traceability

- **Live field metadata.** When the user has connected Zoho CRM, `/generate` reads
  each module's fields (`/crm/v3/settings/fields`) and gives the model the real api
  names, required fields, picklist values and lookups. Without a connection it falls
  back to the built-in templates in `ModuleSchemas`, and says so in `warnings`.
  Layout-level required fields and validation rules are not read yet.
- **Requirement IDs.** Requirements are taken from IDs in the BRD (`FR-1`, `BR-2`,
  `REQ-3`, `UC-`, `US-`, `AC-`, `NFR-` at the start of a line or heading). If the BRD
  has none, each substantive section heading becomes `REQ-01`, `REQ-02`, …
- **Coverage.** Every case carries `req_ids`. The UI shows a coverage table
  (requirement → cases → pass/fail) on the cases and execution steps, the CSV
  export has a Requirements column, and Projects tasks and bugs start with a
  `Requirements:` line.

## Try it with sample BRDs

The [`samples/`](samples/) folder has two ready-to-paste BRDs and a golden output:

- [`samples/brd-crm-lead-routing.md`](samples/brd-crm-lead-routing.md) — CRM Lead module
- [`samples/brd-desk-sla-escalation.md`](samples/brd-desk-sla-escalation.md) — Desk Ticket module
- [`samples/golden-output-crm-lead-routing.json`](samples/golden-output-crm-lead-routing.json) — reference shape

Open the web client, pick the matching module, paste the BRD body, and click
**Generate UAT cases**.

## Notes / caveats

- Generation uses a Zia Agent through Zoho's Agent API once its variables are set
  ([agent/ZIA_GENERATOR.md](agent/ZIA_GENERATOR.md)); otherwise GitHub Models
  (`ClaudeClient.java`, name kept for history). Both get the same prompts (`Llm.java`).
- Projects API uses the v3 REST endpoints (`projectsapi.zoho.com`). If you need
  the older `projectsapi.zoho.com/api/v2` shape or DC-specific hosts, change
  `ZOHO_PROJECTS_API_BASE`.
- For QEngine push instead of Projects, add a `QEngineClient.java` mirroring
  `ProjectsClient.java` and a `target` field in the request body to route.

## Building in a test org that isn't a Zoho sandbox

Build writes only to sandbox/developer orgs. To let it write to a dedicated test org, set
`IMPL_BUILD_ORG_IDS` on `uat_generator` to that org's CRM id or ZGID (comma-separated for several;
the Build tab's "Target org" tile shows both). `*` allows every org. To let Build write to any
connected org, production included, set `IMPL_ALLOW_PRODUCTION=1`; Apply then asks for the org
name to be typed before it runs. Environment variables are read at function start: redeploy
`uat_generator` (or restart it) after changing them.
