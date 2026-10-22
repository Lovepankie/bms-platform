package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.loans.internal.Servicing.Item;
import com.rincoltech.bms.lending.loans.internal.Servicing.Replay;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The final review of #270: a reversal's replay settles pass to pass, or the reversal is refused. */
class PenaltyReconcilerTest {

    static final UUID ITEM = UUID.randomUUID();
    static final LocalDate DUE = LocalDate.of(2026, 4, 16);

    static Item item(long penalties) {
        Item i = new Item(ITEM, 1, DUE, 400_000, 120_000, 0, penalties);
        if (penalties > 0) {
            i.chargedOn(DUE.plusDays(1), penalties);
        }
        return i;
    }

    static Replay replayOf(List<Item> planned) {
        return new Replay(Map.of(), planned);
    }

    @Test
    void aReplayThatSettlesIsReturnedOnceItsPenaltiesStopMoving() {
        AtomicInteger plans = new AtomicInteger();
        Replay settled = PenaltyReconciler.settleReplay(
                List.of(item(0)),
                PenaltyReconcilerTest::replayOf,
                replay -> {
                    plans.incrementAndGet();
                    return List.of(item(2_000));
                },
                4);
        assertThat(settled.items().getFirst().penaltiesDue).isEqualTo(2_000);
        assertThat(plans.get()).as("one pass to move, one to see it settled").isEqualTo(2);
    }

    @Test
    void aReplayThatNeverSettlesIsRefusedWithNothingChanged() {
        AtomicInteger pass = new AtomicInteger();
        List<Item> start = List.of(item(0));
        Map<UUID, List<Object>> before = PenaltyReconciler.penaltiesOf(start);
        assertThatThrownBy(() -> PenaltyReconciler.settleReplay(
                        start,
                        PenaltyReconcilerTest::replayOf,
                        // Each pass plans more than the one before: it never settles within the four passes.
                        replay -> List.of(item(1_000L * pass.incrementAndGet())),
                        4))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("reversal_not_settled"));
        assertThat(pass.get()).isEqualTo(4);
        assertThat(PenaltyReconciler.penaltiesOf(start))
                .as("the start is untouched")
                .isEqualTo(before);
    }
}
