package org.acme.commentary;

public record CommentaryTrigger(String id, String raceId, String type, long snapshotAt,
                                int team, Integer milestone, String player) {

    public int priority() {
        return switch (type) {
            case "finish" -> 4;
            case "lead" -> 3;
            case "milestone" -> 2;
            case "surge" -> 1;
            default -> 0;
        };
    }
}
