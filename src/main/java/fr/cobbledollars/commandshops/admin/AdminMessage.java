package fr.cobbledollars.commandshops.admin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.minecraft.network.chat.Component;

/** A message resolved in the recipient's language, with an explicit English fallback. */
public record AdminMessage(String key, String fallback, List<String> arguments) {
    public static final String PREFIX = "cobbledollarscommandshops.admin.message.";
    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_TEXT_LENGTH = 2048;
    public static final int MAX_ARGUMENTS = 8;

    public AdminMessage {
        Objects.requireNonNull(key);
        Objects.requireNonNull(fallback);
        Objects.requireNonNull(arguments);
        if (key.length() > MAX_KEY_LENGTH || (!key.isEmpty() && !key.startsWith(PREFIX))
                || arguments.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("Invalid administrative message.");
        }
        fallback = limit(fallback);
        arguments = arguments.stream().map(Objects::requireNonNull).map(AdminMessage::limit).toList();
    }

    public static AdminMessage translated(String id, String englishPattern, Object... arguments) {
        return new AdminMessage(PREFIX + id, englishPattern,
                Arrays.stream(arguments).map(String::valueOf).toList());
    }

    public static AdminMessage literal(String englishText) {
        return new AdminMessage("", englishText, List.of());
    }

    public Component component() {
        return key.isEmpty() ? Component.literal(fallback)
                : Component.translatableWithFallback(key, fallback, arguments.toArray());
    }

    public String english() {
        return key.isEmpty() ? fallback : String.format(Locale.ROOT, fallback, arguments.toArray());
    }

    private static String limit(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) return text;
        int end = MAX_TEXT_LENGTH;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }
}
