package org.acme.input;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * The input method players use to generate wind turbine power.
 */
public enum InputMode {
    TAP("tap"),
    SHAKE("shake"),
    BOTH("both");

    private final String value;

    InputMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static InputMode from(String value) {
        return find(value).orElseThrow(() ->
            new IllegalArgumentException("Unknown input mode: " + value + ". Expected tap, shake or both"));
    }

    public static Optional<InputMode> find(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (InputMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value.trim())) {
                return Optional.of(mode);
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
