# LS4TC421 — Light Scheduler for TC421

A free Android app to program the TC421 5-channel LED controller over Wi-Fi, in place of the
original app.

- Find your controllers on the local network, or add one by its address.
- Draw a daily lighting program per channel (48 half-hour steps), preview it live on the light,
  keep a bank of programs, export and import them as files.
- Send a program into one of the controller's 20 slots, empty slots, set the controller's clock and
  Wi-Fi.
- English and French.

The app talks only to the controller, on the local network. It has no account, no server, no
analytics: see the [privacy policy](PRIVACY.md).

*Independent project, not affiliated with the manufacturer of the TC421 controller. The TC421 name
is used only to indicate compatibility. The communication protocol was independently reimplemented
for interoperability.*

## Repository

| Module | Content |
|---|---|
| `app` | the Android app (Kotlin, Jetpack Compose, Room) |
| `protocol` | the protocol library, plain Kotlin/JVM |
| `simulator` | a software simulator of the controller, for tests |

The protocol is documented in [docs/PROTOCOL.md](docs/PROTOCOL.md), the program file format in
[docs/PROGRAM-FORMAT.md](docs/PROGRAM-FORMAT.md).

## Building

```bash
./gradlew :app:assembleDebug
```

Tests: `./gradlew :protocol:test :simulator:test :app:testDebugUnitTest`.

## Contact

<ls4tc421@rseb.net>, or open an issue.

## License

GPL-3.0-only: version 3 of the GNU General Public License, and only that version. See [LICENSE](LICENSE).
