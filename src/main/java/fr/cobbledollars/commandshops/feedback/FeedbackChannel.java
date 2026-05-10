package fr.cobbledollars.commandshops.feedback;

import java.io.IOException;
import java.util.Locale;

public enum FeedbackChannel {
    ACTION_BAR,
    CHAT,
    SOUND;

    public static FeedbackChannel parse(String value, String context) throws IOException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "action_bar" -> ACTION_BAR;
            case "chat" -> CHAT;
            case "sound" -> SOUND;
            default -> throw new IOException("Unknown feedback channel '" + value + "' in " + context + ".");
        };
    }
}
