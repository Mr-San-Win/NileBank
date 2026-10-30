# Full-stack source layout

This structural refactor separates source ownership while retaining one same-origin Spring Boot service. It introduces no product features or business-rule changes.

## Build contract

- `backend/` contains the Maven wrapper/configuration, Spring Boot Java sources, configuration, Flyway migrations, tests and Dockerfile.
- `frontend/` contains the original HTML, CSS and JavaScript modules. There is no separate production frontend server.
- Maven processes the sibling frontend directory as an unfiltered resource with target path `static`. The executable JAR contains the files under `BOOT-INF/classes/static/`, and Spring Boot serves the original URLs.
- Root `compose.yaml` uses the repository root as build context and `backend/Dockerfile`. Existing database environment variables, volume, health checks and service names are unchanged.
- CI runs `sh mvnw -B clean verify` inside `backend/`, checks `frontend/*.js`, builds the root-context Docker image and uploads `backend/target/surefire-reports/`.

From the root, run `mvn -f backend/pom.xml clean verify`, or enter `backend/` and use `sh mvnw -B clean verify`. Docker demonstrations still start with `docker compose up --build` from the root. Keep both source directories together and use clean builds after removing or renaming assets to discard stale generated files.

## Compatibility verification — 30 October 2026

| Check | Observed result |
| --- | --- |
| Application/frontend/tests/configuration comparison | All 61 moved files byte-identical to the pre-refactor version |
| Flyway compatibility | All nine migration contents unchanged; existing PostgreSQL database started successfully |
| Backend Maven wrapper clean verify | 56 tests; zero failures, errors or skips; executable JAR produced |
| Packaged static resources | All four JAR assets byte-identical to frontend source files |
| Root Docker Compose build/start | Application and PostgreSQL healthy; existing demo records preserved |
| JavaScript | Both modules passed syntax checks |
| Original HTTP smoke | Passed sessions, CSRF, monetary flows, replay, ownership and logout |
| Extension HTTP smoke | First attempt received provider 503; subsequent full run passed real rates, exchange, partners and finance |
| Browser | Login/logout, dashboard balance, real exchange publication and persisted finance/budget/goal data verified |

The transient provider failure is an existing operational limitation: new quotes are rejected when reference rates cannot be fetched; no fixed-rate fallback was introduced. The UI subsequently displayed the real 2026-10-30 publication.

## Repository integration

The recreated repository's initial main commit is preserved as an ancestor of the refactor branch. Its LICENSE was retained, and the one-line README was combined with the existing application documentation by retaining the complete project README. Main is not updated automatically; the refactor is proposed through a pull request. No force-push or historical commit rewrite is required.
