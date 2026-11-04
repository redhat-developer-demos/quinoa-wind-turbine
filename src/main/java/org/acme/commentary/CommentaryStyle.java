package org.acme.commentary;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * How the AI commentator sounds. The style is a prompt fragment rather than a separate model call:
 * the same race facts go in, only the guidance changes, so switching styles between heats always
 * produces a recognisably different line without touching the model configuration.
 */
public enum CommentaryStyle {
    BROADCAST("broadcast", """
        Sound like a calm television race commentator. Be clear and factual, and pick one vivid
        detail from the facts instead of listing everything."""),
    HYPE("hype", """
        Sound like a stadium announcer on the final lap. High energy, short exclamations, present
        tense. Never use the words 'unbelievable' or 'goosebumps'."""),
    ANALYST("analyst", """
        Sound like a technical race analyst. Explain what the numbers mean: the power gap, the
        distance left and what the trailing team has to change. Stay calm and precise."""),
    DRY("dry", """
        Sound deadpan and understated. One wry observation about the race, no excitement, no
        exclamation marks.""");

    private final String value;
    private final String guidance;

    CommentaryStyle(String value, String guidance) {
        this.value = value;
        this.guidance = guidance;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /**
     * The prompt fragment handed to the model for this style.
     */
    public String guidance() {
        return guidance.trim().replaceAll("\\s+", " ");
    }

    @JsonCreator
    public static CommentaryStyle from(String value) {
        return find(value).orElseThrow(() ->
            new IllegalArgumentException("Unknown commentary style: " + value
                + ". Expected broadcast, hype, analyst or dry"));
    }

    public static Optional<CommentaryStyle> find(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (CommentaryStyle style : values()) {
            if (style.value.equalsIgnoreCase(value.trim())) {
                return Optional.of(style);
            }
        }
        return Optional.empty();
    }

    public static boolean isValid(String value) {
        return find(value).isPresent();
    }

    @Override
    public String toString() {
        return value;
    }
}
