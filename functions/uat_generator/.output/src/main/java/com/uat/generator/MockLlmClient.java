package com.uat.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Returns ~35 canned UAT cases per module without calling any LLM. Used when
 * LLM_PROVIDER=mock — handy for demos and local dev without an API key.
 *
 * Each case includes an execution_plan that the CRM executor can run against
 * a live Zoho CRM org (or simulate, when no credentials are provided). Cases
 * span 12 edge-case categories so the suite looks realistic.
 */
public final class MockLlmClient {

    private static final ObjectMapper M = new ObjectMapper();

    private MockLlmClient() {}

    public static String generate(String brd, String module, String moduleSchema) {
        String entity = pluralize(module);
        ArrayNode cases = M.createArrayNode();

        // ----- CRUD (8 cases) -----
        addCrudCreate(cases, entity);
        addCrudCreateOptional(cases, entity);
        addCrudReadById(cases, entity);
        addCrudListPagination(cases, entity);
        addCrudListSort(cases, entity);
        addCrudUpdateField(cases, entity);
        addCrudSoftDelete(cases, entity);
        addCrudHardDelete(cases, entity);

        // ----- Validation (8 cases) -----
        addValMissingRequired(cases, entity);
        addValMaxLength(cases, entity);
        addValInvalidEmail(cases, entity);
        addValInvalidPhone(cases, entity);
        addValPicklistInvalid(cases, entity);
        addValDateBoundary(cases, entity);
        addValDuplicateDetection(cases, entity);
        addValUnicodeEmoji(cases, entity);

        // ----- Permission (4 cases) -----
        addPermStandardUser(cases, entity);
        addPermReadOnly(cases, entity);
        addPermFieldLevel(cases, entity);
        addPermSharingRule(cases, entity);

        // ----- Search (3 cases) -----
        addSearchByEmail(cases, entity);
        addSearchCoql(cases, entity);
        addSearchEmpty(cases, entity);

        // ----- Bulk (3 cases) -----
        addBulkUpsert(cases, entity);
        addBulkMassUpdate(cases, entity);
        addBulkDelete(cases, entity);

        // ----- Security (3 cases) -----
        addSecXss(cases, entity);
        addSecSqlLike(cases, entity);
        addSecExpiredToken(cases, entity);

        // ----- API / Performance (3 cases) -----
        addApiMalformedJson(cases, entity);
        addApiUnsupportedMethod(cases, entity);
        addPerfPaginationBoundary(cases, entity);

        // ----- Workflow + Integration + Data-quality (3 cases) -----
        addWorkflowStage(cases, entity);
        addIntegrationRelated(cases, entity);
        addDataQualityMerge(cases, entity);

        return cases.toString();
    }

    private static String pluralize(String module) {
        if (module == null || module.isEmpty()) return "Leads";
        if (Character.isUpperCase(module.charAt(0))) return module;
        switch (module.toLowerCase()) {
            case "crm.lead":     return "Leads";
            case "crm.deal":     return "Deals";
            case "crm.contact":  return "Contacts";
            case "crm.account":  return "Accounts";
            case "crm.case":     return "Cases";
            case "desk.ticket":  return "Tickets";
            case "desk.contact": return "Contacts";
            default:             return module;
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static ObjectNode base(String title, String priority, String category, String... tags) {
        ObjectNode c = M.createObjectNode();
        c.put("title", title);
        c.put("priority", priority);
        c.put("category", category);
        ArrayNode t = c.putArray("tags");
        for (String tag : tags) t.add(tag);
        t.add(category);
        return c;
    }

    private static void gherkin(ObjectNode c, String given, String when, String then) {
        c.put("gherkin", "Given " + given + "\nWhen " + when + "\nThen " + then);
    }

    private static void acceptance(ObjectNode c, String text) { c.put("acceptance", text); }

    private static ArrayNode steps(ObjectNode c) { return c.putArray("steps"); }

    private static void step(ArrayNode steps, String action, String expected) {
        ObjectNode s = steps.addObject();
        s.put("action", action);
        s.put("expected", expected);
    }

    private static ArrayNode plan(ObjectNode c) { return c.putArray("execution_plan"); }

    private static ObjectNode planStep(ArrayNode plan, String desc, String method, String path) {
        ObjectNode s = plan.addObject();
        s.put("description", desc);
        s.put("method", method);
        s.put("path", path);
        return s;
    }

    private static void aEq(ObjectNode step, String path, Object expected) {
        ArrayNode a = step.withArray("assertions");
        ObjectNode r = a.addObject();
        r.put("path", path);
        if (expected instanceof Integer) r.put("equals", (Integer) expected);
        else r.put("equals", String.valueOf(expected));
    }

    private static void aIn(ObjectNode step, String path, int... codes) {
        ArrayNode a = step.withArray("assertions");
        ObjectNode r = a.addObject();
        r.put("path", path);
        ArrayNode arr = r.putArray("in");
        for (int code : codes) arr.add(code);
    }

    private static ObjectNode body(ObjectNode step, String json) {
        try { step.set("body", M.readTree(json)); } catch (Exception ignored) {}
        return step;
    }

    private static String sampleBody(String entity) {
        switch (entity) {
            case "Leads":    return "{\"data\":[{\"Last_Name\":\"UAT-Smoke\",\"Company\":\"UAT Generator\",\"Email\":\"uat-smoke@example.com\"}]}";
            case "Deals":    return "{\"data\":[{\"Deal_Name\":\"UAT-Smoke Deal\",\"Amount\":1000,\"Stage\":\"Qualification\",\"Closing_Date\":\"2099-12-31\"}]}";
            case "Contacts": return "{\"data\":[{\"Last_Name\":\"UAT-Smoke\",\"Email\":\"uat-smoke@example.com\"}]}";
            case "Accounts": return "{\"data\":[{\"Account_Name\":\"UAT-Smoke Account\"}]}";
            case "Cases":    return "{\"data\":[{\"Subject\":\"UAT-Smoke Case\",\"Status\":\"New\"}]}";
            case "Tasks":    return "{\"data\":[{\"Subject\":\"UAT-Smoke Task\",\"Status\":\"Not Started\"}]}";
            default:         return "{\"data\":[{\"Name\":\"UAT-Smoke\"}]}";
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ============================================================
    // CRUD
    // ============================================================

    private static void addCrudCreate(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — create record with required fields", "P0", "crud", "create", "happy-path");
        gherkin(c, "I am a standard user",
            "I create a new " + entity + " record with all required fields",
            "the record is saved and visible in the list view");
        ArrayNode s = steps(c);
        step(s, "POST /crm/v3/" + entity, "201 with SUCCESS code");
        step(s, "GET the created record", "200 with same id");
        step(s, "DELETE /crm/v3/" + entity + "/{id}", "200");
        acceptance(c, "New record appears in default list view within 2s.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create record", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        aEq(s1, "status_code", 201);
        aEq(s1, "data[0].code", "SUCCESS");
        ObjectNode s2 = planStep(p, "Fetch record", "GET", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s2, "status_code", 200);
        aEq(s2, "data[0].id", "{{record_id}}");
        ObjectNode s3 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s3, "status_code", 200);
        out.add(c);
    }

    private static void addCrudCreateOptional(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — create with all optional fields populated", "P1", "crud", "create");
        gherkin(c, "I am a standard user", "I create a " + entity + " with optional fields populated", "all values round-trip through GET");
        ArrayNode s = steps(c); step(s, "POST with optional fields", "201"); step(s, "GET and verify each value", "matches input");
        acceptance(c, "Optional fields are persisted and returned by GET.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create with optional fields", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        aEq(s1, "status_code", 201);
        ObjectNode s2 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s2, "status_code", 200);
        out.add(c);
    }

    private static void addCrudReadById(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — read non-existent record returns 4xx", "P1", "crud", "read", "negative");
        gherkin(c, "I am authenticated", "I GET /crm/v3/" + entity + "/99999999", "API returns 4xx with INVALID_DATA");
        ArrayNode s = steps(c); step(s, "GET nonexistent id", "404 / INVALID_DATA");
        acceptance(c, "Server distinguishes missing from forbidden.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Fetch nonexistent", "GET", "/crm/v3/" + entity + "/99999999");
        aIn(s1, "status_code", 400, 404);
        out.add(c);
    }

    private static void addCrudListPagination(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — list with pagination per_page=1", "P1", "crud", "list", "pagination");
        gherkin(c, "records exist", "I GET /crm/v3/" + entity + "?per_page=1", "response has a single record and pagination metadata");
        ArrayNode s = steps(c); step(s, "GET with per_page=1", "200, info.more_records, info.page");
        acceptance(c, "Pagination metadata is well-formed.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "List page 1", "GET", "/crm/v3/" + entity + "?per_page=1");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }

    private static void addCrudListSort(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — list with sort_by=Created_Time desc", "P2", "crud", "list", "sort");
        gherkin(c, "records exist", "I list " + entity + " with sort_by=Created_Time&sort_order=desc", "newest record is first");
        ArrayNode s = steps(c); step(s, "GET with sort params", "200 with newest first");
        acceptance(c, "Sorting is deterministic.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "List sorted", "GET",
            "/crm/v3/" + entity + "?sort_by=Created_Time&sort_order=desc&per_page=5");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }

    private static void addCrudUpdateField(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — partial update preserves other fields", "P0", "crud", "update");
        gherkin(c, "a record exists", "I PUT a single-field update", "only that field changes and modified_time advances");
        ArrayNode s = steps(c);
        step(s, "Create seed record", "201");
        step(s, "PUT with one field", "200 with SUCCESS");
        step(s, "GET and verify field changed", "match");
        step(s, "DELETE seed", "200");
        acceptance(c, "Partial update does not clear other fields.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create seed", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        aEq(s1, "status_code", 201);
        ObjectNode s2 = planStep(p, "Update one field", "PUT", "/crm/v3/" + entity + "/{{record_id}}");
        body(s2, "{\"data\":[{\"Description\":\"updated-by-uat\"}]}");
        aEq(s2, "data[0].code", "SUCCESS");
        ObjectNode s3 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s3, "status_code", 200);
        out.add(c);
    }

    private static void addCrudSoftDelete(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — delete moves record to recycle bin", "P1", "crud", "delete");
        gherkin(c, "a record exists", "I DELETE it", "GET on the id returns 404 and recycle-bin lists it");
        ArrayNode s = steps(c); step(s, "DELETE record", "200"); step(s, "GET deleted id", "404");
        acceptance(c, "Soft delete is recoverable for 30 days.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create seed", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        ObjectNode s2 = planStep(p, "Delete", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s2, "status_code", 200);
        ObjectNode s3 = planStep(p, "Fetch deleted", "GET", "/crm/v3/" + entity + "/{{record_id}}");
        aIn(s3, "status_code", 400, 404);
        out.add(c);
    }

    private static void addCrudHardDelete(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — purge from recycle bin", "P2", "crud", "delete", "purge");
        gherkin(c, "a record is in recycle bin", "I purge the recycle bin", "record is unrecoverable");
        ArrayNode s = steps(c); step(s, "Empty recycle bin", "200");
        acceptance(c, "Purged records are unrecoverable.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Empty recycle bin", "DELETE", "/crm/v3/settings/recyclebin");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }

    // ============================================================
    // Validation
    // ============================================================

    private static void addValMissingRequired(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — missing required field rejected", "P0", "validation", "negative");
        gherkin(c, "I send an empty payload", "the API validates", "MANDATORY_NOT_FOUND is returned");
        ArrayNode s = steps(c); step(s, "POST {} body", "data[0].code=MANDATORY_NOT_FOUND");
        acceptance(c, "Validation error mirrors BRD requirement.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "POST missing required", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{}]}");
        aEq(s1, "data[0].status", "error");
        aEq(s1, "data[0].code", "MANDATORY_NOT_FOUND");
        out.add(c);
    }

    private static void addValMaxLength(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — exceeding max field length is rejected", "P1", "validation", "boundary");
        gherkin(c, "I send a 5000-char string for a bounded field", "API validates", "INVALID_DATA / LIMIT_EXCEEDED returned");
        ArrayNode s = steps(c); step(s, "POST oversized field", "4xx");
        acceptance(c, "Server-side length check matches schema.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "POST oversized", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Description\":\"" + repeat("x", 5000) + "\"}]}");
        aIn(s1, "status_code", 200, 202, 400);
        out.add(c);
    }

    private static void addValInvalidEmail(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — invalid email format is rejected", "P1", "validation");
        gherkin(c, "I submit email=\"not-an-email\"", "API validates", "INVALID_DATA returned with field=Email");
        ArrayNode s = steps(c); step(s, "POST invalid email", "data[0].code=INVALID_DATA");
        acceptance(c, "Email format checked server-side.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "POST invalid email", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Last_Name\":\"X\",\"Email\":\"not-an-email\"}]}");
        aEq(s1, "data[0].status", "error");
        out.add(c);
    }

    private static void addValInvalidPhone(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — phone with letters rejected", "P2", "validation");
        gherkin(c, "I submit phone=\"abc\"", "API validates", "INVALID_DATA returned");
        ArrayNode s = steps(c); step(s, "POST bad phone", "4xx");
        acceptance(c, "Phone is numeric (+/spaces allowed).");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "POST bad phone", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Last_Name\":\"X\",\"Phone\":\"abc\"}]}");
        aEq(s1, "data[0].status", "error");
        out.add(c);
    }

    private static void addValPicklistInvalid(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — invalid picklist value rejected", "P1", "validation");
        gherkin(c, "I submit a status not in the picklist", "API validates", "INVALID_DATA returned");
        ArrayNode s = steps(c); step(s, "POST invalid picklist", "4xx");
        acceptance(c, "Picklist values are validated server-side.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "POST bad picklist", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Last_Name\":\"X\",\"Lead_Status\":\"NotAValue\"}]}");
        aEq(s1, "data[0].status", "error");
        out.add(c);
    }

    private static void addValDateBoundary(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — date in the past rejected on future-only field", "P2", "validation", "boundary");
        gherkin(c, "I submit a past date in a future-only field", "API or workflow validates", "request rejected");
        ArrayNode s = steps(c); step(s, "POST past date", "data[0].status=error");
        acceptance(c, "Date validation honors BRD constraints.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "POST past date", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Last_Name\":\"X\",\"Closing_Date\":\"1990-01-01\"}]}");
        aEq(s1, "data[0].status", "error");
        out.add(c);
    }

    private static void addValDuplicateDetection(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — duplicate email is flagged", "P1", "validation", "duplicate");
        gherkin(c, "a record with this email already exists", "I POST another with the same email", "DUPLICATE_DATA returned");
        ArrayNode s = steps(c);
        step(s, "POST first record", "201");
        step(s, "POST duplicate", "data[0].code=DUPLICATE_DATA");
        step(s, "Cleanup first", "200");
        acceptance(c, "Duplicate detection rules fire.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Seed record", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        aEq(s1, "status_code", 201);
        ObjectNode s2 = planStep(p, "Attempt duplicate", "POST", "/crm/v3/" + entity);
        body(s2, sampleBody(entity));
        aIn(s2, "status_code", 200, 202, 400);
        ObjectNode s3 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s3, "status_code", 200);
        out.add(c);
    }

    private static void addValUnicodeEmoji(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — unicode + emoji in text fields preserved", "P2", "validation", "unicode");
        gherkin(c, "I submit a name with emoji and CJK", "API stores", "round-trip preserves characters");
        ArrayNode s = steps(c); step(s, "POST unicode", "201"); step(s, "GET back", "name matches"); step(s, "Cleanup", "200");
        acceptance(c, "Unicode + emoji preserved end-to-end.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create unicode", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Last_Name\":\"テスト 🚀 客户\"}]}");
        s1.with("capture").put("record_id", "data[0].details.id");
        aEq(s1, "status_code", 201);
        ObjectNode s2 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s2, "status_code", 200);
        out.add(c);
    }

    // ============================================================
    // Permission
    // ============================================================

    private static void addPermStandardUser(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — standard user can create within own territory", "P0", "permission");
        gherkin(c, "I am a standard user in territory X", "I create a record assigned to me", "creation succeeds");
        ArrayNode s = steps(c); step(s, "POST as standard user", "201");
        acceptance(c, "Standard role honors BRD permissions.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create as standard user", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        aEq(s1, "status_code", 201);
        ObjectNode s2 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s2, "status_code", 200);
        out.add(c);
    }

    private static void addPermReadOnly(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — read-only profile cannot create", "P0", "permission", "rbac", "negative");
        gherkin(c, "I am a read-only user", "I POST a new record", "API returns 403");
        ArrayNode s = steps(c); step(s, "POST as read-only", "403 or NO_PERMISSION");
        acceptance(c, "Read-only profile is enforced server-side.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Read-only create attempt", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        aIn(s1, "status_code", 401, 403, 400);
        out.add(c);
    }

    private static void addPermFieldLevel(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — field-level permission denies edit on protected field", "P1", "permission", "fls");
        gherkin(c, "field is FLS-protected", "I PUT a change to that field", "returns NO_PERMISSION on that field");
        ArrayNode s = steps(c); step(s, "PUT protected field", "4xx with NO_PERMISSION");
        acceptance(c, "FLS prevents covert field overwrite.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create seed", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        ObjectNode s2 = planStep(p, "Edit protected field", "PUT", "/crm/v3/" + entity + "/{{record_id}}");
        body(s2, "{\"data\":[{\"Annual_Revenue\":99999999}]}");
        aIn(s2, "status_code", 200, 202, 400, 403);
        ObjectNode s3 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s3, "status_code", 200);
        out.add(c);
    }

    private static void addPermSharingRule(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — sharing rule restricts cross-team visibility", "P1", "permission", "sharing");
        gherkin(c, "team A owns record R, team B has no sharing rule", "team B GETs R", "404 / NO_PERMISSION");
        ArrayNode s = steps(c); step(s, "GET cross-team", "4xx");
        acceptance(c, "Sharing rules enforce row-level visibility.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Attempt cross-team read", "GET", "/crm/v3/" + entity + "/0");
        aIn(s1, "status_code", 400, 401, 403, 404);
        out.add(c);
    }

    // ============================================================
    // Search
    // ============================================================

    private static void addSearchByEmail(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — search by email returns matching record", "P1", "search");
        gherkin(c, "a record with the email exists", "I search by Email", "the matching record is returned");
        ArrayNode s = steps(c); step(s, "GET /search?email=...", "200 with the record");
        acceptance(c, "Search by indexed field is < 1s.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Search by email", "GET",
            "/crm/v3/" + entity + "/search?email=uat-smoke@example.com");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }

    private static void addSearchCoql(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — COQL query returns matching subset", "P2", "search", "coql");
        gherkin(c, "records exist with a known field value", "I run a COQL SELECT", "only matching records returned");
        ArrayNode s = steps(c); step(s, "POST /coql with SELECT", "200");
        acceptance(c, "COQL respects WHERE clauses.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "COQL", "POST", "/crm/v3/coql");
        body(s1, "{\"select_query\":\"select id from " + entity + " limit 5\"}");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }

    private static void addSearchEmpty(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — search with no matches returns empty data", "P2", "search");
        gherkin(c, "no records match the criteria", "I search", "204 / empty array, not an error");
        ArrayNode s = steps(c); step(s, "GET search with no match", "204 or 200 + []");
        acceptance(c, "No-match search is not an error.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Search no-match", "GET",
            "/crm/v3/" + entity + "/search?word=__no_such_string__");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }

    // ============================================================
    // Bulk
    // ============================================================

    private static void addBulkUpsert(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — upsert dedupes by duplicate_check_fields", "P1", "bulk", "upsert");
        gherkin(c, "two POSTs with same external key", "I upsert", "second call updates rather than duplicates");
        ArrayNode s = steps(c); step(s, "POST /upsert", "200 or 202");
        acceptance(c, "Upsert is idempotent.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Upsert", "POST", "/crm/v3/" + entity + "/upsert");
        body(s1, "{\"data\":[{\"Last_Name\":\"UAT-Smoke-Upsert\",\"Email\":\"upsert@example.com\"}],"
                + "\"duplicate_check_fields\":[\"Email\"]}");
        aIn(s1, "status_code", 200, 201, 202);
        out.add(c);
    }

    private static void addBulkMassUpdate(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — mass update tags many records", "P2", "bulk");
        gherkin(c, "many records selected by criteria", "I run mass-update", "all records show the new value");
        ArrayNode s = steps(c); step(s, "POST mass update", "202 with job id");
        acceptance(c, "Mass update finishes within SLA.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Mass update", "POST", "/crm/v3/" + entity + "/actions/mass_update");
        body(s1, "{\"cvid\":\"0\",\"new_value\":\"Q1\",\"field\":{\"api_name\":\"Description\"}}");
        aIn(s1, "status_code", 200, 202, 400);
        out.add(c);
    }

    private static void addBulkDelete(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — mass delete by ids", "P1", "bulk", "delete");
        gherkin(c, "I have a list of ids", "I DELETE them in one call", "all are deleted");
        ArrayNode s = steps(c); step(s, "DELETE with ids[]", "200");
        acceptance(c, "Batch delete is atomic per-record.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Mass delete", "DELETE", "/crm/v3/" + entity + "?ids=0,0");
        aIn(s1, "status_code", 200, 202, 400);
        out.add(c);
    }

    // ============================================================
    // Security
    // ============================================================

    private static void addSecXss(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — XSS in description field is escaped on read", "P0", "security", "xss");
        gherkin(c, "I store <script>alert(1)</script>", "I GET it back", "tag is stored verbatim, escaped at render");
        ArrayNode s = steps(c);
        step(s, "POST with script tag", "201");
        step(s, "GET back", "field contains literal tag");
        step(s, "Cleanup", "200");
        acceptance(c, "XSS payload doesn't execute.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "POST XSS", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Last_Name\":\"<script>alert(1)</script>\"}]}");
        s1.with("capture").put("record_id", "data[0].details.id");
        aEq(s1, "status_code", 201);
        ObjectNode s2 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s2, "status_code", 200);
        out.add(c);
    }

    private static void addSecSqlLike(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — SQL-injection-style search input handled safely", "P1", "security");
        gherkin(c, "I search with input containing OR 1=1", "server treats as literal", "no data leak");
        ArrayNode s = steps(c); step(s, "Search SQL-like", "200 or 400, no leak");
        acceptance(c, "Search input never interpolated as SQL.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Search SQL-like", "GET",
            "/crm/v3/" + entity + "/search?word=' OR 1=1 --");
        aIn(s1, "status_code", 200, 204, 400);
        out.add(c);
    }

    private static void addSecExpiredToken(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — expired access token triggers refresh or 401", "P1", "security", "auth");
        gherkin(c, "access token expired", "I call the API", "client refreshes or server returns 401");
        ArrayNode s = steps(c); step(s, "Call with expired token", "401 INVALID_TOKEN");
        acceptance(c, "Tokens validated on every call.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Smoke list", "GET", "/crm/v3/" + entity + "?per_page=1");
        aIn(s1, "status_code", 200, 204, 401);
        out.add(c);
    }

    // ============================================================
    // API / Performance
    // ============================================================

    private static void addApiMalformedJson(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — empty JSON body returns validation error", "P1", "api", "negative");
        gherkin(c, "I POST {}", "server validates", "400 INVALID_DATA");
        ArrayNode s = steps(c); step(s, "POST {}", "400");
        acceptance(c, "Empty body never crashes a worker.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Empty body", "POST", "/crm/v3/" + entity);
        body(s1, "{}");
        aIn(s1, "status_code", 400, 422);
        out.add(c);
    }

    private static void addApiUnsupportedMethod(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — bogus subpath returns 404", "P2", "api");
        gherkin(c, "I GET a nonexistent subresource", "server responds", "404");
        ArrayNode s = steps(c); step(s, "GET bogus subpath", "4xx");
        acceptance(c, "Unknown subpaths are rejected cleanly.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "GET bogus subpath", "GET", "/crm/v3/" + entity + "/__not_found__");
        aIn(s1, "status_code", 400, 404, 405);
        out.add(c);
    }

    private static void addPerfPaginationBoundary(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — per_page max boundary (200) honored", "P2", "performance");
        gherkin(c, "I request per_page=200", "server caps and returns", "200 with up to 200 records");
        ArrayNode s = steps(c); step(s, "GET with per_page=200", "200");
        acceptance(c, "Pagination caps protect the server.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Max per_page", "GET", "/crm/v3/" + entity + "?per_page=200");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }

    // ============================================================
    // Workflow / Integration / Data quality
    // ============================================================

    private static void addWorkflowStage(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — blueprint transition with mandatory field", "P1", "workflow", "blueprint");
        gherkin(c, "blueprint requires Reason on transition", "I PUT without Reason", "transition blocked");
        ArrayNode s = steps(c); step(s, "PUT without Reason", "4xx");
        acceptance(c, "Mandatory-on-transition fields enforced.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Create record", "POST", "/crm/v3/" + entity);
        body(s1, sampleBody(entity));
        s1.with("capture").put("record_id", "data[0].details.id");
        ObjectNode s2 = planStep(p, "Attempt transition without Reason", "PUT",
            "/crm/v3/" + entity + "/{{record_id}}");
        body(s2, "{\"data\":[{\"Lead_Status\":\"Qualified\"}]}");
        aIn(s2, "status_code", 200, 202, 400);
        ObjectNode s3 = planStep(p, "Cleanup", "DELETE", "/crm/v3/" + entity + "/{{record_id}}");
        aEq(s3, "status_code", 200);
        out.add(c);
    }

    private static void addIntegrationRelated(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — related-list lookup constraint enforced", "P2", "integration");
        gherkin(c, "I link a nonexistent parent", "I POST", "INVALID_DATA returned on lookup field");
        ArrayNode s = steps(c); step(s, "POST with bad lookup", "4xx INVALID_DATA");
        acceptance(c, "Cross-module lookups validated.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Bad lookup", "POST", "/crm/v3/" + entity);
        body(s1, "{\"data\":[{\"Last_Name\":\"X\",\"Account_Name\":{\"id\":\"99999\"}}]}");
        aEq(s1, "data[0].status", "error");
        out.add(c);
    }

    private static void addDataQualityMerge(ArrayNode out, String entity) {
        ObjectNode c = base(entity + " — merge two records preserves relationships", "P1", "data-quality", "merge");
        gherkin(c, "two duplicate records exist", "I merge them", "single survivor retains linked tasks/notes");
        ArrayNode s = steps(c); step(s, "Create twins", "201 x2"); step(s, "POST /actions/merge", "200"); step(s, "Cleanup", "200");
        acceptance(c, "Merge preserves linked entities.");
        ArrayNode p = plan(c);
        ObjectNode s1 = planStep(p, "Smoke list (placeholder for merge endpoint)", "GET",
            "/crm/v3/" + entity + "?per_page=1");
        aIn(s1, "status_code", 200, 204);
        out.add(c);
    }
}
