# ToonShift Android

Android 8 or newer. Pair on your home Wi-Fi, then control your Toon through your NAS's Tailscale subnet route. The app supports room and target temperatures, preset selection, holds, and editing the weekly program.

## Set up

1. Install a ToonShift build with Settings → Remote access and reserve the Toon's LAN address in your router.
2. In Remote access select the reserved address, enable remote access, then choose Pair phone.
3. In the Android app open Devices, name your phone, and scan the QR code. Compare the confirmation code and tap Allow control on the Toon.
4. Run Tailscale on the NAS with IP forwarding and advertise just the Toon's LAN IPv4 address as a /32. Approve the subnet route and grant your phone's identity TCP access to that address on port 10083 in your tailnet policy. Keep the default subnet-route SNAT enabled.
5. Install Tailscale on Android and connect it to the same tailnet. Test with Wi-Fi off. The app still uses the Toon's LAN address through the advertised route.

Do not forward port 10083 on the internet router. HTTPS identity is verified using the certificate fingerprint scanned on the Toon. An address change requires pairing again. Revoke a lost phone from the Toon's Paired phones list.

[Tailscale subnet router setup](https://tailscale.com/docs/features/subnet-routers/how-to/setup)

## Build

Use JDK 17 and Android SDK 35. Run ./gradlew testDebugUnitTest lintDebug assembleDebug. A version tag triggers the signed release job after those checks. The Android release key is stored independently from ToonShift's update key.
