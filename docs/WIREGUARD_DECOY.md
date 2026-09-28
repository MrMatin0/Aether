# WireGuard and WARP-in-WARP decoy packets

Aether can prepend a small number of decoy UDP packets to the WireGuard opening sequence. The decoys have the WireGuard initiation message type and the expected 148-byte length, but random contents and invalid MACs. A real WireGuard endpoint drops them before changing handshake state; they are intended only to change the opening shape seen by a passive classifier.

## How to enable it

The app enables this behaviour through the existing obfuscation presets:

- **Aggressive** enables the WireGuard decoy.
- **GFW** enables the WireGuard decoy.
- **Off**, **Light**, **Firewall**, and **Balanced** keep the decoy off.

The decoy is also available to headless/core launches with `AETHER_NOIZE=decoy`. For testing, `AETHER_WG_DECOY=1` forces it on and `AETHER_WG_DECOY=0` forces it off without changing the rest of the selected profile.

## WARP-in-WARP / gool

The outer WireGuard hop uses the same `AetherNoizeConfig`, so the decoy is sent on the device-to-network leg that DPI can observe. The inner hop is carried inside the outer tunnel and does not need to expose a second decoy on the local network.

## Deliberate limits

This is not the old MASQUE TLS Decoy. It does not inject a record into a TLS stream, does not claim to alter TCP sequence numbers, and does not send a valid authenticated WireGuard handshake. It is disabled by default in the conservative presets because extra packets can hurt networks that rate-limit UDP. Endpoint verification still waits for the real authenticated handshake and end-to-end data probe, so a decoy alone can never mark an endpoint as working.
