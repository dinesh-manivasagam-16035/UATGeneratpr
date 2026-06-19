package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ZohoMcpClient {

    private static final String MCP_ENDPOINT =
            "https://crm-data-metadata-60072625065.zohomcp.in/mcp/f214a9de9f41f0d968f369d3d743b90a/message";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger ID_SEQ = new AtomicInteger(1);

    private final String bearerToken;

    public ZohoMcpClient(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public JsonNode send(String method, ObjectNode params) throws Exception {
        ObjectNode rpc = MAPPER.createObjectNode();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", ID_SEQ.getAndIncrement());
        rpc.put("method", method);
        if (params != null) {
            rpc.set("params", params);
        }

        HttpPost post = new HttpPost(MCP_ENDPOINT);
        post.setEntity(new StringEntity(MAPPER.writeValueAsString(rpc), ContentType.APPLICATION_JSON));
        if (bearerToken != null && !bearerToken.isEmpty()) {
            post.setHeader("Authorization", "Bearer " + bearerToken);
        }

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            return http.execute(post, response -> {
                int code = response.getCode();
                String body = EntityUtils.toString(response.getEntity());
                if (code >= 400) {
                    throw new RuntimeException("MCP request failed (" + code + "): " + body);
                }
                return MAPPER.readTree(body);
            });
        }
    }

    public JsonNode listTools() throws Exception {
        return send("tools/list", null);
    }

    public JsonNode callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", MAPPER.valueToTree(arguments));
        return send("tools/call", params);
    }

    public static ZohoMcpClient fromEnv() {
        String refreshToken = System.getenv("ZOHO_REFRESH_TOKEN");
        String clientId     = System.getenv("ZOHO_CLIENT_ID");
        String clientSecret = System.getenv("ZOHO_CLIENT_SECRET");

        if (notEmpty(refreshToken) && notEmpty(clientId) && notEmpty(clientSecret)) {
            try {
                String accessToken = refreshAccessToken(refreshToken, clientId, clientSecret);
                return new ZohoMcpClient(accessToken);
            } catch (Exception ignored) {}
        }
        return new ZohoMcpClient(null);
    }

    private static String refreshAccessToken(String refreshToken, String clientId,
                                              String clientSecret) throws Exception {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost("https://accounts.zoho.com/oauth/v2/token");
            post.setEntity(new UrlEncodedFormEntity(Arrays.asList(
                    new BasicNameValuePair("grant_type",    "refresh_token"),
                    new BasicNameValuePair("refresh_token", refreshToken),
                    new BasicNameValuePair("client_id",     clientId),
                    new BasicNameValuePair("client_secret", clientSecret)
            )));
            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                if (response.getCode() >= 400) {
                    throw new RuntimeException("Token refresh failed: " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                String token = root.path("access_token").asText("");
                if (token.isEmpty()) {
                    throw new RuntimeException("Missing access_token: " + body);
                }
                return token;
            });
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
