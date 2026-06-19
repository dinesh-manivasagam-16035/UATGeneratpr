package com.uat.generator;

import java.io.InputStream;
import java.util.Properties;

/**
 * Loads default Zoho Projects portal/project identifiers from
 * {@code project-defaults.properties} on the classpath. Used by
 * {@link PushServlet} as the lowest-priority fallback when the request
 * body and env vars don't supply portal_id / project_id.
 */
public final class ProjectDefaults {

    private static final String RESOURCE = "/project-defaults.properties";

    private static final Properties PROPS = loadProps();

    private ProjectDefaults() {}

    public static String portal() {
        return get("portal");
    }

    public static String project() {
        return get("project");
    }

    private static String get(String key) {
        String v = PROPS.getProperty(key);
        return v == null ? null : v.trim();
    }

    private static Properties loadProps() {
        Properties p = new Properties();
        try (InputStream in = ProjectDefaults.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (Exception ignored) {
            // Optional file. If unreadable, defaults are simply absent and the
            // caller falls back to its own error message.
        }
        return p;
    }
}
