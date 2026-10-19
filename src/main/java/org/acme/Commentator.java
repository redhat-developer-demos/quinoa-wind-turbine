package org.acme;

public interface Commentator {
    String comment(CommentaryTrigger trigger, RaceState race);
}
