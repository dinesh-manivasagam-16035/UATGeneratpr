package com.uat.generator;

/**
 * Returns canned UAT cases without calling any LLM. Used when
 * LLM_PROVIDER=mock — handy for local dev and demos without an API key.
 *
 * Each case includes an execution_plan that the CRM executor can run against
 * a live Zoho CRM org (or simulate, when no credentials are provided).
 */
public final class MockLlmClient {

    private MockLlmClient() {}

    public static String generate(String brd, String module, String moduleSchema) {
        String entity = entityName(module);
        return "["
            + happyPathCreate(module, entity) + ","
            + missingRequired(module, entity) + ","
            + readOnlyRole(module, entity)
            + "]";
    }

    private static String entityName(String module) {
        String key = module == null ? "" : module.toLowerCase();
        switch (key) {
            case "crm.lead":     return "Leads";
            case "crm.deal":     return "Deals";
            case "crm.contact":  return "Contacts";
            case "desk.ticket":  return "Tickets";
            case "desk.contact": return "Contacts";
            default:             return "Leads";
        }
    }

    private static String happyPathCreate(String module, String entity) {
        String sampleBody = sampleCreateBody(entity);
        return "{"
            +   "\"title\":\"" + module + " — happy path create\","
            +   "\"priority\":\"P0\","
            +   "\"tags\":[\"create\",\"happy-path\"],"
            +   "\"gherkin\":\"Given I am a standard user\\n"
            +              "When I create a new " + entity + " record with all required fields\\n"
            +              "Then the record is saved and visible in the list view\","
            +   "\"steps\":["
            +     "{\"action\":\"POST /crm/v3/" + entity + " with required fields\",\"expected\":\"HTTP 201 + SUCCESS code\"},"
            +     "{\"action\":\"GET /crm/v3/" + entity + "/{id}\",\"expected\":\"Record returned with same id\"},"
            +     "{\"action\":\"DELETE /crm/v3/" + entity + "/{id}\",\"expected\":\"Cleanup ok\"}"
            +   "],"
            +   "\"acceptance\":\"New record appears in default list view within 2 seconds.\","
            +   "\"execution_plan\":["
            +     "{\"description\":\"Create record\","
            +      "\"method\":\"POST\",\"path\":\"/crm/v3/" + entity + "\","
            +      "\"body\":" + sampleBody + ","
            +      "\"capture\":{\"record_id\":\"data[0].details.id\"},"
            +      "\"assertions\":["
            +        "{\"path\":\"status_code\",\"equals\":201},"
            +        "{\"path\":\"data[0].code\",\"equals\":\"SUCCESS\"}"
            +      "]},"
            +     "{\"description\":\"Fetch record\","
            +      "\"method\":\"GET\",\"path\":\"/crm/v3/" + entity + "/{{record_id}}\","
            +      "\"assertions\":["
            +        "{\"path\":\"status_code\",\"equals\":200},"
            +        "{\"path\":\"data[0].id\",\"equals\":\"{{record_id}}\"}"
            +      "]},"
            +     "{\"description\":\"Cleanup\","
            +      "\"method\":\"DELETE\",\"path\":\"/crm/v3/" + entity + "/{{record_id}}\","
            +      "\"assertions\":[{\"path\":\"status_code\",\"equals\":200}]}"
            +   "]"
            + "}";
    }

    private static String missingRequired(String module, String entity) {
        return "{"
            +   "\"title\":\"" + module + " — missing required field is rejected\","
            +   "\"priority\":\"P1\","
            +   "\"tags\":[\"validation\",\"negative\"],"
            +   "\"gherkin\":\"Given I submit a " + entity + " record without required fields\\n"
            +              "When the API validates the payload\\n"
            +              "Then the record is rejected with MANDATORY_NOT_FOUND\","
            +   "\"steps\":["
            +     "{\"action\":\"POST /crm/v3/" + entity + " with empty body\",\"expected\":\"4xx with code MANDATORY_NOT_FOUND\"}"
            +   "],"
            +   "\"acceptance\":\"Validation error mirrors BRD requirement.\","
            +   "\"execution_plan\":["
            +     "{\"description\":\"POST with missing required fields\","
            +      "\"method\":\"POST\",\"path\":\"/crm/v3/" + entity + "\","
            +      "\"body\":{\"data\":[{}]},"
            +      "\"assertions\":["
            +        "{\"path\":\"data[0].status\",\"equals\":\"error\"},"
            +        "{\"path\":\"data[0].code\",\"equals\":\"MANDATORY_NOT_FOUND\"}"
            +      "]}"
            +   "]"
            + "}";
    }

    private static String readOnlyRole(String module, String entity) {
        return "{"
            +   "\"title\":\"" + module + " — list endpoint is reachable\","
            +   "\"priority\":\"P1\","
            +   "\"tags\":[\"smoke\",\"read\"],"
            +   "\"gherkin\":\"Given I am authenticated\\n"
            +              "When I list " + entity + " records\\n"
            +              "Then the API returns 200 with a data array\","
            +   "\"steps\":["
            +     "{\"action\":\"GET /crm/v3/" + entity + "?per_page=1\",\"expected\":\"200 with data array (possibly empty)\"}"
            +   "],"
            +   "\"acceptance\":\"List endpoint is reachable and well-formed.\","
            +   "\"execution_plan\":["
            +     "{\"description\":\"Smoke list\","
            +      "\"method\":\"GET\",\"path\":\"/crm/v3/" + entity + "?per_page=1\","
            +      "\"assertions\":["
            +        "{\"path\":\"status_code\",\"in\":[200,204]}"
            +      "]}"
            +   "]"
            + "}";
    }

    private static String sampleCreateBody(String entity) {
        switch (entity) {
            case "Leads":
                return "{\"data\":[{\"Last_Name\":\"UAT-Smoke\",\"Company\":\"UAT Generator\",\"Email\":\"uat-smoke@example.com\"}]}";
            case "Deals":
                return "{\"data\":[{\"Deal_Name\":\"UAT-Smoke Deal\",\"Amount\":1000,\"Stage\":\"Qualification\",\"Closing_Date\":\"2099-12-31\"}]}";
            case "Contacts":
                return "{\"data\":[{\"Last_Name\":\"UAT-Smoke\",\"Email\":\"uat-smoke@example.com\"}]}";
            case "Tickets":
                return "{\"data\":[{\"subject\":\"UAT-Smoke\",\"description\":\"auto\",\"departmentId\":\"\",\"contactId\":\"\"}]}";
            default:
                return "{\"data\":[{\"Last_Name\":\"UAT-Smoke\"}]}";
        }
    }
}
