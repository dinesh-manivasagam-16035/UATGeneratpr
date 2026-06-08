package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@WebServlet("/auth/callback")
public class AuthCallbackServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.addCorsHeaders(req, resp);

        String error = req.getParameter("error");
        String state = req.getParameter("state");

        // Parse state:  sid  |  <next-or-empty>  |  <iframe-flag-or-absent>
        String sid = "";
        String next = null;
        boolean isIframe = false;
        if (state != null && !state.isEmpty()) {
            String[] parts = state.split("\\|", 3);
            sid = parts[0];
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                next = parts[1];
            }
            if (parts.length >= 3 && "iframe".equals(parts[2])) {
                isIframe = true;
            }
        }

        // Ensure the sid cookie is refreshed / written
        SessionUtil.getOrCreateSid(req, resp, sid);

        // --- Error path (e.g. user denied, or prompt=none with no session) ---
        if (error != null) {
            if (isIframe) {
                sendPostMessage(resp, "zoho_auth_error", error);
            } else {
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "OAuth error: " + sanitize(error));
            }
            return;
        }

        String code = req.getParameter("code");
        if (code == null || code.isEmpty()) {
            if (isIframe) {
                sendPostMessage(resp, "zoho_auth_error", "missing_code");
            } else {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing authorization code");
            }
            return;
        }

        // --- Read required env vars ---
        String clientId = System.getenv("ZOHO_OAUTH_CLIENT_ID");
        String clientSecret = System.getenv("ZOHO_OAUTH_CLIENT_SECRET");
        String redirectBase = System.getenv("OAUTH_REDIRECT_BASE");
        String accountsBase = System.getenv().getOrDefault("ZOHO_ACCOUNTS_BASE", "https://accounts.zoho.com");

        if (clientId == null || clientSecret == null || redirectBase == null) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "OAuth environment variables not configured");
            return;
        }

        if (redirectBase.endsWith("/")) redirectBase = redirectBase.substring(0, redirectBase.length() - 1);
        if (accountsBase.endsWith("/")) accountsBase = accountsBase.substring(0, accountsBase.length() - 1);

        String redirectUri = redirectBase + "/auth/callback";

        // --- Exchange code for tokens ---
        TokenBundle bundle;
        try {
            bundle = exchangeCodeForTokens(code, clientId, clientSecret, redirectUri, accountsBase);
        } catch (Exception e) {
            if (isIframe) {
                sendPostMessage(resp, "zoho_auth_error", "token_exchange_failed");
            } else {
                resp.sendError(HttpServletResponse.SC_BAD_GATEWAY,
                        "Token exchange failed: " + e.getMessage());
            }
            return;
        }

        // --- Fetch user profile ---
        try {
            enrichWithUserProfile(bundle, accountsBase);
        } catch (Exception e) {
            // Non-fatal: profile fetch failure does not block the login
        }

        // --- Persist tokens ---
        TokenStore.put(sid, bundle);

        // --- Respond ---
        if (isIframe) {
            sendPostMessage(resp, "zoho_auth_success", null);
        } else {
            String destination = (next != null && !next.isEmpty()) ? next : "/uat_generator/";
            resp.sendRedirect(destination);
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.addCorsHeaders(req, resp);
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TokenBundle exchangeCodeForTokens(
            String code,
            String clientId,
            String clientSecret,
            String redirectUri,
            String accountsBase) throws Exception {

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(accountsBase + "/oauth/v2/token");

            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("grant_type", "authorization_code"));
            params.add(new BasicNameValuePair("client_id", clientId));
            params.add(new BasicNameValuePair("client_secret", clientSecret));
            params.add(new BasicNameValuePair("redirect_uri", redirectUri));
            params.add(new BasicNameValuePair("code", code));
            post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));

            return http.execute(post, response -> {
                int status = response.getCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (status != 200) {
                    throw new IOException("Token endpoint returned HTTP " + status + ": " + body);
                }
                JsonNode json = MAPPER.readTree(body);
                if (json.has("error")) {
                    throw new IOException("Token error: " + json.get("error").asText());
                }

                String accessToken = json.path("access_token").asText(null);
                String refreshToken = json.path("refresh_token").asText(null);
                long expiresIn = json.path("expires_in").asLong(3600);
                String apiDomain = json.path("api_domain").asText("https://www.zohoapis.in");

                if (accessToken == null) {
                    throw new IOException("No access_token in response");
                }

                long expiresAt = System.currentTimeMillis() + (expiresIn * 1000L);
                return new TokenBundle(accessToken, refreshToken, expiresAt, apiDomain, accountsBase, null, null);
            });
        }
    }

    private void enrichWithUserProfile(TokenBundle bundle, String accountsBase) throws Exception {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(accountsBase + "/oauth/user/info");
            get.setHeader("Authorization", "Zoho-oauthtoken " + bundle.getAccessToken());

            http.execute(get, response -> {
                if (response.getCode() != 200) return null;
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                JsonNode json = MAPPER.readTree(body);
                bundle.setEmail(json.path("Email").asText(null));
                bundle.setUserId(json.path("ZSUID").asText(
                        json.path("user_id").asText(null)));
                return null;
            });
        }
    }

    /**
     * Renders a minimal HTML page that calls window.parent.postMessage and then
     * closes the iframe / window.
     */
    private void sendPostMessage(HttpServletResponse resp, String type, String detail) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);

        // Build a safe JSON payload — values are ASCII identifiers so no escaping needed
        String detailJson = (detail != null)
                ? "\"" + detail.replaceAll("[^a-zA-Z0-9_\\-]", "") + "\""
                : "null";

        String payload = "{\"type\":\"" + type + "\",\"detail\":" + detailJson + "}";

        PrintWriter w = resp.getWriter();
        w.println("<!DOCTYPE html>");
        w.println("<html><head><meta charset=\"UTF-8\"></head><body>");
        w.println("<script>");
        // postMessage to parent (iframe scenario) and to opener (popup scenario)
        w.println("(function(){");
        w.println("  var msg = " + payload + ";");
        w.println("  try { window.parent.postMessage(msg, '*'); } catch(e){}");
        w.println("  try { window.opener && window.opener.postMessage(msg, '*'); } catch(e){}");
        w.println("})();");
        w.println("</script>");
        w.println("</body></html>");
    }

    /** Strip any characters that could be used for injection in error messages. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "");
    }
}
