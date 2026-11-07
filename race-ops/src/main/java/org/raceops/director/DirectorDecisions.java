package org.raceops.director;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.raceops.analytics.EngagementReport;
import org.raceops.mcp.AdminRaceClient;
import org.raceops.model.RaceSnapshot;

import java.util.List;

/**
 * The deterministic parts of the director's decisions: the facts the model (or the
 * fallback) reasons over, the parsing of what the mesh returns, and the guardrails applied
 * to whatever comes back. The model never gets the last word — the trailing team is forced,
 * the multiplier and duration clamped, the theme pinned to a preset the game really offers
 * (or an AI brief) — so a bad answer cannot boost the winning team or invent a theme.
 */
public final class DirectorDecisions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The phase line used between heats; mid-race phases come from {@link #racePhase}. */
    public static final String BETWEEN_HEATS = "between heats";

    /**
     * What a supervised run supports. Read from the specialists that ran, never from the
     * model's wording: the scope keys are written by the framework when an agent answers.
     */
    public enum Route {NONE, BOOST, THEME}

    private DirectorDecisions() {
    }

    /**
     * @param diagnosed the analyst ran, so its diagnosis can be the boost card's reason
     * @param themed    the creative director ran, so the model chose a next-heat look
     */
    public static Route route(boolean diagnosed, boolean themed) {
        if (themed) {
            return Route.THEME;
        }
        return diagnosed ? Route.BOOST : Route.NONE;
    }

    /**
     * Clamps the model's route to the situation. A next-heat look below the quitter share
     * would replace the boost the race still needs, so it becomes the boost route and the
     * analyst's diagnosis becomes the card's reason. At or above the share, the collapse is
     * real and a fresh look is the right card.
     */
    public static Route guardRoute(Route route, BoostPolicy.Situation situation) {
        if (route == Route.THEME && situation.stalledShare() < BoostPolicy.QUITTER_SHARE) {
            return Route.BOOST;
        }
        return route;
    }

    /**
     * The request text the supervisor's planner reasons over: the phase, then the facts. The
     * payloads travel separately as typed arguments.
     */
    public static String interventionRequest(String phase, String facts) {
        return "phase: " + phase + "\n" + facts;
    }

    /**
     * The phase's standing order. This is the only place the director can steer the
     * supervisor's planner: the framework builds that planner's system message itself and
     * appends this text to it, so a {@code @SystemMessage} on the supervisor interface is
     * silently ignored (confirmed in the 1.22.0 source and in a live run on 2026-11-06).
     * The model still chooses; the standing order only tells it what the moment calls for.
     */
    public static String supervisorContext(String phase) {
        if (BETWEEN_HEATS.equals(phase)) {
            return "Between heats. The standing order is a fresh look for the next heat: call "
                + "the Race Creative Director once, then return done.";
        }
        return """
            Mid-race. Call the Race Analyst first, with the snapshots it is given, then decide \
            from its diagnosis:
            - its players still race but generate less power, or the diagnosis names no problem: \
            return done, agentName "done", and do not call the other specialist. The director \
            boosts the trailing team and the analyst's sentence becomes the card's reason.
            - a large share of the trailing team's players stopped tapping and engagement \
            collapsed: call the Race Creative Director once, for a next-heat look.
            Done is the default once the analyst has answered. The Race Creative Director \
            cannot boost and changes nothing in the race in progress, so calling it while \
            players still tap loses the moment the director needed.""";
    }

    /**
     * The facts for a between-heats proposal, where there is no trailing team to diagnose.
     */
    public static String themeFacts(RaceSnapshot snapshot, String currentTheme) {
        return """
            {"raceId": "%s", "status": "%s", "elapsedMs": %d, "winner": %s, "currentTheme": "%s"}"""
            .formatted(snapshot.raceId(), snapshot.status(), snapshot.elapsedMs(),
                snapshot.winner() == null ? "null" : snapshot.winner(), currentTheme);
    }

    /**
     * What the supervisor's model answers for a boost: the card fields, before clamping.
     */
    public record BoostAdvice(boolean propose, int team, double multiplier, int seconds, String message) {
    }

    /**
     * What the creative agent answers for the next heat: a preset id, or an AI brief. The
     * kind is one of {@code preset}, {@code ai-theme}, {@code ai-names}.
     */
    public record ThemeAdvice(String kind, String presetId, String brief, String message) {

        static final String PRESET = "preset";
        static final String AI_THEME = "ai-theme";
        static final String AI_NAMES = "ai-names";
    }

    /**
     * The analyst's contract in one object: the two snapshots, the older one or null.
     */
    public static String analysisRequest(RaceSnapshot base, RaceSnapshot current) {
        try {
            return MAPPER.writeValueAsString(new AnalysisRequest(base, current));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the analysis request: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * The facts handed to the supervisor or printed into the fallback message. Plain JSON
     * with rounded numbers, because the model echoes the precision it receives.
     */
    public static String raceFacts(RaceSnapshot snapshot, EngagementReport engagement, BoostPolicy.Situation situation) {
        String teams = engagement.teams() == null ? ""
            : engagement.teams().stream()
                .map(t -> """
                    {"team": %d, "position": %d, "tappers": %d, "active": %d, "stalled": %d, \
                    "engagementRate": %.2f, "power": %d, "distance": %.1f}""".formatted(
                    t.team(), t.position(), t.tappers(), t.active(), t.stalled(),
                    t.engagementRate(), t.power(), t.distance()))
                .reduce((a, b) -> a + ", " + b).orElse("");
        return """
            {"raceId": "%s", "status": "%s", "elapsedMs": %d, "teams": [%s], \
            "trailingTeam": %d, "deficitPercent": %.1f, "trailingStalledShare": %.2f}""".formatted(
            snapshot.raceId(), snapshot.status(), snapshot.elapsedMs(), teams,
            situation.team(), situation.deficit(), situation.stalledShare());
    }

    /**
     * Which part of the race the facts describe, used to steer the boost message. It is derived
     * from the leading distance so the model does not have to infer it, and the three labels stay
     * stable so the prompt has a fixed set of cases.
     */
    public static String racePhase(RaceSnapshot snapshot) {
        RaceSnapshot.Team leader = snapshot.leader();
        double distance = leader == null ? 0 : leader.distance();
        if (distance < 40) {
            return "opening laps";
        }
        if (distance < 80) {
            return "middle of the race";
        }
        return "final laps";
    }

    /**
     * The fallback when the model is off or fails: boost the trailing team with the
     * default parameters and a plain diagnosis.
     */
    public static Proposal fallbackProposal(BoostPolicy.Situation situation, RaceSnapshot snapshot) {
        String why = situation.stalledShare() >= BoostPolicy.QUITTER_SHARE
            ? "%.0f%% of its players stopped tapping".formatted(situation.stalledShare() * 100)
            : "its players are generating less power";
        return Proposal.boost(snapshot.raceId(), situation.team(), 2.5, 30,
            "Team %d is %.1f%% behind because %s. Boost them for 30 s to keep the race close?"
                .formatted(situation.team(), situation.deficit(), why));
    }

    /**
     * Reads the supervisor's answer. With {@code LAST}, that is the analyst's answer verbatim:
     * the engagement report plus one diagnosis sentence. A model-style answer with its own
     * fields is still accepted, so the two shapes can be swapped without touching the director.
     */
    public static BoostAdvice parseBoostAdvice(String raw) {
        JsonNode answer = jsonObject(raw, "The director's mesh returned no proposal");
        if (answer.hasNonNull("diagnosis")) {
            // The card's reason is the analyst's sentence; the deterministic policy still owns
            // the numbers. Summarizing the answer with a second model call cost 5 s of a 20 s
            // race and bought nothing the analyst had not already said.
            String diagnosis = answer.get("diagnosis").asText("").trim();
            return new BoostAdvice(true, 0, 2.5, 30,
                diagnosis.isBlank() ? "" : diagnosis + " Boost them for 30 s to keep the race close?");
        }
        return new BoostAdvice(
            answer.path("propose").asBoolean(false),
            answer.path("team").asInt(0),
            answer.path("multiplier").asDouble(2.5),
            answer.path("seconds").asInt(30),
            answer.path("message").asText(""));
    }

    /**
     * Clamps the model's advice to the situation: only the trailing team, a sane
     * multiplier, a sane duration, a non-empty message.
     */
    public static Proposal advice(BoostAdvice advice, BoostPolicy.Situation situation,
                                    RaceSnapshot snapshot) {
        int team = situation.team(); // the model does not get to pick the winner
        double multiplier = Math.clamp(advice.multiplier(), 1.5, 3.0);
        int seconds = (int) Math.clamp(advice.seconds(), 10, 60);
        String message = advice.message() == null || advice.message().isBlank()
            ? "Team " + team + " is behind. Boost them to keep the race close?"
            : advice.message().trim();
        return Proposal.boost(snapshot.raceId(), team, multiplier, seconds, message);
    }

    /**
     * What race-creative receives when it proposes the next heat's look.
     */
    public static String themeRequest(String raceId, String currentTheme, String event, String audience) {
        try {
            return MAPPER.writeValueAsString(new ThemeRequest(raceId, currentTheme, event, audience));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the theme request: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads the creative agent's answer. The same lenient framing as the boost answer, plus
     * the kind check the creative app also applies on its side.
     */
    public static ThemeAdvice parseThemeAdvice(String raw) {
        JsonNode answer = jsonObject(raw, "The creative agent returned no proposal");
        String kind = answer.path("kind").asText("");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("The creative agent returned no proposal kind");
        }
        return new ThemeAdvice(kind,
            answer.path("presetId").asText(""),
            answer.path("brief").asText(""),
            answer.path("message").asText(""));
    }

    /**
     * Turns the creative answer into a card. A preset must be one the game offers and must
     * differ from the theme on; anything else falls back to the deterministic pick. An AI
     * proposal needs a brief, because the generator gets that brief verbatim.
     */
    public static Proposal theme(ThemeAdvice advice, List<AdminRaceClient.ThemeOption> options,
                                 String raceId, String currentTheme, Proposal fallback) {
        if (advice == null) {
            return fallback;
        }
        if (ThemeAdvice.PRESET.equals(advice.kind())) {
            boolean known = advice.presetId() != null && options.stream()
                .anyMatch(o -> o.id().equals(advice.presetId()));
            if (!known || advice.presetId().equals(currentTheme)) {
                return fallback;
            }
            String message = advice.message() == null || advice.message().isBlank()
                ? fallback.message() : advice.message().trim();
            return Proposal.theme(raceId, advice.presetId(), message);
        }
        if (ThemeAdvice.AI_THEME.equals(advice.kind()) || ThemeAdvice.AI_NAMES.equals(advice.kind())) {
            if (advice.brief() == null || advice.brief().isBlank()) {
                return fallback;
            }
            String message = advice.message() == null || advice.message().isBlank()
                ? "A generated look for the next heat?" : advice.message().trim();
            return Proposal.aiTheme(raceId, advice.kind(), advice.brief().trim(), message);
        }
        return fallback;
    }

    /**
     * The deterministic between-heats pick: rotate through the presets so consecutive
     * heats look different, skipping the default and the theme already on.
     */
    public static Proposal themeFallback(List<AdminRaceClient.ThemeOption> options, String raceId, int heat,
                                         String currentTheme) {
        List<AdminRaceClient.ThemeOption> usable = options.stream()
            .filter(o -> !"classic".equals(o.id()) && !o.id().equals(currentTheme))
            .toList();
        if (usable.isEmpty()) {
            usable = options;
        }
        AdminRaceClient.ThemeOption pick = usable.get(Math.floorMod(heat, usable.size()));
        return Proposal.theme(raceId, pick.id(),
            "Next heat: %s? Fresh colors and cars for the teams.".formatted(pick.label()));
    }

    private static JsonNode jsonObject(String raw, String emptyMessage) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(emptyMessage);
        }
        try {
            return MAPPER.readTree(raw.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot parse the agent's answer: " + e.getOriginalMessage(), e);
        }
    }

    private record AnalysisRequest(RaceSnapshot base, RaceSnapshot current) {
    }

    /**
     * The creative app serves two modes on one operation, so the request must name the one it
     * wants. Without {@code mode} every proposal came back as "The creative request needs a
     * mode" and the director fell back to a preset (seen live, 2026-11-06).
     */
    private record ThemeRequest(String mode, String raceId, String currentTheme, String event,
                                String audience) {

        ThemeRequest(String raceId, String currentTheme, String event, String audience) {
            this("propose", raceId, currentTheme, event, audience);
        }
    }
}
