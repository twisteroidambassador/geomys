# Testing

## Unit tests

```bash
./gradlew testDebugUnitTest
```

Covered:

* UDP `uint16` length framing, zero-length and maximum datagrams, truncated input,
  oversized frames.
* Numeric IPv4/IPv6 endpoint validation, hostname rejection, port range.
* JSON round-trip and defaults for server/client/profile models.

## Device test evidence

Tested on the Android 17 emulator (`Medium_Phone`, KVM) with users:

| User | Role |
| --- | --- |
| 0 | owner/client |
| 10 | secondary/server |
| 11 | work profile, app not installed |
| 12 | Private Space, app not installed |

The app was installed in users 0 and 10. `INTERACT_ACROSS_USERS` was granted with:

```bash
adb shell pm grant --user 0 dev.geomys android.permission.INTERACT_ACROSS_USERS
adb shell pm grant --user 10 dev.geomys android.permission.INTERACT_ACROSS_USERS
```

### Discovery

Opening **Client → Add client** scanned IDs and probed the service. The UI showed
exactly the responding profiles:

* `Profile 0` (`owner`)
* `Profile 10` (`secondary`)

Users 11 and 12 did not have the app installed and did not appear. This confirms that
discovery does not rely on `getUserProfiles()` alone and does not import user names.

### TCP relay

Server config in user 10: `TCP 127.0.0.1:8080`. Client config in user 0:
`TCP 127.0.0.1:8081`, server profile 10.

A target echo process was started in user 10 under the app UID:

```bash
adb shell "run-as dev.geomys --user 10 \
  sh -c 'exec toybox nc -l -s 127.0.0.1 -p 8080 /system/bin/cat'"
```

The client foreground service was active and listened on 8081. The test command:

```bash
adb shell '(printf "hello\n"; sleep 3) | toybox nc -w 5 127.0.0.1 8081'
```

Output:

```
hello
```

Half-close is now propagated. The test above keeps stdin open for clarity; the
half-close section below tests the case where the client finishes sending and still
needs the response. The target sees a FIN when the client finishes sending, while
the reverse direction remains open for the response.

### UDP relay

Server config in user 10: `UDP 127.0.0.1:8082`. Client config in user 0:
`UDP 127.0.0.1:8083`, timeout 60 s.

Target:

```bash
adb shell "run-as dev.geomys --user 10 \
  sh -c 'exec toybox nc -u -l -s 127.0.0.1 -p 8082 /system/bin/cat'"
```

Test:

```bash
adb shell '(printf "udp-hello"; sleep 3) | toybox nc -u -w 5 127.0.0.1 8083'
```

Output:

```
udp-hello
```

This exercises the UDP length-prefix framing, client source mapping, server UDP
socket, and response path. The idle timeout is **bidirectional**: both a client
datagram and a server/target datagram update the mapping's last-activity time.

### Foreground service / permission

The client service was observed as a foreground service (`isForeground=true`,
`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) while the UI was in the background. Binding
to `TunnelService` in user 10 succeeded only when the client held
`INTERACT_ACROSS_USERS`; the signature-protected AIDL service rejected a
differently-signed/differently-permissioned client in earlier POC tests.

## TCP half-close check

To verify that the FIN crosses both relays, use a target that only responds after
it sees EOF:

```bash
# files/respond.sh in user 10
cat >/dev/null
printf 'after-eof\n'

adb shell "run-as dev.geomys --user 10 \
  sh -c 'exec toybox nc -l -s 127.0.0.1 -p 8080 sh /data/user/10/dev.geomys/files/respond.sh'"

adb shell 'printf "request\n" | toybox nc -q 5 127.0.0.1 8081'
```

Observed output:

```
after-eof
```

The target only writes after its input reaches EOF, which requires the client relay
to propagate the shutdown through the socketpair and the server relay to call
`shutdownOutput()` on the target socket. The client keeps its read side open with
`nc -q 5`, so the response can return. Before the half-close change, the same test
returned nothing because the relay closed both directions at the first EOF.

## App update restart

The receiver listens for both `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. With an
enabled client config and automatic start enabled, replacing the APK (for example
`adb install -r`) starts `TunnelClientService` again without opening the app.

The emulator run:

1. Wrote an enabled client config to `shared_prefs/geomys.xml`.
2. Launched the app once to clear the stopped state.
3. Replaced the APK with `adb install -r`.
4. Observed `TunnelClientService` running as a foreground service and the TCP
   listener bound after the update.

`MY_PACKAGE_REPLACED` is listed by Android as an exemption from the background
foreground-service start restriction; see the Android Developers
"Restrictions on starting a foreground service from the background" page.

## Reproducing

The test configs were pre-populated through `run-as` to avoid long UI automation,
but the same values can be entered through the app's Server and Client screens. The
`run-as` syntax on Android 17 is:

```bash
adb shell "run-as dev.geomys --user 10 sh -c '...'"
```

(`--user` comes after the package name.)
