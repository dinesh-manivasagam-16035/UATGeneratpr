# BRD: Auto-escalate tickets nearing SLA breach

## Background
Support managers find out about SLA breaches only after they've happened, when
the weekly SLA report is generated. We want proactive escalation so a manager
can intervene before the breach.

## Goals
1. Cut SLA breach rate from 8% to <2%.
2. Give team leads a real-time view of at-risk tickets.

## Functional requirements

### FR-1: At-risk detection
- A ticket is "at risk" when remaining_sla_time <= 25% of the original SLA
  window AND status is not Closed/Resolved.
- Checked every 2 minutes via background job. (System owns the cadence; out of
  scope for UAT to verify cron timing — UAT verifies state transitions.)

### FR-2: Escalation actions
When a ticket transitions to at-risk:
- Add internal-only comment: "Auto-escalated: SLA at-risk".
- Set `Escalation_Status` field to "L1".
- Notify the team lead of the Department via in-app + email.
- If ticket remains at-risk after another 50% of remaining time elapses,
  transition to "L2": notify Department manager.

### FR-3: De-escalation
- If the ticket status changes to Resolved or Closed, `Escalation_Status`
  resets to "None".
- Manual de-escalation by team lead with a reason is allowed at any time.

### FR-4: Visibility
- New saved view "At-Risk Tickets" available to team leads and above.
- Dashboard widget shows count of L1 and L2 at-risk per department.

## Non-functional
- Escalation should not change the ticket's `Modified_Time` (so first-response
  metrics aren't polluted).

## Out of scope
- Customer-facing comments on escalation.
- SLA policy editing UI.

## Acceptance
A. Ticket created with 1h SLA: at-risk fires at 15m remaining; L1 actions
   applied; team lead notified.
B. Ticket de-escalated manually by team lead with reason "customer agreed to
   wait" — `Escalation_Status` returns to "None"; audit shows manual action.
C. Ticket reaches L2 — Department manager notified, comment added.
D. Closing the ticket while at L1 resets `Escalation_Status` to "None" and no
   further escalation fires.
