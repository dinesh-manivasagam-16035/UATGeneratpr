package com.uat.generator;

/** Text returned by an LLM call plus why it stopped (null when unknown). */
public final class LlmResult {
    public final String content;
    public final String finishReason;

    public LlmResult(String content, String finishReason) {
        this.content = content == null ? "" : content;
        this.finishReason = finishReason;
    }

    /** True when the model hit its output-token limit and the text is cut off. */
    public boolean truncated() {
        return "length".equalsIgnoreCase(finishReason);
    }
}
