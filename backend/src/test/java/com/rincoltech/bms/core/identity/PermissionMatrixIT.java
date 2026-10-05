package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * FR-IAM-02 and FR-IAM-03: the seeded role permissions equal the chapter 8 matrix cell for cell,
 * each role's signed-in user holds exactly its column, and for every route a principal holding
 * every permission except the route's own gets 403.
 */
class PermissionMatrixIT extends IntegrationTest {

    static final Path CHAPTER_8 = Path.of("../docs/sdd/08-security-design.md");
    static final List<String> COLUMNS = List.of(
            "tenant_admin",
            "branch_manager",
            "loan_officer",
            "cashier",
            "accountant",
            "auditor",
            "member",
            "retail_sales");
    static final Pattern ROW = Pattern.compile("^\\| `([a-z_]+\\.[a-z_]+\\.[a-z_]+)` \\|(.*)\\|\\s*$");

    @Autowired
    TestRestTemplate http;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    /** Role key to its permissions, read from the matrix table of chapter 8 section 8.3.2. */
    static Map<String, Set<String>> matrix() throws IOException {
        Map<String, Set<String>> byRole = new TreeMap<>();
        COLUMNS.forEach(c -> byRole.put(c, new TreeSet<>()));
        for (String line : Files.readAllLines(CHAPTER_8)) {
            Matcher m = ROW.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String[] cells = m.group(2).split("\\|", -1);
            if (cells.length != COLUMNS.size()) {
                continue;
            }
            for (int i = 0; i < cells.length; i++) {
                if (cells[i].trim().equals("Y")) {
                    byRole.get(COLUMNS.get(i)).add(m.group(1));
                }
            }
        }
        return byRole;
    }

    @Test
    void theSeededRolePermissionsEqualTheChapter8Matrix() throws IOException {
        Map<String, Set<String>> expected = matrix();
        assertThat(expected.get("tenant_admin")).hasSizeGreaterThan(60);
        Map<String, Set<String>> seeded = new TreeMap<>();
        COLUMNS.forEach(c -> seeded.put(c, new TreeSet<>()));
        TestDatabase.owner()
                .sql("SELECT role_key, permission_key FROM role_permissions")
                .query((rs, n) -> seeded.get(rs.getString("role_key")).add(rs.getString("permission_key")))
                .list();
        assertThat(seeded).isEqualTo(expected);
    }

    /** Each staff role, signed in for real, holds exactly its matrix column. */
    @Test
    void eachRoleHoldsExactlyItsColumn() throws IOException {
        Map<String, Set<String>> expected = matrix();
        TestDatabase.Fixture t = TestDatabase.tenant("matrix", true);
        Api api = Api.tenant(http, t.slug());
        for (String role : COLUMNS.stream().filter(c -> !c.equals("member")).toList()) {
            String email = Api.email(role.replace("_", ""));
            UUID branch = role.equals("tenant_admin") ? null : t.headOffice();
            UUID id = Api.staff(t, email, new Role(role, branch));
            Session session = api.signIn(id, email, Api.PASSWORD, null);
            JsonNode me = api.get("/api/v1/me", session.accessToken()).getBody();
            Set<String> held = new TreeSet<>();
            me.get("permissions").forEach(p -> held.add(p.asString()));
            assertThat(held).as("permissions of %s", role).isEqualTo(expected.get(role));
            assertThat(me.get("all_branches").asBoolean()).isEqualTo(branch == null);
        }
    }

    /** FR-IAM-03: for each declared permission, lacking exactly that one gets 403. */
    @Test
    void everyRouteRefusesAPrincipalWithoutItsPermission() {
        TestDatabase.Fixture t = TestDatabase.tenant("routes", true, true);
        List<String> all = TestDatabase.owner()
                .sql("SELECT key FROM permissions")
                .query(String.class)
                .list();
        List<String> checked = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                mapping.getHandlerMethods().entrySet()) {
            RequiresPermission required = e.getValue().getMethodAnnotation(RequiresPermission.class);
            if (required == null || !e.getValue().getBeanType().getPackageName().startsWith("com.rincoltech.bms")) {
                continue;
            }
            String pattern = e.getKey()
                    .getPathPatternsCondition()
                    .getPatternValues()
                    .iterator()
                    .next();
            if (pattern.startsWith("/api/v1/platform")) {
                continue; // platform routes accept platform tokens only; PlatformIT covers them
            }
            String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            HttpMethod method = HttpMethod.valueOf(e.getKey()
                    .getMethodsCondition()
                    .getMethods()
                    .iterator()
                    .next()
                    .name());
            HttpHeaders h = new HttpHeaders();
            h.add("X-Tenant", t.slug());
            h.add("X-Dev-User-Id", UUID.randomUUID().toString());
            h.add("X-Dev-Branch-Ids", "*");
            h.add(
                    "X-Dev-Permissions",
                    all.stream().filter(p -> !p.equals(required.value())).collect(Collectors.joining(",")));
            h.setAccept(List.of(MediaType.ALL));
            // A well-formed request in the media type the route declares (JSON unless it says
            // otherwise, as uploads do), so only the missing permission can refuse it.
            boolean multipart = e.getKey().getConsumesCondition().getConsumableMediaTypes().stream()
                    .anyMatch(MediaType.MULTIPART_FORM_DATA::includes);
            Object body;
            if (multipart) {
                MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
                form.add("placeholder", "x");
                h.setContentType(MediaType.MULTIPART_FORM_DATA);
                body = form;
            } else {
                h.setContentType(MediaType.APPLICATION_JSON);
                body = "{}";
            }
            ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
            assertThat(response.getStatusCode())
                    .as("%s %s without %s", method, pattern, required.value())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().get("code").asString()).isEqualTo("permission_denied");
            checked.add(method + " " + pattern);
        }
        assertThat(checked).as("routes checked").hasSizeGreaterThan(25);
    }
}
