package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/**
 * The interleavings behind issue #487, held still: another administrator's switch-on commits while a second switch-on is
 * between its reads of the settings group and of the version. The second request lost the race and must be told so
 * (409), not have its acknowledgement refused (422) against settings it never saw. SmsSettingsIT races real requests;
 * this case is the one that race reached only now and then.
 */
class SmsSettingsServiceRaceTest {

    /**
     * Which read of the losing request the winner's switch-on commits just before. {@code BEFORE_THE_GROUP_READ} is the
     * regression check for #487 (422 before the fix). {@code BEFORE_THE_VERSION_READ} passed before the fix too; it
     * pins the other side of the new order, so a change that reads the version first again fails one of the two.
     */
    enum CommitLands {
        BEFORE_THE_GROUP_READ,
        BEFORE_THE_VERSION_READ
    }

    /** Settings at version 1 with SMS off, until the winner's switch-on commits at the read {@code lands} names. */
    static final class RacingSettings implements ModuleSettings {
        final CommitLands lands;
        int version = 1;
        boolean enabled;

        RacingSettings(CommitLands lands) {
            this.lands = lands;
        }

        void commitTheWinner() {
            enabled = true;
            version = 2;
        }

        @Override
        public Map<String, Object> group(String moduleKey) {
            if (lands == CommitLands.BEFORE_THE_GROUP_READ) {
                commitTheWinner();
            }
            return Map.of(SmsSettingsGroup.ENABLED, enabled);
        }

        @Override
        public int version() {
            if (lands == CommitLands.BEFORE_THE_VERSION_READ) {
                commitTheWinner();
            }
            return version;
        }

        @Override
        public Map<String, Object> change(String moduleKey, Map<String, Object> proposed, int expectedVersion) {
            throw new AssertionError("the loser of the race never reaches the change");
        }

        @Override
        public Map<String, List<String>> enabledCapabilities() {
            return Map.of();
        }
    }

    /**
     * Both places the winner's commit can land between the loser's two reads. The other collaborators are null on
     * purpose: the conflict is decided before any of them is touched, so reaching one fails here as "expected
     * ApiException but was NullPointerException" at the line that reached it.
     */
    @ParameterizedTest
    @EnumSource(CommitLands.class)
    void aSwitchOnThatLostTheRaceIsAConflictNotARefusedAcknowledgement(CommitLands lands) {
        SmsSettingsService service = new SmsSettingsService(new RacingSettings(lands), null, null, null, null);
        SmsSettingsService.Change switchOn = new SmsSettingsService.Change(
                true, SmsSettingsService.ACK_TEXT_VERSION, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update("\"1\"", switchOn)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("version_conflict");
        });
    }
}
