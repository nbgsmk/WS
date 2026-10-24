# Headless Module Agent Prototype

This project is a prototype for independently running, headless Java modules. Each module is expected to run in its own process or Docker container and expose a small HTTP control and status interface. Docker is responsible for container lifecycle and restart policy; this project controls the job running inside a container.

## Current architecture

```text
ModuleAgent
    └── Worker
          └── dummy processing loop
```

`ModuleAgent` is the per-module HTTP server. It owns a `Worker`, accepts lifecycle commands, and exposes status and event endpoints. `Worker` owns the background virtual thread and its lifecycle state.

The intended future architecture is:

```text
Browser / Control Gateway
    ├── ModuleAgent A ── Worker ── real module task
    ├── ModuleAgent B ── Worker ── real module task
    └── ModuleAgent C ── Worker ── real module task
```

The browser-facing control gateway has not been implemented yet. `WebUiProxy` is only a low-level fixed-target TCP proxy used for experiments; it is not the production dashboard or control gateway.

## Implemented behavior

### `Worker`

`Worker` runs a dummy loop on a Java virtual thread. It currently logs one dummy iteration every second and supports:

- `start()`
- `stop()`
- `pause()`
- `resume()`
- `close()`
- status listeners

The states are:

```text
STOPPED → RUNNING ⇄ PAUSED
              ↓
            FAILED
```

The worker is designed to run without a browser or any connected client.

`Worker.Status` currently contains:

- worker name
- state
- iteration count
- last activity timestamp
- last error

### `ModuleAgent`

`ModuleAgent` runs a simple HTTP server and controls one worker instance. The two-argument constructor automatically starts the worker after the HTTP server binds successfully:

```java
ModuleAgent agent = new ModuleAgent("processor", 8080);
agent.start();
```

For manual worker startup:

```java
ModuleAgent agent = new ModuleAgent("processor", 8080, false);
agent.start();
```

Current endpoints:

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/status` | Returns structured worker status JSON. |
| `POST` | `/start` | Starts the worker. |
| `POST` | `/stop` | Stops the worker. |
| `POST` | `/pause` | Pauses at the worker loop boundary. |
| `POST` | `/resume` | Resumes a paused worker. |
| `GET` | `/.sse` | Persistent SSE status stream with heartbeats. |
| `GET` | `/.ws` | Experimental WebSocket HTML/test endpoint. |

The current SSE stream sends an initial status event, status changes, dummy-work updates, and a heartbeat approximately every 15 seconds.

### Tests

- `WorkerTest` covers start, stop, idempotency, pause, resume, and stop while paused.
- `ModuleAgentTest` covers automatic headless startup and optional manual startup.
- `WebUiProxyTest` covers the experimental proxy’s HTTP, SSE, WebSocket, POST, and unavailable-target behavior.

## Running and testing

The project uses Gradle and targets Java 21 or newer. IntelliJ’s bundled runtime was found at:

```text
/home/mcu/opt/IntelliJ_IDEA/jbr
```

From a writable environment, run:

```bash
JAVA_HOME=/path/to/jbr PATH="$JAVA_HOME/bin:$PATH" ./gradlew test
```

In IntelliJ, configure the Gradle JVM as `IntelliJ IDEA Runtime`.

The current development environment can locate the bundled runtime, but its Gradle cache is read-only, so Gradle tests could not be executed there. `git diff --check` passed after the latest changes.

## Naming decisions

- `ModuleAgent`: per-container HTTP control and status service.
- `Worker`: lifecycle runner for one long-lived background job.
- `GenericTask`: possible future interface for the actual unit of work; it should not replace `Worker`.
- `ControlGateway`: planned central service aggregating multiple agents for the browser.
- `WebUiProxy`: experimental transparent proxy, not the user-facing UI.

## Next steps

1. Extract the dummy loop behind an injectable work abstraction, such as `GenericTask` or `WorkProcessor`, so `Worker` can run real module-specific processing.
2. Define safe processing boundaries so cooperative pause finishes the current item before entering `PAUSED`.
3. Add a real module status model and persistent event handling suitable for MQTT, database, and processing jobs.
4. Improve the HTTP implementation or replace it with a standard HTTP framework once the API grows.
5. Add a central `ControlGateway` that discovers/configures multiple `ModuleAgent` instances and aggregates status and logs.
6. Add the browser dashboard after the agent API is stable.
7. Add Docker Compose deployment for the modules while leaving container restart behavior to Docker.

## Important limitations

This is still a prototype:

- The worker performs dummy work only.
- Status JSON is assembled manually.
- The HTTP server is a custom minimal implementation.
- Authentication and authorization are not implemented.
- Logs are currently console output only.
- SSE is implemented per agent and is not yet aggregated.
- The WebSocket implementation is experimental.
- No MQTT or database integration exists yet.
- No Docker deployment configuration exists yet.
