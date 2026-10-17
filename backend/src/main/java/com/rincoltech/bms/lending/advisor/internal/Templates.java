package com.rincoltech.bms.lending.advisor.internal;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills a reviewed template in one pass: a value that itself looks like a placeholder is text, never filled again. */
final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z]+)\\}\\}");

    private Templates() {}

    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
