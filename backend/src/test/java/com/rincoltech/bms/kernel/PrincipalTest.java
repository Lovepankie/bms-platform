package com.rincoltech.bms.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.Principal.BranchScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Branch scope applies per permission (chapter 8 section 8.3.1, FR-BR-04, NFR-ISO-04). */
class PrincipalTest {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    /** Branch manager at A and loan officer at B: approves loans at A only. */
    @Test
    void aPermissionAppliesOnlyInTheBranchesOfTheRoleThatGrantsIt() {
        Principal p = new Principal(
                UUID.randomUUID(),
                "staff",
                UUID.randomUUID(),
                Map.of(
                        "lending.loans.approve",
                        new BranchScope(false, Set.of(A)),
                        "lending.loans.read",
                        new BranchScope(false, Set.of(A)).union(new BranchScope(false, Set.of(B)))));
        assertThat(p.may("lending.loans.approve", A)).isTrue();
        assertThat(p.may("lending.loans.approve", B)).isFalse();
        assertThat(p.may("lending.loans.read", B)).isTrue();
        assertThat(p.may("lending.loans.create", A)).isFalse();
        assertThat(p.allBranches()).isFalse();
        assertThat(p.branchIds()).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void listFiltersIntersectTheScopeWithWhatWasAsked() {
        Principal scoped =
                new Principal(UUID.randomUUID(), "staff", null, Map.of("x.y.read", new BranchScope(false, Set.of(A))));
        assertThat(scoped.branchFilter("x.y.read", null)).containsExactly(A);
        assertThat(scoped.branchFilter("x.y.read", List.of(B))).isEmpty();
        assertThat(scoped.branchFilter("x.z.read", null)).isEmpty();

        Principal all = Principal.uniform(UUID.randomUUID(), "staff", Set.of("x.y.read"), true, Set.of());
        assertThat(all.branchFilter("x.y.read", null)).isNull();
        assertThat(all.branchFilter("x.y.read", List.of(B))).containsExactly(B);
        assertThat(new BranchScope(false, Set.of(A))
                        .union(new BranchScope(true, Set.of()))
                        .all())
                .isTrue();
    }
}
