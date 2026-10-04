# WireGuard experiments

Two independent, default-OFF experiments. Neither guarantees a DPI bypass or changes the WireGuard handshake format, key exchange or peer authentication.

## Android usage

Disconnect, select **WireGuard** explicitly, then open **Settings > Advanced > Transport > WireGuard experiments**. Toggle either setting and reconnect. Both switches are locked while connection settings are locked.

The values persist in device-local `wg_experiments` SharedPreferences, initialized by `AetherApp`. They are deliberately separate from saved setups, profile export/import and profile reset. Switch both OFF in this section to reset the experiments. `AetherProcess` snapshots them once per child launch and sends explicit `0`/`1` values. Non-WireGuard launches receive zeroes. Smart Auto choosing WireGuard can use previously enabled experiments; use explicit WireGuard for controlled A/B tests.

## Port hopping

During the existing handshake retries, the client attempts to rebind to a new source port, retaining the scanner-selected destination. Preparation is deadline-bounded and failure leaves the current socket intact.

For live sessions, every 15 seconds the client prepares a new UDP socket. For known WARP ingress IPs it also rotates destination ports among 2408, 500, 1701 and 4500; other endpoints keep the destination port. `ArcSwap<SocketPath>` publishes socket, peer and `DetourGuard` together. Owned Arc snapshots keep the detour alive through in-flight operations. A watch notification wakes any receive waiting on the retired socket.

A published candidate must return authenticated data/keepalive during its trial or the retained previous path is restored. Ordinary traffic and existing health probes exercise the candidate. A conservative rollback can occur on slow links or while idle. Packet loss during a hop is possible. All data, handshake replies, timer output and health probes resolve the current transport. Teardown aborts the hop task along with the tunnel tasks.

### Android protect() equivalent

The Rust engine is a subprocess under the application UID. `TunFactory.applyAppFilter` excludes that UID in OFF/EXCLUDE mode and omits it from INCLUDE mode. This covers newly created sockets too. There is no per-fd JNI `VpnService.protect(fd)` bridge for Rust subprocess descriptors. Every replacement uses the same egress/socket-mark/upstream factory as the initial socket; the existing application-UID exclusion is preserved. A different embedding that routes its engine UID into the VPN must supply a real socket-protection/FD-transfer hook before using this factory.

## Data padding

Zero bytes are appended **before** `boringtun::Tunn::encapsulate`, inside the authenticated ciphertext. IPv4 Total Length, IPv6 Payload Length, checksums and payload bytes remain unchanged. The peer is expected to trim the decrypted data to the IP-declared length. An in-process two-peer boringtun test pins the IPv4 and IPv6 trimming contract.

The plaintext is aligned to 16 bytes and gains a random 1 to 8 extra blocks, capped at 1200 bytes total. Newly padded packets therefore fit within 1280 bytes including outer IPv6, UDP and WireGuard overhead. Packets with no padding budget remain unchanged; this does not fix pre-existing fragmentation from an oversized MTU. Empty keepalives, handshakes, malformed inputs and IPv6 jumbograms are not padded. Scanner dataplane verification and health probes use the same option as ordinary data. Noize remains independent.

## Native usage

```sh
AETHER_WG_PORT_HOP=1 AETHER_WG_DATA_PADDING=1 ./aether --wg
```

Only the exact value `1` enables an experiment; missing values and `0` mean OFF.

## Validation status

No local Rust/Kotlin/Gradle compiler was available in the editing environment, and that environment has no internet access for installing dependencies. No local compile, native-test execution, APK build or live filtered-network test was performed. The existing PR Build workflow compiles native libraries/APKs and runs JVM tests; check its result before installing. An additional Rust-test workflow was not added because the connected credentials lack workflow-write permission.

Added tests cover padding boundaries, IPv4/IPv6 encrypted round-trip trimming, destination-port restrictions, receiver wakeup on socket replacement, default-OFF behavior, independent toggles and transport gating. They are source additions, not a claim that tests have passed.

**Before merge:** ArcSwap is pinned exactly to 1.7.1, but the checked-in Cargo.lock has not been regenerated. Ordinary cargo builds resolve the new dependency; a fresh `--locked` build needs the updated lockfile. Run the following on a development machine, review and commit the resulting lock, and inspect all test results:

```sh
cd native/aether/aether
cargo check
cargo test --lib wireguard:: -- --test-threads=1
cd ../../..
gradle :app:testReleaseUnitTest
```

## Device matrix

Keep endpoint, Noize, MTU and network fixed. Disconnect/reconnect between OFF/OFF, ON/OFF, OFF/ON and ON/ON. Record handshake success, verified data, time to connect, throughput and packet loss. Test IPv4/IPv6, VPN/proxy mode, SOCKS upstream, several hops, failed-hop rollback, network interruption and disconnect during hop preparation. Check OFF/INCLUDE/EXCLUDE split tunneling, settings persistence after process death, protocol switching and socket/relay cleanup after disconnect.

Padding cannot help a filter that drops the initial handshake or blocks the endpoint IP outright. This PR is experimental and not release-validated.
