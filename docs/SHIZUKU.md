# Shizuku access and thermal diagnostics

This slice adds an explicit authorization screen and one privileged operation:
reading `dumpsys thermalservice`. It does not expose an arbitrary shell, change
settings, or request root. The existing `termux-capabilities` command stays read-only.

```sh
termux-shizuku --status
termux-shizuku --request-permission
termux-shizuku --thermal
```

All commands emit JSON (optional `--json`). `--status` is the default and never
requests permission. `--request-permission` opens a foreground screen and asks
Shizuku for access only because that operation was explicitly selected. It returns
`pending`, not `granted`; run `--status` after reviewing the dialog. If Android
blocks the launch from a background session, open Termux:API's menu > Shizuku access.
The screen also offers Request access and Open Shizuku buttons.

Install and start the [official Shizuku app](https://shizuku.rikka.app/download/)
first. The thermal operation requires Shizuku 13+ and Android 8+. ADB-started
Shizuku needs to be started again after a reboot. Revoking authorization or stopping
Shizuku makes privileged calls fail explicitly; it does not break the public
battery, thermal-state, or CPU-capability observations.

## Response and interpretation

Thermal reports contain `schema_version`, `operation`, `status`, a timestamp,
`service_uid`, `temperatures`, and `other_readings`. ADB mode should report service
UID 2000; an independently root-started Shizuku reports UID 0. Each Celsius sensor
has its name/type and throttling severity. Readings are supplied by the device's
thermal HAL, not independently calibrated measurements.

Only the **current HAL section** is parsed. Cached/event temperatures elsewhere in
the dump may be old and are deliberately excluded. Unknown sensor types and
battery-current-limit values go into `other_readings` without a Celsius label.
NaN/infinite measurements and invalid severity values remain null. Missing or
changed dump formats yield `unavailable` or `partial`, not fabricated readings.
No temperature thresholds are changed.

The helper command has a 20-second deadline (exit 124). Backend responses use
`ok`, `partial`, `denied`, `unavailable`, `unsupported`, `busy`, `pending`, or
`timeout`. A transport exit of zero means JSON was returned; inspect its status
before using it as data.

## Privilege and lifecycle boundary

The CLI reaches the existing same-UID API transport. The app checks live Shizuku
authorization before binding a non-daemon UserService. Its AIDL exposes only
`readThermal()` and Shizuku's reserved destroy method: no command strings,
arguments, filenames, or settings are accepted. The service additionally checks
that Binder callers have the API app's UID.

The only subprocess is the fixed argv `/system/bin/dumpsys -t 5 thermalservice`.
Output is capped at 64 KiB. Subprocess wait is bounded to six seconds, output
completion to one second, service connection to three seconds, and the outer
Binder read to eight seconds. A concurrent read returns `busy`. Cleanup kills
the child and removes the UserService after success, error, or timeout. Revoked
permission, Binder death and service restart return explicit failure states.

The permission UI handles Binder arrival/death and removes listeners on destroy.
It does not silently request authorization after a reboot. The current shared
UID/debug-signing compatibility boundary is documented in [CAPABILITIES.md](CAPABILITIES.md).

## Validation

Java tests run in CI. Parser fixtures distinguish stale cached temperatures from
current HAL data and cover unknown types, NaN, format changes and numeric overflow.
CLI tests cover explicit operations, invalid arguments, quoting and deadline status.
On the Pixel, validate unavailable, denied, granted and service-restart states;
confirm that thermal snapshots report the expected privileged UID and that no
`termux_thermal` process remains after a request.

References: [Shizuku UserService guide](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md),
[Android thermal types](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/thermal/aidl/android/hardware/thermal/TemperatureType.aidl).
