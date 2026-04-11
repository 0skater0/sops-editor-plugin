<!-- Thanks for contributing. Please fill out the checklist below so we can review quickly. -->

## Summary

<!-- What does this PR change and why? One paragraph is fine. -->

## Checklist

- [ ] Code builds locally (`./gradlew buildPlugin`)
- [ ] Unit tests pass (`./gradlew test`)
- [ ] New user-facing strings go through `SopsBundle.message(...)` with matching entries in both `SopsBundle.properties` and `SopsBundle_de.properties`
- [ ] `CHANGELOG.md` updated under `## [Unreleased]`
- [ ] No secrets, decrypted content or full command lines added to log calls
- [ ] Plugin verifier is clean (`./gradlew verifyPlugin`)

## Breaking changes

<!-- Does this change affect existing users? Settings migration, action shortcuts,
     persistent state format, etc. If no breaking changes, leave "None". -->

None.
