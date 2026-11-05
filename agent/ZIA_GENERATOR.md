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
| `ZIA_AGENT_TIMEOUT_SEC` | optional, per-call timeout, default 120 |

Save. Catalyst applies env vars to new invocations; redeploy the function if you changed code.

## 5. Check it

1. In LiftOff, the Blueprint tab's header line ends with **"— via Zia Agent"** after a run.
2. A 401 `INVALID_OAUTHTOKEN` from the agent means the token and the Agent API are on different data
   centers (an India token must call `ziaagents.zoho.in`), or the token lacks `ZiaAgents.agents.TRIGGER`.
   A failed *refresh* (wrong refresh token or client ID/secret) shows as an OAuth refresh error instead.
3. A 404, or an error mentioning the version, after a redeploy means `ZIA_AGENT_VERSION_ID` is out of date.
4. "Response was cut off" warnings mean the agent's model hit its output limit: pick a model
   with a larger limit, or lower `CASES_PER_CALL` / `GEN_CHUNK_CHARS`.

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
plus one for the plan; up to `MAX_LLM_CALLS` for UAT), `GEN_CONCURRENCY` (default 2) at a time.
