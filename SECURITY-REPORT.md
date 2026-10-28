# Camera Translator — Security Report

## Kaspersky OpenTIP Security Analysis

**Verdict: Clean**

The Camera Translator v1.0.2 Android application package was analyzed using Kaspersky OpenTIP.

### Tested APK

| Property | Value |
|---|---|
| Application | Camera Translator v1.0.2 |
| File name | `camera-translator-v1.0.2.apk` |
| File format | Android APK |
| File size | 110,340,090 bytes |
| SHA-256 | `3883484b38090dd9657fe45f04b5515416d4ff32233afd76011b266a9ca69482` |

The SHA-256 above matches both the Kaspersky OpenTIP report and the digest GitHub records for the APK attached to the [v1.0.2 release](https://github.com/crosswindfighter/camera-translator/releases/tag/v1.0.2).

### Scan Result

| Check | Result |
|---|---|
| Kaspersky verdict | Clean |

Detailed results (detections and behavior analysis) are available in the original report below.

### Original Kaspersky Report

**[View Camera Translator v1.0.2 Kaspersky OpenTIP Report](https://opentip.kaspersky.com/3883484B38090DD9657FE45F04B5515416D4FF32233AFD76011B266A9CA69482/results?tab=upload)**

Previous version: [v1.0.1 report](https://opentip.kaspersky.com/03591F4B4D2B92D7C7227316D645F9F592AAB089854983BC7009A6325EA93D36/results?tab=upload)

### Offline translation model

The optional OPUS translation model (downloaded separately inside the app from the [`offline-model-v1`](https://github.com/crosswindfighter/camera-translator/releases/tag/offline-model-v1) release) is not part of the APK scan. The app verifies every downloaded model file with a SHA-256 checksum before use and stores it in app-private storage.

### Scope and Limitations

This report documents the results displayed by Kaspersky OpenTIP for the specific APK identified above.

A clean result does not guarantee that the application is completely free of security vulnerabilities or malicious behavior. Dynamic analysis results do not prove that every possible application behavior has been tested.

This report is not a formal security certification, a complete source-code audit, or Google Play Protect approval.

### APK Verification

Users can verify the SHA-256 hash of their downloaded APK using Windows PowerShell:

```powershell
Get-FileHash ".\camera-translator-v1.0.2.apk" -Algorithm SHA256
```

The result must be `3883484B38090DD9657FE45F04B5515416D4FF32233AFD76011B266A9CA69482` (PowerShell prints it in upper case).

---

*Published for transparency. This report covers the v1.0.2 APK build and the security analysis results available at the time of testing.*

<!-- Security report maintained for transparency. -->
