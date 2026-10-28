package com.rincoltech.bms.core.help;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The small reads of a model's JSON answer every setup draft needs (ADR-102): a fenced answer, a list, a name, a number.
 * Deterministic, so the same answer always reads the same way.
 */
public final class DraftJson {

    private DraftJson() {}

    /** The list under {@code field} of the answer's object, or the answer itself when it is a list; null when neither. */
    public static JsonNode array(ObjectMapper mapper, String answer, String field) {
        String body = answer.strip();
        if (body.startsWith("```")) {
            body = body.replaceAll("^```[a-zA-Z]*\\s*|\\s*```$", "");
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode list = root.isArray() ? root : root.path(field);
            return list.isArray() ? list : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A text field trimmed, with its runs of spaces made one, or null when empty or longer than {@code max}. */
    public static String text(JsonNode node, String field, int max) {
        JsonNode v = node.path(field);
        if (!v.isString() && !v.isNumber()) {
            return null;
        }
        String s = v.asString("").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return s.isEmpty() || s.length() > max ? null : s;
    }

    /**
     * A number, written as a number or as text the way a stock book writes it ("5,000", "UGX 5000", "5000/="); null when
     * there is none.
     */
    public static BigDecimal number(JsonNode node, String field) {
        JsonNode v = node.path(field);
        try {
            if (v.isNumber()) {
                return v.decimalValue();
            }
            if (v.isString()) {
                String s = v.asString("")
                        .replace(",", "")
                        .replace("/=", "")
                        .replaceAll("(?i)ugx|ksh|kes|tzs|usd|shs?\\.?", "")
                        .strip();
                return s.matches("\\d{1,15}(\\.\\d{1,6})?") ? new BigDecimal(s) : null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }
}
