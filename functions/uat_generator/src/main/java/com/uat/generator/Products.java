package com.uat.generator;

import java.util.Locale;

/** Registry of product adapters. Today: CRM. Adding a product = one more class here. */
public final class Products {
    private Products() {}

    public static Product byId(String id) {
        String k = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        switch (k) {
            case "crm":
            case "":
            default:
                return CrmProduct.INSTANCE;
        }
    }

    /** The product the current request is for: body "platform"/"product", else CRM. */
    public static Product forRequest(com.fasterxml.jackson.databind.JsonNode body) {
        if (body == null) return CrmProduct.INSTANCE;
        String p = body.path("product").asText(body.path("platform").asText(""));
        return byId(p);
    }
}
