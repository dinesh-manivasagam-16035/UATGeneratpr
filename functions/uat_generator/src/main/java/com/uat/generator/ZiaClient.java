package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

public final class ZiaClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ZiaClient() {}

    public static String generate(String brd, String module, String moduleSchema) throws Exception {
        String endpoint = System.getenv("ZIA_ENDPOINT");
        String token = System.getenv("ZIA_OAUTH_TOKEN");
        if (endpoint == null || endpoint.isEmpty() || token == null || token.isEmpty()) {
            throw new IllegalStateException(
                "ZIA_ENDPOINT and ZIA_OAUTH_TOKEN env vars must be set when LLM_PROVIDER=zia.");
        }

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("module", module);
        payload.put("schema", moduleSchema);
        payload.put("brd", brd);
        payload.put("instruction",
            "Generate 6-10 UAT test cases as a JSON array with fields: "
          + "title, priority, tags, gherkin, steps, acceptance.");

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("Authorization", "Zoho-oauthtoken " + token);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException("ZIA error " + code + ": " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                JsonNode out = root.path("output");
                return out.isMissingNode() ? body : out.asText();
            });
        }
    }
}
