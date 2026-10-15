package com.uat.generator;

/** One LLM call's worth of work: a BRD chunk, a module, a focus and a case budget. */
public final class GenerationRequest {
    public String platform = "crm";     // "crm" or "creator"
    public String module = "";          // CRM module api_name or Creator form link name
    public String moduleSchema = "";    // CRM only
    public String creatorOwner = "";
    public String creatorApp = "";
    public String outline = "";         // headings of the whole BRD, for context
    public String chunkText = "";
    public int part = 1;                // 1-based
    public int totalParts = 1;
    public String focus = "";
    public int wantedCases = 8;
    public String retryNote = "";       // set on a retry to explain what went wrong
}
