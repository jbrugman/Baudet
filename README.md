# Baudet

A local-first coding assistant: a desktop app (not a CLI) for talking to an
LLM that runs on your own machine through an OpenAI-compatible API.

## About

**Baudet** is a local-first desktop assistant built to talk to local Large
Language Models (served through APIs like [oMLX](https://github.com/jundot/omlx))
without the sugarcoating.

In French, a *baudet* is a donkey - the ultimate beast of burden. Where
frontier-model clients assume cheap tokens, huge contexts and flawless tool
calls, **Baudet** is built for the reality of local hardware. It treats your
local model like a hardworking donkey: highly capable and industrious, but in
need of the right harness to stay efficient, transparent and honest.

The name is a layered inside joke and a working metaphor for local AI developers:

- **The sound of IA**: in French, Artificial Intelligence is abbreviated
  **IA** (pronounced *ee-yah*). Fittingly, *IA* - written *Hi-Han* - is the
  onomatopoeia for a donkey's bray.
- **The beast of burden**: local models do the heavy lifting on your own
  machine. Baudet manages server-side sessions, tracks token metrics and
  handles client-side compaction, so local inference is practical for
  day-to-day work.
- **Taming the stubborn donkey**: local backends are unpredictable - they
  deviate from event names, invent budgets, or burn the context window on
  reasoning before any answer. Like an experienced handler harnessing a clever
  but stubborn donkey, Baudet enforces honest token accounting, isolates
  reasoning tokens and absorbs parsing quirks without crashing.

By dropping cloud-first assumptions and putting real telemetry on screen,
Baudet lets you and your local model push through heavy workloads together.

## What this is

Baudet is a small desktop client for a model served locally, e.g. behind
[oMLX](https://github.com/jundot/omlx) on `http://127.0.0.1:8000/v1`.
That loopback address is the default; a server elsewhere is set in Settings and
stored, not hardcoded.
It speaks the OpenAI **Responses API** by default, with the Chat Completions
shape tolerated as a fallback.

By default the client bounds and replays recent history itself; the backend carries
the context through `previous_response_id` only under the Stop at limit strategy with
the send-response-id option on.
Settings, projects and transcripts are persisted locally in an embedded H2 database.

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
  on the summary before any answer exists. Baudet shows it separately and keeps
  it out of the prompt.
- **Tolerant parsing.** Local backends deviate from the documented event
  names. An unknown event is counted and sampled, never fatal, and the census
  stays visible so a silently missed delta cannot hide.
- **Client-side compaction**, because there is no compact endpoint to call.

## Current state: alpha

The wire layer and the project tools work; the editor surface around them is still
thin. The model can read a project file, apply a patch to one, query git read-only,
and run a shell command it first has to ask you to approve — which is what tips Baudet
from a chat client toward a coding assistant.

Working:

- list models, pick one, and save settings
- streaming chat over `/v1/responses`, keeping the server-side session through
  `previous_response_id`
- **Stop** closes the stream still open on the backend, so a read waiting for the
  next event releases at once; **Steer** sends your next instruction as soon as
  the cut lands and keeps the partial answer so that instruction can steer
- reasoning stays out of the transcript: it fills a closable dock above the input,
  as tall as the input, and is display-only
- answers render fenced blocks, headings, bullets, bold, italic and inline code, and
  identifiers such as `max_output_tokens` survive verbatim
- an honest capability probe reports what the backend *does*, not what it merely
  accepts, and the last conversation is restored after a restart
- context is bounded before it leaves the app: a **context strategy** (Keep last by
  default, Rolling window, Stop at limit) picks the history, the system prompt always
  leads, and a turn that cannot fit is folded or refused rather than sent doomed
- a project sidebar groups conversations and gates tools by the linked folder's rights
  (Read, Write, Execute); the tools are `read_file`, `edit_file`, `run_command` and a
  read-only `git`, driven through a tool loop
- `run_command` runs a shell in the project folder with `TMPDIR` set to the temp folder;
  by default every command asks. Unattended auto-approval stays strictly read-only (a chain,
  redirect, substitution or a payload tool such as `sed` is never auto-run). **Always allow**
  records a reusable pin: a family signature for a provably read-only command, and, for a
  command whose program the user vouches for and `SafeCommands` deems safe (such as `mvn` or
  a `mvnw` or `gradlew` wrapper), a trusted family key, so later variations run without a
  prompt. A chain, redirect or payload tool is never family-trusted and is trusted exactly
  as approved
- a file tree follows the selected project, colors each file by its git state (green
  changed, blue untracked, red deleted), opens a colored git patch on double-click, and
  refreshes itself when files change on disk
- UI text in English, Dutch, French or German, switchable at runtime, English by default

Not there yet:

- no in-app editor: edits happen through the model, not a text buffer
- git is read-only inspection — no commit, no checkpoints
- the GraalVM `native-image` profile is not wired up

## Stack

Kotlin on the JVM, Swing + FlatLaf, Maven.

The choices are all pulled by one requirement: a native binary later.
Swing survives `native-image`, which is why not Compose Multiplatform;
`java.net.http` instead of Ktor/OkHttp because it is in the JDK and uses no
reflection; H2 instead of sqlite-jdbc because sqlite ships platform `.dylib`
files that are guesswork under AOT; kotlinx-serialization for the same reason.
JDK 25, matching the installed GraalVM CE 25.

## Architecture

The runtime is sketched in five PlantUML sources under `Docs/`, which you can open
in any PlantUML viewer or render with `plantuml Docs/*.puml`:

- [`components.puml`](Docs/components.puml) - the layers and who calls whom
- [`sse-transport.puml`](Docs/sse-transport.puml) - one Responses call, streamed,
  framed and accumulated into a Turn
- [`turn-decision.puml`](Docs/turn-decision.puml) - how `AppState.send` routes,
  retries and stops a turn
- [`tool-loop.puml`](Docs/tool-loop.puml) - the model-driven tool loop and its
  approval gates
- [`compaction-fold.puml`](Docs/compaction-fold.puml) - the client-side compaction fold

They track the code by hand. When you move a class, update the matching diagram.

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
mvn -B package                                        # -> target/baudet-<version>.jar
java -jar target/baudet-<version>.jar                   # the app
java -jar target/baudet-<version>.jar --smoke           # headless model-list check
```

The store location defaults to a per-user directory that does not depend on the
working directory -- `~/Library/Application Support/Baudet` on macOS, `%APPDATA%\Baudet`
on Windows, `$XDG_DATA_HOME/baudet` on Linux -- so the packaged app still finds it
writable when Finder launches it with cwd `/`. Override with `-Dbaudet.home=/some/dir`;
`mvn exec:java` pins it to `./baudet-data` so a dev run keeps its store
checkout-local. Note that the 0.4.0 rename moved the store from the Mule
location to Baudet, so an earlier pre-alpha store is not read and the app
opens with a fresh database.

## Packaging (macOS)

`jpackage` turns the shaded jar into a self-contained `Baudet.app` with its own
bundled runtime. The icon is not a committed bitmap: the app draws it itself,
and `--export-icons` writes the `.iconset` that `iconutil` compiles into the
`.icns` -- so the mark is defined in exactly one place.

```bash
mvn -B clean verify -Pmac-app          # -> target/dist/Baudet.app
```

The `mac-app` profile runs `packaging/make-mac-app.sh` once the jar is built.
That script exports the icon, compiles the `.icns`, and bundles the full default
runtime. Set `BAUDET_PKG_TYPE=dmg` to also wrap the `.app` into a `.dmg`.

One honest wrinkle: `jpackage` refuses a version whose major number is `0`, so
the bundle is built with a placeholder version and the real pre-release version
from the pom is written back into `Info.plist`. The shipped `.app` therefore
reports that pom version in Finder, not the placeholder it was built from.

The bundle is **ad-hoc signed** but carries no Apple Developer ID, so a copy
downloaded from another machine is blocked by Gatekeeper until it is re-signed with
a Developer ID and notarized -- a separate step that is not part of this build.

Two things about launching it on Apple Silicon were learned the hard way, both
specific to the Finder/`open` path (running the jar directly dodges them):

- **The signature seal.** `jpackage` ad-hoc signs the bundle, then this build
  stamps the real pre-release version into `Info.plist`. Editing a signed bundle
  invalidates its seal (`invalid Info.plist: plist or signature have been
  modified`), and a bundle with a broken seal aborts during GUI startup in
  `-[NSApplicationAWT registerWithProcessManager]` before any window appears. The
  build re-signs ad-hoc after the stamp and asserts the seal with
  `codesign --verify`, so this cannot regress silently.
- **The working directory.** Finder launches the app with cwd `/`, where the store
  path once resolved to the read-only root and the JVM exited on startup. The data
  directory is now an absolute per-user path (see *Build and run*), so the store
  opens however the app is launched.

What is measured here: the bundle builds, `Info.plist` carries the stamped pom version, and
`codesign --verify` passes on the re-signed bundle; forcing cwd `/` reproduced the
store-open failure and clears it after the fix. The graphical window opening on a
real desktop session is the last step and has not been captured headlessly.

### Launching the packaged app

Two ways to start `Baudet.app` from a shell:

```bash
open target/dist/Baudet.app                     # normal launch (LaunchServices: Dock, icon)
target/dist/Baudet.app/Contents/MacOS/Baudet      # direct: stdout/stderr and exit code stay in the shell
```

`open` is the double-click path; app output does not appear in the terminal.
Arguments go through `--args`, e.g. `open target/dist/Baudet.app --args --smoke`.
The direct launcher boots the bundled runtime under `Contents/app`, so no local
`java` or `JAVA_HOME` is needed -- handy for a headless run such as
`target/dist/Baudet.app/Contents/MacOS/Baudet --export-icons=/tmp/baudet-icons`.

The bundle is ad-hoc signed (see above). Built on the machine that runs it, jpackage
applies an ad-hoc signature and it launches normally; a copy carried from another
machine is quarantined by Gatekeeper until cleared with
`xattr -dr com.apple.quarantine target/dist/Baudet.app`, or opened once with
right-click -> Open in Finder.

## Continuous integration

GitHub Actions, in `.github/workflows/`:

- `java-maven-ci.yml` -- on every push and pull request to `main`: build and run
  the test suite with `mvn -B clean verify`.
- `package-mac.yml` -- on push to `main`: build on `macos-latest`, run the same
  `mac-app` packaging, and upload the resulting `Baudet.app` as a build artifact.
  The artifact is unsigned; downloading it and opening it locally is on you.
  A qualifying push also publishes a GitHub Release carrying two assets:
  `Baudet-<version>-macos.zip` (the ad-hoc-signed `.app`) and `Baudet-<version>.jar`
  (the runnable shaded jar -- the portable artifact that runs on any JVM, since
  the `.app` is not notarized and Gatekeeper blocks it on another Mac).

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
What matters here is the model behind it: every model call went to a local
model over oMLX, a local serving platform that runs on your own machine rather
than in the cloud. I wanted to prove that a local model can be used to write a
useful program; this project is the result of that experiment.

Claims labeled "Measured" or "verified" above were obtained by running against
the backend, not inferred from documentation — including one initial diagnosis
that turned out to be wrong and was corrected by the probe output.

## License

MIT. See [LICENSE](LICENSE).
