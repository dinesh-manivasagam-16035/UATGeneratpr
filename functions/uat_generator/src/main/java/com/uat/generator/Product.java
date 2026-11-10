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

    /** One row of the capability matrix. */
    final class Capability {
        public final String kind, need, lane, how;

        public Capability(String kind, String need, String lane, String how) {
            this.kind = kind; this.need = need; this.lane = lane; this.how = how == null ? "" : how;
        }
    }
}
