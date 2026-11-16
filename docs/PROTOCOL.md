# TC421 WiFi protocol

An independent description of the UDP protocol of the TC421 5-channel LED ramp controller,
written for interoperability. Reference frames are in [`spec/vectors.json`](../spec/vectors.json);
the reference implementation is the `:protocol` module of this repository.

Unless stated otherwise, what this document describes was seen on a real controller; what is only
inferred from the original app is marked *deduced*.

Nothing is authenticated or encrypted. Anyone on the network can send these frames.

## Transport

* UDP, port **5000** on the controller.
* **Replies go to the source port of the request.** Observed from an arbitrary local
  port for a unicast `17`, `01 01`, `01 02`, `04 0A` and `03 08`, and for a broadcast `17`.
  The original app binds local port 5000: a habit, not a requirement. The reference implementation
  binds a free port chosen by the system, so that no other program sharing port 5000 can receive
  its replies.
* **Discovery**: a `17` frame sent by broadcast (255.255.255.255:5000), twice 300 ms apart; the
  sender of the reply is the controller, which answers from port 5000 to the source port of the
  request, within about 200 ms. **Observed: a unicast `17` to the controller's address
  works too**, so discovery can cross a router.
* **Commands**: unicast to the controller's address, port 5000.
* **The controller sometimes loses a frame** (observed: one discovery in a series, one ping in
  three). A client must resend; the reference implementation does so for every command
  with an acknowledgement.
* **Reliability**: the controller acknowledges most commands. The original app waits about one
  second per attempt and tries **3 times**. That wait is too short for a program write (see
  [Writing a program](#writing-a-program)): the reference implementation waits **2 seconds**.

## Frame format

Every frame is **64 bytes**.

| Bytes | Request (app to controller) | Reply (controller to app) |
|---|---|---|
| 0-1 | `55 AA` | `88 00` |
| 2 | command | **not** the command (see quirks); `17` in a discovery reply |
| 3 | sub-command | — |
| 4 | — | **acknowledgement code** |
| 5 | length of the data | — |
| 6... | data | — |
| 49-60 | controller MAC, **12 ASCII characters** (upper-case hex, no separator) | controller MAC |
| 61 | checksum | (see below) |
| 62-63 | `00` | — |

Unused bytes are `00`. A request to a controller carries its MAC at 49..60; the discovery request
carries none (zeros).

**Request checksum**: sum, modulo 256, of bytes **0 to 48** (the MAC is not included). Observed:
a request with this checksum is accepted.

**Reply checksum**: **unknown formula.** The checksum byte of replies does not follow the request
formula (several other formulas were tried). A client must **never verify it**.

**Discovery reply**: the MAC is at bytes **4 to 15**, not 49-60. Observed reply (MAC replaced):

```
00: 88 00 17 00 'A''A''B''B''C''C''D''D''E''E''F''F'   (MAC at 4..15)
16-60: 00        61: not significant        62-63: 00
```

**Valid acknowledgement**: bytes 0-1 = `88 00`, bytes 49-60 = the MAC of the targeted controller,
byte 4 = the expected code.

## Commands

| Cmd / sub | Name | Data | Ack code (byte 4) | Effect |
|---|---|---|---|---|
| `17` | search | byte 5 = `03`; bytes 6-8 = `DD EE FF`; no MAC | reply `17` | none (read) |
| `02` | set clock | byte 5 = 7; 6..12 = year (2 bytes, high first), month, day, hour, minute, second | `85` | sets the clock |
| `01 01` | start program write | byte 5 = name length; 6 = program slot (0-19); 7... = name (1 to 9 bytes, see [Program names](#program-names)) | `80`; `87` (refused) if the name is 10 bytes or more | opens the write of a program |
| `01 02` | write one step | byte 5 = 8; 6 = step (0-47); 7 = hour (step / 2); 8 = minute (0 or 30); 9..13 = channels 1-5 (0-100) | `81` | writes one 30-minute step |
| `04 0A` | end of write session | none | `91` | closes a write session, once after its last program; never followed closely by another `01 01` (see [Writing a program](#writing-a-program)) |
| `03 08` | end of program / end of preview | none | `83` | in a write session, ends the program just written (after its 48 steps); otherwise ends a preview, back to the stored program |
| `06 01` | start preview | byte 5 = name length; 6... = name (see [Program names](#program-names)) | none | starts a preview |
| `06 02` | preview values | byte 5 = 5; 6..10 = channels 1-5 | none | live levels, overriding the program |
| `18` | set Wi-Fi | byte 5 = text length; 6 = sub-command; 7... = text (at most 32 bytes) | `70` / `72` / `74` (deduced) | network configuration |

A command `05 FE` (erase all programs, ack `82`) is known to exist. It is **not implemented** by this
project and must not be sent casually: it is described as erasing every stored program.

### Wi-Fi (`18`)

* Protected network: sub-command **1** = SSID (ack `70`), with byte **39** = security type
  (`1` WPA, `2` WPA2) and byte **40** = cipher (`1` AES, `2` TKIP); then sub-command **2** = passphrase
  (ack `72`).
* Open network: sub-command **3** = SSID (ack `74`).
* The text is limited to 32 bytes: the original app truncates SSID and key to 32 bytes, so a longer
  key silently fails to authenticate. A client should refuse longer values.
* The key travels in clear text.
* Observed: after the configuration of a protected network, sent while the phone was on the
  controller's access point, the controller leaves its access point and joins the given network.

## Access point

* A controller can be put in access-point mode from its own menu (long press on Menu; its screen
  shows “Set: SoftAP”). Its light does not change.
* The network is **open** (no key).
* Its name: on one controller, observed, **`Smart-` followed by 6 hexadecimal digits** (the end of
  its MAC); the manual of another controller gives a name starting with `Smarttime`.
* The controller's address on its access point is **`192.168.4.1`**. The range of addresses it gives
  to clients has not been observed.
* Discovery, by broadcast or by unicast to that address, works there.

## Data model

* A **program** is 48 steps of 30 minutes (step 0 = 00:00) x 5 channels, each 0 to 100 %.
* At most **20 programs** (slots 0-19).
* The controller **interpolates** between two steps by itself (smooth transitions, seen by eye): the
  written steps are enough.
* A preview does not write anything: it overrides the running program live, and `03 08` brings the
  stored program back exactly (observed).

## Writing a program

Programs are written in a **write session**. This is the sequence of the original app (read in its
code), seen working on a real controller:

1. For each program of the session:
   1. `01 01` (slot, name), ack `80`; ack `87` instead when the name is refused (10 bytes or more).
   2. A **pause of about one second** (see below).
   3. 48 x `01 02`, ack `81` each; the original app spaces them by 50 ms.
   4. `03 08`, ack `83`.
2. After the last program, **a single `04 0A`**, ack `91`.

Writing one program is a session of one program: `01 01`, pause, 48 x `01 02`, `03 08`, `04 0A`
(51 frames). On a failure or an abort, the original app sends nothing more: no `03 08`, no `04 0A`.
The reference implementation does the same, and resumes the session at the frame that failed.

Observations:

* **With this sequence, 18 slots were emptied in one session without incident**, then 14 programs
  were written in one session, and the final `04 0A` was acknowledged.
* **After an emptying cancelled, so without its `04 0A`, the controller kept answering.**
  What it keeps in memory of that open session is not known (see
  [Unknowns](#unknowns-to-be-settled-by-tests-on-a-real-controller)).
* **A `01 01` that follows a `04 0A` is ignored.** With a `04 0A` after every program, then `01 01`
  for the next one, that `01 01` was acknowledged only at its third send, about 4 seconds later; at
  the fourth program the controller stopped answering entirely (see
  [Lock-ups](#lock-ups-after-01-01-sent-after-04-0a)).
* **The first step needs a pause after the acknowledgement of `01 01`.** Sent 0 or 0.25 s after
  that acknowledgement, the first `01 02` was dropped (no acknowledgement within 2 s). Sent 0.5, 0.75,
  1, 1.25 or 1.5 s after it, it was acknowledged at the first send, 14 times out of 14, in 57 to
  182 ms. The reference implementation waits **1 second**, twice the shortest pause that worked; the
  other steps keep the 50 ms spacing. Without that pause, the first step
  usually got no acknowledgement at its first send and was acknowledged when resent 2 seconds later.

Other observations:

* **An opening `01 01` left without a follow-up does not block the controller**, observed for
  10 seconds: it still answers discovery and accepts a new `01 01`.
* **A write can make the light flicker briefly** while it runs (observed once or twice, no lasting
  effect).
* **Writing a slot does not change the running program.** Writing to a slot other than the one
  running had no visible effect, neither at once nor at the next step change. **The program that runs
  is selected on the controller itself, in its menu**; the name of the selected program is shown on
  its screen. No command is known to read which slot is running or to select one.
* The writes were acknowledged without any erase (`05 FE`) first. Whether `01 01` fully replaces
  what a slot held is not proven: the controller cannot be read back.

A single lost acknowledgement among these frames makes the original app abandon the transfer,
without retry: a plausible explanation of its erratic behaviour (not confirmed). A robust client
retries each frame and can resume at the failing frame.

### Program names

* **`01 01`: the name is 1 to 9 bytes.** With a name of 10 bytes or more, the controller answers
  **`87`** (refusal) instead of `80`. The limit counts bytes, not characters: no byte value is
  refused in itself.
* **The controller's screen shows only ASCII.** Bytes above `7E` are accepted but are shown blank or
  as shapeless pixels, whatever the encoding (Latin-1, CP437 or UTF-8). Sending a name does not
  change the light.
* **The controller's screen shows only the first 7 characters of a name.** Seen with
  names of 8 and 9 bytes, written by this library and by the original app, with a length byte that
  counts or does not count a trailing `00`: the screen shows the first 7 characters in every case.
  The name follows the date on the top line of the screen. Whether the controller keeps the bytes
  after the 7th cannot be checked: a slot cannot be read back.
* **The reference implementation sends printable ASCII only** (`20` to `7E`, one byte per
  character), 1 to 7 characters (what the screen shows; the controller itself accepts 9), and
  refuses any other name before building the frame.
* **`06 01`**: the reference implementation applies the same rule. The controller's own limit for
  a preview name has not been observed.

## Observed quirks

* **Byte 2 of an acknowledgement is not the command.** Observed `03` for a reply to `02` and `11`
  for a reply to `03 08`. Never pair a reply with a request through byte 2.
* In observed acknowledgements, bytes 5-15 hold the end of the MAC: the acknowledgement code seems
  to overwrite the first MAC character at byte 4 in a buffer that kept the MAC at bytes 4-15. The MAC
  is also present at 49-60. Bytes 16-48 of replies were not captured.
* `06 01` and `06 02` get **no reply**.
* Replies carry no sequence number: a late acknowledgement can be mistaken for the answer to the
  next command of the same kind. A client should discard stale datagrams before each send.

## Lock-ups after `01 01` sent after `04 0A`

**Cause established:** a `01 01` sent after a `04 0A`, program after program. The first
such `01 01` is ignored and acknowledged only when resent; repeated over a few programs, the
controller ends up answering nothing at all, not even a discovery (`17`), from the phone or from a
PC, until its own power supply is cycled. Neither a `04 0A` nor a `03 08` sent then brought it back.
With one `04 0A` per session (see [Writing a program](#writing-a-program)), it did not happen.

The first two lock-ups happened with the sequence used at the time
(`04 0A` after every program), right after a `01 01` acknowledged `80`: the first `01 02` got no
acknowledgement, and from then on the controller answered nothing. Its screen still lit
up at each frame received: it was receiving, not sending. The light and the running program were not
affected, and cycling the controller's power supply cleared it both times, with nothing lost.

Still unknown: how long after a `04 0A` a `01 01` is ignored, and how many ignored `01 01` lead to the
lock-up (observed: two per program, silent at the fourth program); whether anything other than a power
cycle clears it.

## Unknowns, to be settled by tests on a real controller

* **Writing a program** (`01`): how the controller numbers its programs on its screen compared to
  the slot numbers 0-19; whether it keeps the write open when a write is resumed later at a failed
  step (resending a step within a few seconds works).
* **What a session left open leaves in the controller**: after a failure or an abort, no `03 08` or
  no `04 0A` is sent (as the original app does). The controller kept answering after
  an emptying cancelled without its `04 0A`; what it keeps in memory, the effect on the slot being
  written, and on the next session, are not known.
* Whether a program is stored at its `03 08` or only when the session is closed by `04 0A`.
* Exact meaning of the length byte (name length versus data length) for `01 01`, `06 01` and `18`:
  this library follows "length of the text / name".
* Maximum length of a preview name (`06 01`); this library applies the `01 01` limit (9 bytes).
* Checksum formula of replies.
* The exact shortest pause before the first step (between 0.25 and 0.5 s on one controller).
* Role of `03 08` and `04 0A` beyond the observations above.
* Contents of reply bytes 16-48.
* The address range given to clients by the access point.
* The name of the access point on other controllers.
