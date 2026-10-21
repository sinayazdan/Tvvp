# SinaTV

Simple Android TV VPN client — big remote-friendly UI, paste a config link and connect.

## Supported protocols (phase 1, sing-box core)
- VLESS (incl. Reality, WS/gRPC/HTTPUpgrade transports)
- VMess
- Trojan
- Shadowsocks
- Hysteria2
- TUIC

OpenVPN (.ovpn) support is planned as phase 2 (ics-openvpn engine).

## Build
APKs are built by GitHub Actions (`.github/workflows/build.yml`):
the workflow builds `libbox.aar` from sing-box v1.12.20, then assembles the
debug APK and attaches it to a `build-N` GitHub release.

## Notes
- Core: [sing-box](https://github.com/SagerNet/sing-box) (GPLv3), embedded via libbox.
- Persian UI, designed for D-pad navigation on Android TV (minSdk 23).
