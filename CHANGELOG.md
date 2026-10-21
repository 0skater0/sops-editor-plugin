# Changelog

All notable changes to this plugin go here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-10-22

### Added

- Support for projects stored in WSL. On Windows, files on `\\wsl.localhost\` or `\\wsl$\` paths are now decrypted and encrypted by `sops` inside that distribution, through a login shell with Linux paths, so the project's `.sops.yaml` creation rules and the default age key file in WSL apply. Shell profile output such as `ssh-agent` messages no longer ends up in decrypted or encrypted content.
- New setting *SOPS executable in WSL* for the `sops` command or absolute Linux path used inside WSL. Custom environment variables are passed to WSL without appearing on the command line. Variables that the WSL shell profile sets itself take precedence over them.
- Diffs of SOPS files, for example in the Git history, the commit window or *Compare with Revision*, get a new viewer *SOPS: decrypted* in the diff viewer switcher. It decrypts both sides in the background and shows the plaintext side by side, read-only, with *(decrypted)* in the side titles and an open lock icon in its banner and, from IDE 2026.2 on, in the viewer switcher. The *All-in-One Diff* does not offer it. *Side-by-side viewer* still shows the ciphertext. If a side cannot be decrypted, the viewer says why and the other viewers keep working. New setting *Show decrypted content in diffs*, on by default.
- With *Automatically re-encrypt on save* turned off, *Encrypt with SOPS* (`Ctrl+Alt+E` or the editor tab menu) now saves the SOPS split editor: it encrypts the current plaintext and writes the file, like the automatic save. Previously that mode had no way to save.

### Fixed

- *New SOPS Encrypted File* wrote the starter template as plaintext first and encrypted it afterwards, so a failed encryption, for example without a matching `.sops.yaml` rule, left a plaintext file behind. The template is now encrypted first and the file is only created with the ciphertext. On failure no file is created, not even an empty one, and a notification shows the error.
- *Encrypt with SOPS* inside the split editor no longer tries to encrypt the in-memory decrypted pane as if it were a file on disk; it saves the SOPS file instead.
- After a failed or skipped save in the split editor, later saves are no longer blocked. This includes a file that could not be written, for example because it is read-only; a notification now says so and the changes stay in the editor.
- A failed save no longer offers *Try Again* in the editor banner. That action decrypted the file again and replaced the unsaved changes; a failed save now only shows a notification, and *Try Again* after a decrypt error leaves an editor with unsaved changes untouched.
- A `sops` call that hangs no longer blocks the plugin. Calls are stopped after two minutes, decryption in the diff viewer after one minute, and closing a decrypted diff or canceling its progress stops the running `sops` process.
- The log no longer contains the file path when retrying a failed decryption.
- Typing in the split editor while a save is still running no longer marks those changes as saved; the editor stays modified and saving them again stays possible.
- Saving again while a save is still running, for example with a second `Ctrl+S`, no longer starts a second `sops` run that could finish first and leave older ciphertext on disk. The newer text is saved right after the running save.
- An empty file encrypted in binary mode was taken for a SOPS JSON document, because `sops` leaves an empty payload unencrypted. It now opens as a binary SOPS file.
- If `sops` cannot be started, for example because the configured path is wrong, the log no longer contains the path of the executable; it records the error type and the system error code instead.

### Security

- No plaintext temporary files with `sops` 3.10 or newer. Saving and decrypting older revisions pass the text to `sops` through stdin with `--filename-override`, natively and in WSL. The plugin detects the `sops` version once per configured binary; older versions keep the previous temporary file for saving, and decrypting content that is not on disk writes only the ciphertext to a temporary file. If the version cannot be detected, the plugin tries stdin first and only falls back to the temporary file when `sops` refuses to read stdin.
- Updated the build toolchain to Gradle 9.8.1, Kotlin 2.4.21 and IntelliJ Platform Gradle Plugin 2.19.0, and raised Jackson and jsoup on the build classpath. Fixes the Dependabot alerts for build-tooling dependencies; none of them ship in the plugin.
- GitHub Actions in the build and release workflows are pinned to full commit SHAs, and the Gradle wrapper verifies the distribution checksum.
- Dependabot now proposes updates for GitHub Actions and Gradle dependencies weekly, with a 7-day cooldown after each release.

## [1.1.1] - 2026-06-02

### Fixed

- High CPU usage that could occur while a SOPS file was open. The split-view editor reported the on-disk ciphertext file from `getFile()` while showing the decrypted document, so the IDE's code-analysis daemon built a highlighting session for one file but ran its passes against the other editor and kept restarting itself in a loop. `getFile()` now returns the decrypted pane's file, so the daemon completes normally and the editor goes idle. Most noticeable on recent IntelliJ Platform builds (2026.1+).

## [1.1.0] - 2026-05-07

### Added

- Binary-mode SOPS files now open in the split-view editor. The `.sops` extension is recognised in addition to `.bin`, so files driven by a `.sops.yaml` rule like `path_regex: \.sops$` work out of the box. The decrypted plaintext is shown on the left, the JSON envelope on the right (always read-only for binary mode), and saving re-encrypts the file in place. The decrypted side picks its syntax highlighting from the inner extension, so `app.conf.sops` is highlighted as `.conf` while the encrypted side stays as plain text.
- The plugin now auto-detects the SOPS format from a file's content. The content is the source of truth for both whether a file is SOPS at all and which of the five supported formats it uses, so files with non-standard extensions (for example `secrets.config` containing per-key encrypted YAML, or `data.cfg` containing per-key encrypted JSON) are picked up automatically, and renamed or mislabeled files still decrypt with the right store. Filename-based matching is only consulted as a fallback when content has no SOPS markers, e.g. for plaintext files headed for the *Encrypt with SOPS* action. Resolves [#1](https://github.com/0skater0/sops-editor-plugin/issues/1).

### Changed

- New SOPS files created with the *Binary* format now default to a `.sops` extension (previously `.bin`) and start from an empty plaintext template. The new default lines up with the typical `.sops.yaml` rule `path_regex: \.sops$` so a fresh file is encrypted as binary out of the box.

### Removed

- The TOML format dropped out of the supported list. The `sops` CLI accepts `--input-type toml` but treats it internally as binary mode, so a `.toml` SOPS file produced by SOPS is in fact a JSON envelope and was never recognised by the previous TOML probe. Files with a `.toml` extension that contain SOPS data are now detected as binary mode, which matches what SOPS actually produces.

### Fixed

- Save (Ctrl+S) on a SOPS file no longer crashes with `Write-unsafe context!` on newer IntelliJ Platform builds. VFS writes and editor document updates are now scheduled through a non-modal write-action helper that sets up the correct modality, so the platform's reload-from-disk listener can run safely.
- Opening a SOPS file with CRLF line endings (typical when SOPS produces a JSON envelope on Windows) no longer fails with a `Wrong line separators` assertion. Content is normalised to LF before it reaches the editor document.
- Decrypting and re-encrypting SOPS files now passes `--input-type` and `--output-type` to the sops CLI based on the detected format, so files with non-standard extensions decrypt with the right store. Without these flags sops fell back to the binary store on unknown extensions and failed on per-key encrypted YAML/JSON.

### Security

- Added a `SECURITY.md` documenting the vulnerability reporting process and the triage policy for transitive dependency CVEs that do not apply to an IDE plugin (no network listener, no remote-supplied JSON parsing).
- The build workflow now sets explicit `permissions: contents: read` to follow least-privilege for the `GITHUB_TOKEN`.

## [1.0.0] - 2026-04-11

First public release.

### Features

- A split-view editor for SOPS-encrypted files with the plaintext on the left and the ciphertext on the right, kept in sync when you save.
- Automatic re-encryption on save. You can turn it off in settings.
- Support for `.env`, YAML, JSON, INI, TOML and binary files, matching what the `sops` CLI handles.
- Context-menu actions for Encrypt with SOPS, Decrypt with SOPS and New SOPS Encrypted File. The encrypt and decrypt actions ship with `Ctrl+Alt+E` and `Ctrl+Alt+D` shortcuts.
- Works through whatever keys `sops` is configured for, including age, PGP and the cloud KMS backends. If `sops` can decrypt the file from your terminal, so can the plugin.
- Auto-detection of the `sops` binary on `PATH`, with fallbacks for Chocolatey on Windows, Homebrew on macOS, and the common `/usr/local/bin` location on Linux.
- A setup validator in the settings dialog that runs `sops --version`, checks your age key file, and reports any missing pieces before you hit a real encryption error.
- A log file that masks AGE secret keys, PEM private key blocks and SSH public keys before writing. The log lives in the system temp directory by default, has owner-only permissions, and rotates at 5 MB.
- Filename validation for the New SOPS Encrypted File action that rejects path traversal attempts like `../evil.env` or absolute paths.

### IDE compatibility

The JetBrains Plugin Verifier passes against these IDE builds:

| IDE                      | 2024.2 | 2024.3 | 2025.1 | 2025.2 | 2025.3 | 2026.1 |
|--------------------------|--------|--------|--------|--------|--------|--------|
| IntelliJ IDEA Community  |   ✓    |   ✓    |   ✓    |   ✓    |        |        |
| IntelliJ IDEA Ultimate   |        |        |        |        |   ✓    |   ✓    |
| PhpStorm                 |   ✓    |        |        |        |   ✓    |   ✓    |

From 2025.3 onwards, JetBrains ships IDEA Community and Ultimate as a single distribution, so the Ultimate column covers both.

[1.1.1]: https://github.com/0skater0/sops-editor-plugin/releases/tag/v1.1.1
[1.1.0]: https://github.com/0skater0/sops-editor-plugin/releases/tag/v1.1.0
[1.0.0]: https://github.com/0skater0/sops-editor-plugin/releases/tag/v1.0.0
