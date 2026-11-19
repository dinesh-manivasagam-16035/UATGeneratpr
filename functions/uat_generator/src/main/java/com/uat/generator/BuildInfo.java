package com.uat.generator;

/**
 * The build this code belongs to. The page carries the same value as the ?v= on its scripts and
 * stylesheets (client/index.html), and compares it with what the functions report:
 *
 *   uat_generator  →  GET /health, GET /crm/status and POST /implement/lanes return {build}
 *   impl_worker    →  every job status it writes carries {build}
 *
 * A mismatch means a deploy shipped only part of the app — typically `catalyst deploy` without
 * functions/uat_generator/build-for-deploy.sh, which leaves lib/uat-classes.jar (these classes) at
 * the previous build — and the page says so instead of quietly running old lanes and old prompts.
 *
 * Bump VERSION and the ?v= values together; SelfCheck fails when they differ.
 */
public final class BuildInfo {
    public static final String VERSION = "20261119a";

    private BuildInfo() {}
}
