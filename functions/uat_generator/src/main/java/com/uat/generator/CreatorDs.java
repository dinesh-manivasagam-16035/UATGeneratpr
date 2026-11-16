package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a LiftOff blueprint into a Zoho Creator application file (.ds) that Creator imports in one
 * step (Solutions → Create Solution → Applications → Import from file). Creator's REST API cannot
 * create forms, fields, reports or workflows, so this file is the build lane for Creator.
 *
 * The dialect is the one real exports use (checked against two applications exported from Creator):
 * forms and lists sit inside top-level {@code section} blocks, every list carries quickview and
 * detailview blocks, and scripts sit in a top-level {@code workflow { form { … } }} block.
 *
 * Field types marked UNVERIFIED below were not present in the exports the syntax was checked
 * against; the result lists every field that used one, so a failed import points at them first.
 */
final class CreatorDs {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String T = "\t";
    private static final String ICON = "ui-1-lock-circle";        // an icon name known to be accepted

    private CreatorDs() {}

    /** One generated file plus what went into it and what was left out. */
    static final class Result {
        String ds = "";
        final List<String> forms = new ArrayList<>(), reports = new ArrayList<>(), workflows = new ArrayList<>();
        final List<String[]> skipped = new ArrayList<>();        // {id, label, why}
        final List<String> unverified = new ArrayList<>();       // "Form.Field (type)"

        ObjectNode json(String appName) {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("filename", linkName(appName, "App") + ".ds");
            o.put("application", appName);
            o.put("ds", ds);
            ArrayNode f = o.putArray("forms"); forms.forEach(f::add);
            ArrayNode r = o.putArray("reports"); reports.forEach(r::add);
            ArrayNode w = o.putArray("workflows"); workflows.forEach(w::add);
            ArrayNode u = o.putArray("unverified"); unverified.forEach(u::add);
            ArrayNode s = o.putArray("skipped");
            for (String[] x : skipped) s.addObject().put("id", x[0]).put("label", x[1]).put("why", x[2]);
            return o;
        }
    }

    /** A Creator link name: letters, digits and underscores, starting with a letter. */
    static String linkName(String s, String fallback) {
        String n = (s == null ? "" : s).trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (n.isEmpty()) n = fallback;
        if (!Character.isLetter(n.charAt(0))) n = fallback + "_" + n;
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static String q(String s) { return (s == null ? "" : s).replace("\\", "\\\\").replace("\"", "\\\""); }

    /** LiftOff field type → {Creator type, "verified"|"unverified"}. */
    static String[] type(String liftoff) {
        switch (liftoff == null ? "" : liftoff.toLowerCase(Locale.ROOT)) {
            case "textarea":            return new String[]{"textarea", "verified"};
            case "email":               return new String[]{"email", "verified"};
            case "phone":               return new String[]{"phonenumber", "verified"};
            case "website":             return new String[]{"url", "verified"};
            case "picklist":            return new String[]{"picklist", "verified"};
            case "multiselectpicklist": return new String[]{"list", "verified"};
            case "integer": case "bigint": return new String[]{"number", "verified"};
            case "currency":            return new String[]{"USD", "verified"};
            case "boolean":             return new String[]{"checkbox", "verified"};
            case "datetime":            return new String[]{"datetime", "verified"};
            case "lookup":              return new String[]{"picklist", "verified"};
            case "date":                return new String[]{"date", "unverified"};
            case "decimal":             return new String[]{"decimal", "unverified"};
            case "percent":             return new String[]{"percentage", "unverified"};
            default:                    return new String[]{"text", "verified"};
        }
    }

    /** "on add" | "on edit" | "on add or edit" | "on delete" from a trigger in words. */
    static String recordEvent(String trigger) {
        String t = trigger == null ? "" : trigger.toLowerCase(Locale.ROOT);
        if (t.contains("delet")) return "on delete";
        boolean add = t.contains("creat") || t.contains("add") || t.contains("submit") || t.contains("new");
        boolean edit = t.contains("edit") || t.contains("updat") || t.contains("modif") || t.contains("chang");
        if (add && edit) return "on add or edit";
        return edit ? "on edit" : "on add";
    }

    /** "load" | "validate" | "success" from a trigger in words. */
    static String moment(String trigger) {
        String t = trigger == null ? "" : trigger.toLowerCase(Locale.ROOT);
        if (t.contains("load") || t.contains("open")) return "load";
        if (t.contains("validat") || t.contains("before")) return "validate";
        return "success";
    }

    /** Builds the .ds for every new form in the spec, with its fields, a list report and the Deluge the spec carries. */
    static Result generate(JsonNode spec, String appName) {
        Result res = new Result();

        // ---- which forms the file creates ----
        Map<String, ObjectNode> forms = new LinkedHashMap<>();          // link name → {label, fields[]}
        Map<String, String> byAnyName = new LinkedHashMap<>();           // lower(api/label) → link name
        Set<String> existing = new LinkedHashSet<>();
        for (JsonNode m : spec.path("modules")) {
            String api = m.path("api_name").asText(m.path("plural_label").asText(""));
            if ("exists".equals(m.path("status").asText())) { existing.add(ImplSpec.low(api)); continue; }
            if (!m.path("include").asBoolean(true)) continue;
            addForm(forms, byAnyName, api, m.path("plural_label").asText(m.path("singular_label").asText(api)),
                    m.path("singular_label").asText(""), m.path("plural_label").asText(""));
        }
        for (JsonNode f : spec.path("fields")) {
            if (!f.path("include").asBoolean(true) || "exists".equals(f.path("status").asText())) continue;
            String module = f.path("module").asText("");
            if (module.isEmpty() || existing.contains(ImplSpec.low(module)) || byAnyName.containsKey(ImplSpec.low(module))) continue;
            // A field on a form the spec doesn't list as existing: the form is new too.
            if ("exists".equals(f.path("module_status").asText())) { existing.add(ImplSpec.low(module)); continue; }
            addForm(forms, byAnyName, module, module);
        }

        // ---- fields ----
        for (JsonNode f : spec.path("fields")) {
            if (!f.path("include").asBoolean(true)) continue;
            String id = f.path("id").asText(""), label = f.path("label").asText(f.path("api_name").asText(""));
            String form = byAnyName.get(ImplSpec.low(f.path("module").asText("")));
            if (form == null) {
                res.skipped.add(new String[]{id, label, "its form \"" + f.path("module").asText() + "\" already exists in the app — an import creates a new application, so add this field in the builder"});
                continue;
            }
            ObjectNode fl = MAPPER.createObjectNode();
            fl.put("name", linkName(f.path("api_name").asText(label), "Field"));
            fl.put("label", label);
            fl.put("spec_type", f.path("data_type").asText("text"));
            fl.put("required", f.path("required").asBoolean(false));
            fl.put("unique", f.path("unique").asBoolean(false));
            fl.set("values", f.path("picklist_values"));
            fl.put("lookup", f.path("lookup_module").asText(""));
            fl.put("id", id);
            forms.get(form).withArray("fields").add(fl);
        }

        // ---- emit ----
        List<String> out = new ArrayList<>();
        out.add("/*"); out.add(" * Author       : liftoff"); out.add(" * Generated on : " + stamp()); out.add(" * Version      : 1.0"); out.add(" */");
        out.add(" application \"" + q(appName) + "\""); out.add(" {");
        out.add(" \tdate format = \"dd-MMM-yyyy\""); out.add(" \ttime zone = \"" + q(timeZone(spec)) + "\""); out.add(" \ttime format = \"24-hr\"");
        for (Map.Entry<String, ObjectNode> e : forms.entrySet()) {
            String form = e.getKey();
            ObjectNode fm = e.getValue();
            if (fm.path("fields").size() == 0) {
                res.skipped.add(new String[]{"", fm.path("label").asText(), "the blueprint lists no fields for this form, and Creator needs at least one"});
                continue;
            }
            out.add(T + "section " + form + "_Section"); out.add(T + "{");
            out.add(T + T + "displayname= \"" + q(fm.path("label").asText()) + "\"");
            out.add(T + T + "icon = \"" + ICON + "\"");
            List<String> cols = new ArrayList<>();
            emitForm(out, form, fm, forms, byAnyName, cols, res);
            String report = "All_" + form;
            emitList(out, report, "All " + fm.path("label").asText(), form, cols);
            out.add(T + "}");
            res.forms.add(form);
            res.reports.add(report);
        }

        // ---- workflows: the Deluge the spec carries, on the forms this file creates ----
        List<String> wf = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode fn : spec.path("functions")) {
            if (!fn.path("include").asBoolean(true)) continue;
            String id = fn.path("id").asText(""), name = fn.path("name").asText("script");
            String form = byAnyName.get(ImplSpec.low(fn.path("module").asText("")));
            String code = fn.path("code").asText("").replace("\r\n", "\n").trim();
            if (form == null || !res.forms.contains(form)) { res.skipped.add(new String[]{id, name, "its form isn't one this file creates"}); continue; }
            if (code.isEmpty()) { res.skipped.add(new String[]{id, name, "the blueprint has no Deluge for it"}); continue; }
            if (code.matches("(?s)^\\s*(void|string|int|map|list|bool|float|date)\\s+[A-Za-z_][\\w.]*\\s*\\(.*")) {
                res.skipped.add(new String[]{id, name, "it is a standalone function (has its own declaration) — add it under Workflow → Functions in the builder"});
                continue;
            }
            String link = linkName(name, "Script");
            while (!names.add(link)) link = link + "_" + names.size();
            String trigger = fn.path("trigger").asText("");
            wf.add(T + T + link + " as \"" + q(fn.path("purpose").asText("").isEmpty() ? name : trim(fn.path("purpose").asText(), 80)) + "\"");
            wf.add(T + T + "{");
            wf.add(T + T + T + "type =  form");
            wf.add(T + T + T + "form = " + form);
            wf.add(T + T + T + "record event = " + recordEvent(trigger));
            wf.add("");
            wf.add(T + T + T + "on " + moment(trigger));
            wf.add(T + T + T + "{");
            wf.add(T + T + T + T + "actions ");
            wf.add(T + T + T + T + "{");
            wf.add(T + T + T + T + "custom deluge script");
            wf.add(T + T + T + T + "(");
            for (String line : code.split("\n")) wf.add(line.isEmpty() ? "" : T + T + T + T + T + line);
            wf.add(T + T + T + T + ")");
            wf.add(T + T + T + T + "}");
            wf.add(T + T + T + "}");
            wf.add(T + T + "}");
            res.workflows.add(link);
        }
        if (!wf.isEmpty()) {
            out.add(T + "workflow"); out.add(T + "{"); out.add(T + "form"); out.add(T + "{");
            out.addAll(wf);
            out.add(T + "}"); out.add(T + "}");
        }
        out.add(" }");

        for (JsonNode w : spec.path("workflows")) {
            if (w.path("include").asBoolean(true)) res.skipped.add(new String[]{w.path("id").asText(""), w.path("name").asText(""),
                    "described in words only — a Creator workflow is Deluge, so write it in the builder (or give it as a function with code)"});
        }
        for (JsonNode v : spec.path("validation_rules")) {
            if (v.path("include").asBoolean(true)) res.skipped.add(new String[]{v.path("id").asText(""), v.path("name").asText(""),
                    "an on-validate script must be written in Deluge from the condition — add it in the builder"});
        }
        for (JsonNode r : spec.path("roles")) {
            if (r.path("include").asBoolean(true)) res.skipped.add(new String[]{r.path("id").asText(""), r.path("name").asText(""),
                    "roles and permission sets are set under Users and Permissions after the import"});
        }
        res.ds = res.forms.isEmpty() ? "" : String.join("\n", out) + "\n";
        return res;
    }

    private static void addForm(Map<String, ObjectNode> forms, Map<String, String> byAnyName, String api, String label, String... aliases) {
        String link = linkName(api, "Form");
        if (forms.containsKey(link)) return;
        ObjectNode fm = MAPPER.createObjectNode();
        fm.put("label", label == null || label.isEmpty() ? api : label);
        fm.putArray("fields");
        forms.put(link, fm);
        byAnyName.put(ImplSpec.low(api), link);
        byAnyName.put(ImplSpec.low(link), link);
        byAnyName.putIfAbsent(ImplSpec.low(fm.path("label").asText()), link);
        for (String a : aliases) if (a != null && !a.isEmpty()) byAnyName.putIfAbsent(ImplSpec.low(a), link);
    }

    private static void emitForm(List<String> out, String form, ObjectNode fm, Map<String, ObjectNode> forms, Map<String, String> byAnyName, List<String> cols, Result res) {
        String p = T + T, f1 = p + T, f2 = f1 + T;
        out.add(p + "form " + form); out.add(p + "{");
        out.add(f1 + "displayname = \"" + q(fm.path("label").asText()) + "\"");
        out.add(f1 + "success message = \"Data Added Successfully!\"");
        out.add(f1 + "field alignment = left");
        out.add(f1 + "Section"); out.add(f1 + "("); out.add(f2 + "type = section"); out.add(f2 + "row = 1"); out.add(f2 + "column = 0"); out.add(f2 + "width = medium"); out.add(f1 + ")");
        Set<String> used = new LinkedHashSet<>();
        for (JsonNode fl : fm.path("fields")) {
            String name = fl.path("name").asText();
            while (!used.add(name.toLowerCase(Locale.ROOT))) name = name + "_" + used.size();
            String specType = fl.path("spec_type").asText("text");
            String[] ty = type(specType);
            String lookupForm = null, lookupShow = null;
            if ("lookup".equalsIgnoreCase(specType)) {
                lookupForm = byAnyName.get(ImplSpec.low(fl.path("lookup").asText("")));
                if (lookupForm != null && forms.get(lookupForm).path("fields").size() > 0) {
                    lookupShow = forms.get(lookupForm).path("fields").get(0).path("name").asText();
                    for (JsonNode cand : forms.get(lookupForm).path("fields")) {
                        if ("text".equals(type(cand.path("spec_type").asText())[0])) { lookupShow = cand.path("name").asText(); break; }
                    }
                } else {
                    res.skipped.add(new String[]{fl.path("id").asText(""), fl.path("label").asText(), "its lookup target \"" + fl.path("lookup").asText()
                            + "\" isn't a form this file creates — added as a single-line field; turn it into a lookup in the builder"});
                    ty = new String[]{"text", "verified"};
                    lookupForm = null;
                }
            }
            boolean choice = ty[0].equals("picklist") || ty[0].equals("list");
            if (choice && lookupForm == null && fl.path("values").size() == 0) {
                res.skipped.add(new String[]{fl.path("id").asText(""), fl.path("label").asText(), "it is a dropdown with no choices in the blueprint — added as a single-line field"});
                ty = new String[]{"text", "verified"};
                choice = false;
            }
            if ("unverified".equals(ty[1])) res.unverified.add(form + "." + name + " (" + ty[0] + ")");
            String head = (fl.path("required").asBoolean(false) ? (fl.path("unique").asBoolean(false) ? "must have unique " : "must have ") : "") + name;
            out.add(f1 + head); out.add(f1 + "(");
            out.add(f2 + "type = " + ty[0]);
            if (!fl.path("label").asText("").isEmpty()) out.add(f2 + "displayname = \"" + q(fl.path("label").asText()) + "\"");
            if (lookupForm != null) {
                out.add(f2 + "values  = " + lookupForm + ".ID");
                out.add(f2 + "displayformat = [" + lookupShow + "]");
                out.add(f2 + "sortorder = ascending");
            } else if (choice) {
                List<String> vals = new ArrayList<>();
                for (JsonNode v : fl.path("values")) vals.add("\"" + q(v.asText()) + "\"");
                out.add(f2 + "values = {" + String.join(", ", vals) + "}");
            }
            if (ty[0].equals("checkbox")) out.add(f2 + "initial value = false");
            if (ty[0].equals("datetime")) { out.add(f2 + "timedisplayoptions = \"hh:mm:ss\""); out.add(f2 + "alloweddays = 0,1,2,3,4,5,6"); }
            if (ty[0].equals("date")) out.add(f2 + "alloweddays = 0,1,2,3,4,5,6");
            if (ty[0].equals("list")) out.add(f2 + "height = 60px");
            out.add(f2 + "row = 1"); out.add(f2 + "column = 1");
            if (ty[0].equals("textarea")) out.add(f2 + "height = 100px");
            out.add(f2 + "width = medium");
            out.add(f1 + ")");
            if (cols.size() < 8) cols.add(fl.path("label").asText("").isEmpty() || fl.path("label").asText().equals(name) ? name : name + " as \"" + q(fl.path("label").asText()) + "\"");
        }
        out.add("");
        out.add(f1 + "actions"); out.add(f1 + "{");
        out.add(f2 + "on add"); out.add(f2 + "{");
        button(out, "submit", "submit", "Submit"); button(out, "reset", "reset", "Reset");
        out.add(f2 + "}");
        out.add(f2 + "on edit"); out.add(f2 + "{");
        button(out, "update", "submit", "Update"); button(out, "cancel", "cancel", "Cancel");
        out.add(f2 + "}");
        out.add(f1 + "}");
        out.add(p + "}");
    }

    private static void button(List<String> out, String name, String type, String label) {
        String b = T + T + T + T + T;
        out.add(b + name); out.add(b + "("); out.add(b + T + "type = " + type); out.add(b + T + "displayname = \"" + label + "\""); out.add(b + ")");
    }

    private static void block(List<String> out, int indent, String... lines) {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < indent; i++) p.append(T);
        for (String l : lines) out.add(p + l);
    }

    private static void emitList(List<String> out, String name, String label, String form, List<String> cols) {
        String[] c = cols.toArray(new String[0]);
        block(out, 2, "list " + name, "{");
        block(out, 3, "displayName = \"" + q(label) + "\"", "show all rows from " + form, "(");
        block(out, 4, c);
        block(out, 3, ")", "options", "(");
        block(out, 4, "icon = \"shopping-award\"");
        block(out, 3, ")", "quickview", "(");
        block(out, 4, "layout", "(");
        block(out, 5, "type = -1", "datablock1", "(");
        block(out, 6, "layout type = -1", "title = \"Overview\"", "fields", "(");
        block(out, 7, c);
        block(out, 6, ")");
        block(out, 5, ")");
        block(out, 4, ")", "menu", "(");
        block(out, 5, "header", "(");
        block(out, 6, "Edit", "Duplicate", "Delete", "Add", "Print", "Import", "Export");
        block(out, 5, ")", "record", "(");
        block(out, 6, "Edit", "Duplicate", "Print", "Delete");
        block(out, 5, ")");
        block(out, 4, ")", "action", "(");
        block(out, 5, "on click", "(");
        block(out, 6, "View Record");
        block(out, 5, ")", "on right click", "(");
        block(out, 6, "Edit", "Delete", "Duplicate", "Print", "View Record");
        block(out, 5, ")");
        block(out, 4, ")");
        block(out, 3, ")", "detailview", "(");
        block(out, 4, "layout", "(");
        block(out, 5, "type = 1", "datablock1", "(");
        block(out, 6, "layout type = -2", "title = \"Overview\"", "fields", "(");
        block(out, 7, c);
        block(out, 6, ")");
        block(out, 5, ")");
        block(out, 4, ")", "menu", "(");
        block(out, 5, "header", "(");
        block(out, 6, "Edit", "Duplicate", "Print", "Delete");
        block(out, 5, ")");
        block(out, 4, ")");
        block(out, 3, ")");
        block(out, 2, "}");
    }

    private static String timeZone(JsonNode spec) {
        String tz = spec.path("time_zone").asText("");
        return tz.isEmpty() ? "Asia/Kolkata" : tz;
    }

    private static String stamp() {
        return new java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(new java.util.Date());
    }

    private static String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max).trim(); }
}
