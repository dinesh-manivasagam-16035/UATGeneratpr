package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProjectsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase;
    private final String accountsBase;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;

    private String cachedAccessToken;
    private long tokenExpiresAt;

    private final Map<String, String> portalIdCache = new HashMap<>();

    public ProjectsClient(String apiBase, String accountsBase, String clientId,
                          String clientSecret, String refreshToken) {
        this.apiBase = apiBase;
        this.accountsBase = accountsBase;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    public static ProjectsClient fromEnv() {
        String apiBase = System.getenv().getOrDefault(
            "ZOHO_PROJECTS_API_BASE", "https://projectsapi.zoho.com");
        String accountsBase = System.getenv().getOrDefault(
            "ZOHO_ACCOUNTS_BASE", "https://accounts.zoho.com");
        String clientId = require("ZOHO_CLIENT_ID");
        String clientSecret = require("ZOHO_CLIENT_SECRET");
        String refreshToken = require("ZOHO_REFRESH_TOKEN");
        return new ProjectsClient(apiBase, accountsBase, clientId, clientSecret, refreshToken);
    }

    public String createTask(String portalId, String projectId, JsonNode testCase) throws Exception {
        String accessToken = getAccessToken();
        String name = trimName(testCase.path("title").asText("UAT Case"));
        String description = buildDescription(testCase);

        URI url = new URIBuilder(apiBase + "/restapi/portal/" + portalId
                + "/projects/" + projectId + "/tasks/").build();

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Zoho-oauthtoken " + accessToken);

            List<NameValuePair> form = new ArrayList<>();
            form.add(new BasicNameValuePair("name", name));
            form.add(new BasicNameValuePair("description", description));
            String priority = mapPriority(testCase.path("priority").asText("P1"));
            form.add(new BasicNameValuePair("priority", priority));
            post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));

            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException("Projects API error " + code + ": " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                JsonNode tasks = root.path("tasks");
                if (tasks.isArray() && tasks.size() > 0) {
                    return tasks.get(0).path("id_string").asText(
                            tasks.get(0).path("id").asText());
                }
                return body;
            });
        }
    }

    /** Package-private accessor so BugsClient can reuse the cached token. */
    synchronized String accessToken() throws Exception {
        return getAccessToken();
    }

    /**
     * Returns a numeric portal id_string. If {@code portalOrSlug} is already
     * all-digits, it's returned as-is. Otherwise we hit /restapi/portals/ and
     * match the value against id_string / name / url. Results are cached for
     * the lifetime of this client instance.
     */
    public synchronized String resolvePortalId(String portalOrSlug) throws Exception {
        if (portalOrSlug == null || portalOrSlug.isEmpty()) return portalOrSlug;
        if (portalOrSlug.chars().allMatch(Character::isDigit)) return portalOrSlug;
        String cached = portalIdCache.get(portalOrSlug);
        if (cached != null) return cached;

        String accessToken = getAccessToken();
        URI url = new URIBuilder(apiBase + "/restapi/portals/").build();
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url);
            get.setHeader("Authorization", "Zoho-oauthtoken " + accessToken);
            String resolved = http.execute(get, response -> {
                String body = EntityUtils.toString(response.getEntity());
                if (response.getCode() >= 400) {
                    throw new RuntimeException("Projects portals lookup failed "
                            + response.getCode() + ": " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                JsonNode portals = root.path("portals");
                if (!portals.isArray()) return null;
                String wanted = portalOrSlug.toLowerCase();
                for (JsonNode p : portals) {
                    String id = p.path("id_string").asText(p.path("id").asText(""));
                    String name = p.path("name").asText("").toLowerCase();
                    String slug = p.path("url").asText("").toLowerCase();
                    String company = p.path("company_name").asText("").toLowerCase();
                    if (id.equalsIgnoreCase(portalOrSlug)
                            || name.equals(wanted)
                            || slug.equals(wanted)
                            || company.equals(wanted)
                            || slug.endsWith("/" + wanted)) {
                        return id;
                    }
                }
                return null;
            });
            if (resolved == null) {
                throw new RuntimeException("Portal '" + portalOrSlug
                        + "' not found. Refresh token may not have access to it.");
            }
            portalIdCache.put(portalOrSlug, resolved);
            return resolved;
        }
    }

    private synchronized String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpiresAt - 60_000) {
            return cachedAccessToken;
        }
        String url = accountsBase + "/oauth/v2/token"
            + "?refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
            + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
            + "&grant_type=refresh_token";

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                if (response.getCode() >= 400) {
                    throw new RuntimeException("Zoho OAuth refresh failed: " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                cachedAccessToken = root.path("access_token").asText();
                long expiresIn = root.path("expires_in").asLong(3600);
                tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000;
                return cachedAccessToken;
            });
        }
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(name + " env var must be set to push to Zoho Projects.");
        }
        return v;
    }

    private static String trimName(String s) {
        if (s == null) return "UAT Case";
        s = s.trim();
        return s.length() > 250 ? s.substring(0, 247) + "..." : s;
    }

    private static String buildDescription(JsonNode tc) {
        StringBuilder sb = new StringBuilder();
        String gherkin = tc.path("gherkin").asText("");
        if (!gherkin.isEmpty()) {
            sb.append("Gherkin:\n").append(gherkin).append("\n\n");
        }
        JsonNode steps = tc.path("steps");
        if (steps.isArray() && steps.size() > 0) {
            sb.append("Steps:\n");
            int i = 1;
            for (JsonNode step : steps) {
                sb.append(i++).append(". ").append(step.path("action").asText(""))
                  .append(" -> ").append(step.path("expected").asText("")).append("\n");
            }
            sb.append("\n");
        }
        String acceptance = tc.path("acceptance").asText("");
        if (!acceptance.isEmpty()) {
            sb.append("Acceptance:\n").append(acceptance);
        }
        return sb.toString();
    }

    private static String mapPriority(String p) {
        if (p == null) return "Medium";
        switch (p.toUpperCase()) {
            case "P0": return "High";
            case "P2": return "Low";
            case "P1":
            default:   return "Medium";
        }
    }
}
