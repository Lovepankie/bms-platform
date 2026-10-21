package com.rincoltech.bms.core.notifications.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTML part of every outbox email (issue #396, ADR-054): one table-based, inline-styled layout, at most 600 px wide,
 * with no external stylesheet or script, built from the same template and the same values as the plain-text part. The
 * header is the Rincoltech logo, or a business's own logo on the emails of its own staff; one big button carries the
 * main link, with the raw link below it for copy and paste; the footer says who sent it, why the reader got it and how
 * to reach support. Every value is HTML-escaped here, so a name a user typed can never become markup. Only a URL in a
 * link parameter that the caller trusts (the platform host or a tenant host, with the platform's scheme) becomes an
 * anchor or the button; any other URL, wherever it appears, is printed as escaped text.
 */
final class EmailLayout {

    /** The platform's own colour, the PWA's default {@code --brand}. */
    static final String PLATFORM_COLOUR = "#0D5C75";

    /** The Rincoltech logo of the PWA, served from the platform host (frontend/public/brand), 400 x 114. */
    static final String PLATFORM_LOGO_PATH = "/brand/rincoltech-logo.png";

    static final int PLATFORM_LOGO_WIDTH = 180;
    static final int PLATFORM_LOGO_HEIGHT = 51;

    static final String SENDER_LINE = "BMS by Rincoltech Solutions Ltd";
    static final String POWERED_BY = "Powered by BMS from Rincoltech";

    /** The standard signature delimiter, dash dash space, on a line of its own before the text part's footer. */
    static final String SIGNATURE_DELIMITER = "-- ";

    /** The parameters whose values are links: the only ones whose trusted URLs become a button or an anchor. */
    static final Set<String> LINK_PARAMS = Set.of("link", "links", "sign_in_url", "portal_url");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");
    /** Punctuation that ends a sentence after a link and is not part of it. */
    private static final Pattern TRAILING = Pattern.compile("[.,;:!?)\\]}]+$");

    private static final Pattern COLOUR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final String FONT = "font-family:Arial,Helvetica,sans-serif;";
    private static final String TEXT_COLOUR = "#1F2933";
    private static final String MUTED = "#52606D";

    private EmailLayout() {}

    /**
     * Whose look the email carries.
     *
     * @param logoUrl absolute URL of the header logo
     * @param logoAlt the logo's alt text (the business's name, or Rincoltech)
     * @param colour the button colour, {@code #RRGGBB}; anything else is replaced by the platform's
     * @param textOnColour the readable text colour on {@code colour}, {@code #RRGGBB}; anything else is white
     * @param tenant true for a business's own look: the footer then says it is powered by BMS
     */
    record Brand(
            String logoUrl,
            String logoAlt,
            int logoWidth,
            int logoHeight,
            String colour,
            String textOnColour,
            boolean tenant) {

        /** A colour that is not {@code #RRGGBB} never reaches a style attribute. */
        Brand {
            colour = isColour(colour) ? colour : PLATFORM_COLOUR;
            textOnColour = isColour(textOnColour) ? textOnColour : "#FFFFFF";
        }

        static Brand platform(String platformOrigin) {
            return new Brand(
                    platformOrigin + PLATFORM_LOGO_PATH,
                    "Rincoltech",
                    PLATFORM_LOGO_WIDTH,
                    PLATFORM_LOGO_HEIGHT,
                    PLATFORM_COLOUR,
                    "#FFFFFF",
                    false);
        }
    }

    /** One block of the body: a paragraph of escaped HTML, or the main button. */
    sealed interface Block {}

    record Paragraph(String html) implements Block {}

    record Button(String label, String url) implements Block {}

    /** What a template says, ready for the layout: the title, the blocks and the reason line, all escaped. */
    record Content(String title, List<Block> blocks, String reason) {}

    /**
     * The body of a template as blocks. A paragraph (lines between blank lines) that is exactly one link placeholder
     * whose value is a trusted URL becomes the button when the template names one, the first time only; every other
     * paragraph is escaped text with its line breaks kept and the trusted URLs of the link parameters as anchors.
     *
     * @param trusted whether a URL may become an anchor or the button (see {@link #trustedLinks})
     */
    static Content content(
            String title,
            String template,
            Map<String, String> params,
            String button,
            String reason,
            Predicate<String> trusted) {
        List<Block> blocks = new ArrayList<>();
        boolean buttonUsed = false;
        for (String paragraph : paragraphs(template)) {
            Matcher only = PLACEHOLDER.matcher(paragraph.strip());
            if (button != null && !buttonUsed && only.matches() && LINK_PARAMS.contains(only.group(1))) {
                String url = params.get(only.group(1));
                if (url != null && isWebLink(url.strip()) && trusted.test(url.strip())) {
                    blocks.add(new Button(escape(button), url.strip()));
                    buttonUsed = true;
                    continue;
                }
            }
            blocks.add(new Paragraph(fillHtml(paragraph, params, trusted)));
        }
        return new Content(escape(title), blocks, reason == null ? null : fillHtml(reason, params, null));
    }

    /** A body that has no template (a message composed elsewhere): its text as escaped paragraphs, no links. */
    static Content fromText(String title, String text) {
        List<Block> blocks = new ArrayList<>();
        for (String paragraph : paragraphs(text)) {
            blocks.add(new Paragraph(linesHtml(paragraph, null)));
        }
        return new Content(escape(title == null ? "" : title), blocks, null);
    }

    /** The plain-text footer appended to the text part, after the signature delimiter. */
    static String textFooter(Brand brand, String reasonText, String support) {
        StringBuilder out = new StringBuilder("\n" + SIGNATURE_DELIMITER + "\n");
        out.append(brand.tenant() ? POWERED_BY : SENDER_LINE).append('\n');
        if (reasonText != null && !reasonText.isBlank()) {
            out.append(reasonText).append('\n');
        }
        if (support != null && !support.isBlank()) {
            out.append("Need help? ").append(support.strip()).append('\n');
        }
        return out.toString();
    }

    /** The whole HTML document. */
    static String html(Brand brand, Content content, String support) {
        StringBuilder body = new StringBuilder();
        for (Block block : content.blocks()) {
            switch (block) {
                case Paragraph p ->
                    body.append("<p style=\"margin:0 0 16px 0;")
                            .append(FONT)
                            .append("font-size:16px;line-height:24px;color:")
                            .append(TEXT_COLOUR)
                            .append(";\">")
                            .append(p.html())
                            .append("</p>\n");
                case Button b -> body.append(button(brand, b));
            }
        }
        String preheader = content.title();
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"x-apple-disable-message-reformatting\">\n")
                // Light only: the colours below are chosen for a light card; mail apps that honour this keep them.
                .append("<meta name=\"color-scheme\" content=\"light only\">\n")
                .append("<meta name=\"supported-color-schemes\" content=\"light only\">\n")
                .append("<title>")
                .append(content.title())
                .append("</title>\n</head>\n")
                .append("<body style=\"margin:0;padding:0;background-color:#F0F2F5;\">\n")
                .append("<div style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;\">")
                .append(preheader)
                .append("</div>\n")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                        + " bgcolor=\"#F0F2F5\" style=\"background-color:#F0F2F5;\">\n<tr><td align=\"center\""
                        + " style=\"padding:24px 12px;\">\n")
                .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                        + " style=\"width:100%;max-width:600px;\">\n")
                // Header: the logo on a white band, so a transparent logo stays readable in dark mode.
                .append("<tr><td align=\"left\" bgcolor=\"#FFFFFF\" style=\"background-color:#FFFFFF;padding:20px 32px;"
                        + "border-top:4px solid ")
                .append(brand.colour())
                .append(";border-radius:8px 8px 0 0;\">")
                .append("<img src=\"")
                .append(escape(brand.logoUrl()))
                .append("\" alt=\"")
                .append(escape(brand.logoAlt()))
                .append("\" width=\"")
                .append(brand.logoWidth())
                .append("\" height=\"")
                .append(brand.logoHeight())
                .append("\" style=\"display:block;border:0;outline:none;text-decoration:none;height:")
                .append(brand.logoHeight())
                .append("px;width:")
                .append(brand.logoWidth())
                .append("px;max-width:100%;")
                .append(FONT)
                .append("font-size:18px;font-weight:bold;color:")
                .append(TEXT_COLOUR)
                .append(";\"></td></tr>\n")
                // Title and body.
                .append("<tr><td bgcolor=\"#FFFFFF\" style=\"background-color:#FFFFFF;padding:8px 32px 16px 32px;\">\n")
                .append("<h1 style=\"margin:0 0 20px 0;")
                .append(FONT)
                .append("font-size:22px;line-height:30px;font-weight:bold;color:")
                .append(TEXT_COLOUR)
                .append(";\">")
                .append(content.title())
                .append("</h1>\n")
                .append(body)
                .append("</td></tr>\n")
                // Footer.
                .append("<tr><td bgcolor=\"#F7F8FA\" style=\"background-color:#F7F8FA;padding:20px 32px;"
                        + "border-top:1px solid #E4E7EB;border-radius:0 0 8px 8px;")
                .append(FONT)
                .append("font-size:13px;line-height:20px;color:")
                .append(MUTED)
                .append(";\">\n");
        html.append(footerLine(brand.tenant() ? POWERED_BY : SENDER_LINE, true));
        if (content.reason() != null && !content.reason().isBlank()) {
            html.append(footerLine(content.reason(), false));
        }
        if (support != null && !support.isBlank()) {
            html.append(footerLine("Need help? " + escape(support.strip()), false));
        }
        html.append("</td></tr>\n</table>\n</td></tr>\n</table>\n</body>\n</html>\n");
        return html.toString();
    }

    private static String footerLine(String html, boolean strong) {
        return "<p style=\"margin:0 0 6px 0;" + FONT + "font-size:13px;line-height:20px;color:" + MUTED + ";"
                + (strong ? "font-weight:bold;" : "") + "\">" + html + "</p>\n";
    }

    /** A bulletproof button (a coloured table cell with a padded link), then the raw link for copy and paste. */
    private static String button(Brand brand, Button b) {
        String href = escape(b.url());
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:8px 0 16px 0;\">"
                + "<tr><td align=\"center\" bgcolor=\"" + brand.colour()
                + "\" style=\"border-radius:6px;background-color:"
                + brand.colour() + ";\"><a href=\"" + href
                + "\" target=\"_blank\" rel=\"noopener\" style=\"display:inline-block;"
                + "padding:14px 28px;" + FONT + "font-size:17px;line-height:22px;font-weight:bold;color:"
                + brand.textOnColour() + ";text-decoration:none;border-radius:6px;\">" + b.label()
                + "</a></td></tr></table>\n"
                + "<p style=\"margin:0 0 16px 0;" + FONT + "font-size:13px;line-height:20px;color:" + MUTED
                + ";word-break:break-all;\">If the button does not work, copy this link into your browser:<br>"
                + "<a href=\"" + href + "\" target=\"_blank\" rel=\"noopener\" style=\"color:" + brand.colour() + ";\">"
                + href + "</a></p>\n";
    }

    /**
     * Fills a template paragraph for HTML: literal text and values escaped, breaks kept, and with {@code trusted} the
     * trusted URLs in the values of the link parameters as anchors (null: no anchors at all).
     */
    private static String fillHtml(String text, Map<String, String> params, Predicate<String> trusted) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(linesHtml(text.substring(last, m.start()), null));
            String name = m.group(1);
            String value = params.get(name);
            if (value == null) {
                throw new IllegalArgumentException("missing template parameter " + name);
            }
            out.append(linesHtml(value, trusted != null && LINK_PARAMS.contains(name) ? trusted : null));
            last = m.end();
        }
        out.append(linesHtml(text.substring(last), null));
        return out.toString();
    }

    /** Escapes text, keeps its line breaks as {@code <br>}, and with {@code trusted} turns trusted URLs into anchors. */
    private static String linesHtml(String text, Predicate<String> trusted) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append("<br>");
            }
            out.append(trusted == null ? escape(lines[i]) : linkify(lines[i], trusted));
        }
        return out.toString();
    }

    /** Trusted URLs as anchors, cut before trailing punctuation; every other character, untrusted URLs included, escaped. */
    private static String linkify(String line, Predicate<String> trusted) {
        Matcher m = URL.matcher(line);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String url = trimTrailing(m.group());
            if (url.isEmpty() || !trusted.test(url)) {
                continue;
            }
            out.append(escape(line.substring(last, m.start())));
            String href = escape(url);
            out.append("<a href=\"")
                    .append(href)
                    .append("\" target=\"_blank\" rel=\"noopener\" style=\"color:")
                    .append(PLATFORM_COLOUR)
                    .append(";word-break:break-all;\">")
                    .append(href)
                    .append("</a>");
            last = m.start() + url.length();
        }
        out.append(escape(line.substring(last)));
        return out.toString();
    }

    /** Paragraphs separated by blank lines, trailing space trimmed, empty ones dropped. */
    static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.strip().split("\\n\\s*\\n")) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** A URL in running text without the sentence punctuation after it. */
    static String trimTrailing(String url) {
        return TRAILING.matcher(url).replaceFirst("");
    }

    static boolean isWebLink(String value) {
        return URL.matcher(value).matches();
    }

    static boolean isColour(String value) {
        return value != null && COLOUR.matcher(value).matches();
    }

    /**
     * The URLs an email may carry as anchors: the scheme of the platform's own links ({@code https} on staging and
     * production) and a host the predicate accepts (the platform host or a host under the tenant pattern).
     */
    static Predicate<String> trustedLinks(String scheme, Predicate<String> host) {
        return url -> {
            try {
                URI uri = new URI(url);
                return scheme.equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && host.test(uri.getHost());
            } catch (URISyntaxException e) {
                return false;
            }
        };
    }

    /** HTML escaping of text and attribute values. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
