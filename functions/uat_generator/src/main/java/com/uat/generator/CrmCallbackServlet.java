package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

public class CrmCallbackServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            handleCallback(req, resp);
        } catch (Exception e) {
            resp.sendRedirect("/app/index.html?crm_auth_error=1");
        }
    }

    private void handleCallback(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        // Validate state to prevent CSRF.
        String stateParam  = req.getParameter("state");
        String stateCookie = cookieValue(req, "tp_oauth_state");
        if (stateParam == null || !stateParam.equals(stateCookie)) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "OAuth state mismatch.");
            return;
        }

        String code = req.getParameter("code");
        if (code == null || code.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing OAuth code.");
            return;
        }

        String clientId     = System.getenv("ZOHO_CLIENT_ID");
        String clientSecret = System.getenv("ZOHO_CLIENT_SECRET");
        String redirectUri  = CrmAuthServlet.buildRedirectUri(req);

        // Exchange code for tokens.
        List<BasicNameValuePair> params = Arrays.asList(
                new BasicNameValuePair("grant_type",    "authorization_code"),
                new BasicNameValuePair("code",          code),
                new BasicNameValuePair("client_id",     clientId),
                new BasicNameValuePair("client_secret", clientSecret),
                new BasicNameValuePair("redirect_uri",  redirectUri)
        );

        JsonNode tokenResponse;
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(CrmAuthServlet.accountsBase() + "/oauth/v2/token");
            post.setEntity(new UrlEncodedFormEntity(params));
            tokenResponse = http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                if (response.getCode() >= 400) {
                    throw new RuntimeException("Token exchange failed: " + body);
                }
                return MAPPER.readTree(body);
            });
        }

        String accessToken  = tokenResponse.path("access_token").asText("");
        String refreshToken = tokenResponse.path("refresh_token").asText("");
        long   expiresIn    = tokenResponse.path("expires_in").asLong(3600);
        long   expiresAt    = System.currentTimeMillis() + expiresIn * 1000L;

        if (accessToken.isEmpty()) {
            throw new RuntimeException("Missing access_token in token response.");
        }

        // Use api_domain from the token response — it is org-specific and more
        // accurate than the DC env var (important when the user has multiple orgs).
        String apiDomainFromToken = tokenResponse.path("api_domain").asText("").trim();
        String apiBase = apiDomainFromToken.isEmpty()
                ? CrmAuthServlet.apiBase() : apiDomainFromToken;
        String accountsBase = CrmAuthServlet.accountsBase();

        // Optionally fetch the user's email.
        String email = fetchEmail(accessToken);

        // Persist credentials so CrmClient can refresh the token automatically.
        String clientIdEnv     = System.getenv("ZOHO_CLIENT_ID");
        String clientSecretEnv = System.getenv("ZOHO_CLIENT_SECRET");
        String sid = CrmTokenStore.create(accessToken, expiresAt, refreshToken, email,
                clientIdEnv, clientSecretEnv, accountsBase, apiBase);

        // Fetch the org name for display — non-fatal if it fails.
        fetchAndStoreOrgInfo(accessToken, apiBase, sid);

        Cookie sidCookie = new Cookie("tp_crm_sid", sid);
        sidCookie.setPath("/");
        sidCookie.setHttpOnly(true);
        sidCookie.setMaxAge(60 * 60 * 24 * 30); // 30 days
        resp.addCookie(sidCookie);

        // Clear the state cookie.
        Cookie clearState = new Cookie("tp_oauth_state", "");
        clearState.setPath("/");
        clearState.setMaxAge(0);
        resp.addCookie(clearState);

        resp.sendRedirect("/app/index.html");
    }

    private String fetchEmail(String accessToken) {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(
                    CrmAuthServlet.accountsBase() + "/oauth/v2/user?access_token=" + accessToken);
            return http.execute(get, response -> {
                String body = EntityUtils.toString(response.getEntity());
                JsonNode root = MAPPER.readTree(body);
                return root.path("result").path("Email").asText("");
            });
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Calls GET <apiBase>/crm/v3/org and stores the org id + name into the session bundle.
     * Failures are silently swallowed — org info is cosmetic and must not break the auth flow.
     */
    private void fetchAndStoreOrgInfo(String accessToken, String apiBase, String sid) {
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(apiBase + "/crm/v3/org");
            get.setHeader("Authorization", "Zoho-oauthtoken " + accessToken);
            http.execute(get, response -> {
                String body = EntityUtils.toString(response.getEntity());
                JsonNode root = MAPPER.readTree(body);
                JsonNode orgArr = root.path("org");
                if (orgArr.isArray() && orgArr.size() > 0) {
                    JsonNode org = orgArr.get(0);
                    String orgId   = org.path("id").asText("");
                    // Zoho may use "company_name" or "name" depending on plan/edition.
                    String orgName = org.path("company_name").asText("");
                    if (orgName.isEmpty()) orgName = org.path("name").asText("");
                    CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);
                    if (bundle != null) {
                        bundle.orgId   = orgId;
                        bundle.orgName = orgName;
                    }
                }
                return null;
            });
        } catch (Exception ignored) {
            // Org name is cosmetic — swallow errors silently.
        }
    }

    private static String cookieValue(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
