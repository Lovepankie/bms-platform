package com.rincoltech.bms.core.notifications.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Operator alerts through the Telegram Bot API {@code sendMessage} over HTTPS, with the JDK HTTP
 * client (spec section 11). The only recipient is the operator chat of
 * {@code BMS_TELEGRAM_OPERATOR_CHAT_ID}. The bot token is part of the URL, so it is checked
 * against the bot token shape once at startup (a token with a stray character, for example from a
 * CRLF {@code .env}, switches the sender off instead of reaching an error message), the URI is
 * built once, and no JDK exception text, which can quote the URL, is ever kept (review N2).
 */
@Component
class TelegramChannel implements OutboxChannel {

    private static final String API = "https://api.telegram.org/bot";
    static final Pattern TOKEN = Pattern.compile("^\\d{1,20}:[A-Za-z0-9_-]{20,100}$");
    static final Pattern CHAT = Pattern.compile("^(-?\\d{1,20}|@[A-Za-z0-9_]{5,32})$");
    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

    private final NotificationProperties.Telegram telegram;
    private final ObjectMapper mapper;
    private final URI sendMessage;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    TelegramChannel(NotificationProperties properties, ObjectMapper mapper) {
        this.telegram = properties.telegram();
        this.mapper = mapper;
        this.sendMessage = wellFormed(telegram) ? URI.create(API + telegram.botToken() + "/sendMessage") : null;
        if (telegram.configured() && sendMessage == null) {
            log.warn("Telegram sender off: the bot token or chat id is not in the expected shape (value not logged)");
        }
    }

    static boolean wellFormed(NotificationProperties.Telegram telegram) {
        return telegram.configured()
                && TOKEN.matcher(telegram.botToken()).matches()
                && CHAT.matcher(telegram.operatorChatId()).matches();
    }

    @Override
    public String channel() {
        return "telegram";
    }

    @Override
    public boolean enabled() {
        return sendMessage != null;
    }

    @Override
    public void send(Delivery delivery) throws Exception {
        if (!"operator".equals(delivery.recipient())) {
            throw new SendFailure("Telegram sends to the operator chat only");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", telegram.operatorChatId());
        body.put("text", delivery.text());
        body.put("disable_web_page_preview", true);
        HttpRequest request = HttpRequest.newBuilder(sendMessage)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            // The exception text can name the URL, which carries the token.
            throw new SendFailure("Telegram unreachable: " + e.getClass().getSimpleName());
        }
        if (response.statusCode() / 100 != 2) {
            throw new SendFailure("Telegram answered HTTP " + response.statusCode());
        }
    }
}
