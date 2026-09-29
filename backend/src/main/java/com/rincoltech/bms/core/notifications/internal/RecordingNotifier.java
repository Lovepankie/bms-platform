package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Notifier;
import com.rincoltech.bms.kernel.Masking;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The fake adapter: records every message in memory and logs only the template key and the
 * masked recipient (no token, no link, no full phone number or address). It is the only adapter
 * until a provider is chosen, so staging never blocks on one; tests read {@link #sent()}.
 */
@Component
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
