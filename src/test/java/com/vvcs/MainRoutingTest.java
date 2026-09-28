package com.vvcs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entry point decides whether arguments are a vvcs command or a project file to convert.
 * A command missing from that list is not a cosmetic mistake: it falls through to the XML
 * converter, which then complains that a commit id is an unusable filename.
 */
class MainRoutingTest {

    @ParameterizedTest
    @ValueSource(strings = {"init", "commit", "log", "checkout", "diff", "web", "help"})
    @DisplayName("every vvcs command is recognised as a command")
    void vvcsCommandsAreRecognised(String command) {
        assertTrue(Main.isVvcsCommand(new String[]{command}),
                command + " should be routed to the command handler");
    }

    @Test
    @DisplayName("a file to convert is not mistaken for a command")
    void aProjectFileIsNotACommand() {
        assertFalse(Main.isVvcsCommand(new String[]{"sample-project/sample.kdenlive"}));
        assertFalse(Main.isVvcsCommand(new String[]{"project.kdenlive", "--out", "p.json"}));
    }

    @Test
    @DisplayName("no arguments at all is not a command")
    void noArgumentsIsNotACommand() {
        assertFalse(Main.isVvcsCommand(new String[0]));
    }

    @Test
    @DisplayName("a command is recognised whatever follows it")
    void trailingArgumentsDoNotChangeTheRouting() {
        assertTrue(Main.isVvcsCommand(new String[]{"diff", "abc123", "def456"}));
    }
}
