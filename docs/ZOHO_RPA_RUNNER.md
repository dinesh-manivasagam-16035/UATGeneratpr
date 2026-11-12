# LiftOff Setup runner on Zoho RPA

LiftOff builds everything the product's API allows. What's left (validation rules, Blueprints, Deluge
functions, per-profile layouts …) is a **UI-work queue**: each item has a plain-language step list
and, where LiftOff knows the screen, a **recipe** — a list of operations a bot performs in Setup.
This document specifies the bot so it can be built once in Zoho RPA Studio and reused for every
product LiftOff supports.

## 1. How a job reaches the bot

In Build → Needs you → **Send to Zoho RPA**, LiftOff calls `POST /implement/rpa-job` which:

- stores the job for 24 h, and
- when `RPA_WEBHOOK_URL` is set on `uat_generator`, POSTs the job JSON to that URL
  (header `X-LiftOff-Token: <RPA_WEBHOOK_TOKEN>` when that env var is set);
- otherwise the browser downloads `liftoff-rpa-job-<id>.json` to start the bot by hand.

Recommended trigger: a **Zoho Flow** webhook trigger → "Run RPA bot" action with the payload as the
bot's input variable. Set that Flow webhook URL as `RPA_WEBHOOK_URL`.

## 2. The job

```json
{
  "job_id": "jk3f9a2b7c1d0",
  "created_at": "2026-11-12T09:12:00Z",
  "product": "crm",
  "runner": "zoho-rpa",
  "org": { "id": "60071738809", "zgid": "…", "name": "Vikaas - Test Org", "domain": "…" },
  "callback": {
    "url": "https://<app-host>/server/uat_generator/implement/rpa-result",
    "token": "n3k…(32 chars)",
    "method": "POST",
    "body_example": "{\"job_id\":\"…\",\"token\":\"…\",\"results\":[{\"id\":\"VR-1\",\"status\":\"done\"}]}"
  },
  "recipe_ops": { "goto": "…", "click": "…", "fill": "…", "choose": "…", "type": "…", "confirm": "…", "expect": "…" },
  "items": [
    {
      "id": "VR-1", "section": "validation_rules", "module": "Leads",
      "label": "validation rule Leads: Flag PIDG Pending",
      "mode": "auto",
      "steps": ["Setup → Customization → Modules and Fields → Leads → Validation Rules → Create Rule.", "Name: Flag PIDG Pending.", "…"],
      "recipe": [
        { "op": "goto",    "path": "/settings/modules" },
        { "op": "click",   "text": "Leads" },
        { "op": "click",   "text": "Validation Rules" },
        { "op": "click",   "text": "Create Rule|New Rule|Create Validation Rule|\\+ Rule" },
        { "op": "fill",    "label": "Rule Name|Name", "value": "Flag PIDG Pending" },
        { "op": "choose",  "near": "Field|Choose field|Select field", "option": "PIDG Code" },
        { "op": "choose",  "near": "Condition|Operator|is", "option": "is empty" },
        { "op": "click",   "text": "Next|Continue" },
        { "op": "fill",    "label": "Alert|Error message|Alert message|Message", "value": "A PIDG code is required before this lead can be worked." },
        { "op": "confirm", "text": "Check the rule, then Resume to save (or press Save yourself)." },
        { "op": "click",   "text": "Save" },
        { "op": "expect",  "text": "Flag PIDG Pending" }
      ]
    },
    { "id": "FN-2", "section": "functions", "mode": "auto", "code": "…Deluge…", "recipe": [ … { "op": "type", "value": "…" } … ] },
    { "id": "BP-1", "section": "workflows", "mode": "manual", "steps": ["Setup → Process Management → Blueprint → …"], "recipe": [] }
  ]
}
```

`mode: "manual"` items have no recipe: the bot shows the steps to a person (RPA "human task") and
records their answer.

## 3. The bot, op by op

Inputs: the job JSON. Browser: Chrome, signed in to the product's Setup as a dedicated LiftOff user
(an admin profile). Setup URL = `https://crm.zoho.<dc>/crm/org<zgid>/settings` for CRM;
`https://desk.zoho.<dc>/agent/<portal>/…/setup` for Desk.

| op | What the bot does | Failure handling |
|---|---|---|
| `goto {path}` | Navigate to Setup URL + `path`; wait for the page to be idle. | Retry once; then mark the item `failed` with note "couldn't open <path>". |
| `click {text}` | Find a button, link, tab or menu item whose **visible text** matches `text` as a case-insensitive regular expression (`a|b` means either); click; wait 700 ms. | Not found within 6 s → raise a **human task** "Please click “<text>”", continue after the person confirms. |
| `fill {label, value}` | Find the input or textarea whose label or placeholder matches `label` (regex, contains); clear; type `value`. | Not found → human task "Enter <value> in <label>". |
| `choose {near, option}` | Find the dropdown next to the text `near`; open it; pick the option whose text matches `option`. Native `<select>`: select by label. | Option not listed → human task. |
| `type {value}` | Focus the code editor; select all; paste `value`. | No editor → human task. |
| `confirm {text}` | **Approval step**: show `text` with a screenshot; wait for a person to approve. If declined → mark item `skipped` with their comment and move on. | — |
| `expect {text}` | Verify `text` is visible on the page. | Missing → item `failed`, note "expected <text>". |

After each item: take a screenshot, upload it (WorkDrive or any URL the bot can produce), and call
the callback.

## 4. Reporting back

```
POST <callback.url>
Content-Type: application/json

{ "job_id": "<job_id>", "token": "<callback.token>",
  "results": [ { "id": "VR-1", "status": "done", "note": "", "screenshot_url": "https://…/VR-1.png" } ] }
```

`status` is `done`, `failed` or `skipped`. Send one call per item (preferred, so LiftOff shows
progress) or one call at the end with all results. The last result per item wins. The browser polls
`/implement/rpa-status` every 15 s while Build is open; `done` items are ticked as "done by Zoho RPA"
with the screenshot link, `failed` items show the note and fall back to the manual steps.

## 5. Where the approval sits

Two modes, chosen on the LiftOff side:

- **Approve each save (default).** Recipes contain `confirm` ops; the bot raises an approval before every
  Save and continues only when a person approves.
- **Run through, approve after** — `RPA_AUTO_APPROVE=1` on `uat_generator`. LiftOff strips the `confirm`
  ops from the job (`job.auto_approve: true`), the bot performs every item without stopping, and the
  person's approval is the Build step's **Approve build → Verify & UAT**, which is still required
  before anything is verified or tested. Use this when the approver works in LiftOff, not in RPA.

The webhook payload's *structure* never changes between jobs (only values do), so Zoho Flow's
"review new data" prompt on the trigger appears once, after the first job; approve it with a real
job as the sample and later jobs flow without it.

## 6. Security notes

- The callback token is per job, random, 32 chars, stored with the job for 24 h; the endpoint needs no
  Zoho session (the bot has none). Keep `RPA_WEBHOOK_TOKEN` secret if you set it.
- The bot user should be a dedicated admin in the target org so Setup changes are attributable.
- `confirm` ops are where the human approval sits; keep them, don't auto-approve.
- Set `PUBLIC_FUNCTION_BASE` on `uat_generator` (e.g. `https://<app-host>/server/uat_generator`) if the
  app is served behind a different host than the function sees.

## 7. One generic bot for every item (build this once)

Every item, for every product, is the same shape: `recipe[]` of ops. So the bot is a loop and a
switch, and never needs editing when LiftOff adds a product or an item kind.

```
job   = parse(webhook.payload)                      // the JSON LiftOff posted
base  = job.setup_url                               // e.g. https://crm.zoho.in/crm/<org domain>
for item in job.items:
    status = "done"; note = ""
    if item.recipe is empty:                        // manual item: open Setup, hand over
        human_task(item.label, item.steps)          // the person does it and confirms
    else:
        for op in item.recipe:
            switch op.op:
              "goto":    open_url(base + op.path)                // deep link into Setup
              "click":   click_by_text(op.text)                  // regex, case-insensitive, "a|b" = either
              "fill":    set_text(find_by_label(op.label), op.value)
              "choose":  pick_option(find_near(op.near), op.option)
              "type":    paste_into_editor(op.value)
              "confirm": approval(op.text)                       // the human gate before Save; on decline → status="skipped", break
              "expect":  if not visible(op.text): status="failed"; note="expected "+op.text
            on any error in click/fill/choose/type:
              human_task("Please " + describe(op), [item.steps])  // person does that one op, then the loop continues
    screenshot → upload → url
    POST job.callback.url  {job_id, token: job.callback.token, results:[{id: item.id, status, note, screenshot_url}]}
```

`job.setup_url` is the org's own Setup base (CRM: `https://crm.zoho.<dc>/crm/<org domain>`; Desk:
`https://desk.zoho.<dc>`), so `goto` paths are the same for every org. Recipes use deep links —
`/settings/modules/<Module>/validation-rules`, `/settings/functions`, `/settings/workflow-rules`,
`/settings/blueprint`, … — so the first op lands on the right page and the clicks that follow are
the ones on that page only. A validation rule with two conditions carries an `Add criteria` click
between them and a `Pattern` choose (AND/OR) after.

Items whose screen LiftOff doesn't script yet (per-profile layouts, Blueprints, approvals, field types
without a create API) still get a `goto` to the right Setup page and a single `confirm` whose text is
the full instruction — the bot opens the page, the person finishes, the bot records it. That is why
every item, API-less or not, flows through the same loop.

### Variable mapping in Studio

| Op field | Studio action | Notes |
|---|---|---|
| `op.path` | Open URL: `${job.setup_url}${op.path}` | wait for page idle |
| `op.text` | Click element — selector by *text* (regex) | prefer button/link/tab roles |
| `op.label`, `op.value` | Set text — element by *label or placeholder* (regex) | clear first |
| `op.near`, `op.option` | Click element near text → click option by text | native select: select by label |
| `op.value` (type) | Focus editor, Select all, Paste | code editors |
| `op.text` (confirm) | Human task / approval with screenshot | decline → skipped |
| `op.text` (expect) | Verify text exists | else failed |

## 8. Building it in RPA Studio (outline)

1. Flow: Webhook trigger (JSON payload; paste a job as the sample so `setup_url`, `callback.*`,
   `items[]` become variables) → the RPA bot action with the payload as its input.
2. Bot: implement §7 with a **Loop** over `items` and, inside, a **Loop** over `item.recipe` with a
   **Decision/Switch** on `op.op`. Record each Web Automation action once with the recorder on your
   validation-rule screen, then replace the recorded literals with the op fields from the table.
3. After each item: Screenshot → upload → **HTTP POST** to `job.callback.url` with the result
   (the same call proven by hand with curl).
4. Error branch: any unhandled error → result `failed` with the exception text; continue with the
   next item. Never auto-approve a `confirm`.
