package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The window the group reads back (review finding 3 of #274): whatever is stored, the window the channel uses is inside
 * the hard limits (06:00 to 21:00, at least 60 minutes), so an older value written past the screen's validation (a legacy
 * flat key, a row edited by hand) cannot send in the small hours or at night. Pure: no database, no clock.
 */
class SmsSettingsGroupTest {

    private final SmsSettingsGroup group = new SmsSettingsGroup(null, null);

    static String pair(LocalTime[] window) {
        return window[0] + "-" + window[1];
    }

    @ParameterizedTest
    @CsvSource({
        "08:00, 20:00, 08:00-20:00",
        "06:00, 21:00, 06:00-21:00",
        "05:00, 23:00, 06:00-21:00",
        "00:00, 23:59, 06:00-21:00",
        "05:30, 12:00, 06:00-12:00",
        "12:00, 22:00, 12:00-21:00",
        "20:00, 20:30, 08:00-20:00",
        "21:30, 22:00, 08:00-20:00",
        "20:00, 08:00, 08:00-20:00",
        "10:00, 10:00, 08:00-20:00",
    })
    void aStoredWindowIsPulledInsideTheLimitsOrReplacedByTheDefault(String start, String end, String expected) {
        assertThat(pair(SmsSettingsGroup.safeWindow(start, end))).isEqualTo(expected);
    }

    @Test
    void anythingThatIsNotATimeIsTheDefaultForItsSide() {
        assertThat(pair(SmsSettingsGroup.safeWindow("8am", "18:00"))).isEqualTo("08:00-18:00");
        assertThat(pair(SmsSettingsGroup.safeWindow(null, null))).isEqualTo("08:00-20:00");
        assertThat(pair(SmsSettingsGroup.safeWindow(25, "7"))).isEqualTo("08:00-20:00");
    }

    @Test
    void readingAGroupWithAnOutOfLimitsWindowGivesTheClampedWindowToTheChannelAndTheScreen() {
        Map<String, Object> read = group.normalise(Map.of("window_start", "05:00", "window_end", "23:00"), Map.of());
        assertThat(read.get("window_start")).isEqualTo("06:00");
        assertThat(read.get("window_end")).isEqualTo("21:00");
        // And a change of something else carries the clamped window forward, not the stored one.
        Map<String, Object> changed = group.normalise(
                Map.of("window_start", "05:00", "window_end", "23:00"), Map.of("include_balance", true));
        assertThat(changed.get("window_start")).isEqualTo("06:00");
        assertThat(changed.get("window_end")).isEqualTo("21:00");
        assertThat(changed.get("include_balance")).isEqualTo(true);
    }
}
