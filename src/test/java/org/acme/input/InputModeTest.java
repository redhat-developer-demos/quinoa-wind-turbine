package org.acme.input;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputModeTest {

    @Test
    void parseValidModes() {
        assertEquals(InputMode.TAP, InputMode.from("tap"));
        assertEquals(InputMode.SHAKE, InputMode.from("shake"));
        assertEquals(InputMode.BOTH, InputMode.from("both"));
        assertEquals(InputMode.TAP, InputMode.from("TAP"));
        assertEquals(InputMode.SHAKE, InputMode.from(" Shake "));
    }

    @Test
    void findValidModes() {
        assertEquals(Optional.of(InputMode.TAP), InputMode.find("tap"));
        assertEquals(Optional.of(InputMode.SHAKE), InputMode.find("shake"));
        assertEquals(Optional.of(InputMode.BOTH), InputMode.find("both"));
        assertTrue(InputMode.isValid("tap"));
        assertTrue(InputMode.isValid("shake"));
        assertTrue(InputMode.isValid("both"));
    }

    @Test
    void rejectInvalidModes() {
        assertThrows(IllegalArgumentException.class, () -> InputMode.from("invalid"));
        assertThrows(IllegalArgumentException.class, () -> InputMode.from(null));
        assertEquals(Optional.empty(), InputMode.find("invalid"));
        assertEquals(Optional.empty(), InputMode.find(null));
        assertEquals(Optional.empty(), InputMode.find(""));
        assertFalse(InputMode.isValid("invalid"));
        assertFalse(InputMode.isValid(null));
    }

    @Test
    void valueAndToString() {
        assertEquals("tap", InputMode.TAP.value());
        assertEquals("shake", InputMode.SHAKE.value());
        assertEquals("both", InputMode.BOTH.value());
        assertEquals("tap", InputMode.TAP.toString());
    }
}
