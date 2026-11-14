# AGENTS.md

Single-module Maven library, Java 11 language level: a Java port of the Python Reticulum protocol. Library version is 1.6.0; wire parity target is Python RNS 1.5.2. Entrypoint `io.reticulum.Reticulum`; `io.reticulum.Transport` is a process-wide singleton (`Transport.getInstance()`). Runnable examples are in `src/main/java/examples`; manual/test harnesses are in `src/test/java/examples`.

## Commands

- Build + test: `mvn clean install`
- Tests only: `mvn test`
- One class: `mvn -Dtest=ConstantParityTest test`
- One method: `mvn -Dtest='ConstantParityTest#reticulumConstants' test`
- Offline compile used by the live harnesses: `mvn -o -q test-compile`

No checkstyle/spotless/PMD/typecheck plugin is configured. Do not invent lint commands. CI (`.github/workflows/maven-publish.yml`) runs only when a release is created; there is no push/PR test gate, so verify locally.

## Protocol parity (most important domain rule)

Protocol constants and timing formulas are pinned to the Python reference by parity tests (`ConstantParityTest`, `ResourceConstantParityTest`, `TransportTimeoutTest`, `ChannelTimingTest`, `TokenTest`, etc.). Changing a constant, header layout, timeout, or token format without matching Python RNS 1.5.2 breaks interoperability. Java often stores times in milliseconds where the reference uses seconds; the parity tests document the conversion.

## Runtime artifacts — never commit

`new Reticulum(configDir)` creates `configDir`, `storage/`, and `storage/resources/`; if `config.yml` is absent it copies the classpath `reticulum.default.yml`. Running then writes `jreticulum.db` and `resources/identity` (raw private keys).

- Examples hardcode config dirs under `src/main/resources/{example,reticulumclient,reticulumserver}`; tests/harnesses use paths under `src/test/resources/` (e.g. `announce`, `node1`, `node2`, `tcp_server_node`).
- Running code dirties the source tree: untracked `storage/`, `resources/identity`, `jreticulum.db`, and generated `config.yml` files. `.gitignore` does not exclude these, so inspect `git status` and stage only intended files.
- `src/main/resources/reticulum.default.config` is an unused Python/ConfigObj-style leftover. The loaded config name is `config.yml` (`ReticulumConstant.CONFIG_FILE_NAME`); the template is `reticulum.default.yml`. Do not model new config work on the `.config` file.

## Testing quirks

- JUnit 5 + Mockito. A plain `mvn test` constructs a real `Reticulum` in `LinkTest`'s `@BeforeAll` (even though its link test is `@Disabled`), binding TCP port 4242 and creating storage under `src/test/resources/tcp_server_node`. Keep that port free and prefer `-Dtest=...` for focused runs.
- Most `src/test/java/examples/*` classes are manual harnesses; several are `@Disabled`.
- `AutoInterfaceAnnounceTest` skips itself via `assumeTrue` when no multicast IPv6 interface is available.
- `livetest/` contains manual interop shell/Python scripts, not Maven tests. They require a Python Reticulum 1.5.x checkout at `$HOME/git/Reticulum` (override with `RNS_SRC=/path`) and run `mvn -o -q test-compile`. They write only to `livetest/**/.work/` and per-run `storage/`, which are ignored by `livetest/.gitignore`.
