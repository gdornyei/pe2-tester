# PE2 Tester – unofficial Android app for the Pockethernet 2

> **Unofficial project.** This app is not made, endorsed or supported by the manufacturer of the Pockethernet.
> "Pockethernet" is a trademark of its respective owner and is used here only to describe which device the app works with.
> If you represent the manufacturer and object to anything in this repository, please open an issue and it will be addressed or taken down promptly.

An open-source Android app for the **Pockethernet 2** network tester. It talks to the device over Bluetooth LE and gives you:

- **One-button quick test** with profiles:
  - **Automatic** – a quick wiremap decides what to run (adapter found → cable test, switch port → network test, open/short → fault distance)
  - **Cable** – wiremap + length / fault distance (TDR)
  - **Network port** – PoE, link, DHCP, LLDP/CDP, VLAN, ping, internet
  - **Full** – everything in sequence
- **Projects** (client, building) with any number of port tests (outlet, switch, port), auto-incrementing labels, re-test with history and "changes since previous test", photos per test.
- **Pass/fail limits**: UTP/STP, crossover / 2-pair allowed, max length, minimum link speed, PoE class, DHCP/ping/internet required.
- **Active switch port detection** – a switch's isolation transformers show up as a short on every pair; this is reported as information, not as a failure.
- **PDF test report** (summary, port map, optional page per port with wiremap diagram and TDR graph), **CSV** export, share as text.
- **Tools**: adapter finder (beeps/vibrates when the wiremap adapter is on the far end), cable toner, port blinker, TDR graph + crosstalk, NVP calibration with a cable of known length.
- **Network discovery** through the Pockethernet's WiFi bridge: IP, hostname, MAC, NetBIOS, mDNS, UPnP, open ports, HTTP title – exportable to CSV.
- Optional **Google Drive sync** (projects, photos, reports) to your own Drive.

## Install

Every push to `main` is built by GitHub Actions and published under
**[Releases → latest](../../releases/tag/latest)** as `pe2-tester.apk`.
Download it on the phone and allow "Install unknown apps" for your browser once.

Requirements: Android 8.0+ (WiFi bridge / network discovery needs Android 10+), Bluetooth LE. No pairing is needed – the app finds the device by scanning.

## Building

```
gradle :app:testDebugUnitTest :app:assembleRelease
```

Kotlin 2.0, Jetpack Compose, AGP 8.5, Gradle 8.9, JDK 17.

**Signing.** Without signing secrets the release APK is signed with the Android debug key (fine for forks and local builds).
CI signs with a stable key when these repository secrets exist: `SIGNING_KEYSTORE_B64` (base64 of a `.jks`),
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. APKs signed with different keys cannot be installed over each other.

**Google Drive (optional, for forks).** Drive sync uses the `drive.file` scope (the app only sees files it created).
Google only issues tokens to apps registered in a Google Cloud project, so if you build with your own key:
create a project, enable the *Google Drive API*, configure the OAuth consent screen, and add an **Android OAuth client**
with package name `io.github.gdornyei.pe2tester` (or your own applicationId) and the SHA-1 of your signing key
(`keytool -list -v -keystore your.jks`). Everything else in the app works without this.

## Protocol (reverse engineered)

Sources: analysis of the vendor app's behaviour and a recorded BLE capture. The commands are byte-identical to the vendor app's;
`ProtocolTest` checks this against a recorded capture (`app/src/test/resources/*.hex`, anonymised: addresses, MACs, names and serial numbers were replaced, CRCs recomputed).

**BLE (no pairing)**
- Service: `0b7beb07-13fa-47b6-909e-a05daad11552`
- Notify (device → phone): `c9eddcb6-04a5-4d58-abbe-a98da1b3f3d0`
- Write (phone → device, write with response): `f63cdc3f-9652-460c-94b1-cc1afc117269`
- MTU 128 is requested; the byte stream is written in chunks of MTU−3.

**Framing:** `00 | COBS( ptype:u16le | crc:u16le | payload ) | 00`. CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over the unstuffed frame with the CRC field zeroed. All integers are little-endian.

| ptype | Name | Request | Response |
|---|---|---|---|
| F104 | DEVINFO | – | serial, hw/bootloader/STM/ESP versions, MAC |
| F053 | WIREMAP (DC resistance) | u32 36363, u32 64 | 36 × i32 resistances (9 conductors per pair) |
| F050 | POE | – | 32 B: events, class, signature, open/load V×10, polarity ADCs |
| F210 | TDR 4 pairs | 8 × u16 (3,3,0,0,189,64,8,3) | compressed samples, 4 × N |
| F211 | TDR crosstalk | 8 × u16 | compressed samples, 6 × N |
| F220 | TONER | mode, tone, shield | ACK |
| F240 | IFCONFIG_ETH | if/ip flags, PHY config, VLAN, static IP | ACK, then F206 link info |
| F245 | IFCONFIG_WIFI | AP/bridge mode, SSID, password | ACK |
| F206 | LINKINFO | – | link, speed, duplex, partner abilities, polarity, skew |
| F230 | DHCPv4 ACK (async) | – | full Ethernet frame |
| F241 / F250 | ETH filter add / match (async) | filter | Ethernet frame (LLDP/CDP complete, others truncated) |
| F260 | PING | timing, count, host | address type, IP, average time (u16 @6, 65535 = timeout) |
| F270 | HTTP GET | timeout, UA, URL | response body |
| F300 | ETH_STOP | – | – |
| F0FA / F0F0 | ACK / NACK | | |

Wiremap and TDR evaluation follow the vendor's algorithms (`proto/Wiremap.kt`, `proto/Tdr.kt`).

## Privacy

All data stays on the phone unless you share a report or turn on Google Drive sync (which writes only to your own Drive).
The optional "Internet" test asks the Pockethernet to fetch `http://ip-api.com/json/` to show the public IP and ISP – note that
ip-api.com's free endpoint is for non-commercial use only. Disable the Internet test if that matters to you.

## License

[MIT](LICENSE). Provided as is, without warranty – always verify critical installations with certified equipment.
