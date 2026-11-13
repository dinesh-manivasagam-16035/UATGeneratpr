package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Registry of product adapters, and the product the current request is for. Servlets call
 * {@link #use(JsonNode)} first thing (the job worker calls it with the job input); everything
 * downstream asks {@link #current()}. Adding a product = one more adapter class here.
 */
public final class Products {
    private Products() {}

    private static final ThreadLocal<Product> CURRENT = new ThreadLocal<>();
    /** Per-request context a product needs but the pipeline doesn't model: Creator's owner and app. */
    private static final ThreadLocal<String[]> CONTEXT = new ThreadLocal<>();

    public static Product byId(String id) {
        String k = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        switch (k) {
            case "desk": return DeskProduct.INSTANCE;
            case "creator": return CreatorProduct.INSTANCE;
            case "crm":
            case "":
            default:
                return CrmProduct.INSTANCE;
        }
    }

    /** Sets the product for this request from body "product"/"platform" (env IMPL_PRODUCT, then CRM, as fallbacks). */
    public static Product use(JsonNode body) {
        String p = body == null ? "" : body.path("product").asText(body.path("platform").asText(""));
        if (p.isEmpty()) p = System.getenv("IMPL_PRODUCT") == null ? "" : System.getenv("IMPL_PRODUCT");
        Product prod = byId(p);
        CURRENT.set(prod);
        String owner = body == null ? "" : body.path("creator_owner").asText(body.path("owner").asText(""));
        String app = body == null ? "" : body.path("creator_app").asText(body.path("app").asText(""));
        if (owner.isEmpty()) owner = env("CREATOR_OWNER");
        if (app.isEmpty()) app = env("CREATOR_APP");
        CONTEXT.set(new String[]{owner, app});
        return prod;
    }

    public static Product current() {
        Product p = CURRENT.get();
        return p != null ? p : byId(System.getenv("IMPL_PRODUCT"));
    }

    public static void clear() { CURRENT.remove(); CONTEXT.remove(); }

    /** Creator's account owner name for this request ("" when unknown). */
    public static String owner() { String[] c = CONTEXT.get(); return c == null ? env("CREATOR_OWNER") : c[0]; }

    /** Creator's app link name for this request ("" when unknown). */
    public static String app() { String[] c = CONTEXT.get(); return c == null ? env("CREATOR_APP") : c[1]; }

    private static String env(String name) { String v = System.getenv(name); return v == null ? "" : v.trim(); }

    /** Same as {@link #use}, for callers that read naturally as "for this request". */
    public static Product forRequest(JsonNode body) { return use(body); }
}
