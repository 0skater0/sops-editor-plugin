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

The plugin only invokes the system `sops` binary as a subprocess. It does not implement its own decryption, does not call any cloud KMS API directly, and does not transmit secret material over the network. Plaintext is held in editor memory only while a SOPS file is open and is not persisted to disk by the plugin itself. Temporary files used during encrypt/decrypt round-trips are restricted to owner read/write on POSIX file systems and deleted after use. On Windows, save-time tempfiles inherit the SOPS file's parent directory ACL (so storing SOPS files in user-owned locations keeps the owner-only restriction); transient tempfiles created during decryption use the per-user system temp directory.

Plugin logs mask AGE secret keys, PEM-encoded private key blocks and SSH public keys before writing. The log lives in the system temp directory by default, with owner-only permissions, and rotates at 5 MB.
