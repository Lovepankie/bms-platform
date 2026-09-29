package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequiresPermission;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** FR-IAM-03, NFR-SEC-04: every route declares one permission or is explicitly public. */
class RoutePermissionIT extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyRouteDeclaresAPermissionOrIsPublic() {
        var ours = mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().getPackageName().startsWith("com.rincoltech.bms"))
                .toList();
        assertThat(ours).as("routes found").isNotEmpty();
        List<String> undeclared = ours.stream()
                .filter(e -> !e.getValue().hasMethodAnnotation(RequiresPermission.class)
                        && !e.getValue().hasMethodAnnotation(PublicEndpoint.class)
                        && !e.getValue().hasMethodAnnotation(AuthenticatedEndpoint.class))
                .map(e -> e.getKey().toString())
                .toList();
        assertThat(undeclared)
                .as("routes without @RequiresPermission or @PublicEndpoint")
                .isEmpty();
    }
}
