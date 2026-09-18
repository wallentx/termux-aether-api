# AVF preflight and the Arch workspace

`termux-virtualization --status` (optional `--json`) reports AVF readiness from
the Termux:API app UID. `termux-capabilities` includes the same `virtualization`
section. The command never grants permissions, bypasses hidden API restrictions,
creates disks, starts/stops VMs, or lists another application's VMs.

The report separates the Android feature flag, visible APEX, effective app
permissions, framework-manager access/capability bits, and availability of the
custom-image API. A visible feature/APEX is not proof that the app can start a VM.
Missing or restricted methods are reported with their exception class rather
than interpreted as lack of hardware support. Unknown capability bits stay raw;
unknown queries produce null booleans, not false hardware claims.

`readiness.can_attempt_custom_vm` requires the feature, both permissions,
non-protected VM support, and the custom-image methods. Even with status `ok`,
`guest_boot` and `arch_compatibility` remain `not_tested`; `backend_running` is null.
This probe checks API surface availability, not successful method invocation for
every future VM operation. CLI exit 0 means JSON was delivered; inspect `status`
and `readiness.blockers`. The CLI transport has a 20-second deadline (exit 124).

The APK declares `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE` for
future explicit development setup. The probe does not grant them. On the tested
Pixel, ADB lists these as development permissions; no assumption is made about
another device or future build. The existing shared UID means effective grants
can apply to other packages in that UID. Separate app permissions from shell or
Shizuku results: `adb shell vm info` succeeding does not prove this APK has access.

## Arch integration target

The user's current `æ` wrapper on the source Termux device executes:

```sh
arch --termux-ids --wd "$PWD" -- "$@"
```

That `arch` symlink targets a PRoot launcher with rootfs at
`/data/data/com.termux/files/archlinux`. It binds Termux home/prefix and selected
Android paths, emulates UID/GID, and passes command arguments without flattening
them into a shell string. The Pixel currently does not have this launcher/rootfs
at those paths. `Æ` is the user's interactive entry point; its exact definition
has not yet been located. Neither entry point is modified by this feature.

The intended replacement is a persistent ARM64 Arch guest with two launch modes:

1. `Æ`: start the VM if stopped, then attach an interactive PTY.
2. `æ command args...`: reuse the running guest and execute an argument vector,
   preserving stdout/stderr, exit status, signals, and working-directory mapping.

The guest needs its own compatible kernel/initramfs, bootable filesystem, guest
user/ownership setup, and an authenticated host-to-guest transport. An existing
PRoot directory is not a bootable VM image. PRoot hard-link emulation and its
Android-specific bindings must be accounted for during a separately validated
migration. Keep the original installation until the VM passes acceptance tests.

Map explicitly shared projects to guest paths; do not silently fall back to a
different working directory. A VM cannot reuse the launcher's Android directory
bindings verbatim. Keep dependency caches and build trees on the guest filesystem
when shared-filesystem overhead would distort results. Preserve quoted/empty
arguments and PTY resize/interrupt behavior. Hardware boot must be confirmed;
software CPU emulation must never be presented as AVF performance.

## Performance acceptance

On the same Pixel with equivalent Arch userspace, compare:

1. Cold VM boot through interactive-shell readiness.
2. Warm `æ true` and a small inline command, repeated across several samples.
3. Process/file-heavy workloads (Python imports, dependency scanning, Git status)
   and a CPU-heavy workload, with the same data and package versions.
4. Project-directory access, quoting, exit codes, Ctrl-C, PTY resize and reconnect.

Removing PRoot interception may help syscall-heavy work; CPU-only workloads may
benefit much less. Cold boot can cost more than starting PRoot. Reusing a live VM
is therefore central to low-latency inline commands. These are hypotheses to
measure, not promised speedups. VM residency also consumes RAM/battery.

Build any native probe/guest artifacts in CI, not on either phone. Measure under
actual Termux/guest execution, with thermal observations and explicit backend
identity. A successful capability query is not a boot or performance result.

References: [AOSP AVF overview](https://source.android.com/docs/core/virtualization),
[Podroid AVF setup and implementation](https://extv.github.io/Podroid/guide/backends.html).
