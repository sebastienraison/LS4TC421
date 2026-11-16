# LS4TC421: Privacy Policy

*[Version française](PRIVACY.fr.md)*

Last updated: 2026-11-16

LS4TC421 ("Light Scheduler for TC421") is an Android app that programs a TC421 lighting controller
over Wi-Fi. It is published by RIC Labs (Sébastien Raison). This policy applies to every version of
the app, whether installed from Google Play, F-Droid or GitHub.

## In short

**LS4TC421 does not collect any personal data.** There is no account, no server run by the
developer, no advertising, no analytics and no crash reporting. The app only talks to your
controller, on your local network. Everything you put into the app stays on your device.

LS4TC421 is free software (GPL-3.0). Its source code is public, so every statement on this page can
be checked: <https://github.com/sebastienraison/LS4TC421>

## Data stored on your device

LS4TC421 stores, in its private space that other apps cannot read:

- your controllers: the name you give them, their address on your local network, their hardware
  identifier (MAC address), and what the app has written into each of their slots;
- your lighting programs;
- your settings (the colour of each channel, the name given to emptied slots).

None of this is sent to the developer, or to anyone.

**The key of your Wi-Fi network**, if you enter it to configure a controller, is never stored: it
is sent once to the controller, then forgotten. It travels in clear text on your local network,
because the controller's protocol cannot encrypt it.

The **exchange log**, used for troubleshooting, exists in memory only: it is gone when the app
closes. It leaves your device only if you share it yourself. The name and the key of your Wi-Fi
network never appear in it; the controller's hardware identifier is partly masked; the addresses of
your local network remain visible, because they are needed for troubleshooting.

## Your location

LS4TC421 asks for the location permission **for one reason only**: Android reveals the name of the
Wi-Fi network your phone is connected to only to apps that hold this permission. The app uses it to
suggest that name when you configure a controller's Wi-Fi. **It never reads your location.** If you
decline the permission, you type the network name yourself.

## Network connections

LS4TC421 does not connect to the Internet. It only exchanges with your controller, on your local
network (UDP, port 5000): searching for controllers, sending programs, setting the clock and the
Wi-Fi.

The links in Settings (source code, this page) open in your browser; the policy of the visited site
then applies.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state, Wi-Fi state | Exchange with the controller on the local network, know whether the phone is on Wi-Fi |
| Location (precise or approximate) | Read the Wi-Fi network name, as explained above; never the location |
| Local network (Android 17 and later) | Reach the controller on your network |

## Backups

- **Your program files.** You can export a program to a file and choose where it goes. That file
  is yours: LS4TC421 keeps no copy of it anywhere else.
- **Android backup.** If backup is enabled on your device, Android may include LS4TC421's data
  (controllers, programs, settings) in the backup stored in your Google account. This is an Android
  feature, set in your device's settings. See
  [Google's documentation](https://support.google.com/android/answer/2819582).

## Security

Your data is stored in the app's private space, protected by Android: other apps cannot access it.
No data is sent to the developer, so no copy exists anywhere but on your device, in your own
backups and in the files you export.

## Retention and deletion

Your data stays on your device until you delete it. You can:

- delete a controller or a program from the app;
- clear the app's data from Android's settings;
- uninstall the app, which deletes everything it stored on the device.

If Android backup is enabled, a copy may remain in your Google account. It is managed from your
device's backup settings.

The developer holds none of your data, so there is nothing to delete on their side.

## This page

This page is hosted by GitHub. Like any host, GitHub records visitors' IP addresses for security
reasons. This processing is governed by
[GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement),
not by this policy, which covers the app.

## Contact

RIC Labs (Sébastien Raison)
<ls4tc421@rseb.net>

You can also open an issue: <https://github.com/sebastienraison/LS4TC421/issues>

## Revisions

| Date | Reason |
|---|---|
| 2026-11-16 | First version |
