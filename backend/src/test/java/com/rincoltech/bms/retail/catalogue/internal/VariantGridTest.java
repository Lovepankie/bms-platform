package com.rincoltech.bms.retail.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.VariantLine;
import com.rincoltech.bms.retail.catalogue.internal.VariantGrid.Planned;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The quick creation of an item's sizes and colours (issue #384, FR-RET-51): combinations, codes, prices and order. */
class VariantGridTest {

    @Test
    void frRet51EveryCombinationIsMadeSizesFirstInTheOrderGiven() {
        List<Planned> plan = VariantGrid.plan(
                "ROMP", List.of("0-3m", "3-6m", "6-12m"), List.of("Blue", "Pink"), null, 4_000, 9_000, Set.of(), 0);
        assertThat(plan)
                .extracting(Planned::code)
                .containsExactly(
                        "ROMP-03M-BLUE",
                        "ROMP-03M-PINK",
                        "ROMP-36M-BLUE",
                        "ROMP-36M-PINK",
                        "ROMP-612M-BLUE",
                        "ROMP-612M-PINK");
        assertThat(plan).extracting(Planned::position).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(plan).allSatisfy(p -> {
            assertThat(p.costMinor()).isEqualTo(4_000);
            assertThat(p.sellMinor()).isEqualTo(9_000);
        });
        assertThat(plan.getFirst().size()).isEqualTo("0-3m");
        assertThat(plan.getFirst().colour()).isEqualTo("Blue");
    }

    @Test
    void frRet51SizesAloneOrColoursAloneMakeOneVariantEach() {
        assertThat(VariantGrid.plan("CAB", List.of("1.5mm", "2.5mm"), List.of(), null, 1, 2, Set.of(), 0))
                .extracting(Planned::code)
                .containsExactly("CAB-15MM", "CAB-25MM");
        assertThat(VariantGrid.plan("SOCK", null, List.of("Red", " red ", "White"), null, 1, 2, Set.of(), 0))
                .extracting(Planned::colour)
                .containsExactly("Red", "White");
        assertThat(VariantGrid.plan("X", List.of(" "), List.of(), null, 1, 2, Set.of(), 0))
                .isEmpty();
    }

    @Test
    void frRet51ACombinationTheItemHasIsSkippedAndPositionsContinue() {
        Set<String> existing = Set.of(Planned.key("0-3m", "Blue"));
        List<Planned> plan =
                VariantGrid.plan("ROMP", List.of("0-3m"), List.of("blue", "Pink"), null, 1, 2, existing, 3);
        assertThat(plan).extracting(Planned::colour).containsExactly("Pink");
        assertThat(plan.getFirst().position()).isEqualTo(3);
    }

    @Test
    void frRet51NamedLinesKeepTheirOwnCodesAndPricesAndRefuseRepeats() {
        List<Planned> plan = VariantGrid.plan(
                "ROMP",
                null,
                null,
                List.of(
                        new VariantLine("0-3m", "Blue", "R-SMALL", 3_000L, 8_000L),
                        new VariantLine("3-6m", null, null, null, null)),
                4_000,
                9_000,
                Set.of(),
                0);
        assertThat(plan.get(0).code()).isEqualTo("R-SMALL");
        assertThat(plan.get(0).sellMinor()).isEqualTo(8_000);
        assertThat(plan.get(1).code()).isEqualTo("ROMP-36M");
        assertThat(plan.get(1).costMinor()).isEqualTo(4_000);
        assertThatThrownBy(() -> VariantGrid.plan(
                        "ROMP",
                        null,
                        null,
                        List.of(
                                new VariantLine("S", "Red", null, null, null),
                                new VariantLine("s", "RED", null, null, null)),
                        1,
                        2,
                        Set.of(),
                        0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already");
        assertThatThrownBy(() -> VariantGrid.plan(
                        "ROMP", null, null, List.of(new VariantLine(" ", null, null, null, null)), 1, 2, Set.of(), 0))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void frRet51TwoCombinationsThatWouldShareACodeAreRefused() {
        // "0-3m" and "03m" are different sizes but both give the token 03M.
        assertThatThrownBy(() -> VariantGrid.plan("ROMP", List.of("0-3m", "03m"), List.of(), null, 1, 2, Set.of(), 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ROMP-03M");
    }

    @Test
    void frRet51AtMostAHundredAreMadeAtATime() {
        List<String> sizes = new ArrayList<>();
        List<String> colours = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            sizes.add("S" + i);
            colours.add("C" + i);
        }
        assertThatThrownBy(() -> VariantGrid.plan("X", sizes, colours, null, 1, 2, Set.of(), 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("121");
    }

    @Test
    void theDescriptionIsTheNameThenTheSizeAndColour() {
        assertThat(VariantGrid.description("Baby romper", "0-3m", "Blue")).isEqualTo("Baby romper (0-3m, Blue)");
        assertThat(VariantGrid.description("Cable", "2.5mm", null)).isEqualTo("Cable (2.5mm)");
        assertThat(VariantGrid.description("Socket", null, "White")).isEqualTo("Socket (White)");
        assertThat(VariantGrid.token("Sky blue stripes")).isEqualTo("SKYBLUE");
    }
}
