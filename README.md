# APK Factory 2.6.2

Permanent-repository / compact-upload build. See `APK-FACTORY-v2.6.2-REUSE-RATE-LIMIT-FIX.txt`.

# APK Factory 2.6.1 — Green Reliability Build

APK Factory turns an Android project ZIP into a temporary GitHub repository, dispatches a dedicated GitHub Actions build, downloads the APK artifact, saves it to `Downloads/APK Factory`, and exposes an Install APK button.

## Why 2.6.1 exists
v2.6 improved artifact retrieval, rotation, keyboard layout, and branch/SHA handling, but its actual build worker still lived in `MainActivity`. 2.6.1 moves the complete job into the foreground service and persists the GitHub recovery identifiers needed to reconnect after process recreation.

## Reliability behavior
- Foreground service owns upload, workflow dispatch, polling, artifact retrieval, and APK save.
- `START_STICKY` recovery resumes from persisted repository, branch, commit SHA, workflow ID, and run ID.
- A per-job repository marker recovers the narrow case where GitHub created the repo immediately before Android killed the process.
- Tracking is locked to the dedicated workflow + `workflow_dispatch` + exact commit SHA.
- Artifact download is locked to that exact run. No repository-wide artifact guessing.
- App waits up to 45 minutes for a workflow whose own timeout is 40 minutes.
- Artifact publication/download gets a separate 5-minute propagation window.
- Completion/failure notification survives foreground-service teardown so tapping it returns to the result.
- Android 15 foreground-service timeout callback is handled.
- Rotation and soft-keyboard layout remain protected.
- GitHub token is AES-GCM encrypted with Android Keystore.

## Install identity
This repair uses application ID `com.apkfactory.v261` and label **APK Factory 2.6.1** so it can be installed beside an older APK Factory even if that older APK was signed by a different ephemeral GitHub debug certificate.

Keep the older APK Factory until 2.6.1 has successfully built and retrieved at least one real APK on the phone.
