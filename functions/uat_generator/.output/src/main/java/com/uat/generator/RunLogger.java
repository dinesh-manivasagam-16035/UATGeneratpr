package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs each generation/push run into a Catalyst Datastore table named
 * "uat_runs". Table is expected to have columns:
 *   - run_id (Bigint, autoincrement primary key)
 *   - created_at (DateTime)
 *   - module (Varchar)
 *   - provider (Varchar)
 *   - brd_length (Int)
 *   - case_count (Int)
 *   - status (Varchar)  // "generated" | "pushed" | "failed"
 *   - error (Text, nullable)
 *
 * Logging failures are swallowed — they must not break the main request.
 */
public final class RunLogger {

    private static final Logger LOG = Logger.getLogger(RunLogger.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TABLE = System.getenv().getOrDefault("CATALYST_RUNS_TABLE", "uat_runs");

    private RunLogger() {}

    public static void logGenerate(HttpServletRequest req, String module, String provider,
                                   int brdLength, int caseCount, String status, String error) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("created_at", Instant.now().toString());
        row.put("module", module);
        row.put("provider", provider);
        row.put("brd_length", brdLength);
        row.put("case_count", caseCount);
        row.put("status", status);
        if (error != null) row.put("error", truncate(error, 2000));
        insertAsync(req, row);
    }

    private static void insertAsync(HttpServletRequest req, ObjectNode row) {
        new Thread(() -> {
            try {
                insert(req, row);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "RunLogger insert failed (non-fatal): " + e.getMessage());
            }
        }, "uat-run-logger").start();
    }

    private static void insert(HttpServletRequest req, ObjectNode row) throws Exception {
        String projectDomain = firstNonEmpty(
            System.getenv("X_ZOHO_CATALYST_PROJECT_DOMAIN"),
            System.getenv("CATALYST_PROJECT_DOMAIN"),
            req.getHeader("x-zoho-catalyst-project-domain"));
        String projectId = firstNonEmpty(
            System.getenv("X_ZOHO_CATALYST_PROJECT_ID"),
            System.getenv("CATALYST_PROJECT_ID"),
            req.getHeader("x-zoho-catalyst-project-id"));
        String credToken = firstNonEmpty(
            req.getHeader("x-zc-admincred-token"),
            req.getHeader("x-zc-user-cred-token"),
            System.getenv("CATALYST_ADMIN_CRED_TOKEN"));

        if (projectDomain == null || projectId == null || credToken == null) {
            LOG.fine("RunLogger skipped: missing Catalyst context (domain/id/token).");
            return;
        }

        String url = projectDomain + "/baas/v1/project/" + projectId
                   + "/table/" + TABLE + "/row";

        ArrayNode payload = MAPPER.createArrayNode();
        payload.add(row);

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Zoho-oauthtoken " + credToken);
            post.setEntity(new StringEntity(MAPPER.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
            http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                if (response.getCode() >= 400) {
                    LOG.log(Level.WARNING, "RunLogger HTTP " + response.getCode() + ": " + body);
                }
                return null;
            });
        }
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max - 3) + "...");
    }

    static JsonNode asJson(ObjectNode row) { return row; }
}
