package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Notifier;
import com.rincoltech.bms.kernel.Masking;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The fake adapter: records every message in memory and logs only the template key and the
 * masked recipient (no token, no link, no full phone number or address); tests read {@link #sent()}.
 * The recorded messages carry one-time invitation and reset links, so it exists only in the dev and
 * test profiles (issue #324, ADR-085); every other profile gets {@link LogOnlyNotifier}, which keeps
 * nothing.
 */
@Component
@Profile({"dev", "test"})
public class RecordingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(RecordingNotifier.class);
    private static final int KEEP = 500;

    private final List<Message> sent = new ArrayList<>();

    @Override
    public synchronized void send(Message message) {
        if (sent.size() >= KEEP) {
            sent.removeFirst();
        }
        sent.add(message);
        log.info(
                "notification recorded, not sent: channel={} template={} to={}",
                message.channel(),
                message.templateKey(),
                Masking.lastFour(message.to()));
    }

    /** Messages recorded so far, oldest first. */
    public synchronized List<Message> sent() {
        return List.copyOf(sent);
    }
}
