# Security Policy

## Reporting a Vulnerability

If you discover a security issue in this plugin, please report it privately by opening a [security advisory](https://github.com/0skater0/sops-editor-plugin/security/advisories/new) on GitHub. Do not open a public issue for security reports.

I aim to acknowledge reports within five working days and to publish a fix in the next release where practical.

## Supported Versions

Only the latest published release receives security updates. Older versions are not patched. Please upgrade through the JetBrains Marketplace or the [Releases](https://github.com/0skater0/sops-editor-plugin/releases) page.

## Dependency Triage Policy

This plugin runs inside a JetBrains IDE and uses the IntelliJ Platform Gradle plugin to pull in the IDE distribution at build time. Many transitive dependencies are dragged in through that bundle. Dependabot scans them and sometimes flags CVEs that do not apply to an IDE plugin context.

Before patching or pinning a transitive dependency, the relevant question is whether the plugin actually exposes the affected attack surface. This plugin does not:

- start an HTTP server, open a listening socket or accept incoming network requests
- handle multipart or form-data requests
- deserialise byte streams that originate from a remote attacker
- invoke non-blocking / async stream parsers on untrusted input

The plugin reads SOPS-encrypted files from the user's project tree, runs string-based pattern matching to detect SOPS markers, and shells out to the system `sops` CLI for cryptographic operations. The IntelliJ Platform itself parses YAML, JSON and other formats for syntax highlighting in the editor, but that exposure belongs to the IDE and is patched by JetBrains in regular IDE updates rather than by this plugin.

When a Dependabot alert describes an attack vector that requires one of the missing capabilities (network DoS, server-side multipart parsing, async-parser exhaustion via attacker-controlled streams), the alert is dismissed with the reason `Risk tolerated` and a comment pointing back to this section.

Alerts that could affect the plugin's own code paths (for example, vulnerabilities in libraries that the plugin loads at runtime to read SOPS-encrypted files) are taken seriously and patched even when no upstream fix is available yet.

## What This Plugin Does With Secrets

The plugin only invokes the system `sops` binary as a subprocess. It does not implement its own decryption, does not call any cloud KMS API directly, and does not transmit secret material over the network. Plaintext is held in editor memory only while a SOPS file is open, and in the decrypted diff viewer only while that diff is open. The plugin does not cache it and does not write it to disk, unless you run *Decrypt with SOPS*, which replaces the file with its plaintext on purpose.

With `sops` 3.10 or newer, plaintext is passed to `sops` through stdin and read back from stdout, with `--filename-override` naming the target file so the `.sops.yaml` creation rules still match. No plaintext temporary files are written, on Windows, macOS, Linux and in WSL alike. The plugin detects the version once per configured binary with `sops --version` (with the online update check turned off) and caches the result until the setting changes. If the output contains no version number, the plugin checks with `sops help encrypt` whether the `encrypt` subcommand exists. If it does, the first call goes through stdin, and only when `sops` refuses that call (exit code 100, `no file specified` or an unknown flag error) does the plugin switch to the temporary file route described below. If the subcommand does not exist, the temporary file route is used right away, because an older `sops` would treat the word `encrypt` as a file name. Either decision is cached like a detected version. If `sops` cannot be started at all, nothing is cached and no temporary file is written. New SOPS files are created only after their template has been encrypted, so a failed encryption leaves no plaintext file behind.

With an older `sops`, or one that refuses stdin, saving falls back to a short-lived plaintext temporary file that is deleted right after the `sops` call. It is restricted to owner read/write on POSIX file systems. On Windows it inherits the SOPS file's parent directory ACL, so storing SOPS files in user-owned locations keeps the owner-only restriction. For files stored in WSL it is created next to the SOPS file inside the WSL project directory, so `.sops.yaml` creation rules match, and it gets the distribution's default permissions for new files (typically `0644`, so not owner-only). Decrypting content that is not on disk, such as older revisions in the decrypted diff viewer, writes only the ciphertext to a temporary file in that case, never the plaintext.

Plugin logs mask AGE secret keys, PEM-encoded private key blocks and SSH public keys before writing. The log lives in the system temp directory by default, with owner-only permissions, and rotates at 5 MB.
