# CRM use cases — what each one exercises

`samples/crm-all-usecases-brd.txt` is one BRD written as a client would write it, covering every
kind of implementation activity LiftOff meets in Zoho CRM. It is the end-to-end test: generate the
blueprint from it, and every lane — the API builders, the "API plus one Setup step" items, the
guided runner and the open questions — should light up.

The table says what each requirement should become, which lane it belongs in, and how to tell the
result is right. "Lane" is what LiftOff does today; the capability sheet
(`docs/capabilities/zoho-crm.csv`) is the source of truth for all 76 activities.

| Req | Becomes | Lane | Check |
|---|---|---|---|
| FR-1 | Text field on Leads and Accounts, unique on Accounts | API (field, unique_field) | Both fields exist, 20 chars; a duplicate code is rejected on save |
| FR-2 | Global picklist "Partner Tier" + picklist fields using it | API (global_picklist, field) | One shared option set, fields on three modules point at it |
| FR-3 | Two picklists plus a dependency between them | API (field, map_dependency) | Choosing Retail offers only its three sub-segments |
| FR-4 | Date, currency and boolean fields | API (field) | Types and labels as stated |
| FR-5 | A "Partner Programme" section on the Accounts layout | API (layout_place) | The section exists with the partner fields in it |
| FR-6 | Custom module "Partner Onboarding" with its fields | API (module, field) | Module created, lookup to Accounts, status picklist |
| FR-7 | Three roles, and a cloned profile with the permissions | API (role, profile) | Roles report to Sales Director; the Executive profile has no delete |
| FR-8 | Territory hierarchy with assignment criteria | API (territory) | India → three regions; Gujarat account lands in West India. Needs Territory Management on |
| FR-9 | Data sharing rule on Deals, read only | API (sharing_rule) | Support reads the Channel Manager's deals and cannot edit |
| FR-10 | Workflow rule on create with a field update | API (rule_field_update) | New status option added, rule fires on create |
| FR-11 | Validation rule | Setup (runner / RPA) | No create API: the runner walks Setup; the message is exactly as stated |
| FR-12 | Assignment rule with two entries, one round robin | API (assignment) | Website leads rotate; event leads go to the regional manager |
| FR-13 | Time-based rule, 2 business days before a date field | API (rule_time) | execute_when date_or_datetime, task action, org time zone |
| FR-14 | Rule with a 24-hour delayed block: field update + email alert | API (rule_time, rule_email) | scheduled_actions on the rule; the alert uses the existing template |
| FR-15 | Rule on field update with an email alert to two recipients | API (rule_email) | Owner and primary contact resolved as merge fields |
| FR-16 | Deluge function called by a rule | API (function) + one Setup step | Function created and published; attaching it to the rule is the Setup step |
| FR-17 | Webhook action on a rule | API (webhook_standalone) | Webhook created with the stated URL |
| FR-18 | Blueprint with four states | API + Setup publish (blueprint) | Draft created with states, transitions and chart; published either by the API or in Setup |
| FR-19 | A second Deals pipeline with its own stages | API (pipeline) | Pipeline created, stages in order, direct pipeline untouched |
| FR-20 | Two fields (review date, outcome) | API (field) | The process itself is out of scope — the fields are not |
| FR-21 | Dashboard | Setup (runner) | No create API; the runner hands it over with the components listed |
| FR-22 | Email / inventory template | Setup (runner) | Templates have no create API |
| FR-23 | Business hours and holidays | Setup (runner today; API planned) | Zoho has add-business-hours; the builder is not written yet |
| FR-24 | Record import | Setup (runner today; API planned) | Bulk Write exists; LiftOff does not run imports yet |

## What to look for in a run

1. **Brief** — 24 requirements extracted, modules inferred as Leads, Accounts, Contacts, Deals.
2. **Blueprint** — items spread across modules, fields, global picklists, dependencies, layouts,
   validation rules, roles, sharing, workflows and functions. Nothing invented that the BRD does
   not ask for; FR-20's process and FR-21's dashboard should not become automation items.
3. **Open questions** — the agent should ask only what no default can answer: which users hold the
   three roles, the holiday list for FR-23, where the migration files are. It should *not* ask
   about field lengths or trigger types.
4. **Build** — the API lane runs in dependency order: modules and global picklists first, then
   fields, layouts and dependencies, then roles and sharing, then automation. The Setup lane is
   queued for the runner with its steps.
5. **Verify** — read-back per item; territories and sharing rules report "check in Setup" because
   the metadata APIs LiftOff uses don't return them.
6. **UAT** — cases per acceptance criterion A–J.

## Known gaps this BRD will surface

- FR-16 needs the function attached to the rule by hand: the workflow API has no function action.
- FR-18 may create the Blueprint as a Draft when Zoho's activation checks refuse the generated
  chart; the follow-up says where to publish it.
- FR-8 fails with a plain message when Territory Management is off in the org.
- FR-23 and FR-24 have APIs Zoho supports but LiftOff doesn't call yet (status "planned" in the
  capability sheet).
