package com.uat.generator;

import com.zc.common.ZCProject;
import com.zc.component.cache.ZCCache;
import com.zc.component.cache.ZCCacheObject;

import java.security.SecureRandom;

/**
 * Hand-off between uat_generator (Advanced I/O, ~30 s per request) and the impl_worker job
 * function (15 min): inputs, progress and results of a background generation job, kept in
 * Catalyst Cache.
 *
 * Cache limits (Catalyst docs): keys up to 50 characters, values up to 16,000 characters,
 * expiry at most 48 hours. Longer values are split into pieces: "{key}" holds the piece count
 * as "#n", and "{key}.0" … "{key}.{n-1}" hold the text.
 *
 * Env: LIFTOFF_CACHE_SEGMENT — a segment name to use instead of the project's default segment.
 *      LIFTOFF_JOBSTORE=memory — keep entries in this JVM instead (local tests only; the
 *      worker and the web function are separate processes on Catalyst).
 */
public final class JobStore {

    private static final int PIECE = 15_000;
    private static final long EXPIRY_HOURS = 24;
    private static final SecureRandom RNG = new SecureRandom();

    private JobStore() {}

    /** A new job key: "j" + 11 random characters, short enough for every suffix used. */
    public static String newKey() {
        String abc = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder("j");
        for (int i = 0; i < 11; i++) sb.append(abc.charAt(RNG.nextInt(abc.length())));
        return sb.toString();
    }

    public static void put(String key, String value) throws Exception {
        String v = value == null ? "" : value;
        int n = Math.max(1, (v.length() + PIECE - 1) / PIECE);
        for (int i = 0; i < n; i++) {
            write(key + "." + i, v.substring(i * PIECE, Math.min(v.length(), (i + 1) * PIECE)));
        }
        write(key, "#" + n);
    }

    /** The stored text, or null when the key doesn't exist (or expired). */
    public static String get(String key) throws Exception {
        String head = read(key);
        if (head == null) return null;
        if (!head.startsWith("#")) return head;
        int n = Integer.parseInt(head.substring(1));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String piece = read(key + "." + i);
            if (piece == null) throw new IllegalStateException("Cache entry " + key + " is incomplete (piece " + i + " missing).");
            sb.append(piece);
        }
        return sb.toString();
    }

    /** Valid key for a job: letters and digits only, so request input can't reach other entries. */
    public static boolean validKey(String key) {
        return key != null && key.matches("j[a-z0-9]{11}");
    }

    // ---- Catalyst Cache ----

    static final java.util.Map<String, String> MEMORY = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean memory() { return "memory".equalsIgnoreCase(System.getenv("LIFTOFF_JOBSTORE")); }

    private static void write(String key, String value) throws Exception {
        if (memory()) { MEMORY.put(key, value); return; }
        String segment = System.getenv("LIFTOFF_CACHE_SEGMENT");
        if (segment != null && !segment.trim().isEmpty()) {
            cache().getSegmentInstance(segment.trim()).putCacheValue(key, value, EXPIRY_HOURS);
        } else {
            cache().putCacheValue(key, value, EXPIRY_HOURS);
        }
    }

    private static String read(String key) throws Exception {
        if (memory()) return MEMORY.get(key);
        try {
            String segment = System.getenv("LIFTOFF_CACHE_SEGMENT");
            if (segment != null && !segment.trim().isEmpty()) {
                return cache().getSegmentInstance(segment.trim()).getCacheValue(key);
            }
            ZCCacheObject o = cache().getCacheValue(key);
            return o == null ? null : o.getValue();
        } catch (Exception e) {
            // The SDK throws for a missing key; treat "not found" as absent, rethrow anything else.
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (m.contains("not found") || m.contains("no such") || m.contains("invalid key") || m.contains("does not exist")) return null;
            throw e;
        }
    }

    private static ZCCache cache() throws Exception {
        ZCProject.initProject();
        return ZCCache.getInstance();
    }
}
