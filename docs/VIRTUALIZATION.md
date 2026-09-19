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

## Writable Arch workspace (v2)

The current launcher uses `/data/local/tmp/termux-arch-v2`, separately from the
read-only v1 boot proof below. Stage the new guest artifact with `stage.py`, install
Python and OpenSSH in Termux, and run `termux-arch --shell` (or `Æ`). The first
launch creates a device-local Ed25519 client key in `~/.config/termux/arch-vm`.
Only its public key crosses the API. The guest creates its own host key on first
boot; the CLI pins the public key obtained through the owned VM's console and
refuses subsequent identity changes. CI tests use a disposable disk copy, so the
published image contains neither test keys nor test files.

The complete ext4 root filesystem is writable, including `/root`, packages, and
configuration. The kernel replays the ext4 journal after an unexpected power loss;
this is not a substitute for backups or offline filesystem repair. Normal stop
terminates guest processes, syncs and remounts root read-only, then powers off.
A static shutdown helper runs as PID 1 from `/run` (tmpfs), releasing Bash,
libraries and startup-script inodes even if a package update replaced them.
A stop timeout leaves the guest running and reports an error. Unexpected Shizuku
process death or Android shutdown is still a guest power loss. Stop the guest
before replacing its APK or staged files. Staging refuses to overwrite an existing
v2 installation; image upgrades must preserve its data explicitly.

SSH runs only on guest loopback. A guest relay accepts only host-CID vsock traffic
on port 2222. The Shizuku service creates the VM through the platform AVF Binder
interface and requests sockets from that owned handle. This respects SELinux's
prohibition on shell-created raw vsock sockets. The private framework surface is
version-dependent and fails explicitly when unavailable; the normal app preflight
still does not bypass its restrictions. The service holds an exclusive disk-owner
lock before writing configuration or attaching storage. It binds a random Android loopback port and relays
at most eight sessions to the fixed port of its owned guest. Other Android apps
can reach that loopback listener but cannot authenticate without the private key.
Password login and SSH forwarding are disabled. Guest `avf0` is a TAP interface
carried over owned AVF vsock port 2223 to a userspace IPv4 backend. The Android
17 preview advertises native networking, but its bundled crosvm rejects AVF's
`--net` argument before boot. Native networking therefore remains disabled.
The fallback uses `gvisor-tap-vsock` v0.8.9, cross-built for Android arm64 in CI;
the artifact includes dependency license notices. Guest `dhcpcd` acquires
192.168.127.2/24, gateway 192.168.127.1 and DNS from this private backend.
The DHCP hook writes a readable runtime resolver file without requiring systemd.
DHCP retries asynchronously, so an offline phone can still open local SSH.
On first boot, `pacman-key --init` and `--populate archlinuxarm` initialize package
signing trust on the device. No generated private key is published in the image.
The host supplies its current Unix time as a numeric boot parameter, which init
applies before key generation and HTTPS. The original AVF guest had no working
wall clock and created files dated 1970. This is boot-time synchronization, not
a continuous time service across long host suspend periods.
The backend supports outbound IPv4 TCP and UDP, including LAN destinations.
It uses Android sockets with Shizuku's shell identity. It opens no Android
listening port and installs no host port forwards. Guest packets addressed to
loopback, unspecified or link-local IPv4 are rejected. IPv6, raw ICMP and
multicast discovery are not supplied. This is a userspace packet bridge, not
direct Wi-Fi access or a claim of native-NIC performance.

`network_enabled` reports configuration; `network_bridge_state=connected`
reports transport setup, not internet reachability. `network_connectivity`
remains `not_probed` until independently tested. A missing helper or failed
network connection leaves local SSH available and reports `network_error`. The
initial shell is guest root; it does not grant Android root or expose host paths.
The bridge uses the standard SSH protocol for PTY resize, interrupts, binary I/O,
separate stdout/stderr and exit codes. No guest command is executed by Android's
shell. The API continues to accept only fixed lifecycle operations and an
Ed25519 public key and optional RAM size, never arbitrary host paths or commands.

`æ command args...` executes an argument vector. `termux-arch --cwd /root -- command
args...` selects an explicit guest working directory. Commands launched from
Termux home default to guest `/root`; commands from other host directories require
`--cwd` because project sharing is not implemented. A nonexistent guest directory
fails before command execution. `Æ` opens a login shell in guest home. Exiting a
shell releases its session lease. The last session exits by cleanly shutting down
Arch and releasing its RAM by default. `Æ --keep-memory` or
`æ --keep-memory command args...` instead suspends the guest after the last session,
preserving its RAM and processes for the next invocation. Background guest jobs
stop with default shutdown; they freeze during suspension. The next ordinary
invocation resumes and returns to default shutdown on exit. Overlapping sessions
keep the guest running until all exit; any `--keep-memory` in that busy period
selects suspension. `termux-arch --start` explicitly starts a manually managed VM;
`termux-arch-vm --stop` always requests a clean shutdown. A later managed `Æ`/`æ`
session takes over idle cleanup.

Leases renew every 15 seconds and expire after 60 seconds if a launcher dies;
live SSH connections also prevent idle cleanup. Shutdown/suspend failures are
reported, never converted to forced shutdown. Status includes `active_sessions`,
`ssh_connections`, `idle_policy`, `suspended` and `idle_error`. Suspension pauses
the host network helper too; existing remote TCP connections can time out while
paused and applications may need to reconnect. Failed commands are
never automatically retried, since they may have changed files already.

RAM is a launch setting, independent of the APK and guest disk:

```sh
termux-arch-vm --stop
termux-arch-vm --start --memory 8G
# Or start and open a shell: termux-arch --memory 8G --shell
```

`--memory` accepts GiB (`8G`, `1.5GiB`) or MiB (`8192M`, `8192`). The value
must resolve to positive whole MiB and cannot exceed host physical RAM. The
initial default is 8 GiB. After a successful boot, the owner atomically saves
the choice to its private `/data/local/tmp/termux-arch-v2/memory-mib` file.
Later launches through `Æ`, `æ`, or either CLI reuse that value, including
after an owner-service restart. A failed boot does not replace the saved choice.
Changing RAM while the VM is running returns an error without stopping the VM
or executing a guest command; stop it cleanly first. Repeating its current size
reuses the existing VM. `--start --memory` waits for readiness and exits nonzero
if launch fails; the legacy lifecycle commands without `--memory` return JSON
whose `status` must be checked separately from transport exit status.

Status reports `memory_mib` for the current/last launch and
`next_start_memory_mib` for the saved/default choice. Guest usable memory is
slightly smaller because the kernel reserves some RAM. This changes the ceiling
at boot; automatic memory ballooning is not enabled. No APK reinstall is needed
for subsequent RAM changes, and the guest disk and installed software are retained.

CI validates the root image under QEMU with a management NIC, checks SSH,
PTY allocation, binary stdin, separate output/exit code and persistence across
a clean reboot. It also exercises Landlock enforcement and a sandboxed Pacman
download against a local test repository. A separate TAP-to-backend path tests
the production packet protocol, DHCP, DNS, TCP and UDP; SSH replaces vsock only
as its CI transport. Only the CI flag exposes SSH on Ethernet (port 2222);
production SSH remains loopback-only. Actual AVF vsock access, Android socket
policy and internet reachability require Pixel testing. No PRoot speedup is claimed.

### Guest defaults and their costs

| Default | Reason / requirement to change |
| --- | --- |
| Host-matched vCPUs, initially 8 GiB RAM, 6 GiB disk | Exposes the host CPU topology for parallel workloads. Geekbench 7 ARM preview triggered guest OOM kills with the former 4 GiB ceiling. Change the RAM ceiling with `--memory` at launch; disk growth and memory ballooning are separate changes. Android still schedules VM threads alongside other apps. |
| Landlock enabled | Pacman 7's filesystem sandbox requires kernel enforcement; disabling the sandbox is not the fix. |
| Userspace IPv4 bridge; native NIC disabled | Preview crosvm rejects native networking. TCP/UDP use host sockets; IPv6, raw ICMP and multicast are unavailable. Adds a host helper and packet-copy overhead. |
| No Android directory sharing | Requires an explicit host/guest sharing mechanism and selected paths. VIRTIO_FS is not enabled in the current kernel. |
| DRM, audio, WLAN, Bluetooth, modules disabled | Smaller fixed kernel; enabling guest drivers alone cannot provide host virtual devices or passthrough. Modules need matching installed files on each kernel upgrade. |
| Minimal Bash PID 1 | Fast shell workspace; normal systemd service management is unavailable. |
| Password SSH, forwarding, tunnels disabled | Only the device's generated key opens guest sessions. No new guest-to-host forwarding capability. |
| Non-protected VM | Custom Arch uses host-managed storage and communication. Protected execution and nested virtualization have not been validated. |

SVE, seccomp, namespaces, cgroups, overlayfs and FUSE are compiled in. Guest CPU
exposure and actual SIMD dispatch must be checked independently; a kernel flag
does not prove acceleration. There is no automatic VM start or restart.

### Updating an existing guest without replacing its disk

The CI artifact includes `guest-update.tar`, `arch-network-host` and dependency
license notices alongside `Image` and SHA256SUMS.
Verify checksums first. Keep a backup and save work before stopping the VM.

1. Extract the update payload into a temporary guest directory over authenticated
   SSH. **Rename** the existing `/usr/local/sbin/termux-vm-init` into a unique
   backup directory on the same filesystem before installing the new script.
   Do not merely copy it and overwrite/unlink the original: Bash PID 1 retains
   the old inode, and an open unlinked inode can prevent ext4 remount-read-only.
   Retain that backup until after a successful shutdown.
2. Install `termux-vm-network`, `termux-vm-shutdown` and `termux-vsock-net` under `/usr/local/sbin`,
   `termux-landlock-check` under `/usr/local/bin`, and `termux-dhcp-hook` under
   `/usr/local/libexec`, all mode 0755. For the original image's dangling
   `/etc/resolv.conf -> /run/systemd/resolve/resolv.conf` link, preserve a backup
   and replace it with `/run/termux-network/resolv.conf`. Preserve custom DNS
   configuration instead of overwriting it blindly.
3. Run `termux-arch-vm --stop` and verify `running=false` and
   `clean_shutdown=true`. Only then replace the host-side kernel with the
   verified `Image`, retaining the old kernel. Stage `arch-network-host` at
   `/data/local/tmp/termux-arch-v2/arch-network-host`, owned by shell UID 2000,
   mode 0700. Install any API APK update while stopped. Never replace
   `arch-rootfs.img` or reset SSH keys.
4. Start the VM and run `guest/arch/network_test.py` from native Termux. It checks
   real Landlock enforcement, address/DNS, HTTPS, sandboxed repository download
   and SSH binding. Its temporary Pacman DB does not update installed packages
   or the production package database. It leaves the VM running.

If a stop fails, do not replace the APK or disk. The service deliberately leaves
the VM alive. Recovery that powers off a still-writable guest requires explicit
approval and an offline disk backup/check before further use.

### Pixel validation: September 19, 2026

Guest CI run `35418699483` passed TAP DHCP/DNS, TCP/UDP, Landlock enforcement,
sandboxed Pacman downloads, SSH persistence and clean shutdown. API CI run
`35419089131` built commit `6e622cb`. The Pixel 11 Pro XL then passed the real
AVF/vsock path: `avf0` received 192.168.127.2/24, DNS and HTTPS worked, and Pacman
synchronized repositories into a temporary database with its production sandbox
settings. Separate TCP and UDP echo tests reached a LAN fixture on the development
phone. The guest clock differed from Android by 2.1 seconds; Landlock ABI 7
allowed the permitted write and denied the forbidden write.

A clean stop removed the network helper. Restart acquired networking again,
returned HTTPS 200, and preserved the saved marker and SSH identity. SSH readiness
after that restart was 1618 ms; this is not an internet-readiness measurement or
a PRoot comparison. An initial Pixel test exposed buffered Android process stdin
stalling small DHCP packets even though the direct-pipe CI test passed. Explicit
flushing in the Android transport fixed the issue and the device tests were repeated.
Detailed results are in `~/reports/termux-app/pixel11-arch-network-20260919.json`
on the development phone. Re-run `guest/arch/network_test.py` from native Pixel
Termux to validate the installed guest without upgrading packages.


## Historical read-only boot milestone (v1)

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
storage, plus the compressed artifact and a temporary sparse disk file (up to
6 GiB) on the host. ADB compresses the disk transfer over Wi-Fi. Installation and
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

### Pixel acceptance result - 2026-09-18

API APK `41f45a4` passed 17 Java tests and was installed on the Pixel. CLI commit
`0fbae82` passed 17 Python tests. Guest CI run `35405911121` built and boot-checked
the image at `30b95be`; the published SHA256 checksums were verified before staging.
No build ran on either phone.

The real Termux app runtime (UID 10445, `untrusted_app` SELinux domain) successfully
booted fresh Arch Linux ARM with Pacman 7.1.0 and Linux 6.18.52-termux-avf. Android's
VM list recorded the named instance with requester UID 2000, and the owned console
identified crosvm using `/dev/kvm`. There was no QEMU execution on the Pixel.

| Check | Result |
| --- | --- |
| First boot readiness | 3,477 ms; CID 2051 |
| Repeated start | Reused CID 2051 and the same readiness timestamp |
| Clean stop | Guest shutdown marker, process exit 0 |
| Restart readiness | 6,605 ms; CID 2052 |
| Owner process killed | CID 2052 disappeared; all other entries matched the immediate pre-kill snapshot |
| Fresh boot after owner death | 1,953 ms; CID 2053; ownership lock released correctly |
| Final cleanup | Clean exit 0; no owned VM or VM service remained |

These are three functional smoke-test observations from the service's launch-to-
readiness timer, not a statistically controlled benchmark or end-to-end `æ`
latency. The repeated-start CLI round trip was about 897 ms, including API
transport. No PRoot speed comparison has been made. Preserve the distinction when
adding the lower-overhead guest command channel.

### Headless native Termux testing

A debuggable Termux installation can receive a same-UID intent through its
installed `termux-am`. TermuxService then launches the test as a real app task:

```sh
adb -s IP:PORT shell run-as com.termux /system/bin/sh \
  /data/data/com.termux/files/usr/bin/am startservice --user 0 \
  -n com.termux/.app.TermuxService -a com.termux.service_execute \
  -d com.termux.file:///data/data/com.termux/files/usr/bin/python \
  --esa com.termux.execute.arguments /data/data/com.termux/files/home/REPORT_DIR/device_test.py,/data/data/com.termux/files/home/REPORT_DIR \
  --ez com.termux.execute.background true
```

Stage the script and create a unique report directory first; this example assumes
paths without commas. Verify the resulting process's UID, SELinux context **and
parent process**, not merely the success of `am startservice`. The tested child
had UID 10445, `u:r:untrusted_app:s0:...`, and Termux's app process as its parent.
Directly executing the Python test under `adb shell` or `run-as` is not equivalent.
The service remains non-exported and no external-app execution setting or broad
permission grant is needed. This command testing route needs no keyboard input or
screen unlock; visual UI tests still need the display.

### Writable workspace acceptance - 2026-09-19

Pixel API APK `26447e4`, guest `15baca1` (CI run `35412136070`), and CLI `54a0d9e`
passed the native Termux workspace harness at UID 10445. Android's VM list named
`termux-arch-v2` with shell requester UID 2000. The installed Android 17 framework
uses a different device-assignment configuration type from AOSP main, so the
adapter uses its own `VirtualMachineConfig` builder and `toVsRawConfig` conversion
instead of manually constructing a guessed raw schema. The owned AVF Binder
handle supplies each vsock connection; no raw host vsock socket is created.

Validated: ext4 mounted writable, unrelated SSH key rejected, exact arguments
including empty/newline/dollar values, 130000-byte binary stdin, separate stdout
and stderr, exit 37, explicit guest working directory, missing-directory failure,
`æ` inline execution, `Æ` login shell, PTY resize from 31x93 to 42x107 and Ctrl-C.
All first-run commands reused CID 2054. After clean shutdown, CID 2055 retained the
same file contents and SSH host key. Both stops confirmed the guest remounted
root read-only and the VM stopped. The final Android list contains no owned VM.

Readiness observations were 8989 ms initially and 5846 ms on restart. They are not
PRoot comparisons. Guest networking, Android project sharing, resource tuning,
and controlled performance benchmarking remain separate work.

`guest/arch/workspace_test.py` runs these checks from the real Termux runtime.
It writes `~/arch-workspace-test-20260919/report.json` and a PTY transcript, creates
a test marker in `/root/termux-acceptance-20260919`, and leaves the VM stopped.
Save work and exit active guest sessions before running it; shutdown terminates
guest processes. Device credentials and project data are not reset or replaced.

### Resource resizing without a new APK

`termux-arch-vm --grow-disk 16G` increases the existing sparse image capacity,
boots it, grows ext4, and cleanly shuts down after the resize session. Existing
files and SSH identity are preserved. The VM must already be stopped because
AVF must reopen the block image at its new size. Shrinking is rejected. Sparse
capacity consumes Android storage as guest blocks are written; it does not reserve
all 16 GiB immediately. The host can still run out of real storage. If ext4 growth
is interrupted, retry the same size; do not replace the image.

`termux-arch-vm --memory-live 6G` requests a balloon target on a running guest
whose launch ceiling is at least 6 GiB. `--memory-live 8G` restores an 8 GiB guest's
full ceiling. Status exposes `memory_balloon_enabled` and `memory_balloon_bytes`;
requests fail explicitly if this host disables balloon control. Reclamation is
asynchronous and not proof of exact resident memory. This is manual live sizing;
automatic pressure-based resizing is not enabled. Avoid shrinking below the active
workload's needs. Suspend/shutdown still follow the session policy.
