# Publishing to Maven Central

`com.ayinbox:ayinbox-kotlin` publishes to Maven Central through the Central Portal (https://central.sonatype.com). Maven Central has no OIDC trusted publishing, so the release workflow uses a Portal user token and a GPG key kept as secrets in a GitHub environment. Nothing secret is committed.

## One-time setup

1. **Central Portal account.** Sign in at https://central.sonatype.com with the AY Inbox team account.
2. **Verify the `com.ayinbox` namespace.** In the Portal, open Namespaces, add `com.ayinbox`, and copy the verification key. Add it as a DNS TXT record on `ayinbox.com` (the value is the key the Portal shows), then press Verify. This namespace is shared with `ayinbox-java`: if it is already verified, skip this step.
3. **User token.** In the Portal, open Account, then Generate User Token. Keep the username and password it shows.
4. **GPG signing key.**
   ```sh
   gpg --quick-generate-key "AY Inbox <team@ayinbox.com>" rsa4096 sign 2y
   gpg --list-secret-keys --keyid-format SHORT          # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   gpg --armor --export-secret-keys <KEYID> > signing-key.asc
   ```
   The same key can sign the Java SDK.
5. **GitHub environment.** In `ayinbox/ayinbox-kotlin`, Settings, Environments, create `maven-central` (optionally limit it to `v*` tags and add required reviewers) with these secrets:
   - `MAVEN_CENTRAL_USERNAME`: token username from step 3
   - `MAVEN_CENTRAL_PASSWORD`: token password from step 3
   - `SIGNING_KEY`: the full contents of `signing-key.asc`
   - `SIGNING_KEY_PASSWORD`: the key passphrase

   Then delete `signing-key.asc` from disk.

## Releasing

1. Set `version` in `build.gradle.kts` and `AyInbox.VERSION` to the new version, and add it to `CHANGELOG.md`.
2. Commit, then tag and push:
   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```
3. `.github/workflows/release.yml` checks the tag matches the version, runs the tests, signs and uploads to the Central Portal, and releases the deployment. It shows on Maven Central within about 30 minutes.
4. Update the README install section: replace "Not on Maven Central yet" with the Gradle and Maven coordinates.
