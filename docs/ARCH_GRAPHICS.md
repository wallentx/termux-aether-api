# Experimental Arch Gfxstream launch mode

The Arch VM can opt into AVF's Gfxstream virtual GPU for one boot. Normal
`Æ`/`æ` launches remain headless. This is a prerequisite for DroidDeck's PowerVR
backend, not a claim that Steam or on-screen presentation is integrated.

## Prerequisites

- A host with working AVF Gfxstream. The Pixel 11 Pro XL has passed the
  [DroidDeck diskless GPU probe](https://github.com/wallentx/DroidDeck/tree/wallentx/powervr-avf/tools/avf).
- A separately staged, private shell-owned `Image-gfxstream` beside the existing
  `/data/local/tmp/termux-arch-v2/Image`. It must contain built-in virtio-gpu DRM
  support. Staging must verify the CI artifact and hold the existing POSIX
  `owner.lock`, and must not overwrite the normal `Image` or the writable disk.
- `vulkan-gfxstream` and `vulkan-tools` in the guest for Vulkan validation.

No APK permission or caller authorization rule changes. Only the already
authorized Termux UID can request this mode through the Shizuku VM service.

## Launch

First close the guest's sessions and verify `termux-arch-vm --status` reports
`running=false` and `clean_shutdown=true`. Check that the updated API reports
`graphics_configurable=true`; an older API may ignore unknown intent extras.

```sh
"$PREFIX/libexec/termux-api" Virtualization \
  --es operation arch-start --es graphics_mode gfxstream
```

The request returns startup status. Wait for `status=ready` and verify
`graphics_mode=gfxstream` before invoking `Æ` or `æ`. An existing SSH seed is
reused; run a normal `Æ` session first if the guest has never been initialized.
Ordinary clients attaching to this running VM retain its graphics mode.

Inside the guest:

```sh
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/gfxstream_vk_icd.json vulkaninfo --summary
```

Require the hardware-backed Gfxstream device rather than a CPU renderer. Device
enumeration alone is not a rendered-frame or game-compatibility test.

The mode is not saved across a stop. The next ordinary launch uses `Image` and
headless configuration. An explicit `graphics_mode=none` request is also
available. Switching either direction while a VM is active or suspended returns
`graphics_change_requires_stop` without stopping it.

## Reporting and limits

`graphics_mode` reports the active configuration; `graphics_rendering` remains
`not_probed`. Status never blocks on a guest GPU or Vulkan query. A missing
graphics kernel returns `graphics_kernel_missing`; malformed modes are rejected.
The graphics kernel is not a fallback for the normal kernel, or vice versa.

The GPU receives a placeholder 1280x720 display, Vulkan and composer contexts,
and the renderer feature flags used by the tested Android Gfxstream path.
Android Surface attachment, interactive input and audio routing still need an
explicit client integration. Neither enabling this mode nor installing Mesa
alone constitutes a complete graphical session.
