package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * POST /crm/connect
 *
 * Accepts user-supplied OAuth credentials, validates them by exchanging the
 * refresh token for an access token, then stores the bundle in {@link CrmTokenStore}
 * and sets a session cookie.
 *
 * Body: {
 *   "client_id":     "...",
 *   "client_secret": "...",
 *   "refresh_token": "...",
 *   "accounts_base": "https://accounts.zoho.in",  (optional; defaults to zoho.com)
 *   "api_base":      "https://www.zohoapis.in"     (optional; defaults to zohoapis.com)
 * }
 *
 * Response: { "authorized": true, "email": "..." }
 * On failure: { "authorized": false, "error": "..." }
 */
public class CrmConnectServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setContentType("application/json");
        ObjectNode out = MAPPER.createObjectNode();

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String clientId     = body.path("client_id").asText("").trim();
            String clientSecret = body.path("client_secret").asText("").trim();
            String refreshToken = body.path("refresh_token").asText("").trim();
            String accountsBase = body.path("accounts_base").asText("").trim();
            String apiBase      = body.path("api_base").asText("").trim();

            if (accountsBase.isEmpty()) accountsBase = "https://accounts.zoho.com";
            if (apiBase.isEmpty())      apiBase      = "https://www.zohoapis.com";

            if (clientId.isEmpty() || clientSecret.isEmpty() || refreshToken.isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.put("authorized", false);
                out.put("error", "client_id, client_secret, and refresh_token are required.");
                resp.getWriter().write(MAPPER.writeValueAsString(out));
                return;
            }

            // Exchange refresh token for access token to validate credentials.
            List<BasicNameValuePair> params = Arrays.asList(
                    new BasicNameValuePair("grant_type",    "refresh_token"),
                    new BasicNameValuePair("refresh_token", refreshToken),
                    new BasicNameValuePair("client_id",     clientId),
                    new BasicNameValuePair("client_secret", clientSecret)
            );

            JsonNode tokenResponse;
            try (CloseableHttpClient http = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(accountsBase + "/oauth/v2/token");
                post.setEntity(new UrlEncodedFormEntity(params));
                String tokenBody = http.execute(post, r -> EntityUtils.toString(r.getEntity()));
                tokenResponse = MAPPER.readTree(tokenBody);
            }

            String accessToken = tokenResponse.path("access_token").asText("").trim();
            if (accessToken.isEmpty()) {
                String errMsg = tokenResponse.path("error").asText("invalid_credentials");
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                out.put("authorized", false);
                out.put("error", "Token exchange failed: " + errMsg
                        + ". Check your client_id, client_secret, and refresh_token.");
                resp.getWriter().write(MAPPER.writeValueAsString(out));
                return;
            }

            long expiresIn = tokenResponse.path("expires_in").asLong(3600);
            long expiresAt = System.currentTimeMillis() + expiresIn * 1000L;

            // Fetch user email.
            String email = fetchEmail(accessToken, accountsBase);

            // Store in session store.
            String sid = CrmTokenStore.create(accessToken, expiresAt, refreshToken, email,
                    clientId, clientSecret, accountsBase, apiBase);

            Cookie sidCookie = new Cookie("tp_crm_sid", sid);
            sidCookie.setPath("/");
            sidCookie.setHttpOnly(true);
            sidCookie.setMaxAge(60 * 60 * 24 * 30); // 30 days
            resp.addCookie(sidCookie);

            out.put("authorized", true);
            out.put("email", email != null ? email : "");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));

        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.put("authorized", false);
            out.put("error", "Server error: " + e.getMessage());
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.applyOptions(req, resp);
    }

    private String fetchEmail(String accessToken, String accountsBase) {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(accountsBase + "/oauth/v2/user?access_token=" + accessToken);
            return http.execute(get, response -> {
                String body = EntityUtils.toString(response.getEntity());
                JsonNode root = MAPPER.readTree(body);
                // Try common paths for email in Zoho account response.
                String email = root.path("result").path("Email").asText("");
                if (email.isEmpty()) email = root.path("Email").asText("");
                if (email.isEmpty()) email = root.path("email").asText("");
                return email;
            });
        } catch (Exception e) {
            return "";
        }
    }
}
