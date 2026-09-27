# PASS / PortOne Android regression tests

Run from the Android Gradle root (`ShareGuardAndroid/` inside the repository):

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug
```

On this Windows PC, JDK 17 and the Korean workspace path require the Gradle daemon's
argument-file encoding to match the Windows launcher encoding. Otherwise the test
worker reports `ClassNotFoundException` even though the Kotlin test class exists:

```powershell
$env:JAVA_HOME = 'C:\Users\dollq\.jdks\jbr-17.0.14'
.\gradlew.bat --no-daemon '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=MS949' testDebugUnitTest assembleDebug
```

This is a per-command workaround; do not change the project's UTF-8 source encoding
or move/delete the shared workspace. See [Gradle issue 30391](https://github.com/gradle/gradle/issues/30391).

`MfaPortOneContractTest` covers the HTTP(S) redirect requirement, Inicis PASS options,
success/error callback addresses, and rejection of unrelated or spoofed addresses.
It does not perform an actual PASS authentication or update the database.

The HTTPS address `https://shareguard.invalid/mfa-complete` is a native-only sentinel.
The WebView intercepts it before any network request, following the pattern in
[PortOne's mobile SDK](https://github.com/portone-io/portone_flutter/blob/main/lib/v2/portone_identity_verification.dart).
No DNS, website, Spring callback endpoint, or new API key is required for that address.
The resulting verification ID is still checked by the backend with PortOne; the
redirect alone never grants verified status.
