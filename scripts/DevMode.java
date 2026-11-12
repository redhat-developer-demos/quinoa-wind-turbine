///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.6.3

// Starts the local dev modes of the race game. Each service is a `quarkus:dev` process in the
// background with its output in target/dev-mode/<name>.log, and every start waits for the endpoint
// that only answers when that service is really ready, so the next one can rely on it.
//
//   jbang scripts/DevMode.java start              the whole mesh (default): game, director, both specialists
//   jbang scripts/DevMode.java start bare         the game only, no Keycloak
//   jbang scripts/DevMode.java start oidc         the game with Keycloak, admin MCP available
//   jbang scripts/DevMode.java start ops          the game and the director, no specialists
//   jbang scripts/DevMode.java start mesh --players 8
//   jbang scripts/DevMode.java status
//   jbang scripts/DevMode.java logs ops
//   jbang scripts/DevMode.java stop
//
// Run it from the repository root: the Maven wrapper and the poms are resolved from the working
// directory, and a wrong directory fails before anything starts.

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "DevMode", mixinStandardHelpOptions = true, version = "DevMode 1.0",
        description = "Starts and stops the local dev modes of the race game.",
        subcommands = { DevMode.Start.class, DevMode.Stop.class, DevMode.Status.class, DevMode.Logs.class })
public class DevMode {

    /** The four processes of the mesh, in the order they must start. */
    enum Service {
        GAME("game", null, 8080, 300, "/q/health/ready"),
        ANALYST("analyst", "race-analyst", 8082, 180, "/.well-known/agent-card.json"),
        CREATIVE("creative", "race-creative", 8083, 180, "/.well-known/agent-card.json"),
        OPS("ops", "race-ops", 8081, 180, "/api/ops/state");

        final String id;
        final String module;
        final int port;
        final int timeoutSeconds;
        final String readyPath;

        Service(String id, String module, int port, int timeoutSeconds, String readyPath) {
            this.id = id;
            this.module = module;
            this.port = port;
            this.timeoutSeconds = timeoutSeconds;
            this.readyPath = readyPath;
        }

        List<String> command() {
            return module == null
                    ? List.of("./mvnw", "quarkus:dev")
                    : List.of("./mvnw", "-f", module + "/pom.xml", "quarkus:dev");
        }
    }

    /** What a mode starts. The mode decides whether the game comes up with Keycloak. */
    enum Mode {
        BARE("the game alone, no Keycloak", false, List.of(Service.GAME)),
        OIDC("the game with Keycloak, admin MCP available", true, List.of(Service.GAME)),
        OPS("the game and the director, no specialists", true, List.of(Service.GAME, Service.OPS)),
        MESH("the whole mesh: game, director and both specialists", true,
                List.of(Service.GAME, Service.ANALYST, Service.CREATIVE, Service.OPS));

        final String description;
        final boolean oidc;
        final List<Service> services;

        Mode(String description, boolean oidc, List<Service> services) {
            this.description = description;
            this.oidc = oidc;
            this.services = services;
        }
    }

    /** picocli matches an enum by its constant name, and the modes are typed in lower case. */
    static class ModeConverter implements CommandLine.ITypeConverter<Mode> {

        @Override
        public Mode convert(String value) {
            for (Mode mode : Mode.values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            throw new CommandLine.TypeConversionException(
                    "unknown mode '" + value + "'; use bare, oidc, ops or mesh");
        }
    }

    /** The load generator, not a service: it has no port and no readiness endpoint. */
    static final String PLAYERS = "players";

    public static void main(String[] args) {
        System.exit(new CommandLine(new DevMode()).execute(args));
    }

    @Command(name = "start", description = "Start a mode (the whole mesh unless one is named).")
    static class Start implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", defaultValue = "mesh", paramLabel = "MODE",
                converter = ModeConverter.class,
                description = "bare, oidc, ops or mesh (default: mesh)")
        Mode mode;

        @Option(names = "--players", defaultValue = "0",
                description = "Also start GameLoader with this many simulated players")
        int players;

        @Override
        public Integer call() throws Exception {
            Path root = repoRoot();
            Path state = stateDir(root);
            log("starting " + mode.name().toLowerCase() + ": " + mode.description);

            for (Service service : mode.services) {
                if (answering(service)) {
                    log(service.id + " already answers on :" + service.port + ", leaving it alone");
                    continue;
                }
                await(service, spawn(root, state, service, mode), state);
            }
            if (mode.oidc) {
                requireKeycloak();
            }
            if (players > 0) {
                startPlayers(root, state, players);
            }
            summary(mode);
            return 0;
        }
    }

    @Command(name = "stop", description = "Stop everything this script started.")
    static class Stop implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            Path state = stateDir(repoRoot());
            for (Service service : List.of(Service.values()).reversed()) {
                stop(state, service.id);
            }
            stop(state, PLAYERS);
            return 0;
        }
    }

    @Command(name = "status", description = "Show what is running and where its log is.")
    static class Status implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            Path state = stateDir(repoRoot());
            for (Service service : Service.values()) {
                boolean running = running(state, service.id);
                String health = running ? (answering(service) ? "ready" : "starting") : "-";
                System.out.printf("%-9s :%-5d %-8s %-8s %s%n",
                        service.id, service.port, running ? "running" : "stopped", health,
                        logPath(state, service.id));
            }
            System.out.printf("%-9s %-6s %-8s %-8s %s%n", PLAYERS, "-",
                    running(state, PLAYERS) ? "running" : "stopped", "-", logPath(state, PLAYERS));
            return 0;
        }
    }

    @Command(name = "logs", description = "Print the tail of one service's log.")
    static class Logs implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "SERVICE", description = "game, analyst, creative, ops or players")
        String service;

        @Option(names = { "-n", "--lines" }, defaultValue = "40", description = "How many lines to print")
        int lines;

        @Override
        public Integer call() throws Exception {
            Path log = logPath(stateDir(repoRoot()), service.toLowerCase());
            if (!Files.exists(log)) {
                System.err.println("no log for " + service + " at " + log);
                return 1;
            }
            List<String> all = Files.readAllLines(log);
            all.subList(Math.max(0, all.size() - lines), all.size()).forEach(System.out::println);
            return 0;
        }
    }

    // --- processes -------------------------------------------------------------------------

    private static Process spawn(Path root, Path state, Service service, Mode mode) throws IOException {
        log("starting " + service.id + " (" + String.join(" ", service.command()) + ")");
        ProcessBuilder builder = new ProcessBuilder(service.command())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logPath(state, service.id).toFile()))
                // The dev processes must not read the terminal, or their prompts collide with this shell.
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        if (mode.oidc && service == Service.GAME) {
            builder.environment().put("GAME_OIDC_ENABLED", "true");
        }
        Process process = builder.start();
        savePid(state, service.id, process.pid());
        return process;
    }

    private static void startPlayers(Path root, Path state, int count) throws IOException {
        log("starting " + count + " simulated players");
        Path log = logPath(state, PLAYERS);
        Process process = new ProcessBuilder("jbang", "scripts/GameLoader.java", "http://localhost:8080",
                "--players", String.valueOf(count), "--power", "10", "--interval", "200", "--ws")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .start();
        savePid(state, PLAYERS, process.pid());
        log("players log: " + log + " (they tap once the race starts)");
    }

    private static void await(Service service, Process process, Path state) {
        log("waiting for " + service.id + " on :" + service.port);
        long deadline = System.nanoTime() + Duration.ofSeconds(service.timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail(service, service.id + " exited with " + process.exitValue(), state);
            }
            if (answering(service)) {
                log(service.id + " is up");
                return;
            }
            sleep(2000);
        }
        fail(service, service.id + " did not answer on :" + service.port + " within " + service.timeoutSeconds + "s",
                state);
    }

    private static void stop(Path state, String id) {
        Path pid = state.resolve(id + ".pid");
        if (!Files.exists(pid)) {
            return;
        }
        Optional<ProcessHandle> handle = readPid(pid);
        handle.ifPresent(h -> {
            // The dev mode runs inside the Maven JVM, but every child it forked has to go too.
            h.descendants().forEach(ProcessHandle::destroy);
            h.destroy();
            try {
                h.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                h.descendants().forEach(ProcessHandle::destroyForcibly);
                h.destroyForcibly();
            }
            log("stopped " + id);
        });
        try {
            Files.deleteIfExists(pid);
        } catch (IOException e) {
            // A leftover pid file only makes the next stop a no-op.
        }
    }

    private static boolean running(Path state, String id) {
        Path pid = state.resolve(id + ".pid");
        return Files.exists(pid) && readPid(pid).filter(ProcessHandle::isAlive).isPresent();
    }

    private static void savePid(Path state, String id, long pid) throws IOException {
        Files.writeString(state.resolve(id + ".pid"), Long.toString(pid));
    }

    private static Optional<ProcessHandle> readPid(Path pid) {
        try {
            return ProcessHandle.of(Long.parseLong(Files.readString(pid).trim()));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    // --- readiness -------------------------------------------------------------------------

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static boolean answering(Service service) {
        return get("http://localhost:" + service.port + service.readyPath) == 200;
    }

    private static int get(String url) {
        try {
            return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The admin MCP server only exists when OIDC is on: without it every call answers 403. A 401 is
     * the healthy answer here, because the endpoint rejects the anonymous request before anything runs.
     */
    private static void requireKeycloak() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"devmode\",\"version\":\"1\"}}}";
        int status = 0;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:8080/admin/mcp"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            status = CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            System.err.println("cannot reach /admin/mcp: " + e.getMessage());
            System.exit(1);
        }
        if (status == 401) {
            log("admin MCP answers 401, so Keycloak is up");
            return;
        }
        System.err.println("""

                /admin/mcp answered %d, expected 401.
                A 403 means the game started without OIDC: GAME_OIDC_ENABLED was not set, or Keycloak
                Dev Services did not start. Stop with `jbang scripts/DevMode.java stop` and start again with
                `oidc`, `ops` or `mesh`.""".formatted(status));
        System.exit(1);
    }

    // --- files ----------------------------------------------------------------------------

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.exists(root.resolve("mvnw")) || !Files.exists(root.resolve("race-ops/pom.xml"))) {
            System.err.println("run this from the repository root: " + root
                    + " has no ./mvnw and race-ops/pom.xml");
            System.exit(1);
        }
        return root;
    }

    private static Path stateDir(Path root) throws IOException {
        Path state = root.resolve("target/dev-mode");
        Files.createDirectories(state);
        return state;
    }

    private static Path logPath(Path state, String id) {
        return state.resolve(id + ".log");
    }

    private static void fail(Service failed, String message, Path state) {
        System.err.println("ERROR: " + message);
        Path log = logPath(state, failed.id);
        try {
            if (Files.exists(log)) {
                System.err.println("--- last lines of " + log);
                List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                lines.subList(Math.max(0, lines.size() - 25), lines.size()).forEach(System.err::println);
            }
        } catch (IOException e) {
            // The message above is the failure that matters.
        }
        System.err.println("--- whatever started before this is still running: "
                + "`jbang scripts/DevMode.java status`, then `stop`");
        System.exit(1);
    }

    private static void summary(Mode mode) {
        System.out.println();
        System.out.println("==> " + mode.name().toLowerCase() + " is up: " + mode.description);
        for (Service service : mode.services) {
            System.out.printf("    %-9s %s%n", service.id, switch (service) {
                case GAME -> "http://localhost:8080 (dashboard /dashboard, developer / developer)";
                case OPS -> "http://localhost:8081/ops.html (director console)";
                case ANALYST, CREATIVE -> "http://localhost:" + service.port;
            });
        }
        if (!mode.oidc) {
            System.out.println("    admin MCP is off in this mode; start oidc, ops or mesh to use it");
        }
        System.out.println("    logs in target/dev-mode, stop with `jbang scripts/DevMode.java stop`");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String message) {
        System.out.println("==> " + message);
    }
}
