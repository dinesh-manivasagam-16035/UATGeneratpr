# BRD: Auto-route inbound leads by region

## Background
Sales managers complain that inbound leads land in a generic "Unassigned" queue
and sit there for hours before a rep picks them up. We want CRM to auto-assign
each new lead to the right regional rep on creation, based on the lead's
country/state.

## Goals
1. Reduce average lead-to-first-touch time from 4h to <30min.
2. Eliminate manual triage from the sales-ops team.

## Functional requirements

### FR-1: Region resolution
- On lead create, derive `Region` from `Country` (and `State` for US/IN/AU).
- Mapping table is configurable by Sales Ops admins in a new "Region Mapping"
  settings page. Each row: `Country`, `State (optional)`, `Region`,
  `Assigned_User`.
- Fallback region "ROW" maps to a round-robin pool of 3 global reps.

### FR-2: Auto-assignment
- After region is resolved, set `Owner` to the rep mapped to that region.
- If the mapped rep is on PTO (read from HR system via integration), skip to the
  next rep in the round-robin pool for that region.
- Log every assignment decision (input fields + chosen owner + reason) to an
  audit table viewable by Sales Ops admins.

### FR-3: Notifications
- New lead owner receives an in-app notification and an email within 60 seconds
  of lead create.
- Email includes lead name, company, phone, source, and a deep link to the lead.

### FR-4: Override
- Sales Ops admins can manually reassign a lead even after auto-assignment.
- Manual reassignments must update the audit log with the admin's user id and
  a free-text reason (mandatory).

## Non-functional
- Auto-assignment must complete within 5 seconds of lead create p95.
- No regression in existing duplicate-detection or blueprint flows.

## Out of scope
- Lead scoring changes.
- Round-robin for non-ROW regions (single mapped rep is enough for v1).

## Acceptance
A. Inbound web-form lead from California gets owned by the configured CA rep
   within 5s, with notification fired.
B. Inbound lead from Argentina (no mapping) gets owned via ROW round-robin.
C. Admin reassignment from rep A to rep B with reason "rep on leave" appears
   in the audit log with the admin's id.
D. If the auto-assigned rep is on PTO, the next round-robin rep is picked.
