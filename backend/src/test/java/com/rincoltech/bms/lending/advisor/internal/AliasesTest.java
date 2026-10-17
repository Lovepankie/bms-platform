package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Group;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The alias mapper (spec 5.4, FR-AIA): branches and products only, assigned in the order of their ids, mapped back by
 * whole word and longest alias first in one pass, for the person who asked and never in a prompt.
 */
class AliasesTest {

    static FactSet branches(int n, boolean reversed) {
        Facts f = Facts.asOf("2026-10-14");
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            order.add(i);
        }
        if (reversed) {
            Collections.reverse(order);
        }
        for (int i : order) {
            f.group(Dimension.BRANCH, String.format("b-%02d", i), "Fabricated Branch Name " + i);
        }
        return f.build();
    }

    @Test
    void aliasesFollowTheOrderOfTheIdsWhateverTheInputOrderOrTheNames() {
        Aliases forward = Aliases.of(branches(12, false));
        Aliases backward = Aliases.of(branches(12, true));
        for (int i = 1; i <= 12; i++) {
            Group g = new Group(Dimension.BRANCH, String.format("b-%02d", i));
            assertThat(forward.aliasOf(g)).isEqualTo("Branch " + i);
            assertThat(backward.aliasOf(g))
                    .as("same alias in another input order")
                    .isEqualTo("Branch " + i);
        }
        // The same ids with other names get the same aliases: an alias tells nothing about a name.
        FactSet renamed = Facts.asOf("2026-10-14")
                .group(Dimension.BRANCH, "b-02", "Zzz")
                .group(Dimension.BRANCH, "b-01", "Aaa")
                .build();
        Aliases byId = Aliases.of(renamed);
        assertThat(byId.aliasOf(new Group(Dimension.BRANCH, "b-01"))).isEqualTo("Branch 1");
        assertThat(byId.aliasOf(new Group(Dimension.BRANCH, "b-02"))).isEqualTo("Branch 2");
    }

    @Test
    void productsGetLettersThatContinueAfterZ() {
        assertThat(Aliases.letters(1)).isEqualTo("A");
        assertThat(Aliases.letters(26)).isEqualTo("Z");
        assertThat(Aliases.letters(27)).isEqualTo("AA");
        assertThat(Aliases.letters(28)).isEqualTo("AB");
        assertThat(Aliases.letters(52)).isEqualTo("AZ");
        assertThat(Aliases.letters(53)).isEqualTo("BA");
        Facts f = Facts.asOf("2026-10-14");
        for (int i = 1; i <= 30; i++) {
            f.group(Dimension.PRODUCT, String.format("p-%02d", i), "Fabricated Product " + i);
        }
        Aliases aliases = Aliases.of(f.build());
        assertThat(aliases.aliasOf(new Group(Dimension.PRODUCT, "p-01"))).isEqualTo("Product A");
        assertThat(aliases.aliasOf(new Group(Dimension.PRODUCT, "p-27"))).isEqualTo("Product AA");
        assertThat(aliases.aliasOf(new Group(Dimension.PRODUCT, "p-30"))).isEqualTo("Product AD");
        assertThat(aliases.labels()).doesNotHaveDuplicates().hasSize(30);
    }

    @Test
    void branchesAndProductsAreNumberedApartAndTheSameIdInBothIsTwoGroups() {
        FactSet set = Facts.asOf("2026-10-14")
                .group(Dimension.BRANCH, "x", "Fabricated Branch X")
                .group(Dimension.PRODUCT, "x", "Fabricated Product X")
                .build();
        Aliases aliases = Aliases.of(set);
        assertThat(aliases.aliasOf(new Group(Dimension.BRANCH, "x"))).isEqualTo("Branch 1");
        assertThat(aliases.aliasOf(new Group(Dimension.PRODUCT, "x"))).isEqualTo("Product A");
        assertThat(aliases.aliasOf(new Group(Dimension.BRANCH, "unknown"))).isNull();
    }

    @Test
    void aliasesAreMappedBackByWholeWordLongestFirst() {
        Aliases a = Aliases.of(branches(12, false));
        assertThat(a.toNames("Branch 1 and Branch 10 and Branch 12 are late."))
                .isEqualTo(
                        "Fabricated Branch Name 1 and Fabricated Branch Name 10 and Fabricated Branch Name 12 are late.");
        // Branch 100 does not exist, Branch 1st is not Branch 1, and a glued prefix is not a word start.
        assertThat(a.toNames("Branch 100, Branch 1st, XBranch 1, Branch 1x and Branch 13 stay as written."))
                .isEqualTo("Branch 100, Branch 1st, XBranch 1, Branch 1x and Branch 13 stay as written.");
        assertThat(a.toNames("Branch 2's loans and branch 3: BRANCH 4."))
                .isEqualTo("Fabricated Branch Name 2's loans and Fabricated Branch Name 3: Fabricated Branch Name 4.");
        assertThat(a.toNames("Branch   5")).isEqualTo("Fabricated Branch Name 5");
    }

    @Test
    void theMappingIsOnePassSoANameThatLooksLikeAnAliasIsNotMappedAgain() {
        FactSet set = Facts.asOf("2026-10-14")
                .group(Dimension.BRANCH, "b-1", "Branch 2")
                .group(Dimension.BRANCH, "b-2", "Branch 1")
                .build();
        Aliases a = Aliases.of(set);
        // Branch 1 is named "Branch 2" and Branch 2 is named "Branch 1": swapped once, not twice and not looped.
        assertThat(a.toNames("Branch 1 then Branch 2.")).isEqualTo("Branch 2 then Branch 1.");
    }

    @Test
    void aProductLetterIsExactAndTheWordMayBeInAnyCase() {
        FactSet set = Facts.asOf("2026-10-14")
                .group(Dimension.PRODUCT, "p-1", "Fabricated Product One")
                .group(Dimension.PRODUCT, "p-2", "Fabricated Product Two")
                .build();
        Aliases a = Aliases.of(set);
        assertThat(a.toNames("Product A, product B and PRODUCT A."))
                .isEqualTo("Fabricated Product One, Fabricated Product Two and Fabricated Product One.");
        // A lower case letter is an ordinary word: "a product a customer chose".
        assertThat(a.toNames("Product a customer chose")).isEqualTo("Product a customer chose");
        assertThat(a.toNames("Product AB is not here")).isEqualTo("Product AB is not here");
    }

    @Test
    void invisibleCharactersCannotKeepAnAliasFromBeingMappedBack() {
        Aliases a = Aliases.of(branches(3, false));
        assertThat(a.toNames("Bra" + Facts.cp(0x200B) + "nch 2 and Branch" + Facts.cp(0x00A0) + "3 and Branch "
                        + Facts.cp(0xFF11)))
                .isEqualTo("Fabricated Branch Name 2 and Fabricated Branch Name 3 and Fabricated Branch Name 1");
    }

    @Test
    void aNameWithRegexOrReplacementCharactersIsInsertedLiterally() {
        FactSet set = Facts.asOf("2026-10-14")
                .group(Dimension.BRANCH, "b-1", "Fabricated $1 \\ (Annex) [x] *")
                .build();
        // Replacement characters stay literal; the characters that could start markup are neutralised.
        assertThat(Aliases.of(set).toNames("Branch 1 is open.")).isEqualTo("Fabricated $1 (Annex) x is open.");
    }

    @Test
    void withNoGroupsTheTextIsOnlyCleaned() {
        assertThat(Aliases.none().toNames("Branch 1 is " + Facts.cp(0x200B) + "fine"))
                .isEqualTo("Branch 1 is fine");
        assertThat(Aliases.none().pattern()).isNull();
        assertThat(Aliases.none().labels()).isEmpty();
    }

    @Test
    void theMapperPrintsCountsNeverNames() {
        Aliases a = Aliases.of(branches(3, false));
        assertThat(a.toString()).doesNotContain("Fabricated").isEqualTo("Aliases[3]");
        assertThat(branches(3, false).toString()).doesNotContain("Fabricated");
        assertThat(branches(3, false).names().get(0).toString()).doesNotContain("Fabricated");
        assertThat(a.names()).hasSize(3);
    }

    @Test
    void anyOrderOfAnyGroupsRoundTrips() {
        Random random = new Random(223);
        for (int round = 0; round < 200; round++) {
            int n = 1 + random.nextInt(30);
            List<Integer> ids = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                ids.add(i);
            }
            Collections.shuffle(ids, random);
            Facts f = Facts.asOf("2026-10-14");
            for (int id : ids) {
                f.group(Dimension.BRANCH, String.format("b-%03d", id), "Fabricated Name " + (id * 7919 % 1000));
            }
            Aliases a = Aliases.of(f.build());
            // The aliases are Branch 1..n in id order, each once.
            assertThat(a.labels()).hasSize(n).doesNotHaveDuplicates();
            StringBuilder text = new StringBuilder();
            StringBuilder expected = new StringBuilder();
            for (int k = 0; k < 10; k++) {
                int pick = 1 + random.nextInt(n);
                String sep =
                        k == 0 ? "" : List.of(", ", " and ", "; ", ": ", " vs ").get(random.nextInt(5));
                text.append(sep).append("Branch ").append(pick);
                expected.append(sep).append("Fabricated Name ").append(pick * 7919 % 1000);
            }
            assertThat(a.toNames(text.toString())).as(text.toString()).isEqualTo(expected.toString());
        }
    }

    @Test
    void aRealNameIsNeutralisedForHtmlAndMarkdownWhereItIsMappedBack() {
        for (String hostile : new String[] {
            "<img src=x onerror=alert(1)>",
            "<script>alert(1)</script>",
            "Line one\nLine two\r\n" + Facts.cp(0x2028) + "three",
            "**bold** __strong__ ~~gone~~",
            "[link](javascript:alert(1))",
            "`code` and a | pipe",
            "back\\slash",
            "Smith &amp; Sons &lt;b&gt;"
        }) {
            FactSet set = Facts.asOf("2026-10-14")
                    .group(Dimension.BRANCH, "b-1", hostile)
                    .build();
            String shown = Aliases.of(set).toNames("Branch 1 is open.");
            assertThat(shown)
                    .as(hostile)
                    .doesNotContainPattern("[<>`*~|\\[\\]\\\\_&]")
                    .doesNotContainPattern("\\R");
            assertThat(shown).as(hostile).endsWith(" is open.").isEqualTo(shown.strip());
        }
        assertThat(Aliases.neutral("Smith & Sons")).isEqualTo("Smith and Sons");
        assertThat(Aliases.neutral("   ")).isEqualTo("(unnamed)");
        assertThat(Aliases.neutral("O'Brien \"North\"")).isEqualTo("O'Brien 'North'");
        assertThat(Aliases.neutral("Kampala Road (Main) Branch")).isEqualTo("Kampala Road (Main) Branch");
    }
}
