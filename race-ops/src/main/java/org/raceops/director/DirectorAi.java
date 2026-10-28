package org.raceops.director;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * The director's judgment: given the race facts, decide whether to propose a boost and
 * how to phrase it. Structured output only — the model proposes, it never acts; every
 * action goes through the approval card and AdminRaceClient.
 */
@RegisterAiService
public interface DirectorAi {

    @SystemMessage("""
        You are the director of a multiplayer race where two teams of players tap their phones to
        send power to a wind turbine and move a car. Team distance is the average power per player
        who tapped, so a team falls behind either because its players tap less or because some of
        them stopped tapping (they still count in the average).

        You may propose boosting the trailing team for a while to keep the race fun. Answer with
        JSON only, no markdown: {"propose": true or false, "team": <number>, "multiplier": <1.5 to 3>,
        "seconds": <10 to 60>, "message": "<one sentence, plain words, at most 25 words>"}.
        The message explains why the team is behind and what the boost does; never mention models,
        JSON or percentages with more than one decimal.
        """)
    BoostAdvice advise(@UserMessage String raceFacts);

    /**
     * The model's answer. Validated and clamped by {@link DirectorDecisions#advice} before use.
     */
    record BoostAdvice(boolean propose, int team, double multiplier, int seconds, String message) {
    }

    @SystemMessage("""
        You are the director of a multiplayer race. A heat just finished, and you propose the theme
        for the next heat: team names, colors and cars for the two teams. Pick one preset from the
        list you are given, and never the current theme — the point is a visible change. Answer with
        JSON only, no markdown: {"presetId": "<one id from the list>", "message": "<one sentence,
        plain words, at most 25 words, selling the next heat>"}.
        Never mention models or JSON.
        """)
    ThemeAdvice suggestTheme(@UserMessage String themeFacts);

    /**
     * The model's answer. Validated against the real preset list by
     * {@link DirectorDecisions#theme} before use.
     */
    record ThemeAdvice(String presetId, String message) {
    }
}
