package com.uat.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline implementation spec for LLM_PROVIDER=mock. Deterministic and shallow:
 * backtick-quoted names in the BRD become fields on the first module, "new ... "
 * quoted names become custom modules, and each requirement gets a workflow.
 * Enough to exercise review, apply (dry run), verify and the UI without an LLM.
 */
public final class MockImplClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BACKTICK = Pattern.compile("`([A-Za-z][A-Za-z0-9 _()]{1,40})`");
    private static final Pattern NEW_THING = Pattern.compile("new \"([A-Z][A-Za-z ]{2,40})\"");
    private static final Set<String> STANDARD = new HashSet<>(Arrays.asList(
            "owner", "country", "state", "city", "email", "phone", "company", "first_name",
            "last_name", "lead_source", "stage", "amount", "account_name", "contact_name"));

    private MockImplClient() {}

    public static String generate(String brd, List<String> modules, List<Requirements.Requirement> reqs) {
        String module = modules.isEmpty() ? "Leads" : modules.get(0);
        String firstReq = reqs.isEmpty() ? "" : reqs.get(0).id;
        ObjectNode root = MAPPER.createObjectNode();
        root.put("summary", "Mock spec for " + module + " derived from the BRD's named fields and requirements.");

        ArrayNode mods = root.putArray("modules");
        Matcher nm = NEW_THING.matcher(brd);
        Set<String> newModules = new LinkedHashSet<>();
        while (nm.find()) newModules.add(nm.group(1).trim());
        for (String m : newModules) {
            ObjectNode o = mods.addObject();
            o.put("singular_label", m);
            o.put("plural_label", m.endsWith("s") ? m : m + "s");
            o.put("kind", "custom");
            o.put("purpose", "Named as new in the BRD.");
            reqIds(o, firstReq);
        }

        ArrayNode fields = root.putArray("fields");
        Matcher bm = BACKTICK.matcher(brd);
        Set<String> seen = new LinkedHashSet<>();
        while (bm.find()) {
            String label = bm.group(1).replaceAll("\\(.*\\)", "").replace('_', ' ').trim();
            if (label.isEmpty() || STANDARD.contains(label.toLowerCase().replace(' ', '_'))) continue;
            if (!seen.add(label.toLowerCase())) continue;
            ObjectNode f = fields.addObject();
            f.put("module", module);
            f.put("label", label);
            f.put("data_type", "text");
            f.put("length", 120);
            reqIds(f, reqFor(brd, bm.start(), reqs));
            if (seen.size() >= 8) break;
        }

        ArrayNode flows = root.putArray("workflows");
        for (Requirements.Requirement r : reqs) {
            if (flows.size() >= 6) break;
            ObjectNode w = flows.addObject();
            w.put("type", "workflow");
            w.put("module", module);
            w.put("name", r.title.isEmpty() ? r.id : r.title);
            w.put("trigger", "On create");
            w.put("criteria", "As described in " + r.id);
            w.putArray("actions").add("Field update / notification per " + r.id);
            // Structured form, so the API path can be exercised offline (dry run).
            w.put("trigger_type", "create");
            w.putArray("conditions");
            w.putArray("tasks").addObject().put("subject", "Follow up: " + (r.title.isEmpty() ? r.id : r.title)).put("due_in_days", 1);
            w.putArray("other_actions").add("Email alert to the record owner");
            reqIds(w, r.id);
        }

        root.putArray("layouts");
        root.putArray("global_picklists");
        root.putArray("dependencies");
        root.putArray("validation_rules");
        root.putArray("roles");
        root.putArray("functions");
        ArrayNode qs = root.putArray("open_questions");
        ObjectNode q = qs.addObject();
        q.put("question", "Mock mode: confirm field types and picklist values before applying.");
        q.put("context", "The mock provider marks every field as text.");
        q.put("blocking", false);
        return root.toString();
    }

    private static void reqIds(ObjectNode o, String id) {
        ArrayNode a = o.putArray("req_ids");
        if (id != null && !id.isEmpty()) a.add(id);
    }

    /** The requirement whose id appears last before this position in the BRD. */
    private static String reqFor(String brd, int pos, List<Requirements.Requirement> reqs) {
        String best = reqs.isEmpty() ? "" : reqs.get(0).id;
        int bestAt = -1;
        List<String> ids = new ArrayList<>();
        for (Requirements.Requirement r : reqs) ids.add(r.id);
        for (String id : ids) {
            int at = brd.lastIndexOf(id, pos);
            if (at > bestAt) { bestAt = at; best = id; }
        }
        return best;
    }
}
