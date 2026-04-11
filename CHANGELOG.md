# Changelog

All notable changes to this plugin go here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.0.0]: https://github.com/0skater0/sops-editor-plugin/releases/tag/v1.0.0
