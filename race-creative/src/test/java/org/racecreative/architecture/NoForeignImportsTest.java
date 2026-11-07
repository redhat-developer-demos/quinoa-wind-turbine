package org.racecreative.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creative director is a service, not a library of the game or of the director: it knows
 * the game only through its MCP server. Every form a reference can take shows up as UTF-8 in
 * the constant pool, so scanning the pool catches them all.
 */
class NoForeignImportsTest {

    private static final List<String> FORBIDDEN = List.of("org/acme", "org/raceops");

    @Test
    void creativeMustNotReferenceGameOrDirectorClasses() throws IOException {
        Path classes = Path.of("target", "classes");
        assertTrue(Files.isDirectory(classes), "compiled classes not found at " + classes.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            files.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                try {
                    ClassModel model = ClassFile.of().parse(Files.readAllBytes(path));
                    for (PoolEntry entry : model.constantPool()) {
                        if (entry instanceof Utf8Entry utf8) {
                            for (String forbidden : FORBIDDEN) {
                                if (utf8.stringValue().contains(forbidden)) {
                                    violations.add(path.getFileName() + " references " + utf8.stringValue());
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    violations.add(path.getFileName() + " is unreadable: " + e.getMessage());
                }
            });
        }
        assertTrue(violations.isEmpty(),
            "race-creative must not import the game or race-ops: " + violations);
    }
}
