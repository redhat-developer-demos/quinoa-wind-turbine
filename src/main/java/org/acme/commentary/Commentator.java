package org.acme.commentary;

import org.acme.race.RaceState;

public interface Commentator {
    String comment(CommentaryTrigger trigger, RaceState race);
}
