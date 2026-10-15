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
| `status` | Varchar (16) | `generated` \| `failed` |
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

Response:

```json
{
  "module": "crm.lead",
  "provider": "claude",
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
