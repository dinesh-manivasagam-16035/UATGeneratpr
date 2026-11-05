# LiftOff Generator — Zia Agent setup

LiftOff can use a **Zia Agent** as its LLM for everything it generates: the implementation
blueprint (modules, fields, automation, plan), UAT cases, and module suggestions. LiftOff calls the
agent through Zoho's [Agent API](https://www.zoho.com/agents/resources/help/developer-documentation/api-reference.html);
every request carries its own instructions and asks for JSON, so the agent itself stays simple:
no tools, no knowledge base.

This is a different agent from [LiftOff UAT Pilot](README.md), which *calls* LiftOff. The Generator
is *called by* LiftOff.

## 1. Create the agent

1. Open **Zia Agents** → **Agent Studio** → **Create from Scratch**.
2. **Name:** `LiftOff Generator`
   **Description:** `Returns the JSON that LiftOff asks for: implementation blueprints, UAT cases and module suggestions.`
3. **Model:** pick the most capable model offered, with the largest output limit. Blueprints and
   case batches are long JSON responses.
4. **Instructions** (paste as is):

   ```
   You are LiftOff Generator, a JSON generation service for Zoho CRM implementation work.

   Every message you receive has two parts:
   - INSTRUCTIONS: the role to take and the exact JSON shape to return.
   - REQUEST: the material to work from (BRD text, org metadata, requirement IDs).

   Rules:
   1. Follow the INSTRUCTIONS of each message exactly. They change from message to message.
   2. Reply with ONE JSON object only. No markdown fences, no headings, no text before or after it.
   3. Use only literal JSON values: no comments, no trailing commas, no expressions.
   4. Use only facts from the REQUEST. If something is missing, follow the instructions for
      missing information (usually an open question or an empty value). Never invent values.
   5. Do not ask the user questions and do not call tools. Answer every message in one reply.
   6. If the requested output would be very long, return fewer complete items rather than
      cutting an item off halfway.
   ```

5. **Tools:** none. **Knowledge base:** none.
6. **Test Agent:** send a short message such as
   `INSTRUCTIONS Return {"ok": true}. REQUEST nothing` and check that the reply is just `{"ok": true}`.
7. **Deploy** the agent. The Integrate tab only shows API details for a deployed agent.

## 2. Collect the agent IDs

On the agent's details page → **Integrate** tab (Agent Info / Developer Interface → Agent API):

| Value | Function env var |
|---|---|
| Organization ID | `ZIA_AGENT_ORG_ID` |
| Agent ID | `ZIA_AGENT_ID` |
| Version ID (also under **Versions**) | `ZIA_AGENT_VERSION_ID` |
| OAuth scope (shown as `ZiaAgents.agents.TRIGGER`) | used in step 3 |
| Endpoint URL, if it differs from `https://ziaagents.zoho.com/ziaagents/api/v1/agents/query` | `ZIA_AGENT_URL` |

The **version ID changes every time you redeploy the agent.** Update `ZIA_AGENT_VERSION_ID`
after each redeploy, or calls fail.

## 3. Get a refresh token

Use the API Console for the data center your Zoho account is in (`api-console.zoho.in` for India,
`api-console.zoho.com` for US, and so on).

1. **API Console** → **Add Client** → **Self Client** (or open your existing Self Client).
   Note the **Client ID** and **Client Secret**.
2. **Generate Code** tab → scope `ZiaAgents.agents.TRIGGER`, duration 10 minutes, any
   description → **Create**. Copy the grant code.
3. Within 10 minutes, exchange it for a refresh token (replace the values; use
   `accounts.zoho.in` for India):

   ```bash
   curl -X POST https://accounts.zoho.in/oauth/v2/token \
     -d grant_type=authorization_code \
     -d client_id=YOUR_CLIENT_ID \
     -d client_secret=YOUR_CLIENT_SECRET \
     -d code=YOUR_GRANT_CODE
   ```

   Copy `refresh_token` from the response. It doesn't expire; LiftOff turns it into a fresh access
   token about once an hour.

## 4. Configure the LiftOff function

Catalyst console → **Serverless → Functions → uat_generator → Configuration → Environment
Variables**:

| Variable | Value |
|---|---|
| `ZIA_AGENT_ORG_ID` | from step 2 |
| `ZIA_AGENT_ID` | from step 2 |
| `ZIA_AGENT_VERSION_ID` | from step 2 |
| `ZIA_AGENT_REFRESH_TOKEN` | from step 3 |
| `ZIA_AGENT_CLIENT_ID`, `ZIA_AGENT_CLIENT_SECRET` | the Self Client from step 3 |
| `ZIA_ACCOUNTS_BASE` | `https://accounts.zoho.in` for India (else derived from `ZOHO_DC`) |
| `LLM_PROVIDER` | optional: `zia`. When unset, LiftOff uses the Zia Agent whenever the variables above are set |
| `ZIA_AGENT_URL` | optional. Default: the Agent API on the same data center as `ZIA_ACCOUNTS_BASE` (`https://ziaagents.zoho.in/...` for India). A token only works on its own data center's host |
| `ZIA_AGENT_TIMEOUT_SEC` | optional, per-call timeout, default 25 (Catalyst stops a function request at about 30 s) |

Save. Catalyst applies env vars to new invocations; redeploy the function if you changed code.

## 5. Check it

1. In LiftOff, the Blueprint tab's header line ends with **"— via Zia Agent"** after a run.
2. A 401 `INVALID_OAUTHTOKEN` from the agent means the token and the Agent API are on different data
   centers (an India token must call `ziaagents.zoho.in`), or the token lacks `ZiaAgents.agents.TRIGGER`.
   A failed *refresh* (wrong refresh token or client ID/secret) shows as an OAuth refresh error instead.
3. A 404, or an error mentioning the version, after a redeploy means `ZIA_AGENT_VERSION_ID` is out of date.
4. "Response was cut off" warnings mean the agent's model hit its output limit: pick a model
   with a larger limit, or lower `CASES_PER_CALL` / `GEN_CHUNK_CHARS`.

## 6. Background generation (one-time setup)

A Zia Agent reply for a real BRD takes longer than Catalyst allows one web request (about 30
seconds), so LiftOff generates in a **job function**, `impl_worker`, which may run up to 15
minutes. The Blueprint tab queues the job, shows each step as it runs (part, what it covers,
items found, time taken), and fetches the result when it's done. Each user's run is its own job,
so several people can generate different implementations at the same time.

1. **Create the job function** (once), from the repo folder:

   ```bash
   cd ~/UATGeneratpr && catalyst functions:add
   ```

   Choose **Job**, **Java 11**, and name it **`impl_worker`**. Then put the worker's main class in
   place (see [Worker main class](#worker-main-class)), or ask Claude to.
2. **Create the job pool:** Catalyst console → **Job Scheduling** → **Job Pools** → **Create** →
   type **Function**, name **`liftoff_jobs`**.
3. **Environment variables on `impl_worker`:** each function has its own, so copy the same
   `ZIA_AGENT_*` and `ZIA_ACCOUNTS_BASE` values from `uat_generator` (step 4).
   Optional: `IMPL_WORKER_CALL_TIMEOUT_SEC` (default 240) per agent call.
4. **Cache:** enabled on the project by default. To use a dedicated segment, create one in
   **Cache** and set `LIFTOFF_CACHE_SEGMENT` on both functions.
5. **Build and deploy both functions and the client:**

   ```bash
   cd ~/UATGeneratpr && functions/uat_generator/build-for-deploy.sh && catalyst deploy --only functions:uat_generator,functions:impl_worker,client
   ```

If the job pool or worker is missing, the Blueprint tab falls back to generating in the browser
(one request per agent call) and says so in its notes; long replies may then time out.

Names can be changed with `IMPL_JOBPOOL` and `IMPL_WORKER_FUNCTION` on `uat_generator`.

### Worker main class

`functions/impl_worker/ImplWorker.java` (set `"main": "ImplWorker"` in its `catalyst-config.json`):

```java
import com.catalyst.Context;
import com.catalyst.job.CatalystJobHandler;
import com.catalyst.job.JOB_STATUS;
import com.catalyst.job.JobRequest;

/** Runs one background blueprint generation queued by uat_generator (see ImplJob). */
public class ImplWorker implements CatalystJobHandler {
    @Override
    public JOB_STATUS handleJobExecute(JobRequest request, Context context) throws Exception {
        String key = String.valueOf(request.getJobParam("job_key"));
        boolean ok = com.uat.generator.ImplJob.run(key, () -> context.getRemainingExecutionTimeMs());
        return ok ? JOB_STATUS.SUCCESS : JOB_STATUS.FAILURE;
    }
}
```

The build script copies `uat_generator`'s jars (including `uat-classes.jar` with `ImplJob`) into
`functions/impl_worker/lib/` and writes its `.classpath`.

## How LiftOff calls it

```
POST {ZIA_AGENT_URL}
X-ZIAAGENTS-ORG: {ZIA_AGENT_ORG_ID}
X-ZIAAGENTS-AGENT-ID: {ZIA_AGENT_ID}
X-ZIAAGENTS-AGENT-VERSION-ID: {ZIA_AGENT_VERSION_ID}
Authorization: Zoho-oauthtoken {access token}

{"query": "INSTRUCTIONS\n...\n\nREQUEST\n...", "systemArgs": {}}
→ {"data": {"response": "{ ...JSON... }"}}
```

LiftOff splits a BRD into parts and makes several calls per run (two per part for a blueprint,
plus one for the plan). Catalyst stops an Advanced I/O request at about 30 seconds, so the Blueprint
tab sends **one agent call per request** (`POST /implement/stage`: start → parts, two at a time →
finish → plan). Each call has to finish within `ZIA_AGENT_TIMEOUT_SEC`; if parts time out, lower
`IMPL_CHUNK_CHARS` (default 6000) so each part is smaller.
