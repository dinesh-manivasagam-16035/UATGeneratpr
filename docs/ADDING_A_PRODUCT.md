# Adding a Zoho product to LiftOff

LiftOff's pipeline (BRD → Blueprint → Build → Verify → UAT, the guided UI, Run everything, the
guided runner, failure diagnostics) is product-neutral. Everything that knows a product lives in one
adapter class behind the `Product` interface. Today: `CrmProduct` (full), `DeskProduct` (stage A).

## The adapter

```
Product
  id(), label(), entityWord()          "desk", "Zoho Desk", "module"
  oauthScopes()                        added to Connect (CrmAuthServlet.SCOPES — one Zoho token)
  capabilities()                       the matrix: need → lane (api | partial | ui) + how
  laneFor(section, item)               lane for one spec item (must agree with the matrix)
  systemPrompt(group)                  the agent's brief for "data_model" and "automation"
  readOrg(client, modules, layouts)    the live org as an ImplOrg (modules, fields, layouts, roles, rules)
  setupRoot(section), automationMenu(type)   Setup paths for the guided steps
  buildersReady()                      false until the API builders exist → everything goes to the runner
  defaultModules()                     module list when the org can't be read
```

`Products.use(body)` picks the adapter from the request's `platform` (the browser sends it with every
call; the job worker gets it in the job input). `Products.current()` is what the pipeline asks.

## Checklist for a new product

1. **Scopes.** Add the product's read scopes (and record scopes for UAT) to `CrmAuthServlet.SCOPES`.
   Users must reconnect once.
2. **Org reader.** A class like `DeskOrg` that fills `ImplOrg`: `modules` (api_name, labels),
   `fields` per module in CRM field shape (`api_name`, `field_label`, `data_type`, `pick_list_values`,
   `id`), `layouts` names, `roles`, and `errors` for anything unreadable. Picklist options matter: the
   agent is told about them so it adds options instead of asking.
3. **Capability matrix.** One row per thing a BRD can ask for, with the lane the product's API allows.
   This single table is rendered into the agent's prompt *and* drives `laneFor`, so they never disagree.
4. **Prompts.** Reuse the CRM prompt structure (same JSON sections — `modules`, `fields`, `layouts`,
   `validation_rules`, `roles`, `workflows`, `functions`, `open_questions`) with the product's
   vocabulary and api names. Keep: structured workflow form only, `assumption` instead of trivial
   questions, "prefer what the API can build".
5. **Builders (stage B).** In `ImplementApplyServlet.Run.apply`, dispatch by product to a builder
   that turns an item into the product's create call(s). Record every request in `requests` so
   failures carry the body and response (ImplDiagnose). Flip `buildersReady()` to true.
6. **UI recipes.** `ImplRpa.recipe` ops for items with no API, so the runner can do them in Setup.
7. **UAT.** `ClaudeClient` system prompt for the product's record API (paths, required fields,
   where custom fields live, how to capture the id) and, in `ExecuteServlet.client` / `UatJob`, a
   client on the product's host with any headers and seeded variables (`DeskOrg.prepare`).
8. **Client.** Add the product to `PRODUCTS` in `app.js` (label, fixed module list if any) and a
   button in the platform picker; `detectProduct` keywords so a BRD selects it automatically.

## Where each product stands

| | CRM | Desk | Creator |
|---|---|---|---|
| Connect + read org | ✓ | ✓ departments, fields, layouts | forms via existing Creator servlets |
| Blueprint prompts + matrix | ✓ | ✓ | — |
| API builders | fields, picklists, modules, roles, rules (field update, task, webhook), assignment rules, layout placement | stage B: fields, layouts, teams, roles, SLA, assignment, validation rules, blueprints (API exists; shapes to confirm) | — |
| Guided runner recipes | validation rules, functions | Setup paths only | — |
| Verify read-back | ✓ | fields, layouts | — |
| UAT | ✓ | ✓ tickets API | ✓ (UAT mode) |
