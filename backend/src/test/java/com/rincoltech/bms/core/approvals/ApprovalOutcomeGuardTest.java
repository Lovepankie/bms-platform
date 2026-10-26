package com.rincoltech.bms.core.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Review of #588 (ADR-091): every caller of {@code Approvals.request}
 *
 * <ul>
 *   <li>keeps the outcome and reads {@code executed()} in the same method (or hands it to a helper there), so an action the
 *       approvals module ran at once is answered with its record, never as waiting with no approval;
 *   <li>passes the rule it read, {@code request(request, rule)}, wherever its file reads {@code approvals.rule(...)}, so the rule
 *       is read once and a switch saved in between cannot run what the module answers as waiting.
 * </ul>
 *
 * A caller that answers with the same record whether the action ran or waits is listed with its reason and its exact number of
 * calls, so a new call there is checked like any other.
 */
class ApprovalOutcomeGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** A call of request: on the field {@code approvals}, or on any field when it is handed a new ActionRequest. */
    private static final Pattern CALL =
            Pattern.compile("\\b\\w+\\.request\\((?=\\s*new ActionRequest)|\\bapprovals\\.request\\(");

    /** A read of the rule: on the field {@code approvals}, or on any field when it names an action type. */
    private static final Pattern READS_RULE =
            Pattern.compile("\\bapprovals\\.rule\\(|\\b\\w+\\.rule\\(\\s*\\w+Action\\.TYPE");

    private static final Pattern KEPT = Pattern.compile("(\\w+)\\s*=\\s*\\w+\\.request\\($");

    /**
     * Call sites that answer with the same record either way, each named by the action it asks for, and why. Only a call whose
     * request names one of these actions is exempt; any other call in the same file is checked.
     */
    private static final Map<String, Map<String, String>> SAME_ANSWER = Map.of(
            "ExistingLoanService.java",
            Map.of(
                    "BringInAction.TYPE",
                    "answers with the loan's detail, which says it waits; loan_bring_in always waits and is not switchable",
                    "BringInReversalAction.TYPE",
                    "answers with the loan's detail, which says it waits; its reversal always waits and is not switchable"));

    /**
     * The callers allowed the one-argument {@code request(request)}, which reads the rule inside the approvals module: lending,
     * whose actions are never switchable, so no switch can race them. Every other caller passes the rule it read.
     */
    private static final java.util.Set<String> ONE_ARGUMENT = java.util.Set.of(
            "CollateralService.java",
            "ExistingLoanService.java",
            "ServicingService.java",
            "SavingsService.java",
            "InvestmentService.java");

    @Test
    void everyCallerOfRequestAnswersByExecutedAndPassesTheRuleItRead() throws IOException {
        List<String> problems = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("ApprovalService.java")) {
                    continue;
                }
                String code = Files.readString(file);
                boolean readsRule = READS_RULE.matcher(code).find();
                Map<String, String> exempt = SAME_ANSWER.getOrDefault(name, Map.of());
                Matcher m = CALL.matcher(code);
                while (m.find()) {
                    calls++;
                    int args = arguments(code, m.end());
                    if (args != 2 && (readsRule || !ONE_ARGUMENT.contains(name))) {
                        problems.add(name
                                + ": calls request without the rule it read (only the listed lending callers may)");
                    }
                    String call = code.substring(m.end(), Math.min(code.length(), m.end() + 80));
                    if (exempt.keySet().stream().anyMatch(call::contains)) {
                        continue;
                    }
                    String before = code.substring(Math.max(0, m.start() - 120), m.end())
                            .replaceAll("\\s+", " ");
                    Matcher kept = KEPT.matcher(before);
                    if (!kept.find()) {
                        problems.add(name + ": the outcome of approvals.request is dropped");
                        continue;
                    }
                    String var = kept.group(1);
                    String method = restOfMethod(code, m.end());
                    // Read for executed() in this method, or handed to a named helper whose own body reads it.
                    boolean read = method.contains(var + ".executed()") || helperReads(code, method, var);
                    if (!read) {
                        problems.add(name + ": " + var + ".executed() is not read in the calling method");
                    }
                }
            }
        }
        assertThat(calls).as("the guard found the callers").isGreaterThan(15);
        assertThat(problems).isEmpty();
    }

    /**
     * Whether the outcome is handed to a helper declared in the file, {@code name(x, var)} or {@code name(var)}, whose own body
     * reads {@code executed()} on the parameter that received it.
     */
    static boolean helperReads(String code, String method, String var) {
        Matcher call = Pattern.compile("\\b(\\w+)\\(((?:\\s*\\w+\\s*,)*)\\s*" + var + "\\s*[,)]")
                .matcher(method);
        while (call.find()) {
            int position = call.group(2).isEmpty() ? 0 : call.group(2).split(",").length;
            Matcher declared = Pattern.compile(
                            "\\b[\\w<>.]+\\s+" + Pattern.quote(call.group(1)) + "\\(([^)]*)\\)\\s*\\{")
                    .matcher(code);
            while (declared.find()) {
                String[] params = declared.group(1).split(",");
                if (position >= params.length) {
                    continue;
                }
                String[] words = params[position].trim().split("\\s+");
                String param = words[words.length - 1];
                if (restOfMethod(code, declared.end()).contains(param + ".executed()")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The number of top-level arguments of the call whose opening parenthesis ends at {@code open}. */
    static int arguments(String code, int open) {
        int depth = 0;
        int commas = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) {
                    return commas + 1;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
        }
        return commas + 1;
    }

    /** From the call to the end of the member it is in: the next line holding only a closing brace at member depth. */
    static String restOfMethod(String code, int from) {
        int end = code.indexOf("\n    }\n", from);
        return code.substring(from, end < 0 ? code.length() : end);
    }

    @Test
    void theGuardItselfCountsArgumentsAndSeesTheMethod() {
        String one = "approvals.request(new ActionRequest(a, b, Map.of(c, d)));";
        String two = "approvals.request(new ActionRequest(a, b, Map.of(c, d)), rule);";
        assertThat(arguments(one, one.indexOf('(') + 1)).isEqualTo(1);
        assertThat(arguments(two, two.indexOf('(') + 1)).isEqualTo(2);
        String code = "    void a() {\n        o = x;\n    }\n    void b() {\n        o.executed();\n    }\n";
        assertThat(restOfMethod(code, code.indexOf("o = x"))).doesNotContain("executed");
        // A helper counts only when its own body reads executed(); an unrelated executed() elsewhere does not.
        String helped = "    X a() {\n        return outcome(id, o);\n    }\n    X outcome(UUID id, Outcome o) {\n"
                + "        return o.executed() ? x : y;\n    }\n";
        assertThat(helperReads(helped, restOfMethod(helped, helped.indexOf("return outcome")), "o"))
                .isTrue();
        String unrelated =
                "    X a() {\n        return log(id, o);\n    }\n    X log(UUID id, Outcome o) {\n        return x;\n    }\n"
                        + "    void b() {\n        p.executed();\n    }\n";
        assertThat(helperReads(unrelated, restOfMethod(unrelated, unrelated.indexOf("return log")), "o"))
                .isFalse();
        // The helper must read executed() on the parameter that received the outcome, not on another one.
        String other = "    X a() {\n        return handle(id, o);\n    }\n    X handle(UUID id, Outcome o) {\n"
                + "        return other.executed() ? x : y;\n    }\n";
        assertThat(helperReads(other, restOfMethod(other, other.indexOf("return handle")), "o"))
                .isFalse();
        assertThat(READS_RULE
                        .matcher("Rule r = approver.rule(SaleVoidAction.TYPE);")
                        .find())
                .isTrue();
        assertThat(READS_RULE.matcher("throw ApiException.rule(\"x\", \"y\");").find())
                .isFalse();
        // A caller whose field has another name is still found.
        assertThat(CALL.matcher("approver.request(new ActionRequest(a), rule)").find())
                .isTrue();
    }
}
