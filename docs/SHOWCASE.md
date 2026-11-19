# Showcase: one BRD, both ways of building

`samples/liftoff-showcase-brd.txt` is a four-requirement BRD on Leads, written the way a client
writes one. Three requirements have a Zoho API, so LiftOff builds them itself. One has no create
API, so it is done in CRM Setup by Claude in Chrome, from the prompt LiftOff writes. You approve
both. The whole run is about five minutes.

## What each requirement becomes

| Req | Becomes | Lane | Who does it | How to tell it worked |
|---|---|---|---|---|
| FR-1 | Pick list **Buying Interest** on Leads: Hot, Warm, Cold | API | LiftOff — `POST /settings/fields?module=Leads` | The field is on the Leads form with three options |
| FR-2 | Whole-number field **Expected Seats** on Leads | API | LiftOff — `POST /settings/fields?module=Leads` | The field is on the Leads form |
| FR-3 | Workflow rule on create, when Buying Interest is Hot: set Lead Status to **Priority Follow-up** (a new option, added to the pick list first) and create the task **Call within 4 hours**, priority High | API | LiftOff — the option, a field update, a task, then `POST /settings/automation/workflow_rules` | Setup → Workflow Rules shows the rule, active, with both actions |
| FR-4 | Validation rule on Leads: reject when Expected Seats is less than 1, with the message "Expected Seats must be 1 or more." | Setup | Claude in Chrome, from the copied prompt | Setup → Modules and Fields → Leads → Validation Rules shows the rule |

These lanes are what LiftOff's lane rules give for items of these shapes (SelfCheck covers them).
The agent's wording differs from run to run; the lanes should not.

## Before you start

- The org is a sandbox, or its id is in `IMPL_BUILD_ORG_IDS` on `uat_generator`.
- No yellow "The server is behind this page" notice at the top of LiftOff. If there is one, deploy
  with the command it shows — until then the lanes can be wrong.
- Claude in Chrome is installed and signed in, in the same Chrome profile that is signed in to
  Zoho CRM. It is started from its side panel; it cannot be started from LiftOff.
- The org does not already have these items from an earlier demo (see "Running it again").

## The run

1. **Brief** — attach `liftoff-showcase-brd.txt`. Four requirements, module Leads.
2. **Blueprint** — generate. Expect two fields, one workflow rule and one validation rule; three
   tagged *API* and one tagged *you, in Setup*. If the agent also proposes a Deluge function for
   FR-3, LiftOff excludes it with a note: a rule already covers that requirement. Approve.
3. **Build** — the dry run shows **Will create via API 3** and **Queued for UI work 1**. Confirm
   the org; LiftOff creates the two fields, then the rule and its actions, and shows each call.
4. **Needs you → Copy for Claude in Chrome** — switch to the CRM tab, open Claude's side panel and
   paste. Claude opens Setup, fills in the validation rule and stops before Save for your OK.
   Back in LiftOff, press **I've done this** on the item.
5. **Approve the build**, then **Verify** — LiftOff reads the fields and the rule back. Validation
   rules have no read API either, so that one asks you to confirm it.
6. **UAT** — cases A to D run on real records, which are deleted afterwards.

What to say while it runs: where Zoho has an API LiftOff uses it; where it doesn't, the browser
does the clicking; nothing is saved in either lane without your approval.

## If it looks different

- **A Deluge function is queued for Setup, or "Will create via API" is 0** — the server is on an
  older build than the page, or the blueprint was made before the builders existed. The notice at
  the top says which; a fresh dry run takes the lanes from the server and moves the items.
- **Items say "Already in org"** — an earlier run built them. That is correct behaviour, not a
  failure: LiftOff never builds something twice.
- **Criterion C fails in UAT** — the UAT run saves records through the API. If the org does not
  apply validation rules to API saves, check C once by hand on the Leads form.
- **Claude in Chrome isn't available** — it needs a paid Claude plan and may be switched off by an
  administrator. Use **Send to Zoho RPA** or the Playwright runner for the same item, or do the
  five steps shown under it by hand.

## Running it again

Remove what the last run created, in this order (a field that a rule uses can't be deleted first):

1. Setup → Workflow Rules → delete the Leads rule for hot leads.
2. Setup → Modules and Fields → Leads → Validation Rules → delete the Expected Seats rule.
3. Setup → Modules and Fields → Leads → delete the fields Buying Interest and Expected Seats.
4. Lead Status field → remove the option Priority Follow-up.

Or point LiftOff at a fresh sandbox.
