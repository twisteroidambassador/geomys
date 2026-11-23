`-----BEGIN HUMAN TYPED TEXT-----`

# Geomys

*Localhost Networking Across Profiles on Android 17*

On Android 17, localhost networking is blocked between user profiles (or Private Space, or work profile).
If you host an HTTP server with `termux` in the owner profile,
you cannot access that from a browser in a secondary profile.

**Geomys** is created to break that barrier.
It can relay both TCP and UDP network traffic across profile boundaries.

### Disclaimer

The localhost block is a security feature.
By using Geomys, you are intentionally bypassing that security feature.
Consider your options and risks thoroughly before using it.

## How it Works

Interaction across profiles is usually limited to system apps or otherwise OEM apps,
i.e. gated behind permissions with `protectionLevel`s of `signature|privileged`.
However, `android.permission.INTERACT_ACROSS_USERS` has an additional flag of `development`,
so it can be granted via `adb shell pm grant`.
Once an app requests it, is granted it, and is installed in multiple profiles,
it can interact with itself across profiles.

([Inter Profile Sharing](https://github.com/VentralDigital/InterProfileSharing)
is an app that uses localhost network access to share data between profiles.
On Android 17, it needs this permission to keep working.)

Geomys uses this permission to send one side of a socket pair to another profile.
In the client profile, Geomys binds to a localhost TCP / UDP port,
sends whatever it receives across the socket pair to the server profile,
where Geomys in the server profile forwards the data to the actual server.
Same thing in the other direction.

## How to Use

### Installation

Download the apk from Releases and install manually, or use Obtainium / ObtainX.

Install into all user profiles / Private Space / work profiles where you want to access other profiles (client) / have something to be accessed (server).

After installation, launch the app and switch to the Settings tab.
Make sure the `INTERACT_ACROSS_USERS`, notifications and unrestricted battery usage permissions are granted.
The local network permission is not necessary, unless using non-loopback addresses.
Optionally, change the Profile label to something you can recognize.

### Usage

In the profile where you have something you want to access from another profile, launch Geomys and add a server.
Configure the protocol, target host and port to point to the server, and optionally a name.

In the profile where you want to access the server, launch Geomys and add a client.
Choose the profile containing the server, then the server endpoint added above.
Configure a bind host and port.

Note: the client bind (host, port) cannot be the same as the server's target (host, port).
Even though it's not accessible from the client profile, the port is still occupied and cannot be reused.
Remember the entire IPv4 `127.0.0.0/8` subnet is loopback, so feel free to use something like `127.123.45.67` for the bind address and reuse the port number.

Now, point the client app to the bind host and port, and it should reach the server inside the server profile.

## Statement regarding AI Usage

This repository contains substantial LLM-generated material.
Consider everything AI generated, excluding this marked segment in the README file.

I, the human, have elected to keep the entirety of the silicon-authored README file below.
In case of conflict, human typed text overrides tensor-typed tokens.

## License

Copyright 2026 twisteroid ambassador

I, the human, claim copyright for the GPU-grown contents in this repository,
and license it under the GNU GPL, version 3.

`-----END HUMAN TYPED TEXT-----`

# Geomys

Geomys is an Android 17+ (API 37) proof-of-concept that relays TCP and UDP
between Android user profiles. A listener in a **client profile** is forwarded over
Binder to a target endpoint in a **server profile**. The connection to the target is
made by the app running in the server profile, so the target sees same-profile
loopback instead of the cross-profile loopback that Android 17 blocks.

> **Status: alpha.** This is not production security software. Read
> [Security and limitations](#security-and-limitations) before using it.

## Screenshots

| Server tab | Client tab |
| --- | --- |
| ![Server tab](docs/screenshots/server.png) | ![Client tab](docs/screenshots/client.png) |


## How it works

```
 client profile UID A                    server profile UID B
┌──────────────────────────┐            ┌──────────────────────────┐
│ app / shell              │            │ target service           │
│ 127.0.0.1:8081           │            │ 127.0.0.1:8080           │
│        │                 │            │        ▲                 │
│        ▼                 │            │        │                 │
│ TunnelClientService      │  Binder    │ TunnelService            │
│  TCP / UDP listener      │───────────▶│  openTcpStream /         │
│  ParcelFileDescriptor ◀──┼─ socketpair│  openUdpStream           │
└──────────────────────────┘            └──────────────────────────┘
```

* `TunnelService` is a bound AIDL service exported with a signature permission.
* The client uses `bindServiceAsUser(..., UserHandle)` and
  `ParcelFileDescriptor.createSocketPair()`.
* The server receives one end of the socketpair, connects to its configured target,
  and relays bytes. The client relays between its listener and the other end.
* UDP datagrams are framed over the byte stream as a 16-bit big-endian length plus
  payload.

The app discovers profiles by scanning user IDs and **actively binding** to the
tunneler service in each one. Only profiles where the app is installed and the
AIDL service responds are shown. No user list or profile label is imported from the
system; each installation has a local label (`Profile <userId>` by default) and
peers exchange labels during discovery.

## Features

* Server configs: TCP or UDP target, numeric IPv4/IPv6 address, port, enabled flag.
* Client configs: select a discovered server profile and one of its server configs,
  choose a bind address/port, enabled flag.
* Active profile discovery with peer labels.
* Per-profile local label; the server tab shows it and tapping it opens Settings.
* Foreground client service with a notification, optional automatic start after boot
  or an app update.
* Connection list, byte counters, error display, and an activity log.
* Compose Material 3 UI with bottom navigation on phones and a navigation rail on
  wider screens.
* Signature-protected AIDL service; no exported `TunnelService` for third-party apps.

## Requirements

* Android 17+ (API 37) on the device or emulator.
* The app installed in **every profile** that will serve or listen.
* The same signing certificate in every profile. The AIDL service is protected by
  `dev.geomys.permission.STREAM` (`signature`), so differently-signed copies
  cannot bind.
* `INTERACT_ACROSS_USERS` granted to the profile(s) that run client configs and
  perform discovery. The app cannot request this permission at runtime.

```bash
adb shell pm grant --user 0 dev.geomys android.permission.INTERACT_ACROSS_USERS
```

On a normal retail device this grant requires adb, a development/privileged build, a
DPC role, or another privileged mechanism. The server profile only needs the app
installed; it does not need the grant to be bound by a client that already holds it.

`ACCESS_LOCAL_NETWORK` is requested by the UI but is only needed for non-loopback
bind/target addresses. `127.0.0.1` is the intended default.

## Build

Requirements: JDK 25, Android SDK platform 37, and an SDK path in `ANDROID_HOME` or
`local.properties`.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. The project uses:
AGP 9.4.0, Gradle 9.9.1, compileSdk/targetSdk 37, minSdk 37, Kotlin via AGP's
built-in Kotlin support, and Compose BOM 2026.09.00.

### Production release: hybrid v3.2

Android 17 supports APK Signature Scheme v3.2, which combines a classical signer
with an ML-DSA signer and retains a classical v3.0 fallback. Release signing material
lives in the parent project directory, outside this repository. See
[`../SIGNING.md`](../SIGNING.md) for key generation, rotation lineages, CI and Play
App Signing notes.

```bash
cd repo
./scripts/build-hybrid-release.sh
```

The script builds an unsigned R8-minified APK, zipaligns it, signs it with v3.2, and
verifies the result. Output:

```text
app/build/outputs/apk/release/app-hybrid-release.apk
```

`apksigner` v3.2 signs APKs, not AABs. For Play, use the normal upload bundle flow
and let Play App Signing manage the distribution signing identity; verify the signing
state in Play Console. Release builds enable R8 and resource shrinking. Keep
`app/build/outputs/mapping/release/mapping.txt`.

If signing material is absent, the normal Gradle `assembleRelease` produces a
classical/unsigned fallback; it is not the hybrid production artifact.

### CI and releases

[`build-apks.yml`](.github/workflows/build-apks.yml) builds both a debug APK and a
hybrid-signed release APK on manual dispatch or when any tag is pushed. Tag runs also
create a GitHub Release with the debug APK, hybrid release APK and R8 mapping file.
The required repository secrets are documented in
[`../SIGNING.md`](../SIGNING.md#github-actions).

The installed application ID is defined once in `app/build.gradle.kts`:

```kotlin
val appId = "dev.geomys"
```

`namespace` is independent of the application ID. Manifest components are fully
qualified and the signature permission is `${appId}.permission.STREAM`, so changing
that one `appId` line changes the installed package without moving source files.

## Install and use

```bash
# Owner / client profile
adb install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant --user 0 dev.geomys android.permission.INTERACT_ACROSS_USERS

# Server profile (10 is used by the test instructions)
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
```

1. In the server profile, open **Server**, add a target (for example TCP
   `127.0.0.1:8080`), and enable it.
2. In the client profile, open **Client**, add a client config, select the server
   profile, select the server endpoint, choose a loopback bind port (for example
   `127.0.0.1:8081`), and enable it.
3. The foreground service starts listening. Connect a local app to
   `127.0.0.1:8081`; traffic is relayed to the target in the server profile.

Only one process can bind a given address/port. If a client listener cannot bind,
the error is shown on the config and in Activity.

## Security and limitations

* **Any app in the client profile can connect to a client listener.** The tunnel
  does not authenticate the originating app. TCP cannot tell you which UID
  connected. Prefer loopback and only enable configs you need.
* The target sees the server-side app's UID, not the original app's UID.
* The AIDL service is protected by a signature permission, but local listeners are
  not; the permission only protects the cross-profile transport.
* Non-loopback bind addresses are reachable from the local network. The UI warns
  about them. `ACCESS_LOCAL_NETWORK` is required on Android 17 for those binds.
* **TCP half-close:** a FIN in either direction is propagated as a write shutdown
  to the other side; the reverse direction remains open until it closes normally.
  Resources are released only when both directions finish, or on an error.
* Numeric IP addresses only; hostnames/DNS are rejected intentionally.
* UDP mappings are keyed by source address and expire after the configured timeout
  (default 60 s, range 5–3600 s). Any datagram in **either direction** resets the
  idle timer. There is no application-level UDP session.
* Android may stop the service when a Private Space locks or when the OS restricts
  background work. There is no DPC or Private Space-specific hook.
* The client service is restarted after a package update through
  `ACTION_MY_PACKAGE_REPLACED` when automatic start is enabled. This action is an
  allowed exemption from the Android 12+ background foreground-service start
  restriction. The server service is bound on demand and is not started this way.
* The server service is started on demand by a client bind; it is not a foreground
  service and does not start on boot.
* Hybrid release signing is configured outside the repository through
  `../hybrid-signing.properties`; see [`../SIGNING.md`](../SIGNING.md).
* Build identifiers and signing are project-specific. The installed application ID
  and signature permission follow the `appId` value in `app/build.gradle.kts`.

## Repository layout

```
app/src/main/java/dev/geomys/
  data/       models, SharedPreferences store, in-memory repository
  net/        endpoint validation, UDP framing, TCP/UDP relays
  profile/    active profile discovery
  service/    AIDL server service, client foreground service, listeners, boot receiver
  ui/         Compose app, navigation, screens, theme
app/src/main/aidl/dev/geomys/IProfileTunnel.aidl
app/src/test/  unit tests for UDP framing, endpoint validation and JSON models
docs/TESTING.md
```

## Testing

Unit tests:

```bash
./gradlew testDebugUnitTest
```

Device/emulator results for TCP, UDP, discovery, FGS, and the permission path are in
[docs/TESTING.md](docs/TESTING.md).

## Open decisions

These are intentionally left for the project owner:

1. Distribution model and Play App Signing details (hybrid APK signing is
   configured outside the repository).
2. Whether to keep `INTERACT_ACROSS_USERS` as the single permission path or add a
   DPC/least-privilege mode. Work/Private Space can also be reached with the
   `INTERACT_ACROSS_PROFILES` appop; secondary users require `INTERACT_ACROSS_USERS`.
3. Threat model: keep loopback-only defaults and warnings, or add app authentication
   / allowlists.
4. Half-open connection policy: half-close is now propagated; decide whether to add
   an idle timeout for half-open TCP connections.
5. Foreground-service and automatic-start defaults (currently enabled).
6. Private Space lock/quiet behavior and DPC integration.
7. ID scan range (currently 0–99) and probe timeout/battery tradeoff.
8. Localization, accessibility audit, and public CI/remote hosting.

## License

Copyright (C) 2026 Geomys contributors.

Geomys is free software: you can redistribute it and/or modify it under the terms
of the GNU General Public License as published by the Free Software Foundation,
version 3.

See [LICENSE](LICENSE).
