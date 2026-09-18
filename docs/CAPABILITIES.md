# Pixel device-status bridge

Run `termux-capabilities --json` from native Termux using the matching
`wallentx/termux-api-package` command. The APK adds the `Capabilities` method to the
existing dispatcher. JSON is also the default without `--json`. The CLI imposes a
15-second deadline; exit 124 means the companion app did not respond in time.
An exit of zero means a report was returned, not that every capability is usable.

## Scope and contract

Schema version 1 returns one snapshot with a Unix-millisecond timestamp. Sections
fail independently; an unavailable thermal service does not remove battery or CPU
data. Status values are `ok`, `partial`, `denied`, `unavailable`, `unsupported`, and
`unknown`. Permission entries additionally use `granted` and `not_requested`.
Missing measurements are JSON null, not zero or false.

| Section | Reports | Interpretation |
| --- | --- | --- |
| `device` | Model, Android SDK, ABI list, API app version/target and UID | Describes the Android API process |
| `cpu` | ARM64 HWCAP/HWCAP2, SIMD flags, SVE/SME vector lengths, page size | Availability and current worker-thread vector lengths; not acceleration or maximum vector width |
| `permissions` | Selected effective UID grants and API-app declarations | Shared-UID grants may originate in Termux; a grant alone does not prove an operation will work |
| `battery` | Percentage, battery temperature in Celsius, charging status and plugged bitmask | Uses Android's battery broadcast, not restricted sysfs; temperature is not a CPU/GPU reading |
| `thermal` | Throttling severity, power saver, current thermal headroom | Headroom 1.0 is the severe-throttling threshold; not a percentage of remaining capacity |
| `shizuku` | Manager visibility, live Binder connection, authorization and authorized service UID | No Binder means unavailable; it does not prove the manager is absent or the service stopped |
| `storage` | Current all-files-access state | Does not enumerate content URI grants |
| `transport` | Existing request protocol and authorization boundary | No network listener or new arbitrary-command endpoint |

The snapshot is read-only and does not prompt for permissions, start a VM, change
settings, call privileged Shizuku operations, or contact remote hosts. It excludes
serial numbers, IMEI, accounts and credentials. Shizuku's provider acquires its
Binder normally; this command only inspects connection/permission state. There is
no authorization UI or privileged adapter in this slice.

Avoid rapid polling. Android may return NaN for thermal headroom when unsupported
or sampled too frequently; JSON reports `unavailable` and null in that case.
See [PowerManager](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int))
and the [Shizuku API guide](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md).

## Identity, signing and transport decision

Keep `com.termux.api`, the existing `com.termux` shared UID, and the matching
upstream debug test certificate for this incremental build. The installed Termux
fork uses that same certificate. The receiver remains non-exported; the optional
local-socket request path checks peer UID. An ordinary differently signed app
cannot join the shared UID. The Shizuku provider uses its documented
`INTERACT_ACROSS_USERS_FULL` permission gate.

The debug certificate is public, so this is compatibility, not a private fork
signing identity. Moving both apps to a private signing key and separate UIDs
requires a coordinated app/CLI migration and backup plan. Do not change one app's
key, package name, shared UID, or receiver visibility independently. AVF/vsock,
versioned authenticated streaming and guest access remain future work.

Both apps now default to target SDK 37. Installing an SDK-28 API companion beside
the SDK-37 app would undermine the intended shared-UID runtime baseline. This
fork compiles against 37.2 and uses the app fork's AGP/Gradle versions. The new
native probe defaults to ARM64 in one APK; `-PtermuxBuildProfile=all` retains the
other ABIs (their ARM64 decoder reports unsupported). No optional SIMD instruction
is used by the probe. Existing API operations still require individual API-37
permission and lifecycle validation; this slice does not claim that migration is
complete for camera, microphone, location, notifications or persistent services.

## Build and validate

Builds and Java tests run in GitHub Actions, not on the local Termux host. CI runs
`:app:testDebugUnitTest :app:assembleDebug`; model tests cover missing capability
data, high feature bits, vector-query errors, battery sentinels and error isolation.
The matching CLI has shell syntax and Python subprocess tests.

On the Pixel, install the CI APK with `adb install -r` after comparing its signing
certificate with Termux. Open Termux:API once, then invoke the CLI from the actual
Termux app. Confirm:

1. The output parses as JSON and reports schema 1, the expected UID and target 37.
2. CPU flags agree with the native Termux validator; vector lengths may differ by thread.
3. Battery temperature is a number when supplied by Android; missing values remain null.
4. Shizuku works as an optional observation: unavailable/denied states do not fail unrelated sections.
5. An unrelated app UID cannot access the non-exported receiver or same-UID socket.

The existing all-files-access flow and storage picker APIs remain separate from
capability discovery. No legacy API workflows or user data are migrated by this command.
