package org.acme.commentary;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Commentary(String id, String raceId, String trigger, long snapshotAt, long createdAt,
                         String text, String source) {
}
