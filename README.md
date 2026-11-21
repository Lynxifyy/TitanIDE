# TITAN IDE

Your entire development studio. No desktop required.

TITAN IDE is an Android-native development environment: project scaffolding, a code
editor with real static analysis, Git and GitHub integration, a build engine with local
and remote executors, an AI assistant, a terminal, and developer tools — all in one app,
all backed by real implementations.

---

## What actually works

Every item below is implemented and covered by tests. Nothing here is a mock or a
disabled placeholder.

| Area | Status | Evidence |
| --- | --- | --- |
| Project scaffolding | 4 categories, 20+ languages, game mod catalog | `core/templates`, `ProjectScaffolder` tests |
| Code editor | Tokenizer, undo stack, incremental diff, search, CodeGuard analysis, project-wide symbol index for go-to-definition / find-references / go-to-symbol | `core/editor` tests |
| Project file system | Sandboxed read/write/move/copy, zip-slip hardened extraction | `core/data` `SafeFileSystem` tests |
| Git | Clone, commit, branch, checkout, merge, push, pull, diff, log via JGit | `core/git` tests |
| GitHub | Device-flow OAuth, repo browse, clone-to-project, remote test | `core/git` + app UI |
| Build engine | Local, remote agent and GitHub Actions backends | `core/engine` tests |
| Remote build agent | Real HTTP service, sandboxed subprocess execution | `backend/agent`, 19 tests |
| AI | Provider abstraction, project context builder, edit applier | `core/ai` tests |
| Persistence | Room (projects, builds, tabs, extensions, HTTP history), DataStore settings | `core/data` tests |
| Credentials | Keystore-backed encrypted store, redaction helpers | `core/data` `SecureCredentialStore` |
| UI | 7 primary destinations plus detail routes, phone/tablet adaptive shell | `app` route tests |

## What does not work on-device, and why

TITAN does not pretend. These are platform limits, not unfinished work:

- **On-device Gradle/Android builds.** Android ships no JDK and cannot execute `gradlew`
  against a real Android SDK. The local backend runs whatever toolchain the device
  actually has (often nothing) and reports the real failure. Use the remote agent or
  GitHub Actions target for real compilation.
- **Third-party extension runtime.** Installing arbitrary plugin code on Android without a
  sandbox is unsafe, so the Extensions screen lists only modules compiled into TITAN. The
  toggle state is real and persisted.
- **Rendering a project's Compose previews.** Preview classes would have to be loaded into
  TITAN's classloader. The UI Preview screen lists the layouts and `@Preview` functions a
  project declares instead.
- **Other apps' databases.** Scoped storage blocks it. The Database Viewer reads TITAN's
  own Room tables.
- **A language server.** No LSP server ships with, or can be spawned by, an Android app,
  and bundling a JVM language server would mean shipping a JDK. Navigation is therefore
  answered by TITAN's own symbol index: declarations come from the per-language extractor
  in `core/editor`, and references are matched on real lexer tokens, so identifiers inside
  comments and strings are not reported. This resolves names, not types — it will not tell
  you which overload a call binds to.

## Architecture

```
app/                     Compose UI, ViewModels, navigation, manual DI container
core/model/              Pure Kotlin models, no Android dependencies
core/designsystem/       Custom design system: colors, typography, components
core/editor/             Tokenizer, TextDocument, UndoStack, TextDiff, CodeGuard, search
core/data/               Room, DataStore, safe file system, project detection and scanning
core/engine/             Build engine, planner, process runner, remote backends
core/git/                JGit operations, GitHub REST client, device-flow auth
core/ai/                 Provider abstraction, context building, edit application
core/templates/          Project generators and the game/framework catalog
backend/agent/           Reference remote build agent (Python, stdlib only)
```

Data flows one way: UI to ViewModel to repository or engine to storage. ViewModels expose
`StateFlow` and avoid Android framework classes, so the logic stays testable on the JVM.

## Local versus remote execution

TITAN has one build engine with three interchangeable backends:

1. **Local** runs commands on the device through `ProcessRunner`. It works for shell,
   Python and Node where the device provides them, and fails honestly where it does not.
2. **Remote agent** zips the project, POSTs it with the planned commands, polls for the log
   and downloads artifacts. Protocol in `RemoteAgentBackend`, reference server in
   `backend/agent`.
3. **GitHub Actions** dispatches a workflow in a repository you configure and reads the run
   status back through the GitHub API.

`BuildPlanner` derives the commands from the detected build system, so a new backend only
has to transport commands and results.

## Running the build agent

```bash
cd backend/agent
PYTHONPATH=. python3 -m titan_agent --host 0.0.0.0 --port 8787 --token "$TITAN_AGENT_TOKEN"
```

Then set the agent URL and token in Settings, Build and execution. The agent validates
archive entry names, caps archive and expanded size, bounds the command count, runs each
job in its own directory with `HOME` and `GRADLE_USER_HOME` redirected, and terminates the
whole process group on cancel.

## Building the app

```bash
export JAVA_HOME=/path/to/jdk-17+
export ANDROID_HOME=/path/to/android-sdk

./gradlew testDebugUnitTest          # 249 unit tests
./gradlew :app:assembleDebug         # debug APK, ~26 MB
./gradlew :app:assembleRelease       # minified release APK, ~4 MB
./gradlew :app:lintDebug             # lint; zero errors expected

cd backend/agent && PYTHONPATH=. python3 -m unittest discover -s tests   # 19 tests
```

`local.properties` must point at your SDK (`sdk.dir=...`). The GitHub OAuth client id is
supplied at build time with `-Ptitan.github.clientId=...`; it is never committed.

### Release signing

The release build reads its keystore from the environment or from an untracked
`gradle.properties`, so no key material is ever committed:

```bash
export TITAN_SIGNING_STORE_FILE=/secure/path/titan-release.jks
export TITAN_SIGNING_STORE_PASSWORD=...
export TITAN_SIGNING_KEY_ALIAS=titan
export TITAN_SIGNING_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

When those variables are absent the release build falls back to the debug key. That still
runs R8 and produces an installable APK, so a local or CI build works without secrets, but
the artifact cannot be published. `build.yml` refuses to publish a GitHub release from a
debug-signed tag.

## Continuous integration

Two workflows under `.github/workflows`:

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `ci.yml` | push to `main`, every PR | repository checks (tracked artifacts, credential-shaped literals), the Python agent suite, then unit tests, lint and a debug APK |
| `build.yml` | push to `main`, tags, PRs | assembles the **release** APK, asserts R8 actually shrank it, verifies the signature, and on a `v*` tag publishes a GitHub release |

`build.yml` publishes only when the APK is release-signed. Add these repository secrets to
enable that: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`.

## Security posture

- GitHub tokens and API keys live in `SecureCredentialStore`, backed by the Android
  Keystore, and are redacted from logs.
- Project context is not sent to an AI provider unless the user enables it.
- Archive extraction rejects traversal, absolute paths and size bombs on both the client
  and the agent.
- Destructive operations such as deleting a project require confirmation.
- No credentials are hardcoded anywhere in the tree.
