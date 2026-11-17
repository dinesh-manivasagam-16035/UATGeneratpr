# LiftOff Setup runner on Zoho RPA

LiftOff builds everything the product's API allows. What's left (validation rules, Blueprints, Deluge
per-profile layouts, approval processes …) is a **UI-work queue**: each item has a plain-language step list
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

One endpoint, three shapes — use the simplest one your flow can send. All are `POST <callback.url>`
(the job's `callback.url`), with JSON, or with the same names as form fields or query parameters
when a step can't build JSON.

| Shape | Body | Use when |
|---|---|---|
| Whole job | `{"job_id":"…","token":"…","all":"done"}` | the bot runs the job as one action and reports once. Marks every item that has no result yet. `all` may be `done`, `failed` or `skipped`; add `"note"` to say why. |
| One item, flat | `{"job_id":"…","token":"…","id":"VAL-1","status":"done","note":""}` | one call per item, easiest to build in a flow step |
| Several items | `{"job_id":"…","token":"…","results":[{"id":"VAL-1","status":"done","note":"","screenshot_url":""}]}` | the bot collects results and sends them together |

Nothing has to be assembled by hand: the job carries the bodies ready to send —
`callback.finish_body` and `callback.failed_body` for the whole job, `items[].report_done` and
`items[].report_failed` per item. The last result per item wins, so reporting an item `failed` and
later `done` is fine. A call that records nothing answers 400 and lists the job's item ids.

The browser polls `/implement/rpa-status` every 15 s while Build is open; `done` items are ticked as
"done by Zoho RPA", `failed` items show the note and fall back to the manual steps.

Check the round trip without the bot (replace the three values from a downloaded job file):

```
curl -s -X POST "<callback.url>" -H "Content-Type: application/json" -d '{"job_id":"<job_id>","token":"<callback.token>","all":"done"}'
```

`{"ok":true,"recorded":N,"of":N}` means LiftOff took it; the Build tab ticks the items within 15 s.

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

## 8. Finishing the bot, in three levels

Build level 1 first. It closes the loop today; levels 2 and 3 only add detail and coverage.

### Level 1 — the job runs, LiftOff hears about it (about 15 minutes)

In the Zoho RPA flow that owns the webhook LiftOff posts to:

1. **Trigger:** the incoming webhook you already have. Send one job from LiftOff ("Send to Zoho RPA")
   and accept it as the sample, so `callback.url`, `callback.finish_body` and `callback.failed_body`
   appear as variables.
2. **Action:** your RPA bot, with the payload as input (what you have now).
3. **After the bot, on success:** add a **Custom function** step and call `report_to_liftoff` with
   `callback.url` and `callback.finish_body`.
4. **After the bot, on failure:** the same function with `callback.url` and `callback.failed_body`.

```
void report_to_liftoff(string callback_url, string body)
{
	resp = invokeurl
	[
		url :callback_url
		type :POST
		parameters:body
		headers:{"Content-Type":"application/json"}
	];
	info resp;
}
```

Turn the flow on, send a job, and the Build tab shows "N of N done" when the bot ends. That is the
status update that was missing.

### Level 2 — progress per item

Inside the bot's loop over `items`, after each item call the same function with `callback.url` and
that item's `report_done` (or `report_failed` in the error branch). Keep level 1's final call: it
only fills items that have no result yet, so it never overwrites a failure.

### Level 3 — the generic bot (§7)

1. Loop over `items`; inside, loop over `item.recipe` with a Decision on `op.op`.
2. Record each web action once with the recorder on the validation-rule screen, then replace the
   recorded literals with the op fields from the table in §7.
3. `confirm` is a human task (or absent, with `RPA_AUTO_APPROVE=1`).
4. Error branch: report the item `failed` with the error text and continue with the next item.

Every item now has a recipe. Kinds LiftOff doesn't script click by click (shared option sets,
territories, groups, sharing rules, tags, variables, record locking, business hours) carry a
`goto` to Setup and one `confirm` whose text is the whole instruction — the bot opens the page, a
person finishes, the bot reports. They only reach the bot when their API build was not possible.

### Environment variables on `uat_generator` (redeploy after changing)

| Variable | Purpose |
|---|---|
| `RPA_WEBHOOK_URL` | the flow's incoming-webhook URL, with its key. A secret: never in the repo or in chat. |
| `RPA_WEBHOOK_TOKEN` | optional; sent as header `X-LiftOff-Token` so the flow can check the caller |
| `RPA_AUTO_APPROVE` | `1` removes the per-save `confirm` steps; approval moves to the Build gate |
| `PUBLIC_FUNCTION_BASE` | the function's public base URL when it differs from what the function sees |
