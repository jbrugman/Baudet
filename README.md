# Mule

A local-first coding assistant: a desktop app (not a CLI) for talking to an
LLM that runs on your own machine through an OpenAI-compatible API.

## What this is

Mule is a small desktop client for a model served locally, e.g. behind
[oMLX](https://github.com/jundot/omlx) on `http://127.0.0.1:8000/v1`.
That loopback address is the default; a server elsewhere is set in Settings and
stored, not hardcoded.
It speaks the OpenAI **Responses API** by default, with the Chat Completions
shape tolerated as a fallback.

It keeps the server-side session: each turn sends `previous_response_id`, so
the backend carries the context instead of the client resending it. Settings
and transcripts are persisted locally in an embedded H2 database.

## Why it was started

Existing assistants with excellent interfaces — Codex, LM Studio's Bionic,
OpenCode — are all built around frontier models. Running them against a local
model leaves the local part cosmetic: they assume a huge context, cheap
tokens, a reliable tool caller, and a provider that tells the truth about
what it did.

Local-first here means **removing those assumptions**, and that shows up as
concrete behavior:

- **Honest token accounting.** `response_id`, prompt/completion tokens and the
  finish reason are always on screen, because they are the only evidence of
  whether the server-side session and the prefix cache actually work.
- **Truncation is named, not implied.** "No answer" and "budget exhausted
  before the answer started" look identical otherwise, and only one of them is
  fixed by raising the budget.
- **Reasoning output is kept.** Reasoning models spend most of a small budget
  on the summary before any answer exists. Mule shows it separately and keeps
  it out of the prompt.
- **Tolerant parsing.** Local backends deviate from the documented event
  names. An unknown event is counted and sampled, never fatal, and the census
  stays visible so a silently missed delta cannot hide.
- **Client-side compaction**, because there is no compact endpoint to call.

## Current state: pre-alpha

The wire layer works; the product around it is a skeleton.

Working, and verified against a real backend:

- list models, pick one, save settings
- streaming chat over `/v1/responses` with a server-side session
- reasoning summary shown apart from the answer
- capability probe: what the backend *does* rather than what it accepts
- persisted session: the last conversation is restored after a restart
- client-side compaction
- UI text in English or Dutch, switchable at runtime (English is the default)

Not there yet:

- no tools, no file editing, no git checkpoints, no diff view — so it is not
  yet a coding assistant, only a chat client
- the GraalVM `native-image` profile is not wired up
- single session; no session browser, no markdown rendering

## Stack

Kotlin on the JVM, Swing + FlatLaf, Maven.

The choices are all pulled by one requirement: a native binary later.
Swing survives `native-image`, which is why not Compose Multiplatform;
`java.net.http` instead of Ktor/OkHttp because it is in the JDK and uses no
reflection; H2 instead of sqlite-jdbc because sqlite ships platform `.dylib`
files that are guesswork under AOT; kotlinx-serialization for the same reason.
JDK 25, matching the installed GraalVM CE 25.

## Build and run

Requires JDK 25. The API key is read from the `OMLX_API_KEY` environment
variable unless one is typed into Settings.

Run it straight from source with Maven:

```bash
mvn -B -Dkotlin.compiler.execution.strategy=in-process test          # unit tests
mvn -q -B exec:java                                   # the app, from source

mvn -q -B exec:java -Dexec.args="--smoke"             # models only, no GPU cost
mvn -q -B exec:java -Dexec.args="--smoke --probe"     # + capability probe (2 calls)
```

Or build the runnable jar (shaded, with `Main-Class` set) and run it with any
`java` on your path:

```bash
mvn -B package                                        # -> target/mule-<version>.jar
java -jar target/mule-<version>.jar                   # the app
java -jar target/mule-<version>.jar --smoke           # headless model-list check
```

The store location defaults to a per-user directory that does not depend on the
working directory -- `~/Library/Application Support/Mule` on macOS, `%APPDATA%\Mule`
on Windows, `$XDG_DATA_HOME/mule` on Linux -- so the packaged app still finds it
writable when Finder launches it with cwd `/`. Override with `-Dmule.home=/some/dir`;
`mvn exec:java` pins it to `./mule-data` so a dev run keeps its store checkout-local.

## Packaging (macOS)

`jpackage` turns the shaded jar into a self-contained `Mule.app` with its own
bundled runtime. The icon is not a committed bitmap: the app draws it itself,
and `--export-icons` writes the `.iconset` that `iconutil` compiles into the
`.icns` -- so the mark is defined in exactly one place.

```bash
mvn -B clean verify -Pmac-app          # -> target/dist/Mule.app
```

The `mac-app` profile runs `packaging/make-mac-app.sh` once the jar is built.
That script exports the icon, compiles the `.icns`, and bundles the full default
runtime. Set `MULE_PKG_TYPE=dmg` to also wrap the `.app` into a `.dmg`.

One honest wrinkle: `jpackage` refuses a version whose major number is `0`, so
the bundle is built with a placeholder version and the real pre-alpha version
(currently `0.1.0`) is written back into `Info.plist`. The shipped `.app`
therefore reports `0.1.0` in Finder, not the placeholder it was built from.

The bundle is **ad-hoc signed** but carries no Apple Developer ID, so a copy
downloaded from another machine is blocked by Gatekeeper until it is re-signed with
a Developer ID and notarized -- a separate step that is not part of this build.

Two things about launching it on Apple Silicon were learned the hard way, both
specific to the Finder/`open` path (running the jar directly dodges them):

- **The signature seal.** `jpackage` ad-hoc signs the bundle, then this build
  stamps the real pre-alpha version into `Info.plist`. Editing a signed bundle
  invalidates its seal (`invalid Info.plist: plist or signature have been
  modified`), and a bundle with a broken seal aborts during GUI startup in
  `-[NSApplicationAWT registerWithProcessManager]` before any window appears. The
  build re-signs ad-hoc after the stamp and asserts the seal with
  `codesign --verify`, so this cannot regress silently.
- **The working directory.** Finder launches the app with cwd `/`, where the store
  path once resolved to the read-only root and the JVM exited on startup. The data
  directory is now an absolute per-user path (see *Build and run*), so the store
  opens however the app is launched.

What is measured here: the bundle builds, `Info.plist` carries `0.1.0`, and
`codesign --verify` passes on the re-signed bundle; forcing cwd `/` reproduced the
store-open failure and clears it after the fix. The graphical window opening on a
real desktop session is the last step and has not been captured headlessly.

### Launching the packaged app

Two ways to start `Mule.app` from a shell:

```bash
open target/dist/Mule.app                     # normal launch (LaunchServices: Dock, icon)
target/dist/Mule.app/Contents/MacOS/Mule      # direct: stdout/stderr and exit code stay in the shell
```

`open` is the double-click path; app output does not appear in the terminal.
Arguments go through `--args`, e.g. `open target/dist/Mule.app --args --smoke`.
The direct launcher boots the bundled runtime under `Contents/app`, so no local
`java` or `JAVA_HOME` is needed -- handy for a headless run such as
`target/dist/Mule.app/Contents/MacOS/Mule --export-icons=/tmp/mule-icons`.

The bundle is ad-hoc signed (see above). Built on the machine that runs it, jpackage
applies an ad-hoc signature and it launches normally; a copy carried from another
machine is quarantined by Gatekeeper until cleared with
`xattr -dr com.apple.quarantine target/dist/Mule.app`, or opened once with
right-click -> Open in Finder.

## Continuous integration

GitHub Actions, in `.github/workflows/`:

- `java-maven-ci.yml` -- on every push and pull request to `main`: build and run
  the test suite with `mvn -B clean verify`.
- `package-mac.yml` -- on push to `main`: build on `macos-latest`, run the same
  `mac-app` packaging, and upload the resulting `Mule.app` as a build artifact.
  The artifact is unsigned; downloading it and opening it locally is on you.

## Measured behavior

Recorded against oMLX serving `Qwen3.8-Flash-Next-oQ4e-mtp`, 2026-11-08:

- Event names follow the Responses API spec and arrive in `data.type`
  (`response.output_text.delta`, `response.reasoning_summary_text.delta`, …).
- `previous_response_id` is honored: the prompt grew 67 → 343 tokens across a
  follow-up, and `GET /v1/responses/{id}` returns 200.
- There is **no** `/responses/compact` endpoint, so compaction is the client's
  job.
- The server stores the reasoning summary, so **reasoning inflates every later
  turn** (+276 prompt tokens in that run). Worth knowing before comparing
  local models on cost.

## Provenance

Most of this code was written by an AI coding assistant (OpenAI Codex) driving
the local model **Qwen3.8-Flash-Next-oQ4e-mtp** served by oMLX, under human
direction and review. Per-line attribution is not available, so this is stated
at project level rather than per file.

Claims labeled "Measured" or "verified" above were obtained by running against
the backend, not inferred from documentation — including one initial diagnosis
that turned out to be wrong and was corrected by the probe output.

## License

MIT. See [LICENSE](LICENSE).
