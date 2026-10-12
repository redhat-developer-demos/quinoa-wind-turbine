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
jbang scripts/GameLoader.java http://localhost:8080 --players <NUM_PLAYERS> --clicks <NUM_CLICKS> --power <POWER_PER_CLICK>
```
* **Default benchmark:** `--players 50 --clicks 100 --power 15`
* **Heavy benchmark:** `--players 200 --clicks 200 --power 15`

The script will register players across both teams and poll `/api/game/status` waiting for the race to start.

### 3. Start the Race
Choose one of the following methods to trigger the start event:
- **Dashboard UI:** Open `http://localhost:8080/dashboard` in a browser, log in with `developer` / `developer`, and click **Play**.
- **Admin Event via CLI/CURL:**
  ```bash
  curl -s -X POST http://localhost:8080/api/game/event \
    -u developer:developer \
    -H "Content-Type: application/json" \
    -d '{"type":"start"}'
  ```

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
