# Privacy Policy

SOPS Editor does not collect, transmit, or store any personal data.

All operations — decryption, encryption, file reads, file writes — are performed
locally inside your JetBrains IDE by invoking the `sops` CLI that is already
installed on your machine. No telemetry, no analytics, no remote servers are
contacted by the plugin.

The plugin reads the following on your local system only:

- The `sops` binary at the configured path (or the one on your `PATH`)
- The age key file (default: `~/.config/sops/age/keys.txt`) or a configured PGP key
- The files you explicitly open, encrypt or decrypt
- The `SOPS_AGE_KEY_FILE` and `SOPS_AGE_KEY` environment variables

Plugin logs are written to the IDE log directory. Known secret material — AGE
private keys, PEM-encoded private key blocks and SSH public keys — is masked
before being logged.

If you have questions about privacy, please open an issue at
<https://github.com/0skater0/sops-editor-plugin/issues>.
