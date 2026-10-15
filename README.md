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
│           ├── GenerateServlet.java    # POST /generate  — UAT cases per module
│           ├── ExecuteServlet.java     # POST /execute   — run execution_plan against CRM
│           ├── CrmExecutor.java        #   step runner + assertions
│           ├── PushServlet.java        # POST /push      — cases to Zoho Projects tasks
│           ├── ReportBugServlet.java   # POST /report-bug — failure to Projects bug
│           ├── Modules/Functions/Creator servlets   # CRM + Creator metadata
│           ├── Crm*Servlet.java        # /crm/* OAuth connect, callback, status, orgs
│           ├── ClaudeClient.java       # LLM client (GitHub Models, OpenAI-compatible)
│           ├── ZiaClient.java          # Zia LLM client (placeholder contract)
│           ├── MockLlmClient.java      # offline mock provider
│           └── ProjectsClient.java, BugsClient.java, RunLogger.java, ...
├── client/                              # Catalyst Web Client (static): index.html, app.js, styles.css
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
| `LLM_PROVIDER` | optional | `zia` or `mock`; any other value (or unset) uses GitHub Models |
| `GITHUB_TOKEN` | default provider | Token for GitHub Models |
| `COPILOT_MODEL` | optional | GitHub Models model ID, default `gpt-4o` |
| `COPILOT_ENDPOINT` | optional | Override the inference endpoint |
| `ZIA_ENDPOINT`, `ZIA_OAUTH_TOKEN` | Zia mode | When `LLM_PROVIDER=zia` |
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

| Variable | Default | What it controls |
|---|---|---|
| `GEN_CHUNK_CHARS` | `9000` | Max characters of BRD per call |
| `GEN_MAX_CHUNKS` | `6` | Max chunks per run; extra sections are reported, not dropped silently |
| `MAX_LLM_CALLS` | `8` | Call budget per `/generate` request |
| `CASES_PER_CALL` | `8` | Case budget per call |
| `TARGET_CASES_PER_MODULE` | `30` | Used to decide how many focused passes each chunk gets |
| `GEN_CONCURRENCY` | `2` | Parallel LLM calls (GitHub Models allows ~2 concurrent on most plans) |
| `GEN_DEADLINE_SEC` | `140` | Stop and return partial results after this long (client waits 180s) |
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

### `POST /server/uat_generator/push`

```json
{
  "portal_id": "60012345678",
  "project_id": "78900099112233",
  "cases": [ /* same shape as generate output */ ]
}
```

Creates one Projects task per case. Returns per-case results.

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

- The default LLM provider is GitHub Models (`ClaudeClient.java`, name kept for
  history). Swap to Zia by setting `LLM_PROVIDER=zia` and adjusting
  `ZiaClient.java` to the actual Zia endpoint contract (the current call shape
  is a placeholder).
- Projects API uses the v3 REST endpoints (`projectsapi.zoho.com`). If you need
  the older `projectsapi.zoho.com/api/v2` shape or DC-specific hosts, change
  `ZOHO_PROJECTS_API_BASE`.
- For QEngine push instead of Projects, add a `QEngineClient.java` mirroring
  `ProjectsClient.java` and a `target` field in the request body to route.
