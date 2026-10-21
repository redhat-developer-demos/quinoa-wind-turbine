package org.acme;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.IntFunction;

@ApplicationScoped
public class TemplateCommentator implements Commentator {
    private final IntFunction<String> teamName;

    @Inject
    public TemplateCommentator(ThemeService themes) {
        this(themes::teamName);
    }

    TemplateCommentator(IntFunction<String> teamName) {
        this.teamName = teamName;
    }

    @Override
    public String comment(CommentaryTrigger trigger, RaceState race) {
        return switch (trigger.type()) {
            case "lead" -> teamName.apply(trigger.team()) + " takes the lead!";
            case "milestone" -> teamName.apply(trigger.team()) + " passes " + trigger.milestone() + "%.";
            case "surge" -> trigger.player() + " surges for " + teamName.apply(trigger.team()) + "!";
            case "finish" -> teamName.apply(trigger.team()) + " wins the race!";
            default -> throw new IllegalArgumentException("Unknown commentary trigger: " + trigger.type());
        };
    }
}
