package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One Zoho product that LiftOff can implement into. Everything product-specific lives behind this
 * interface — vocabulary, OAuth scopes, what the API can build, how the agent is briefed — while
 * the pipeline (BRD parts, Brief → Blueprint → Build → Verify, Run everything, UAT) stays shared.
 *
 * Capability lanes:
 *   api      LiftOff builds it fully through the product's settings API
 *   partial  the API creates it; one short Setup step finishes it (e.g. an email template)
 *   ui       no create API; the guided runner walks Setup with the person watching
 */
public interface Product {

    /** Stable id used in state and env vars: "crm", "desk", "creator". */
    String id();

    /** Display name: "Zoho CRM". */
    String label();

    /** What a "module" is called here: "module", "department", "form". */
    String entityWord();

    /** OAuth scopes Connect asks for. */
    List<String> oauthScopes();

    /** The capability matrix, in spec order. */
    List<Capability> capabilities();

    /** Lane for one spec item after it has been compared with the org (item.status is set). */
    String laneFor(String section, JsonNode item);

    /** The capability matrix as prompt text, so the agent proposes what the API can build. */
    default String capabilityPrompt() {
        StringBuilder sb = new StringBuilder("WHAT LIFTOFF CAN BUILD IN " + label().toUpperCase() + " (prefer the first lane that fits):\n");
        for (Capability c : capabilities()) {
            sb.append("- ").append(c.need).append(": ").append(c.lane.toUpperCase());
            if (!c.how.isEmpty()) sb.append(" — ").append(c.how);
            sb.append('\n');
        }
        sb.append("Lanes: API = built automatically; PARTIAL = built by API, one Setup step finishes it; "
                + "UI = no create API, a person does it in Setup with the guided runner. Never propose an item "
                + "that needs a lane this product doesn't offer for it; choose the API construct that meets the "
                + "same requirement instead, and say so in its description.");
        return sb.toString();
    }

    /** System prompt for one blueprint group ("data_model" or "automation"). */
    String systemPrompt(String group);

    /** Reads the connected org into the neutral model the pipeline compares against. */
    ImplOrg readOrg(CrmClient client, java.util.Collection<String> moduleNames, boolean withLayouts);

    /** Whether LiftOff's API builders exist for this product yet (else every build item goes to the guided runner). */
    default boolean buildersReady() { return true; }

    /** Setup menu path for a kind of item, for the guided steps: "Setup → Customization → Modules and Fields". */
    default String setupRoot(String section) { return "Setup → Customization → Modules and Fields"; }

    /** Setup menu path for an automation type ("workflow", "blueprint", "assignment", "sla", …), for the guided steps. */
    default String automationMenu(String type) {
        switch (type == null ? "" : type) {
            case "blueprint":  return "Setup → Process Management → Blueprint → Create Blueprint.";
            case "assignment": return "Setup → Automation → Assignment Rules → New Rule.";
            case "approval":   return "Setup → Process Management → Approval Processes → Create Approval Process.";
            case "scoring":    return "Setup → Automation → Scoring Rules → New Scoring Rule.";
            case "escalation": return "Setup → Automation → Workflow Rules → Create Rule (date/time based) for the escalation.";
            case "schedule":   return "Setup → Automation → Schedules → Create Schedule.";
            case "cadence":    return "Setup → Automation → Cadences → Create Cadence.";
            case "webhook":    return "Setup → Automation → Actions → Webhooks → Configure Webhook.";
            default:           return "Setup → Automation → Workflow Rules → Create Rule.";
        }
    }

    /** Modules the BRD may target when the org can't be read (the module picker's fallback). */
    default List<String> defaultModules() { return java.util.Arrays.asList("Leads", "Contacts", "Accounts", "Deals"); }

    /** One row of the capability matrix. */
    final class Capability {
        public final String kind, need, lane, how;

        public Capability(String kind, String need, String lane, String how) {
            this.kind = kind; this.need = need; this.lane = lane; this.how = how == null ? "" : how;
        }
    }
}
