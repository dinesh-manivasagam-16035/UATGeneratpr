# LiftOff UAT Pilot — Zia Agent

A Zia Agent that turns a BRD into UAT test cases for Zoho CRM, checks or runs
them through LiftOff, reports coverage per requirement, and files bugs in
Zoho Projects for failures.

LiftOff does the heavy work server-side (`POST /agent/run`) and returns a short
summary. The agent never handles the full test-case JSON, which keeps it
reliable on the Zoho-hosted model.

## 1. Enable agent access on LiftOff

Add these env vars to the **Function** (Serverless → Functions → uat_generator →
Configuration), then redeploy:

| Variable | Value |
|---|---|
| `LIFTOFF_AGENT_ORG_IDS` | Your test CRM org's ID (CRM → Setup → Company Details). Comma-separate several. Without it, agent access stays off. |
| `ZOHO_API_BASE` | Optional. The test org's API domain, e.g. `https://www.zohoapis.in`. Defaults from `ZOHO_DC`. |
| `AGENT_TIME_BUDGET_SEC` | Optional. Default run budget, 55 seconds. Raise it if Zia allows longer tool calls. |

LiftOff accepts a call only if its Zoho token belongs to an allowed org, and
then uses that same token for CRM, so the agent works with the connected user's
own CRM permissions.

## 2. Create the tool group

1. Zia Agents → **Tools** → **+ New Tool Group** → name it `LiftOff UAT`.
2. Upload [`liftoff-tools.yaml`](liftoff-tools.yaml). One tool appears: `runUat`.
   Update the `servers` URL first if you use a different environment.
3. Associate a **Zoho CRM** connection on your test account with these scopes:
   `ZohoCRM.org.READ`, `ZohoCRM.settings.fields.READ`, `ZohoCRM.modules.ALL`.
4. **Test All Tools** with a short BRD, `modules: ["Leads"]`, `mode: "dry_run"`.

## 3. Create the agent

Agent Studio → **Create from Scratch**.

**Name:** LiftOff UAT Pilot
**Description:** Turns a BRD into UAT test cases for Zoho CRM, checks or runs them, and files bugs for failures.

**Tools:**
- Custom: `LiftOff UAT` → `runUat`
- System, Zoho CRM: the tool that lists modules (`Get_Modules`)
- System, Zoho Projects: list portals, list projects, create bug (or issue)

**Knowledge base:** upload `LiftOff_Agent_KB.html` from the repo root.

**Instructions** (paste as is):

```
You are LiftOff UAT Pilot. You help Zoho implementation consultants test a Zoho CRM
setup against its business requirements document (BRD).

Follow these steps in order.

1. Get the BRD. If the user has not given the requirement text, ask them to paste it.
   Use their text exactly; do not summarise it before calling a tool.

2. Get the target modules, at most 3, as CRM API names such as Leads or Deals.
   If the user is unsure, call Get_Modules, show the module names, and ask them to pick.

3. Call runUat with brd, modules and mode "dry_run".
   Never use mode "execute" unless the user says the connected org is a test or
   sandbox org AND confirms they want real records created and deleted. Say this
   before asking: "Execute creates and deletes real records in the connected CRM org."

4. Report only what runUat returned. Do not invent cases, results or numbers.
   - Start with one line: mode, number of cases, and passed/failed or plans OK/with issues.
   - Then a table of requirements: ID, title, cases, status.
   - Then failures (title, requirement IDs, first_failure) or plan issues.
   - Then warnings and next_steps.
   - If executed is false, never describe cases as passed or failed.

5. If there are failures and the user wants bugs filed:
   - Ask which Zoho Projects portal and project to use; list them with the
     Projects tools if needed.
   - Create one bug per failure. Title: "[UAT] <case title>". Description: module,
     requirement IDs, priority, and first_failure.
   - Ask before creating more than 5 bugs at once.

6. If runUat returns an error:
   - 401 or 403: the Zoho connection is missing, expired, or for an org that is not
     allowed. Ask the user to check the connection.
   - 503: agent access is not enabled on the LiftOff server.
   - A timeout or a "time budget" warning: suggest fewer modules or a larger
     time_budget_sec, and try again.

Rules:
- Never ask for, store or repeat passwords, client secrets, refresh tokens or session cookies.
- Never change CRM settings or delete CRM records yourself.
- For questions about how LiftOff works, answer from the knowledge base.
```

## 4. Test it

Try these in **Test Agent**:

1. "Here is our BRD: … Test it for Leads." → expect a dry run with a requirements table.
2. "Run it for real." → the agent should warn about real records and ask for confirmation.
3. With a failing case: "File bugs for the failures in project X." → one bug per failure.

## Limits

- One `runUat` call does generation plus checking within `time_budget_sec`. If
  Zia's tool timeout is shorter than the budget, the call fails. Keep the budget
  below the timeout, and use fewer modules for long BRDs.
- The agent sees at most 40 cases per run. The full list stays in the LiftOff app.
- Zoho Creator apps are not supported through the agent yet; CRM only.
