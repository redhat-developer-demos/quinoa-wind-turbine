package org.raceops.architecture;

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
 * race-ops must know the game only through its MCP server. Every form a game reference can
 * take in a class file (type names, field and method descriptors, string literals) shows up
 * in the constant pool as UTF-8 containing "org/acme", so scanning the pool catches them all.
 */
class NoGameImportsTest {

    @Test
    void raceOpsMustNotReferenceGameClasses() throws IOException {
        Path classes = Path.of("target", "classes");
        assertTrue(Files.isDirectory(classes), "compiled classes not found at " + classes.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            files.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                try {
                    ClassModel model = ClassFile.of().parse(Files.readAllBytes(path));
                    for (PoolEntry entry : model.constantPool()) {
                        if (entry instanceof Utf8Entry utf8 && utf8.stringValue().contains("org/acme")) {
                            violations.add(path.getFileName() + " references " + utf8.stringValue());
                        }
                    }
                } catch (IOException e) {
                    violations.add(path.getFileName() + " is unreadable: " + e.getMessage());
                }
            });
        }
        assertTrue(violations.isEmpty(),
            "race-ops must talk to the game over MCP only, never import it: " + violations);
    }
}
