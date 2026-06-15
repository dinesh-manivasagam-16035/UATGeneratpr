package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
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
    private final CatalystConnectionAuth catalystAuth;

    private String cachedAccessToken;
    private long tokenExpiresAt;

    private final Map<String, String> portalIdCache = new HashMap<>();

    public ProjectsClient(String apiBase, String accountsBase, String clientId,
                          String clientSecret, String refreshToken,
                          CatalystConnectionAuth catalystAuth) {
        this.apiBase = apiBase;
        this.accountsBase = accountsBase;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.catalystAuth = catalystAuth;
    }

    /**
     * Resolves auth from the environment. Prefers a Catalyst Connection
     * (env var {@code ZOHO_CONNECTION_NAME}) when present; falls back to
     * the self-client refresh-token flow.
     */
    public static ProjectsClient fromEnv() {
        String apiBase = System.getenv().getOrDefault(
            "ZOHO_PROJECTS_API_BASE", "https://projectsapi.zoho.com");
        String accountsBase = System.getenv().getOrDefault(
            "ZOHO_ACCOUNTS_BASE", "https://accounts.zoho.com");

        String connectionName = CatalystConnectionAuth.configuredConnectionName();
        if (connectionName != null) {
            return new ProjectsClient(apiBase, accountsBase, null, null, null,
                    new CatalystConnectionAuth(connectionName));
        }
        String clientId = require("ZOHO_CLIENT_ID");
        String clientSecret = require("ZOHO_CLIENT_SECRET");
        String refreshToken = require("ZOHO_REFRESH_TOKEN");
        return new ProjectsClient(apiBase, accountsBase, clientId, clientSecret,
                refreshToken, null);
    }

    /** Returns the Projects API base URL used by this client. */
    String getApiBase() { return apiBase; }

    public String createTask(String portalId, String projectId, JsonNode testCase) throws Exception {
        String name = trimName(testCase.path("title").asText("UAT Case"));
        String description = buildDescription(testCase);

        URI url = new URIBuilder(apiBase + "/restapi/portal/" + portalId
                + "/projects/" + projectId + "/tasks/").build();

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            attachAuth(post);

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

    /**
     * Attaches the appropriate auth headers to the request. Uses Catalyst
     * Connection when configured; otherwise falls back to the cached
     * self-client access token.
     */
    public void attachAuth(HttpUriRequestBase req) throws Exception {
        if (catalystAuth != null) {
            catalystAuth.authHeaders().forEach(req::setHeader);
        } else {
            req.setHeader("Authorization", "Zoho-oauthtoken " + getAccessToken());
        }
    }

    /** Whether this client uses a Catalyst Connection for auth. */
    public boolean usesCatalystConnection() {
        return catalystAuth != null;
    }

    /** Source name of the auth (connection name or "self-client"). */
    public String authSource() {
        return catalystAuth != null ? "catalyst:" + catalystAuth.connectionName() : "self-client";
    }

    /**
     * Package-private accessor so BugsClient can reuse the cached token
     * when running in self-client mode. Throws when using Catalyst auth —
     * BugsClient should use {@link #attachAuth} instead.
     */
    synchronized String accessToken() throws Exception {
        if (catalystAuth != null) {
            throw new IllegalStateException(
                    "accessToken() not available when using Catalyst Connection — use attachAuth() instead.");
        }
        return getAccessToken(); // handles both sidForToken and self-client paths
    }

    /**
     * Looks up a project by name within a portal; creates one if missing.
     * Used when the caller wants tasks isolated under a per-BRD project
     * instead of dumping everything into a single static project.
     *
     * Match is case-insensitive on the project's {@code name} field. If the
     * portal has many projects this paginates through them in pages of 200.
     */
    public synchronized String findOrCreateProject(String portalId, String projectName) throws Exception {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("Project name is required.");
        }
        final String wanted = projectName.trim();
        final String wantedLower = wanted.toLowerCase();

        int index = 1;
        final int range = 200;
        while (true) {
            URI listUrl = new URIBuilder(apiBase + "/restapi/portal/" + portalId + "/projects/")
                    .addParameter("index", String.valueOf(index))
                    .addParameter("range", String.valueOf(range))
                    .build();
            try (CloseableHttpClient http = HttpClients.createDefault()) {
                HttpGet get = new HttpGet(listUrl);
                attachAuth(get);
                MatchResult mr = http.execute(get, response -> {
                    String body = EntityUtils.toString(response.getEntity());
                    if (response.getCode() >= 400) {
                        throw new RuntimeException("Projects list failed "
                                + response.getCode() + ": " + body);
                    }
                    JsonNode root = MAPPER.readTree(body);
                    JsonNode projects = root.path("projects");
                    if (!projects.isArray()) return new MatchResult(null, 0);
                    for (JsonNode p : projects) {
                        if (wantedLower.equals(p.path("name").asText("").toLowerCase())) {
                            String id = p.path("id_string").asText(p.path("id").asText(""));
                            if (!id.isEmpty()) return new MatchResult(id, projects.size());
                        }
                    }
                    return new MatchResult(null, projects.size());
                });
                if (mr.id != null) return mr.id;
                if (mr.count < range) break;       // last page, no match
                index += range;
            }
        }

        // Not found — create
        URI createUrl = new URIBuilder(apiBase + "/restapi/portal/" + portalId + "/projects/").build();
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(createUrl);
            attachAuth(post);
            List<NameValuePair> form = new ArrayList<>();
            form.add(new BasicNameValuePair("name", wanted));
            post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                if (response.getCode() >= 400) {
                    throw new RuntimeException("Projects create failed "
                            + response.getCode() + ": " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                JsonNode projects = root.path("projects");
                if (projects.isArray() && projects.size() > 0) {
                    return projects.get(0).path("id_string")
                            .asText(projects.get(0).path("id").asText());
                }
                throw new RuntimeException("Projects create returned no project id: " + body);
            });
        }
    }

    private static final class MatchResult {
        final String id;
        final int count;
        MatchResult(String id, int count) { this.id = id; this.count = count; }
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

        URI url = new URIBuilder(apiBase + "/restapi/portals/").build();
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(url);
            attachAuth(get);
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
