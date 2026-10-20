---
name: wind-turbine-load-test
description: Run load tests and analyze performance for the Quinoa Wind Turbine application using Quarkus dev mode, JBang GameLoader, and Prometheus metrics. Use when the user asks to run load tests, simulate players, benchmark the race game, or analyze performance.
---

# Quinoa Wind Turbine Load Testing & Performance Analysis

This skill guides you through executing load simulation and analyzing runtime metrics for the Quinoa Wind Turbine racing application.

## Workflow

### 1. Ensure the Application is Running in Dev Mode
Use the Quarkus Agent MCP tool:
- Check status: `quarkus_status`
- If not running: Call `quarkus_start`

Verify the app is accessible at `http://localhost:8080`.

### 2. Execute the JBang Load Generator
Run the lightweight non-blocking load script:
```bash
jbang scripts/GameLoader.java http://localhost:8080 --players 12 --clicks 600 --power 10 --interval 75 --ws
```

**Key options:**
* `--players` — number of simulated players (split evenly across teams)
* `--clicks` — a safety cap on click batches per player. With `--ws` the loader taps until the race finishes, so the cap rarely matters; each click is a random 1..power (about `power/2`), so finishing needs roughly `3000 / power` batches. Without `--ws` size it to at least `3000 / power`.
* `--power` — maximum power per click (actual value is random 1..power, so average is ~power/2)
* `--interval` — milliseconds between click batches (default 200); lower = faster race
* `--ws` — send clicks over the player WebSocket instead of `POST /api/power` (recommended; players then wait for the `start` event on their socket)

**Recommended presets:**

| Goal | Command suffix |
|---|---|
| ~20s demo race | `--players 12 --clicks 600 --power 10 --interval 75 --ws` |
| ~45s longer race | `--players 12 --clicks 600 --power 10 --interval 100 --ws` |
| Load/stress test | `--players 50 --clicks 600 --power 15 --interval 75 --ws` |

The script registers players across both teams and waits for the game to start before sending clicks.

### 3. Start the Race
Choose one of the following methods to trigger the start event:
- **Dashboard UI:** Open `http://localhost:8080/dashboard` in a browser, log in with `developer` / `developer`, and click **Play**.
- **Admin Event via CLI/CURL:** the endpoint uses form login, so log in first and send the session cookie (basic auth is not enabled and only gets a redirect to the login page):
  ```bash
  curl -s -c /tmp/wt-cookies -d j_username=developer -d j_password=developer \
    http://localhost:8080/j_security_check
  curl -s -b /tmp/wt-cookies -X POST http://localhost:8080/api/game/event \
    -H "Content-Type: application/json" \
    -d '{"type":"start"}'
  ```
- **MCP:** call `start_race` on the `wind-turbine-admin` server. See the `wind-turbine-mcp-demo` skill.

### 4. Fetch & Analyze Performance Telemetry
Once the load test finishes, gather metrics:
- **Prometheus Metrics:**
  ```bash
  curl -s http://localhost:8080/metrics
  ```
- **Key Metrics to Inspect:**
  - `http_server_requests_seconds_count` & `http_server_requests_seconds_sum`: Total throughput and average response time on `/api/power` and `/api/game/assign`.
  - `counted_user_assigned_total`: Number of registered players.
  - `kafka_producer_record_send_total`: Message publishing rate to Kafka topics (`power`, `game-events`).
  - `jvm_memory_used_bytes` / `jvm_gc_pause_seconds_sum`: Heap utilization and GC overhead during the test run.

### 5. Playwright & Safety Rules
- **NEVER use browser/Playwright tests for load simulation.** Real browser tests spawn full headless Chromium instances that exhaust memory.
- Heavy load simulation is strictly handled via `scripts/GameLoader.java`.
