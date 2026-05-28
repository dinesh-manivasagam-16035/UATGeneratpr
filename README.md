# UAT Script Generator (Catalyst demo)

Generates UAT test cases from a BRD + CRM/Desk module context, and pushes them to
Zoho Projects as tasks. Java AdvancedIO function on Catalyst + a static web client.

## Layout

```
.
├── catalyst.json
├── functions/
│   └── uat_generator/                  # Java AdvancedIO function
│       ├── catalyst-config.json
│       ├── pom.xml
│       └── src/main/
│           ├── java/com/uat/generator/
│           │   ├── GenerateServlet.java     # POST /generate
│           │   ├── PushServlet.java         # POST /push
│           │   ├── HealthServlet.java       # GET  /health
│           │   ├── ClaudeClient.java        # Anthropic call
│           │   ├── ZiaClient.java           # ZIA call (alt provider)
│           │   ├── ProjectsClient.java      # Zoho Projects OAuth + tasks API
│           │   ├── ProjectsPayloadBuilder.java
│           │   ├── RunLogger.java           # Catalyst Datastore run logging
│           │   └── ModuleSchemas.java
│           └── webapp/WEB-INF/web.xml
├── client/                              # Catalyst Web Client (static)
│   ├── client-package.json
│   └── app/
│       ├── index.html
│       ├── app.js
│       └── styles.css
└── samples/                             # Demo BRDs + golden output JSON
```

## Prerequisites

- Catalyst CLI (`npm i -g zcatalyst-cli`)
- Java 11 + Maven
- A Catalyst project (`catalyst init` will populate `catalyst.json`)
- For Claude: `ANTHROPIC_API_KEY`
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
**Functions → uat_generator → Configuration → Environment Variables**:

| Variable | Required for | Notes |
|---|---|---|
| `ANTHROPIC_API_KEY` | Claude | Default provider |
| `CLAUDE_MODEL` | optional | Defaults to `claude-opus-4-7` |
| `LLM_PROVIDER` | optional | `claude` (default) or `zia` |
| `ZIA_ENDPOINT`, `ZIA_OAUTH_TOKEN` | ZIA mode | When `LLM_PROVIDER=zia` |
| `ZOHO_CLIENT_ID` | Projects push | Self-client from api-console.zoho.com |
| `ZOHO_CLIENT_SECRET` | Projects push | |
| `ZOHO_REFRESH_TOKEN` | Projects push | Scope: `ZohoProjects.tasks.CREATE` |
| `ZOHO_PROJECTS_API_BASE` | optional | Default `https://projectsapi.zoho.com` (US). Use `.in/.eu/.com.au` for other DCs |
| `ZOHO_ACCOUNTS_BASE` | optional | Default `https://accounts.zoho.com` |
| `ZOHO_PROJECTS_PORTAL_ID` | optional | Fallback when client doesn't send it |
| `ZOHO_PROJECTS_PROJECT_ID` | optional | Fallback when client doesn't send it |
| `CATALYST_RUNS_TABLE` | optional | Datastore table name for run logs (default `uat_runs`) |

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

- Demo uses Anthropic Messages API directly. Swap to ZIA by setting
  `LLM_PROVIDER=zia` and adjusting `ZiaClient.java` to your actual ZIA endpoint
  contract (the current call shape is a placeholder).
- Projects API uses the v3 REST endpoints (`projectsapi.zoho.com`). If you need
  the older `projectsapi.zoho.com/api/v2` shape or DC-specific hosts, change
  `ZOHO_PROJECTS_API_BASE`.
- For QEngine push instead of Projects, add a `QEngineClient.java` mirroring
  `ProjectsClient.java` and a `target` field in the request body to route.
