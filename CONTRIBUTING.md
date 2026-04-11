# Contributing

How to set the project up locally, where the code lives, and what to watch out for when you send a pull request.

## Development setup

You need:

- JDK 21 (Temurin, Zulu, Corretto or whatever your package manager ships)
- Git
- A JetBrains IDE for testing. IntelliJ IDEA Community is fine.
- The [`sops`](https://github.com/getsops/sops/releases) binary on your `PATH`
- An age key file at `~/.config/sops/age/keys.txt` if you want to run the plugin end-to-end in the sandbox

Clone the repo and run:

```bash
./gradlew buildPlugin    # produce a distributable zip under build/distributions/
./gradlew test           # run unit tests
./gradlew runIde         # launch a sandbox IDE with the plugin loaded
./gradlew verifyPlugin   # run the JetBrains plugin verifier
```

The first run downloads the IntelliJ Platform dependencies, which takes a few minutes. Subsequent runs are cached.

## Project layout

```
src/
├── main/
│   ├── kotlin/dev/ott/sops/editor/
│   │   ├── SopsEditor.kt              # Split-view editor implementation
│   │   ├── SopsEditorProvider.kt      # Registers the editor for SOPS files
│   │   ├── SopsDetector.kt            # Detects SOPS-encrypted files by format
│   │   ├── SopsFormat.kt              # Format enum (ENV, YAML, JSON, INI, TOML, BINARY)
│   │   ├── SopsService.kt             # Async encrypt/decrypt orchestration
│   │   ├── SopsWrapper.kt             # Low-level sops CLI wrapper
│   │   ├── SopsLog.kt                 # Sanitizing dual logger
│   │   ├── SopsEncryptAction.kt       # "Encrypt with SOPS" context action
│   │   ├── SopsDecryptAction.kt       # "Decrypt with SOPS" context action
│   │   ├── SopsNewFileAction.kt       # "New → SOPS Encrypted File" action + dialog
│   │   ├── SopsSaveListener.kt        # Auto-encrypt on save hook
│   │   ├── notifications/
│   │   └── settings/
│   └── resources/
│       ├── META-INF/plugin.xml        # Plugin manifest
│       └── messages/SopsBundle.properties  # English UI strings
└── test/
    └── kotlin/dev/ott/sops/editor/    # JUnit 5 unit tests
```

## Coding conventions

Kotlin is the target language. Java interop code uses the IntelliJ platform Java APIs and is kept thin.

The project uses `snake_case` for functions, properties and local variables. This is an intentional deviation from the usual Kotlin style to match my other codebases. When you edit an existing file, match the surrounding style. When you add a new file, use `snake_case` too.

Comments are English. Explain why, not what. Skip comments that just paraphrase the code.

All logging goes through `SopsLog.info/warn/error/debug`, not `Logger.getInstance()` directly. The messages pass through `LogSanitizer.sanitize()` so new secret patterns only need to be added in one place.

When you add a new log call, ask yourself whether it could include decrypted content, a stack trace containing user data, a command line with a file path you do not control, or an environment map. When in doubt, log less.

## Commit messages

Write commits for someone who is reading the diff six months from now without any of your current context.

- Short title, 70 characters or fewer, imperative mood, no trailing period.
- Body explains why, not what. The diff already says what.
- No `Co-Authored-By` trailers, no emoji in titles.
- Reference the GitHub issue number in the title when applicable: `Fix #42 ...`.

Example:

```
Tighten filename validation in new-file dialog

The previous regex accepted any non-blank name, which allowed "../evil.env"
and similar path traversal attempts to escape the target directory. The
whitelist now accepts letters, digits, dots, underscores and dashes only.
```

## User-facing strings

Every user-facing string goes through `SopsBundle.message("some.key")` with a matching entry in `src/main/resources/messages/SopsBundle.properties`. The plugin is English-only for now, so there is exactly one bundle file. If you want to add another language, open an issue first. A language switch in IntelliJ settings dialogs cannot repaint existing labels without a full IDE restart, so reintroducing a locale setting means reintroducing that restart requirement in the UX.

## Testing

Unit tests live under `src/test/kotlin/` and run with `./gradlew test`. Pure-Kotlin logic like `LogSanitizer` and `validate_new_filename` is deliberately kept free of IntelliJ platform dependencies so it can be tested with plain JUnit. Anything that touches `VirtualFile` or the `Application` instance needs a test that extends `BasePlatformTestCase` from the test framework.

## Release process

1. Bump `pluginVersion` in `gradle.properties`.
2. Update `CHANGELOG.md` under a new version heading with the release date.
3. Update the `<change-notes>` block in `plugin.xml` with the highlights.
4. Commit and push to `master`.
5. Tag the commit `vX.Y.Z` as an annotated tag. CI builds the plugin, creates a GitHub release with the ZIP attached, and uploads to the JetBrains Marketplace if the `PUBLISH_TOKEN` secret is configured.
