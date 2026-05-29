package com.uat.generator;

import com.zc.component.connections.ZCConnections;
import com.zc.component.connections.beans.ZCConnectionResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Auth source backed by a Catalyst Connection. Reads the access-token-bearing
 * header(s) from {@code ZCConnections.getConnectionCredentials(name)} and
 * returns them as a plain map so callers can attach them to outbound HTTP
 * requests.
 *
 * Setup (one-time, in the Catalyst console):
 *   1. Cloud Scale → Connections → Create Connection
 *   2. Pick service "Zoho Projects" (or generic OAuth with the scopes you want)
 *   3. Authenticate in browser; Catalyst stores the refresh + access tokens
 *   4. Name the connection (e.g. "zoho_projects")
 *
 * Runtime: set env var {@code ZOHO_CONNECTION_NAME=zoho_projects} on the
 * AppSail. {@link ProjectsClient} and {@link BugsClient} will route their
 * auth through this class instead of the self-client refresh-token flow.
 *
 * The Catalyst SDK auto-refreshes the access token; no token caching here.
 */
public final class CatalystConnectionAuth {

    private final String connectionName;

    public CatalystConnectionAuth(String connectionName) {
        this.connectionName = connectionName;
    }

    public Map<String, String> authHeaders() throws Exception {
        ZCConnectionResponse resp =
                ZCConnections.getInstance().getConnectionCredentials(connectionName);
        Map<String, String> headers = resp.getHeaders();
        if (headers == null || headers.isEmpty()) {
            // Defensive: SDK should always return Authorization; surface a
            // clearer error than letting a null get attached.
            throw new IllegalStateException(
                    "Catalyst Connection '" + connectionName
                            + "' returned no auth headers. Check the connection's "
                            + "status in the Catalyst console.");
        }
        return new HashMap<>(headers);
    }

    public String connectionName() {
        return connectionName;
    }

    /** Returns the configured Catalyst connection name from env, or null. */
    public static String configuredConnectionName() {
        String v = System.getenv("ZOHO_CONNECTION_NAME");
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }
}
