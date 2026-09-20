# Termux-Æther:API

[![Build](https://github.com/wallentx/termux-aether-api/actions/workflows/github_action_build.yml/badge.svg?branch=dev)](https://github.com/wallentx/termux-aether-api/actions/workflows/github_action_build.yml?query=branch%3Adev)

The Android companion for [Termux-Æther](https://github.com/wallentx/termux-aether).
It extends [Termux:API](https://github.com/termux/termux-api) with device capability
reporting, Shizuku-backed diagnostics, and an on-demand Arch Linux ARM virtual machine.
Development and validation focus on the **Pixel 11 Pro XL running Android 17**.

## What this fork adds

- **Device status from the shell.** `termux-capabilities` reports CPU/SIMD
  availability, Android permissions, battery, thermal throttling, storage access,
  and Shizuku status. Unsupported or denied readings stay explicit.
- **Authorized Shizuku integration.** Request access through a visible screen and
  read privileged thermal diagnostics. The tested setup uses ADB-started Shizuku;
  root is not required. Public capability reporting works without Shizuku.
- **A hardware-virtualized Arch workspace.** `Æ` opens an interactive shell;
  `æ command` runs an inline command. AVF runs an ARM64 Linux kernel with a
  persistent writable root filesystem, authenticated SSH/vsock sessions, and
  outbound IPv4 networking. This is a VM, not a PRoot environment.
- **Resources used on demand.** The last session normally shuts Arch down cleanly
  and releases its RAM. `--keep-memory` suspends it instead. Set RAM at launch,
  adjust the live balloon target, grow the sparse disk, and share selected Termux
  project directories without replacing the guest installation.
- **A modern Android baseline.** Target SDK 37, compile SDK 37.2, and an ARM64
  default build. Existing Termux:API methods remain in the codebase; individual
  legacy APIs still need permission and lifecycle testing on Android 17.

## Components

| Component | Role |
| --- | --- |
| This APK | Android APIs, authorization UI, thermal diagnostics, and AVF control |
| [Termux-Æther](https://github.com/wallentx/termux-aether) | Terminal, Pacman environment, and independent native glibc runtime |
| [CLI companion](https://github.com/wallentx/termux-api-package/tree/wallentx/capabilities) | `termux-capabilities`, `termux-shizuku`, `termux-arch`, `Æ`, `æ`, and related wrappers |
| Shizuku | Separately installed and started authorization service for privileged operations |
| [Arch guest artifacts](https://github.com/wallentx/termux-aether-api/actions/workflows/arch-guest.yml) | Separately staged kernel, root filesystem, and network helper |

Installing the API APK alone does not install Shizuku, the CLI wrappers, or Arch.
The terminal's native Aether/glibc runtime does not need this companion.

## Setup

1. Install [Termux-Æther](https://github.com/wallentx/termux-aether), then the APK
   from a successful [API Build run](https://github.com/wallentx/termux-aether-api/actions/workflows/github_action_build.yml?query=branch%3Adev+event%3Apush).
   Use matching signing certificates. These builds retain `com.termux.api` and
   the shared Termux identity, using the public debug test key.
2. Install the matching [CLI wrappers](https://github.com/wallentx/termux-api-package/tree/wallentx/capabilities).
   Open the API app once, then run `termux-capabilities --json` inside Termux.
3. For privileged diagnostics, install/start Shizuku and follow
   [the authorization guide](docs/SHIZUKU.md).
4. For Arch, follow [guest staging and setup](docs/VIRTUALIZATION.md).
   The tested Android preview needs the Shizuku-backed AVF bridge; ordinary app
   permissions alone are not sufficient. Preview framework changes can affect support.

After the guest and wrappers are set up:

```sh
Æ                              # Open Arch; release RAM when the last session exits
æ uname -a                     # Run a command and return its exit status
Æ --keep-memory                # Suspend after use, retaining RAM and processes
termux-arch-vm --grow-disk 16G   # Grow a stopped guest's disk; preserve its files
termux-arch-vm --memory-live 6G  # Request a live balloon target within its RAM ceiling
```

Project sharing uses Termux `rclone` and guest `sshfs`; an active share keeps the
VM in use. See the [CLI sharing guide](https://github.com/wallentx/termux-api-package/tree/wallentx/capabilities#live-project-sharing).
Live memory adjustment is manual, not automatic pressure-based sizing.

## Tested capabilities and limits

Pixel tests cover persistent guest data, shell/inline commands, session lifetime,
suspension/resume, network recovery, shared-file read/write access, 6-to-16 GiB disk
growth, and reclaiming/restoring 2 GiB from an 8 GiB guest without restarting it.
These are functionality checks, not proof of a speed advantage over PRoot.
[Terminal performance measurements](https://github.com/wallentx/termux-aether/blob/dev/docs/PERFORMANCE.md)
belong to the main app.

The guest currently uses a minimal init rather than a normal systemd environment.
Networking supports outbound IPv4 TCP/UDP; IPv6, raw ICMP, GPU/audio passthrough,
and protected guest execution are not provided by this setup. Shared directories
are trusted workspaces with SFTP/SSHFS semantics, not a hostile-code sandbox.
Keep build trees that require native Unix filesystem behavior on the guest disk.

## Documentation and upstream

- [Capability report](docs/CAPABILITIES.md)
- [Shizuku and thermal diagnostics](docs/SHIZUKU.md)
- [Virtualization, resources, setup, and validation](docs/VIRTUALIZATION.md)
- [Original upstream README](README.upstream.md)

This is an independent Termux:API fork. Report its issues
[here](https://github.com/wallentx/termux-aether-api/issues).
Licensed under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html); upstream attribution is retained.
