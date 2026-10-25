package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Notifier;
import com.rincoltech.bms.kernel.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The port's adapter outside the dev and test profiles (staging and production, issue #324, ADR-085):
 * no provider is behind {@link Notifier} yet, so it sends nothing and keeps nothing. It logs only the
 * template key and the masked recipient; the parameters, which carry one-time invitation and reset
 * links, are never read. The admin who invited someone has the link on screen (FR-IAM-01).
 */
@Component
@Profile("!dev & !test")
public class LogOnlyNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LogOnlyNotifier.class);

    @Override
    public void send(Message message) {
        log.info(
                "notification not sent, no provider: channel={} template={} to={}",
                message.channel(),
                message.templateKey(),
                Masking.lastFour(message.to()));
    }
}
