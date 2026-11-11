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

    public static Product byId(String id) {
        String k = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        switch (k) {
            case "desk": return DeskProduct.INSTANCE;
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
        return prod;
    }

    public static Product current() {
        Product p = CURRENT.get();
        return p != null ? p : byId(System.getenv("IMPL_PRODUCT"));
    }

    public static void clear() { CURRENT.remove(); }

    /** Same as {@link #use}, for callers that read naturally as "for this request". */
    public static Product forRequest(JsonNode body) { return use(body); }
}
