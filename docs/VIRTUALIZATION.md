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

The user selected a **fresh Arch guest first**, with selected files/configuration
migrated afterward. Do not convert or replace the current PRoot installation as
part of the initial boot experiment.

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

## Pixel preflight result - 2026-09-18

API build `cbdff84` passed 17 Java tests; CLI build `aef67db` passed 13 tests.
The installed command completed through the real Termux session (API UID 10445)
and returned `denied`, with manager capability bits **3**: both protected and
non-protected VMs are available. The manager query itself succeeded.

Both app VM permissions are ungranted. The custom-image probe returned
`NoSuchMethodException`; targeted app logcat identified Android's hidden-API
policy denying reflection of `VirtualMachineCustomImageConfig.Builder.setKernelPath`
for target SDK 37. This is a framework access restriction, not evidence that the
Pixel lacks custom-VM hardware support. Permission grants alone do not establish
that this blocked method becomes usable.

ADB's platform `vm info` confirms both VM types and `kvm.arm-protected`.
`/apex/com.android.virt/bin/vm run --help` exposes a custom JSON-config launcher,
CPU topology, memory, console and network options. A bounded Shizuku-backed
launcher around that platform tool is the next candidate to validate, keeping
image/config paths under app control and exposing no arbitrary shell command.
Help output is not proof that an Arch guest boots. No VM was created or changed
during this probe, and neither VM permission was granted.

## Experimental Shizuku Arch boot

`termux-arch-vm --start`, `--status` (default), and `--stop` operate only the fixed
`termux-arch-v1` guest. `termux-virtualization --status` remains the app-UID preflight.
The first milestone boots a fresh Arch Linux ARM root filesystem with a separately
built Linux 6.18.52 kernel. It intentionally uses a **read-only disk, one vCPU,
1 GiB RAM, no networking and no guest login/command execution**. Arch's `pacman
--version`, kernel identity and root mount appear in `console_tail`; only the
guest's readiness marker changes `guest_boot` to `verified`. `backend` names the
selected launcher even before boot; it is not alone proof of successful AVF.

The long-lived Shizuku user service runs as shell UID 2000 and checks the caller
UID on every method. It accepts no caller-supplied paths, configuration, shell
commands or VM IDs. A fixed native helper holds an exclusive file lock, sets
`PR_SET_PDEATHSIG`, and execs Android's platform `vm run` with a generated config.
The thread that creates this child remains alive for its lifetime. Repeated start
requests reuse the same VM; app command completion detaches without stopping it.
Explicit stop first sends a fixed shutdown token, then may kill only that owned
process after a deadline. Forced shutdown is safe for this **read-only** milestone;
that policy must change before enabling a writable guest. Service death/update
stops its VM. No automatic restart is performed, and other Android VMs are never
stopped or modified. The shell staging area is accessible to other shell/root
clients; it is not protection against a compromised Shizuku/ADB session.

Build `Arch AVF guest` in CI. It fetches Arch's official HTTPS mirror, checks the
pinned upstream MD5 snapshot and records SHA256 provenance; kernel source is
SHA256-pinned. It boots the image under QEMU in CI solely as a compatibility test.
Pixel execution exclusively uses AVF. CI publishes `Image`, compressed ext4 disk,
source metadata, kernel config, console evidence and SHA256 checksums. No native
build or image construction runs on either phone.

Download a successful artifact from the trusted fork's CI, then stage once:

```sh
python guest/arch/stage.py --serial IP:PORT /path/to/artifact
# Inside the actual Pixel Termux session:
termux-arch-vm --start
termux-arch-vm --status
termux-arch-vm --stop
```

Staging verifies checksums before transfer, uses a new private shell-owned
directory, and refuses to replace an existing guest. It needs about 6 GiB of Pixel
storage plus the downloaded compressed artifact on the host. Installation and
checksums are separate from ongoing VM lifecycle operations. A rolling upstream
tarball change intentionally fails the pinned snapshot check until reviewed.

Device acceptance: verify actual Arch readiness, repeated-start reuse, clean
stop, restart, and cleanup after killing the owned service. Until these pass,
boot support remains experimental. Writable storage, authenticated guest command
transport, project sharing, `Æ`/`æ` wiring and PRoot comparison remain follow-ups.

Implementation references: [AOSP JSON config schema](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/vmconfig/src/lib.rs),
[AOSP platform VM runner](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/vm/src/run.rs),
[Arch Linux ARM generic image](https://archlinuxarm.org/platforms/armv8/generic).


After replacing the APK while Termux stays alive, Shizuku may report
`binder_not_connected` despite a running server. On this Pixel, opening the
Termux:API main activity once delivered the binder without restarting Shizuku or
changing permissions. The shared UID can remain active across the API process
replacement, so waiting alone does not always trigger delivery. Return to Termux
and repeat the status command; do not interpret this transport state as lack of
AVF hardware support.

`guest/arch/device_test.py REPORT_DIRECTORY` is an ADB-coordinated integration
harness to launch from a real Termux session after installing the APK and CLI.
It records the UID/SELinux domain and waits up to 40 minutes for `image-ready`
in that directory. Create that marker only after verified image staging. It
checks guest readiness, repeated-start reuse, graceful stop and restart, then
waits up to 3 minutes for `owner-killed` after the test operator kills only the
owned Shizuku VM service. It finally verifies the replacement service reports
stopped. The operator must also confirm the owned VM disappeared from Android's
VM list; restarting a service alone does not prove guest cleanup. Results and
console output are retained in `report.json`.
