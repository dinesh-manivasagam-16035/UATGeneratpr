package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates Bugs (not Tasks) in Zoho Projects via REST. Used when a UAT case
 * execution is marked as Failed. Reuses ProjectsClient's OAuth refresh logic
 * via composition to avoid duplicate token caches.
 */
public class BugsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase;
    private final ProjectsClient delegate;

    public BugsClient(String apiBase, ProjectsClient delegate) {
        this.apiBase = apiBase;
        this.delegate = delegate;
    }

    public static BugsClient fromEnv() {
        String apiBase = System.getenv().getOrDefault(
            "ZOHO_PROJECTS_API_BASE", "https://projectsapi.zoho.com");
        return new BugsClient(apiBase, ProjectsClient.fromEnv());
    }

    public String createBug(String portalId, String projectId, JsonNode req) throws Exception {
        String title = trimLen(req.path("title").asText("UAT failure"), 250);
        String description = req.path("description").asText("");
        String severity = mapSeverity(req.path("severity").asText("Medium"));
        String classification = req.path("classification").asText("Bug");
        String reproSteps = req.path("steps_to_reproduce").asText("");
        String reporter = req.path("reporter").asText("");
        String associatedTaskIds = req.path("associated_taskids").asText("");

        String accessToken = delegate.accessToken();
        URI url = new URIBuilder(apiBase + "/restapi/portal/" + portalId
                + "/projects/" + projectId + "/bugs/").build();

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Zoho-oauthtoken " + accessToken);

            List<NameValuePair> form = new ArrayList<>();
            form.add(new BasicNameValuePair("title", title));
            form.add(new BasicNameValuePair("description", description));
            form.add(new BasicNameValuePair("severity", severity));
            form.add(new BasicNameValuePair("classification", classification));
            if (!reproSteps.isEmpty()) {
                form.add(new BasicNameValuePair("reproductionsteps", reproSteps));
            }
            if (!reporter.isEmpty()) {
                form.add(new BasicNameValuePair("reporter", reporter));
            }
            if (!associatedTaskIds.isEmpty()) {
                form.add(new BasicNameValuePair("associated_taskids", associatedTaskIds));
            }
            post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));

            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException("Projects Bugs API error " + code + ": " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                JsonNode bugs = root.path("bugs");
                if (bugs.isArray() && bugs.size() > 0) {
                    return bugs.get(0).path("id_string").asText(
                            bugs.get(0).path("id").asText());
                }
                return body;
            });
        }
    }

    private static String trimLen(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String mapSeverity(String s) {
        if (s == null) return "Medium";
        switch (s.toLowerCase()) {
            case "show stopper":
            case "critical":
            case "high":     return "Show Stopper";
            case "low":
            case "minor":    return "Minor";
            case "medium":
            default:         return "Major";
        }
    }
}
