# Samples

Paste any of these BRDs into the demo UI to see realistic output.

| File | Module | What it covers |
|---|---|---|
| [brd-crm-lead-routing.md](brd-crm-lead-routing.md) | `crm.lead` | Region-based auto-assignment with audit + override |
| [brd-desk-sla-escalation.md](brd-desk-sla-escalation.md) | `desk.ticket` | SLA at-risk detection + L1/L2 escalation |
| [golden-output-crm-lead-routing.json](golden-output-crm-lead-routing.json) | `crm.lead` | Reference shape of `/generate` response for the lead-routing BRD |

The golden output is illustrative — the live LLM output will vary slightly in
wording and ordering. Use it as a contract for the JSON shape, not for an exact
string match.

## Try it locally with curl

```bash
curl -s -X POST http://localhost:9000/server/uat_generator/generate \
  -H 'Content-Type: application/json' \
  -d @- <<EOF
{
  "module": "crm.lead",
  "project_key": "ACME-UAT",
  "brd": $(jq -Rs . < samples/brd-crm-lead-routing.md)
}
EOF
```
