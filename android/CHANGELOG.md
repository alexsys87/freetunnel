# Android changes

## Unreleased

- **Settings says when Android does not block traffic without the VPN.** The
  kill switch holds only while FreeTunnel runs. Under it, on a phone or tablet
  with Android 10 or later, Settings now shows a notice while Android's
  *Always-on VPN* with *Block connections without VPN* is not on for FreeTunnel,
  and *Open Android's VPN settings* to turn them on. With them on, Android
  also blocks the applications that bypass the VPN.

## 1.4.0

- **Applications that go only through the VPN.** The Split page ends with a
  new list, *Applications — VPN only*: pick them with *Choose…*. While the
  VPN is on they are always in it, whatever the mode and the profile, even
  when *Applications — bypass VPN* names them too. While the VPN is off,
  whether you turned it off or the connection failed, they have no internet:
  FreeTunnel holds a VPN of its own for them that goes nowhere, says so in its
  notification, and lets them out again when the VPN connects. The list is
  the app's, not a profile's.
  - Domain and address rules still decide where their traffic goes inside
    the VPN, as for every application.
  - The block lasts while Android keeps FreeTunnel running, and comes back
    when you open the app. Another VPN app, or turning FreeTunnel's VPN off
    in Android's settings, ends it.
- Updates install over 1.3.0 and keep your servers.

## 1.3.0

- **Speed graph on Home.** Over the two speed tiles, a small graph shows the
  last minute of the connection's speeds, one point a second: what comes in
  in green over a faint fill, what goes out in grey, as the tiles' arrows
  are. Its scale follows the fastest moment shown. It starts afresh when you
  connect, come back to Home or return to the app.
- Updates install over 1.2.0 and keep your servers.

## 1.2.0

- **Show QR code.** A config's export menu on Configs (the arrow beside its
  name) has *Show QR code* under *Export .toml…* and *Copy deep-link*, as the
  desktop app has: the same deep link as a QR code, which another phone adds
  with *+ → Scan QR code*. It carries the password, as the link does, so the
  card says to show it only to the phone the server is being added to. A link
  with a long certificate chain can be too long for a QR code; the app then
  says to export the .toml instead.
- Updates install over 1.1.0 and keep your servers.

## 1.1.0

- **Scan QR code.** On a phone or tablet with a camera, the + menu on Configs
  starts with *Scan QR code…*: point the camera at the QR code a TrustTunnel
  server or app shows for a server, and it is added as a pasted link is, after
  the same question. The camera is asked for the first time; the app still
  installs on devices without one, and TVs keep *Receive from phone*.
- **TrustTunnel 1.3.0.** The app is built on TrustTunnel's Android library
  1.3.0, up from 1.1.12, with its newer TLS fingerprints (Chrome and Firefox
  as they are in November 2026) and its fixes.
- **Servers with an auth key work.** A link or a file with a client random
  auth key, which 1.0.x refused as needing a newer core, is imported, and the
  key is used. A link's TLS profile (Chrome Canary, Safari, Firefox, OkHttp,
  OpenSSL or BoringSSL) is kept too. *Copy deep-link* carries both.
- Updates install over 1.0.2 and keep your servers.

## 1.0.2

- Updates come from this project's releases. Until now the app looked for them in
  another project's repository (dimmmmmmmer/freetunnel), found a newer version
  there, and refused to install it, because it is signed with another key. It now
  looks in alexsys87/freetunnel. Because 1.0.1 still asks the old place, **install
  this version by hand once**; later ones are offered in Settings. It installs
  over 1.0.1 and keeps your servers.

## 1.0.1

- Installs on Huawei phones (EMUI, HarmonyOS). The installer there refused the
  APK of 1.0.0 with "no certificates were found in the installation package".
  1.0.1 is signed with a different certificate, and nothing else changed.
- **Android does not update an app across signing keys, so 1.0.0 has to be
  uninstalled first.** That deletes its saved servers: keep your configs or
  links at hand to import them again. Later releases install over 1.0.1 and
  keep the servers.

## 1.0.0

The first official FreeTunnel for Android: a TrustTunnel VPN client for phones,
tablets and Android TV, built on TrustTunnel's own Android library 1.1.12. It
needs Android 8.0 or later.

### Highlights

- Add servers from `tt://` links, TrustTunnel's QR pages, TOML files (a client
  config, or the file a TrustTunnel endpoint exports) and the clipboard.
  Nothing is added until you confirm, and importing never connects.
- The kill switch is on by default. If the connection is lost, traffic stays
  blocked and FreeTunnel reconnects by itself: after 1 s, then up to every
  30 s, and at once when a network appears. An alert offers Reconnect and
  Disconnect.
- Split tunnelling by app or by destination (domains and addresses). A change
  you make is kept intact, and the app tells you when Android storage refuses
  to save it.
- Routing by geo lists, as on the desktop: *Only blocked sites through VPN*
  (sites and addresses blocked in Russia go through the VPN, everything else
  direct), *Everything through VPN except Russian sites*, or *Everything
  through VPN*, at the top of the Split page. A new installation starts with
  only blocked sites through the VPN. The lists are downloaded and checked once
  a day, and their sources can be changed. Your own Bypass VPN rules keep their
  sites direct, and your own Through VPN rules add to the lists. Applications
  set to go through the VPN can't be applied while the lists route, and the app
  says so.
- Android TV: FreeTunnel is on the TV launcher, and every control is reachable
  with a remote. **Receive from phone** shows a QR code on the TV; a phone,
  tablet or laptop on the same network opens it in a browser and sends a link
  or a TOML file.
- Updates come from this project's GitHub releases. The app checks for them on
  its own when it starts, at most every 12 hours, and Settings checks when you
  ask. Each is verified before Android is asked to install it.
- A Quick Settings tile, Android's Always-on VPN, and English and Russian.

### Security

- Server configurations are encrypted with a key kept in Android Keystore, and
  are left out of backups and device transfer.
- The configuration editor hides the password, blocks screenshots and screen
  recording, and asks the keyboard neither to learn from what is typed there
  nor to show its own copy of it.
- Of a server's certificate, only the certificates are kept. Anything else in
  it is left out, and the import says so.
- An update must be a newer version, match the checksum in a manifest signed
  with the project's release key, and be signed with the release certificate.
  A build signed with another key never checks for updates.
- Installing an update while the VPN is on asks first, because Android turns
  the VPN off while it replaces the app. FreeTunnel reconnects after the
  update, unless you disconnected in the meantime.
- Receive from phone listens only on the home network, for at most
  5 minutes, behind a one-time code, and stops after 3 wrong codes. The
  link crosses the network unencrypted, so use it on a network you trust. The
  TV shows which address sent the link, and adds nothing until you confirm.

### Not yet

- TrustTunnel 1.3 subscriptions are not followed. A link or file with only a
  subscription is refused. From one that also has its server, the server is
  added, and won't update itself. The subscription address, which holds the
  VPN password, is not kept.
- Servers that need a client random auth key are refused, with a message: they
  need TrustTunnel core 1.3.
