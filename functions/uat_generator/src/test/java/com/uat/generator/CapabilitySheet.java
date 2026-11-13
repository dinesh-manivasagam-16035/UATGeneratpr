package com.uat.generator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Writes docs/capabilities/<product>.csv from the adapters, so the repo's sheet always matches the code. */
public final class CapabilitySheet {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "../../docs/capabilities");
        Files.createDirectories(dir);
        for (String id : new String[]{"crm", "desk", "creator"}) {
            Product p = Products.byId(id);
            Path f = dir.resolve("zoho-" + id + ".csv");
            Files.write(f, CapabilitiesServlet.csv(p).getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + f + " (" + p.capabilities().size() + " rows)");
        }
    }
}
