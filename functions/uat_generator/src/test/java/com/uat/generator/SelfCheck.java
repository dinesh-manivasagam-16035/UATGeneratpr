package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Dependency-free regression checks for the pure parts of the implementation pipeline: parsing the
 * agent's JSON, deriving the API form of a rule, lanes vs. the capability matrix, the execution
 * plan, failure diagnostics and the runner's recipe parsers. Run with functions/uat_generator/selfcheck.sh.
 */
public final class SelfCheck {
    private static final ObjectMapper M = new ObjectMapper();
    private static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Products.use(M.readTree("{\"platform\":\"crm\"}"));
        parseAndNormalize();
        structuredRuleMirror();
        lanesAgreeWithMatrix(CrmProduct.INSTANCE);
        lanesAgreeWithMatrix(DeskProduct.INSTANCE);
        executionPlan();
        functionDefinitions();
        blueprintPayload();
        profilePermissions();
        timeBasedRules();
        pipelinePayload();
        emailAlerts();
        dependencies();
        globalPicklists();
        sharingAndTerritories();
        duplicateFunctions();
        diagnostics();
        recipeParsers();
        deskFieldMapping();
        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ---- checks ----

    static void parseAndNormalize() throws Exception {
        String reply = "```json\n{\"summary\":\"x\",\"fields\":[{\"module\":\"Leads\",\"label\":\"PIDG Code\",\"data_type\":\"text\",\"length\":50,"
                + "\"required\":false,\"assumption\":\"text, 50 characters, optional\",\"req_ids\":[\"FR-1\"]}],"
                + "\"workflows\":[{\"type\":\"workflow\",\"module\":\"Leads\",\"name\":\"Flag Leads Without PIDG Code\",\"trigger_type\":\"create\","
                + "\"conditions\":[{\"field\":\"PIDG_Code\",\"comparator\":\"is_empty\",\"value\":\"\"}],\"match\":\"and\","
                + "\"field_updates\":[{\"field\":\"Lead_Status\",\"value\":\"PIDG Pending\"}],\"tasks\":[],\"other_actions\":[],\"req_ids\":[\"FR-2\"]}],"
                + "\"open_questions\":[]}\n```";
        ImplSpec.Parsed p = ImplSpec.parse(reply, Arrays.asList("fields", "workflows", "open_questions"));
        check("parse: ok", p.ok());
        check("parse: one field", p.items.get("fields").size() == 1);
        ObjectNode f = p.items.get("fields").get(0);
        check("normalize: api_name derived", "PIDG_Code".equals(f.path("api_name").asText()));
        check("normalize: assumption kept", f.path("assumption").asText().startsWith("text, 50"));
        ObjectNode w = p.items.get("workflows").get(0);
        check("normalize: trigger mirror", "On create".equals(w.path("trigger").asText()));
        check("normalize: criteria mirror", w.path("criteria").asText().contains("PIDG_Code is empty"));
        check("normalize: actions mirror", w.path("actions").size() == 1 && w.path("actions").get(0).asText().contains("Lead_Status"));
    }

    static void structuredRuleMirror() throws Exception {
        // A rule the agent described only in words gets its API form from code.
        ObjectNode spec = emptySpec();
        ObjectNode w = M.createObjectNode().put("type", "workflow").put("module", "Leads").put("name", "Flag")
                .put("trigger", "On create").put("criteria", "When PIDG Code is empty");
        w.putArray("actions").add("Set Lead Status to PIDG Pending");
        w.putArray("field_updates"); w.putArray("tasks"); w.putArray("conditions"); w.putArray("other_actions"); w.putArray("req_ids");
        spec.withArray("workflows").add(w);
        spec.withArray("fields").add(M.createObjectNode().put("module", "Leads").put("label", "PIDG Code").put("api_name", "PIDG_Code").put("data_type", "text"));
        WorkflowStructurer.structureAll(spec, null);
        JsonNode r = spec.path("workflows").get(0);
        check("structurer: trigger_type", "create".equals(r.path("trigger_type").asText()));
        check("structurer: condition", r.path("conditions").size() == 1 && "is_empty".equals(r.path("conditions").get(0).path("comparator").asText()));
        check("structurer: field update", r.path("field_updates").size() == 1 && "PIDG Pending".equals(r.path("field_updates").get(0).path("value").asText()));
        check("structurer: lane api", "api".equals(CrmProduct.INSTANCE.laneFor("workflows", r)));
    }

    static void lanesAgreeWithMatrix(Product prod) {
        // Every capability lane the matrix advertises as "api" must have at least one item shape that laneFor accepts as api,
        // unless the product says its builders aren't ready (then everything but review/plan is rpa).
        boolean anyApi = false;
        for (Product.Capability c : prod.capabilities()) if ("api".equals(c.lane)) anyApi = true;
        if (!prod.buildersReady()) {
            ObjectNode f = M.createObjectNode().put("module", "Tickets").put("label", "x").put("data_type", "text").put("status", "new");
            check(prod.id() + ": builders not ready → rpa", "rpa".equals(prod.laneFor("fields", f)));
            return;
        }
        ObjectNode f = M.createObjectNode().put("module", "Leads").put("label", "x").put("data_type", "text").put("status", "new");
        check(prod.id() + ": text field is api", "api".equals(prod.laneFor("fields", f)));
        ObjectNode a = M.createObjectNode().put("type", "assignment").put("module", "Leads").put("status", "new");
        ObjectNode e = a.putArray("rule_entries").addObject(); e.putArray("conditions").addObject().put("field", "Email").put("comparator", "contains").put("value", "x"); e.putArray("assign_to").add("a@b.c");
        check(prod.id() + ": assignment rule is api", "api".equals(prod.laneFor("workflows", a)));
        ObjectNode lo = M.createObjectNode().put("module", "Leads").put("name", "Standard").put("status", "new");
        lo.putArray("sections").addObject().put("name", "PIDG").putArray("fields").add("PIDG Code"); lo.putArray("profiles");
        check(prod.id() + ": layout placement is api", "api".equals(prod.laneFor("layouts", lo)));
        ObjectNode vr = M.createObjectNode().put("module", "Leads").put("name", "v").put("status", "new");
        check(prod.id() + ": validation rule is rpa", "rpa".equals(prod.laneFor("validation_rules", vr)));
        check(prod.id() + ": matrix has api rows", anyApi);
        check(prod.id() + ": prompt carries the matrix", prod.systemPrompt("automation").contains("WHAT LIFTOFF CAN BUILD"));
        boolean consistent = true;
        for (Product.Capability c : prod.capabilities()) {
            if (!Arrays.asList("api", "partial", "ui").contains(c.lane)) consistent = false;
            if ("builds".equals(c.status) && !"api".equals(c.lane)) consistent = false;        // builds ⇒ API lane
            if ("planned".equals(c.status) && "api".equals(c.lane)) consistent = false;         // planned ⇒ not yet an API lane
            if ("planned".equals(c.status) && c.api.isEmpty()) consistent = false;              // planned needs the endpoint it will call
        }
        check(prod.id() + ": sheet rows consistent (lane vs status vs api)", consistent);
    }

    static void sharingAndTerritories() throws Exception {
        String reply = "{\"summary\":\"x\",\"sharing\":["
                + "{\"type\":\"territory\",\"name\":\"West India\",\"parent\":\"India\",\"manager\":\"asha@x.com\",\"permission_type\":\"read write delete\","
                + "\"criteria\":[{\"module\":\"Accounts\",\"field\":\"Billing_State\",\"comparator\":\"equal\",\"value\":\"Maharashtra\"}],\"req_ids\":[\"FR-7\"]},"
                + "{\"type\":\"sharing_rule\",\"name\":\"Support sees Deals\",\"module\":\"Deals\",\"shared_to\":\"Support role and subordinates\","
                + "\"shared_from\":\"Sales Manager role\",\"permission_type\":\"read write\",\"superiors_allowed\":false,\"req_ids\":[\"FR-8\"]}],\"open_questions\":[]}";
        ImplSpec.Parsed p = ImplSpec.parse(reply, Arrays.asList("sharing", "open_questions"));
        ObjectNode t = p.items.get("sharing").get(0), sr = p.items.get("sharing").get(1);
        check("sharing: both kinds normalised", "territory".equals(t.path("type").asText()) && "India".equals(t.path("parent").asText())
                && "sharing_rule".equals(sr.path("type").asText()) && "Deals".equals(sr.path("module").asText()) && !sr.path("superiors_allowed").asBoolean());
        check("sharing: permissions mapped", "read_write_delete".equals(ImplSharing.territoryPermission(t.path("permission_type").asText()))
                && "read_write".equals(ImplSharing.sharePermission(sr.path("permission_type").asText())) && "read".equals(ImplSharing.sharePermission("view only")));
        java.util.function.Function<String, JsonNode> fieldOf = (api) -> M.createObjectNode().put("api_name", api).put("id", "F_" + api);
        JsonNode tb = ImplSharing.territory(t, "T_India", "U1", fieldOf).path("territories").path(0);
        check("sharing: territory payload", "T_India".equals(tb.path("reporting_to").path("id").asText()) && "U1".equals(tb.path("manager").path("id").asText())
                && "F_Billing_State".equals(tb.path("account_rule_criteria").path("field").path("id").asText()) && "Maharashtra".equals(tb.path("account_rule_criteria").path("value").asText()));
        check("sharing: criteria key per module", "lead_rule_criteria".equals(ImplSharing.criteriaKey("Leads")) && "deal_rule_criteria".equals(ImplSharing.criteriaKey("Deals")) && ImplSharing.criteriaKey("Contacts").isEmpty());
        java.util.Map<String, String> roleIds = new HashMap<>(); roleIds.put("support", "R_S"); roleIds.put("sales manager", "R_M");
        java.util.Map<String, String> grp = new HashMap<>(); grp.put("india team", "G1");
        ObjectNode to = ImplSharing.audience("Support role and subordinates", roleIds, grp);
        check("sharing: audience role + subordinates", "roles".equals(to.path("type").asText()) && to.path("subordinates").asBoolean() && "R_S".equals(to.path("resource").path("id").asText()));
        check("sharing: audience group and all users", "G1".equals(ImplSharing.audience("India Team group", roleIds, grp).path("resource").path("id").asText())
                && "all_users".equals(ImplSharing.audience("all users", roleIds, grp).path("type").asText())
                && ImplSharing.audience("the finance crowd", roleIds, grp) == null);
        ObjectNode from = ImplSharing.audience(sr.path("shared_from").asText(), roleIds, grp);
        JsonNode rb = ImplSharing.sharingRule(sr, to, from, M.createArrayNode()).path("sharing_rules").path(0);
        check("sharing: owner-based rule payload", "Record_Owner_Based".equals(rb.path("type").asText()) && "R_M".equals(rb.path("shared_from").path("resource").path("id").asText())
                && "read_write".equals(rb.path("permission_type").asText()) && !rb.path("superiors_allowed").asBoolean());
        ArrayNode group = M.createArrayNode(); group.add(ImplSharing.condition(M.createObjectNode().put("field", "Stage").put("comparator", "equal").put("value", "Won"), fieldOf));
        JsonNode cb = ImplSharing.sharingRule(sr, to, null, group).path("sharing_rules").path(0);
        check("sharing: criteria-based rule payload", "Criteria_Based".equals(cb.path("type").asText()) && cb.path("shared_from").isNull()
                && "AND".equals(cb.path("criteria").path("group_operator").asText()) && "Stage".equals(cb.path("criteria").path("group").get(0).path("field").path("api_name").asText()));
        ObjectNode st = M.createObjectNode().put("status", "new");
        check("crm: both sharing kinds are api", "api".equals(CrmProduct.INSTANCE.laneFor("sharing", t.deepCopy().setAll(st)))
                && "api".equals(CrmProduct.INSTANCE.laneFor("sharing", sr.deepCopy().setAll(st))));
    }

    static void globalPicklists() throws Exception {
        String reply = "{\"summary\":\"x\",\"global_picklists\":[{\"name\":\"PIDG Stage\",\"values\":[\"Draft\",\"Submitted\",\"Approved\"],\"description\":\"shared\",\"req_ids\":[\"FR-6\"]}],"
                + "\"fields\":[{\"module\":\"Leads\",\"label\":\"PIDG Stage\",\"data_type\":\"picklist\",\"global_picklist\":\"PIDG Stage\",\"picklist_values\":[],\"req_ids\":[\"FR-6\"]}],\"open_questions\":[]}";
        ImplSpec.Parsed p = ImplSpec.parse(reply, Arrays.asList("global_picklists", "fields", "open_questions"));
        ObjectNode g = p.items.get("global_picklists").get(0), f = p.items.get("fields").get(0);
        check("gpl: set normalised with ordered values", g.path("values").size() == 3 && "Draft".equals(g.path("values").get(0).asText()));
        check("gpl: field keeps the set name and no empty-picklist note", "PIDG Stage".equals(f.path("global_picklist").asText()) && f.path("note").asText("").isEmpty());
        JsonNode body = ImplGlobalPicklists.payload(g).path("global_picklists").path(0);
        check("gpl: payload display_label and sequenced display_values", "PIDG Stage".equals(body.path("display_label").asText())
                && body.path("pick_list_values").size() == 3 && body.path("pick_list_values").get(2).path("sequence_number").asInt() == 3
                && "Approved".equals(body.path("pick_list_values").get(2).path("display_value").asText()) && !body.path("pick_list_values").get(0).has("actual_value"));
        ObjectNode have = M.createObjectNode(); have.putArray("global_picklists").addObject().put("id", "G1").put("api_name", "PIDG_Stage").put("display_label", "PIDG Stage");
        check("gpl: existing set matched by label or api name", "G1".equals(ImplGlobalPicklists.existing(have, "pidg stage").path("id").asText())
                && "G1".equals(ImplGlobalPicklists.existing(have, "PIDG_Stage").path("id").asText()) && ImplGlobalPicklists.existing(have, "Other") == null);
        check("crm: global picklist lane is api", "api".equals(CrmProduct.INSTANCE.laneFor("global_picklists", g.deepCopy().put("status", "new"))));
        ObjectNode spec = emptySpec(); spec.withArray("global_picklists").add(g); ImplSpec.finish(spec);
        check("gpl: id prefix GPL", "GPL-1".equals(spec.path("global_picklists").get(0).path("id").asText()));
    }

    static void dependencies() throws Exception {
        String reply = "{\"summary\":\"x\",\"dependencies\":[{\"module\":\"Leads\",\"parent\":\"Industry\",\"child\":\"Sub_Industry\","
                + "\"mapping\":{\"Banking\":[\"Retail Banking\",\"Investment\"],\"Insurance\":[\"Life\"]},\"req_ids\":[\"FR-5\"]}],\"open_questions\":[]}";
        ImplSpec.Parsed p = ImplSpec.parse(reply, Arrays.asList("dependencies", "open_questions"));
        ObjectNode d = p.items.get("dependencies").get(0);
        ObjectNode spec = emptySpec(); spec.withArray("dependencies").add(d); ImplSpec.finish(spec);
        check("deps: normalised with id prefix and mapping", spec.path("dependencies").get(0).path("id").asText().equals("DEP-1") && d.path("mapping").path("Banking").size() == 2 && "Industry → Sub_Industry".equals(d.path("name").asText()));
        check("crm: dependency lane is api", "api".equals(CrmProduct.INSTANCE.laneFor("dependencies", d.deepCopy().put("status", "new"))));
        ObjectNode parent = M.createObjectNode().put("api_name", "Industry").put("id", "P").put("data_type", "picklist");
        ArrayNode pp = parent.putArray("pick_list_values"); pp.addObject().put("id", "p1").put("actual_value", "Banking").put("display_value", "Banking"); pp.addObject().put("id", "p2").put("actual_value", "Insurance").put("display_value", "Insurance");
        ObjectNode child = M.createObjectNode().put("api_name", "Sub_Industry").put("id", "C").put("data_type", "picklist");
        ArrayNode cp = child.putArray("pick_list_values"); cp.addObject().put("id", "c1").put("actual_value", "Retail Banking").put("display_value", "Retail Banking"); cp.addObject().put("id", "c3").put("actual_value", "Life").put("display_value", "Life");
        check("deps: missing options detected", ImplDependencies.missing(child, ImplDependencies.childValues(d.path("mapping"))).equals(Arrays.asList("Investment")) && ImplDependencies.missing(parent, ImplDependencies.parentValues(d.path("mapping"))).isEmpty());
        cp.addObject().put("id", "c2").put("actual_value", "Investment").put("display_value", "Investment");
        JsonNode body = ImplDependencies.payload(parent, child, d.path("mapping")).path("map_dependency").path(0);
        check("deps: payload maps parent option ids to child option ids", "P".equals(body.path("parent").path("id").asText()) && body.path("pick_list_values").size() == 2
                && "p1".equals(body.path("pick_list_values").get(0).path("id").asText()) && body.path("pick_list_values").get(0).path("maps").size() == 2 && "c2".equals(body.path("pick_list_values").get(0).path("maps").get(1).path("id").asText()));
        ObjectNode have = M.createObjectNode(); ObjectNode ex = have.putArray("map_dependency").addObject(); ex.putObject("parent").put("api_name", "Industry"); ex.putObject("child").put("api_name", "Sub_Industry");
        check("deps: existing pair detected", ImplDependencies.existing(have, "industry", "Sub_Industry") != null && ImplDependencies.existing(have, "Industry", "Other") == null);
    }

    static void emailAlerts() {
        java.util.Map<String, String> roles = new HashMap<>(); roles.put("sales manager", "R1");
        java.util.function.Function<String, JsonNode> fieldOf = (api) -> api.equals("Email") ? M.createObjectNode().put("api_name", "Email").put("data_type", "email")
                : api.equals("Account_Name") ? M.createObjectNode().put("api_name", "Account_Name").put("data_type", "lookup") : null;
        check("alerts: record owner → merge field", "${!Leads.Owner}".equals(ImplEmailAlerts.recipient("record owner", "Leads", roles, fieldOf).path("details").path("api_name").asText()));
        check("alerts: owner's manager", "${!Leads.Owner.Reporting_To}".equals(ImplEmailAlerts.recipient("owner's manager", "Leads", roles, fieldOf).path("details").path("api_name").asText()));
        check("alerts: role by name", "R1".equals(ImplEmailAlerts.recipient("Sales Manager", "Leads", roles, fieldOf).path("resource").path("id").asText()));
        check("alerts: email field and lookup email", "${!Leads.Email}".equals(ImplEmailAlerts.recipient("Email", "Leads", roles, fieldOf).path("details").path("api_name").asText())
                && "${!Leads.Account_Name.Email}".equals(ImplEmailAlerts.recipient("Account_Name", "Leads", roles, fieldOf).path("details").path("api_name").asText()));
        check("alerts: literal address", "emails".equals(ImplEmailAlerts.recipient("ops@example.com", "Leads", roles, fieldOf).path("type").asText()));
        check("alerts: unknown recipient is null", ImplEmailAlerts.recipient("the finance team", "Leads", roles, fieldOf) == null);
        ObjectNode tpls = M.createObjectNode(); tpls.putArray("email_templates").addObject().put("id", "T1").put("name", "Big Deal Alert");
        check("alerts: template by name", "T1".equals(ImplEmailAlerts.template(tpls, "big deal alert").path("id").asText()) && ImplEmailAlerts.template(tpls, "Nope") == null);
        ArrayNode to = M.createArrayNode(); to.add(ImplEmailAlerts.recipient("record owner", "Deals", roles, fieldOf));
        JsonNode body = ImplEmailAlerts.body("Rule: X! - email 1", "Deals", "M1", ImplEmailAlerts.template(tpls, "Big Deal Alert"), to).path("email_notifications").path(0);
        check("alerts: body shape, safe name", "Rule X - email 1".equals(body.path("name").asText()) && "T1".equals(body.path("template").path("id").asText()) && body.path("recipients").path("to").size() == 1 && "workflow".equals(body.path("feature_type").asText()));
        ObjectNode wf = M.createObjectNode().put("type", "workflow").put("trigger_type", "create").put("status", "new");
        wf.putArray("email_alerts").addObject().put("template", "Big Deal Alert").putArray("to").add("record owner");
        check("crm: rule with only an email alert is api", "api".equals(CrmProduct.INSTANCE.laneFor("workflows", wf)));
    }

    static void pipelinePayload() {
        ObjectNode wf = M.createObjectNode().put("type", "pipeline").put("module", "Deals").put("name", "Partner Sales").put("default", false);
        wf.putArray("states").add("Qualification").add("Proposal").add("Closed Won");
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>(); ids.put("Qualification", "s1"); ids.put("Proposal", "s2"); ids.put("Closed Won", "s3");
        JsonNode body = ImplPipeline.payload(wf, ImplBlueprint.states(wf), ids).path("pipeline").path(0);
        check("pipeline: maps in sequence with stage ids", body.path("maps").size() == 3 && body.path("maps").get(1).path("sequence_number").asInt() == 2 && "s2".equals(body.path("maps").get(1).path("id").asText()));
        check("pipeline: name and default", "Partner Sales".equals(body.path("display_value").asText()) && !body.path("default").asBoolean());
        ObjectNode have = M.createObjectNode(); have.putArray("pipeline").addObject().put("display_value", "Standard").put("id", "p1");
        check("pipeline: existing lookup", ImplPipeline.existing(have, "standard") != null && ImplPipeline.existing(have, "Partner Sales") == null);
        check("crm: pipeline lane is api", "api".equals(CrmProduct.INSTANCE.laneFor("workflows", wf.deepCopy().put("status", "new"))));
    }

    static void timeBasedRules() throws Exception {
        String reply = "{\"summary\":\"x\",\"workflows\":[" 
                + "{\"type\":\"time_based\",\"module\":\"Leads\",\"name\":\"Follow-up reminder\",\"trigger_type\":\"date_or_datetime\","
                + "\"date_trigger\":{\"field\":\"Follow_Up_Date\",\"offset\":-2,\"period\":\"business days\",\"time\":\"9:00\",\"recur\":\"once\"},"
                + "\"conditions\":[],\"tasks\":[{\"subject\":\"Call the lead\",\"due_in_days\":1}],\"req_ids\":[\"FR-3\"]},"
                + "{\"type\":\"workflow\",\"module\":\"Leads\",\"name\":\"Escalate untouched\",\"trigger_type\":\"create\",\"conditions\":[],"
                + "\"scheduled_actions\":[{\"after\":{\"unit\":24,\"period\":\"hours\"},\"field_updates\":[{\"field\":\"Lead_Status\",\"value\":\"Escalated\"}]}],\"req_ids\":[\"FR-4\"]}],"
                + "\"open_questions\":[]}";
        ImplSpec.Parsed p = ImplSpec.parse(reply, Arrays.asList("workflows", "open_questions"));
        ObjectNode t = p.items.get("workflows").get(0), e = p.items.get("workflows").get(1);
        check("time: time_based becomes a workflow with a date trigger", "workflow".equals(t.path("type").asText()) && "date_or_datetime".equals(t.path("trigger_type").asText())
                && "b_days".equals(t.path("date_trigger").path("period").asText()) && -2 == t.path("date_trigger").path("offset").asInt() && "9:00".equals(t.path("date_trigger").path("time").asText()));
        check("time: trigger mirror in words", t.path("trigger").asText().startsWith("2 business days before Follow_Up_Date at 9:00"));
        check("time: scheduled block normalised", e.path("scheduled_actions").size() == 1 && e.path("scheduled_actions").get(0).path("after").path("unit").asInt() == 24
                && e.path("actions").get(0).asText().startsWith("After 24 hours: set Lead_Status"));
        ObjectNode st = M.createObjectNode().put("status", "new");
        check("crm: date trigger with a task is api", "api".equals(CrmProduct.INSTANCE.laneFor("workflows", t.deepCopy().setAll(st))));
        check("crm: create + scheduled block is api", "api".equals(CrmProduct.INSTANCE.laneFor("workflows", e.deepCopy().setAll(st))));
        java.util.function.Function<String, JsonNode> fieldOf = (api) -> M.createObjectNode().put("api_name", api).put("id", "F_" + api);
        ObjectNode body = ImplRules.rule(t, "Leads", "M1", fieldOf, java.util.Collections.singletonList(new String[]{"tasks", "T1"}), java.util.Collections.emptyList(), "+05:30");
        JsonNode when = body.path("workflow_rules").get(0).path("execute_when");
        check("time: execute_when date_or_datetime shape", "date_or_datetime".equals(when.path("type").asText()) && "F_Follow_Up_Date".equals(when.path("details").path("field").path("id").asText())
                && when.path("details").path("unit").asInt() == -2 && "b_days".equals(when.path("details").path("period").asText()) && "09:00:00+05:30".equals(when.path("details").path("execute_at").asText()));
        ObjectNode body2 = ImplRules.rule(e, "Leads", "M1", fieldOf, java.util.Collections.emptyList(),
                java.util.Collections.singletonList(new ImplRules.Scheduled(24, "hours", java.util.Collections.singletonList(new String[]{"field_updates", "FU1"}))), "+05:30");
        JsonNode cond = body2.path("workflow_rules").get(0).path("conditions").get(0);
        check("time: scheduled_actions block in the rule", cond.path("scheduled_actions").size() == 1 && cond.path("scheduled_actions").get(0).path("execute_after").path("unit").asInt() == 24
                && "FU1".equals(cond.path("scheduled_actions").get(0).path("actions").get(0).path("id").asText()) && cond.path("instant_actions").path("actions").size() == 0);
    }

    static void profilePermissions() {
        ArrayNode perms = M.createArrayNode().add("View Leads").add("Create and Edit Deals").add("No Delete on Deals").add("Export").add("Fly to the moon");
        List<ImplProfiles.Want> wants = ImplProfiles.parse(perms);
        check("profiles: words parsed into actions", wants.size() == 6 && "View".equals(wants.get(0).action) && "Leads".equals(wants.get(0).module)
                && "Create".equals(wants.get(1).action) && "Edit".equals(wants.get(2).action) && "Deals".equals(wants.get(2).module)
                && "Delete".equals(wants.get(3).action) && !wants.get(3).enabled && "Export".equals(wants.get(4).action) && wants.get(4).module.isEmpty());
        ArrayNode pds = M.createArrayNode();
        pds.addObject().put("id", "1").put("name", "Crm_Implied_View_Leads").put("display_label", "View").put("module", "Leads").put("enabled", false);
        pds.addObject().put("id", "2").put("name", "Crm_Implied_Create_Deals").put("display_label", "Create").put("module", "Deals").put("enabled", false);
        pds.addObject().put("id", "3").put("name", "Crm_Implied_Edit_Deals").put("display_label", "Edit").put("module", "Deals").put("enabled", false);
        pds.addObject().put("id", "4").put("name", "Crm_Implied_Delete_Deals").put("display_label", "Delete").put("module", "Deals").put("enabled", true);
        pds.addObject().put("id", "5").put("name", "Crm_Implied_Delete_Leads").put("display_label", "Delete").put("module", "Leads").put("enabled", true);
        pds.addObject().put("id", "6").put("name", "Crm_Implied_Export").put("display_label", "Export").put("module", "null").put("enabled", false);
        ObjectNode m = ImplProfiles.match(wants, pds, null);
        List<String> ids = new java.util.ArrayList<>(); for (JsonNode c : m.path("changes")) ids.add(c.path("id").asText() + ":" + c.path("enabled").asBoolean());
        check("profiles: matched by module + action, delete only on Deals, export module-less", ids.equals(Arrays.asList("1:true", "2:true", "3:true", "4:false", "6:true")));
        check("profiles: unmatched reported", m.path("unmatched").size() == 1 && m.path("unmatched").path(0).asText().startsWith("Fly"));
        ObjectNode body = ImplProfiles.updateBody(m.withArray("changes"));
        check("profiles: PUT body shape", body.path("profiles").path(0).path("permissions_details").size() == 5 && body.path("profiles").path(0).path("permissions_details").path(0).has("enabled"));
        check("profiles: clone body", "Sales Rep".equals(ImplProfiles.cloneBody("Sales Rep", "").path("profiles").path(0).path("name").asText()));
        check("crm: role with permissions is api", "api".equals(CrmProduct.INSTANCE.laneFor("roles", M.createObjectNode().put("status", "new").set("permissions", perms))));
    }

    static void blueprintPayload() {
        ObjectNode wf = M.createObjectNode().put("type", "blueprint").put("module", "Leads").put("name", "Lead Qualification");
        wf.putArray("states").add("New").add("Contacted").add("Qualified");
        ObjectNode fieldA = M.createObjectNode().put("api_name", "Lead_Status").put("field_label", "Lead Status").put("data_type", "picklist").put("id", "f1");
        ArrayNode pv = fieldA.putArray("pick_list_values");
        pv.addObject().put("id", "p1").put("actual_value", "New").put("display_value", "New");
        pv.addObject().put("id", "p2").put("actual_value", "Contacted").put("display_value", "Contacted");
        ObjectNode fieldB = M.createObjectNode().put("api_name", "Rating").put("data_type", "picklist").put("id", "f2");
        fieldB.putArray("pick_list_values").addObject().put("id", "p9").put("actual_value", "Hot").put("display_value", "Hot");
        List<String> states = ImplBlueprint.states(wf);
        JsonNode picked = ImplBlueprint.pickField(wf, Arrays.asList(fieldB, fieldA), states);
        check("blueprint: picks the picklist covering the states", picked != null && "Lead_Status".equals(picked.path("api_name").asText()));
        check("blueprint: option ids mapped, missing state absent", ImplBlueprint.optionIds(fieldA, states).size() == 2);
        pv.addObject().put("id", "p3").put("actual_value", "Qualified").put("display_value", "Qualified");
        ObjectNode layout = M.createObjectNode().put("id", "L1").put("name", "Standard").put("api_name", "Standard");
        ObjectNode body = ImplBlueprint.payload(wf, "Leads", "M1", layout, fieldA, states, ImplBlueprint.optionIds(fieldA, states)).path("blueprints").path(0).deepCopy();
        check("blueprint: 3 states, 2 chain transitions, 2 connections", body.path("states").size() == 3 && body.path("transitions").size() == 2 && body.path("connections").size() == 2);
        check("blueprint: state carries picklist id and module", "p2".equals(body.path("states").path(1).path("pick_list_value").path("id").asText()) && "Leads".equals(body.path("states").path(1).path("module").path("api_name").asText()));
        check("blueprint: connection links states by api_name through its transition", body.path("connections").path(0).path("from_state").path("api_name").asText().equals(body.path("states").path(0).path("api_name").asText())
                && body.path("connections").path(0).path("transitions").path("api_name").asText().equals(body.path("transitions").path(0).path("api_name").asText()));
        check("blueprint: chart nodes match states", body.path("chart_data").path("nodes").size() == 3 && body.path("chart_data").path("connections").size() == 2);
        check("blueprint: field, layout and module referenced by id", "f1".equals(body.path("field").path("id").asText()) && "L1".equals(body.path("layout").path("id").asText()) && "M1".equals(body.path("module").path("id").asText()));
        check("crm: blueprint lane is partial", "partial".equals(CrmProduct.INSTANCE.laneFor("workflows", wf.deepCopy().put("status", "new"))));
    }

    static void functionDefinitions() {
        ObjectNode fn = M.createObjectNode().put("name", "set_pidg_code").put("module", "Leads")
                .put("trigger", "automation: on create of Leads").put("purpose", "Fills PIDG Code")
                .put("code", "lead = zoho.crm.getRecordById(\"Leads\", leadId);\nmp = Map();\nmp.put(\"PIDG_Code\", \"X\");\nzoho.crm.updateRecord(\"Leads\", leadId, mp);");
        fn.putArray("arguments").addObject().put("name", "leadId").put("type", "int");
        String def = ImplFunctions.definition(fn);
        check("functions: declaration from category and name", def.startsWith("void automation.set_pidg_code(int leadId)"));
        check("functions: body indented inside braces", def.contains("{\n\tlead = zoho.crm") && def.endsWith("}"));
        ObjectNode md = ImplFunctions.metadata(fn).path("functions").path(0).deepCopy();
        check("functions: metadata has runtime, category and _code", "Deluge 1.0".equals(md.path("runtime").asText()) && "Automation".equals(md.path("category").asText()) && md.path("_code").asText().contains("set_pidg_code"));
        ObjectNode btn = M.createObjectNode().put("name", "Send Quote").put("module", "Deals").put("trigger", "button on Deals").put("code", "return \"ok\";");
        check("functions: button → string return, record id argument", ImplFunctions.definition(btn).startsWith("string button.send_quote(int dealId)"));
        ObjectNode declared = M.createObjectNode().put("name", "x").put("code", "void automation.x(int id)\n{\n}");
        check("functions: declared body kept as is", ImplFunctions.definition(declared).startsWith("void automation.x(int id)"));
        check("crm: functions lane is api", "api".equals(CrmProduct.INSTANCE.laneFor("functions", M.createObjectNode().put("status", "new"))));
    }

    static void executionPlan() {
        ObjectNode spec = emptySpec();
        spec.withArray("fields").add(M.createObjectNode().put("id", "FLD-1").put("module", "Leads").put("label", "PIDG Code").put("executor", "api").put("include", true));
        spec.withArray("validation_rules").add(M.createObjectNode().put("id", "VR-1").put("module", "Leads").put("name", "Flag").put("executor", "rpa").put("include", true));
        ObjectNode w = M.createObjectNode().put("id", "WF-1").put("module", "Leads").put("name", "Rule").put("type", "workflow").put("trigger_type", "create").put("executor", "api").put("include", true);
        w.putArray("field_updates").addObject().put("field", "Lead_Status").put("value", "PIDG Pending"); w.putArray("conditions"); w.putArray("tasks"); w.putArray("other_actions");
        spec.withArray("workflows").add(w);
        List<ObjectNode> plan = ImplPlan.derive(spec);
        check("plan: has steps", plan.size() >= 5);
        check("plan: api lane first", "api".equals(plan.get(0).path("lane").asText()));
        boolean uiStep = plan.stream().anyMatch((t) -> "ui".equals(t.path("lane").asText()) && t.path("spec_refs").toString().contains("VR-1"));
        check("plan: setup step for the validation rule", uiStep);
        check("plan: ends with UAT", "uat".equals(plan.get(plan.size() - 1).path("lane").asText()));
        check("plan: every step has verify text", plan.stream().allMatch((t) -> t.has("verify")));
    }

    static void duplicateFunctions() {
        ObjectNode spec = emptySpec();
        ObjectNode w = M.createObjectNode().put("id", "WF-1").put("module", "Leads").put("name", "Rule").put("executor", "api").put("include", true);
        w.putArray("req_ids").add("FR-2");
        spec.withArray("workflows").add(w);
        ObjectNode fn = M.createObjectNode().put("id", "FN-1").put("module", "Leads").put("name", "flag_pidg").put("include", true);
        fn.putArray("req_ids").add("FR-2");
        spec.withArray("functions").add(fn);
        ImplementServlet.excludeDuplicateFunctions(spec);
        check("dupes: function excluded", !spec.path("functions").get(0).path("include").asBoolean(true));
        check("dupes: note names the rule", spec.path("functions").get(0).path("note").asText().contains("Rule"));
        fn.put("include", true).put("manual_include", true);
        ImplementServlet.excludeDuplicateFunctions(spec);
        check("dupes: manual include respected", spec.path("functions").get(0).path("include").asBoolean());
        // a function twinning a validation rule, and a workflow with no action at all
        ObjectNode spec2 = emptySpec();
        ObjectNode vr = M.createObjectNode().put("id", "VAL-1").put("module", "Leads").put("name", "Block qualification").put("include", true); vr.putArray("req_ids").add("FR-3");
        spec2.withArray("validation_rules").add(vr);
        ObjectNode fn2 = M.createObjectNode().put("id", "FN-2").put("module", "Leads").put("name", "validate_on_qualify").put("include", true); fn2.putArray("req_ids").add("FR-3");
        spec2.withArray("functions").add(fn2);
        ObjectNode w2 = M.createObjectNode().put("id", "WF-2").put("type", "workflow").put("module", "Leads").put("name", "Block qualification").put("trigger_type", "field_update").put("include", true);
        w2.putArray("req_ids").add("FR-3"); w2.putArray("field_updates"); w2.putArray("tasks"); w2.putArray("webhooks"); w2.putArray("other_actions"); w2.putArray("actions");
        spec2.withArray("workflows").add(w2);
        ImplementServlet.excludeEmptyWorkflows(spec2);
        ImplementServlet.excludeDuplicateFunctions(spec2);
        check("dupes: function twinning a validation rule excluded", !spec2.path("functions").get(0).path("include").asBoolean(true));
        check("empty workflow: excluded with a pointer to the validation rule", !spec2.path("workflows").get(0).path("include").asBoolean(true) && spec2.path("workflows").get(0).path("note").asText().contains("validation rule"));
    }

    static void diagnostics() throws Exception {
        ObjectNode body = M.createObjectNode();
        ImplDiagnose.StepFailure sf = new ImplDiagnose.StepFailure("field update: HTTP 400: the value given seems to be invalid",
                "POST", "/crm/v8/settings/automation/field_updates", body, 400,
                "{\"field_updates\":[{\"code\":\"INVALID_DATA\",\"details\":{\"api_name\":\"value\",\"json_path\":\"$.field_updates[0].value\"},\"message\":\"the value given seems to be invalid\"}]}");
        ObjectNode f = ImplDiagnose.describe(sf, "workflows", M.createObjectNode());
        check("diagnose: picklist cause", f.path("cause").asText().contains("picklist"));
        check("diagnose: checks present", f.path("checks").size() >= 2);
        check("diagnose: response kept", f.path("response").path("status").asInt() == 400);
        ObjectNode g = ImplDiagnose.describe(new IllegalStateException("HTTP 401: OAUTH_SCOPE_MISMATCH"), "fields", M.createObjectNode());
        check("diagnose: scope cause", g.path("cause").asText().toLowerCase().contains("scope"));
    }

    static void recipeParsers() throws Exception {
        java.lang.reflect.Method fl = ImplRpa.class.getDeclaredMethod("fieldLabel", String.class); fl.setAccessible(true);
        java.lang.reflect.Method cw = ImplRpa.class.getDeclaredMethod("conditionWord", String.class); cw.setAccessible(true);
        check("recipe: field from 'PIDG Code is empty'", "PIDG Code".equals(fl.invoke(null, "PIDG Code is empty")));
        check("recipe: field from 'when Amount > 1000'", "Amount".equals(fl.invoke(null, "when Amount > 1000")));
        check("recipe: 'is not empty' before 'is empty'", "is not empty".equals(cw.invoke(null, "Email is not empty")));
        ObjectNode vr = M.createObjectNode().put("module", "Leads").put("name", "Flag").put("condition", "PIDG Code is empty").put("error_message", "Required");
        check("recipe: validation rule has steps", ImplRpa.recipe("validation_rules", vr).size() >= 8);
        check("recipe: blueprint opens Setup and hands over", ImplRpa.recipe("workflows", M.createObjectNode().put("type", "blueprint").put("name", "B")).toString().contains("\"confirm\""));
        java.lang.reflect.Method cs = ImplRpa.class.getDeclaredMethod("conditions", String.class); cs.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.List<String[]> parts = (java.util.List<String[]>) cs.invoke(null, "Lead Status is 'Qualified' and PIDG Code is empty");
        check("recipe: two conditions parsed", parts.size() == 2 && "Qualified".equals(parts.get(0)[2]) && "is empty".equals(parts.get(1)[1]));
        ObjectNode vr2 = M.createObjectNode().put("module", "Leads").put("name", "Block").put("condition", "Lead Status is 'Qualified' and PIDG Code is empty").put("error_message", "Enter the PIDG code");
        String rec = ImplRpa.recipe("validation_rules", vr2).toString();
        check("recipe: deep link to the module's validation rules", rec.contains("/settings/modules/Leads/validation-rules"));
        check("recipe: second criterion added", rec.contains("Add criteria"));
    }

    static void deskFieldMapping() throws Exception {
        JsonNode f = M.readTree("{\"id\":\"1\",\"apiName\":\"cf_escalation_status\",\"displayLabel\":\"Escalation Status\",\"type\":\"Picklist\",\"isCustomField\":true,\"allowedValues\":[\"None\",\"L1\",\"L2\"]}");
        JsonNode c = DeskOrg.toCrmField(f);
        check("desk: picklist type", "picklist".equals(c.path("data_type").asText()));
        check("desk: options carried", c.path("pick_list_values").size() == 3);
        check("desk: label", "Escalation Status".equals(c.path("field_label").asText()));
        Products.use(M.readTree("{\"platform\":\"desk\"}"));
        check("desk: adapter selected", "desk".equals(Products.current().id()));
        check("desk: setup menu for SLA", Products.current().automationMenu("sla").contains("SLA"));
        Products.use(M.readTree("{\"platform\":\"crm\"}"));
    }

    // ---- helpers ----

    static ObjectNode emptySpec() {
        ObjectNode s = M.createObjectNode();
        for (String sct : ImplSpec.SECTIONS) s.putArray(sct);
        return s;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) pass++; else fail++;
    }
}
