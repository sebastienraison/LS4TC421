# Program file format

The app exports and imports a program as a small JSON file (UTF-8). This document describes version 1
of the format. The reference implementation is `ProgramFile` in the `:app` module
(`app/src/main/kotlin/net/rseb/ls4tc421/model/ProgramFile.kt`).

## What a program is

A TC421 program is 48 steps of 30 minutes (step 0 = 00:00) x 5 channels, each 0 to 100 %
(see [PROTOCOL.md](PROTOCOL.md)). The app does not store the 48 steps: the user edits a few **key
points** per channel, and the steps are **computed** from them. The file carries both: the key points
and options (what the app reads back), and the 48 computed steps (so that another tool can use the
program without recomputing it).

## Fields

| Field | Type | Meaning |
|---|---|---|
| `format` | string | always `"ls4tc421-program"` |
| `version` | integer | `1` |
| `name` | string | 1 to 9 characters, printable ASCII only (`0x20` to `0x7E`): sent to the controller with the program, whose screen shows only ASCII. A file with another name is refused at import, without correction |
| `symmetry` | object or `null` | `{ "axis": "HH:MM" }`: the axis, on a quarter-hour (`00:00` to `23:45`) |
| `channels` | array of 5 objects | one per channel, see below |
| `steps` | array of 48 objects | the computed program, see below |

Each element of `channels`:

| Field | Type | Meaning |
|---|---|---|
| `channel` | integer | 1 to 5, each once |
| `follows` | object or `null` | `{ "channel": n, "percent": p }`: this channel is channel `n` x `p` % (0 to 100) |
| `points` | array | key points `{ "time": "HH:MM", "level": 0..100 }`, times on the hour or half-hour, one per time |

Each element of `steps`: `{ "time": "HH:MM", "levels": [c1, c2, c3, c4, c5] }`, in order from
`00:00` to `23:30`.

## How the steps are computed

1. **Interpolation**, per channel: linear between key points, **rounded to the nearest integer**
   (halves up), looping over midnight: after the last point, the curve goes back to the first one. A
   channel without points is 0 all day; a channel with one point is that level all day.
2. **Symmetry**, when `symmetry` is not `null`: only the points of the half-day *before* the axis
   count (a point exactly on the axis, or exactly 12 hours away from it, is its own mirror). Each
   such point at step `i` also gives a point at step `2 x axis - i` (axis counted in steps, so a
   quarter-hour axis falls between two steps), modulo 48. Points after the axis are ignored; the
   app does not write them.
3. **Linked channels**, after 1 and 2, step by step: a channel that `follows` channel `n` at `p` %
   is `round(level of n x p / 100)`. Its own `points` are ignored (the app keeps them, so that
   unlinking gives them back). A followed channel cannot itself follow another one (no chains).

## Reading a file

* The app reads `name`, `symmetry`, `channels` and their `points` and `follows`; when `channels` is
  present, `steps` is ignored (it is recomputed).
* A file **without `channels`** but with `steps` is accepted: each channel then gets 48 key points,
  its 48 levels. In that case `steps` may also be a plain array of 48 arrays of 5 levels. `format`,
  `version` and `name` are still required.
* Anything else invalid (missing field, value out of range, time not on a half-hour, a chain of
  links, a channel twice, a non-integer level, more than 256 KB) refuses the whole file, with the
  reason; nothing is saved.

## Example

The program "aquarium" entered by its rise only (08:00 to 11:00), with symmetry around 15:30; its
48 steps are the full day, descent included.

```json
{
  "format": "ls4tc421-program",
  "version": 1,
  "name": "aquarium",
  "symmetry": { "axis": "15:30" },
  "channels": [
    {
      "channel": 1,
      "follows": null,
      "points": [
        { "time": "08:00", "level": 0 },
        { "time": "08:30", "level": 0 },
        { "time": "09:00", "level": 20 },
        { "time": "09:30", "level": 40 },
        { "time": "10:00", "level": 100 },
        { "time": "10:30", "level": 100 },
        { "time": "11:00", "level": 100 }
      ]
    },
    {
      "channel": 2,
      "follows": null,
      "points": [
        { "time": "08:00", "level": 0 },
        { "time": "08:30", "level": 0 },
        { "time": "09:00", "level": 0 },
        { "time": "09:30", "level": 20 },
        { "time": "10:00", "level": 40 },
        { "time": "10:30", "level": 80 },
        { "time": "11:00", "level": 90 }
      ]
    },
    {
      "channel": 3,
      "follows": null,
      "points": [
        { "time": "08:00", "level": 0 },
        { "time": "08:30", "level": 15 },
        { "time": "09:00", "level": 40 },
        { "time": "09:30", "level": 60 },
        { "time": "10:00", "level": 80 },
        { "time": "10:30", "level": 100 },
        { "time": "11:00", "level": 100 }
      ]
    },
    {
      "channel": 4,
      "follows": null,
      "points": [
        { "time": "08:00", "level": 0 },
        { "time": "08:30", "level": 5 },
        { "time": "09:00", "level": 20 },
        { "time": "09:30", "level": 40 },
        { "time": "10:00", "level": 80 },
        { "time": "10:30", "level": 90 },
        { "time": "11:00", "level": 100 }
      ]
    },
    {
      "channel": 5,
      "follows": null,
      "points": [
        { "time": "08:00", "level": 0 },
        { "time": "08:30", "level": 0 },
        { "time": "09:00", "level": 0 },
        { "time": "09:30", "level": 0 },
        { "time": "10:00", "level": 0 },
        { "time": "10:30", "level": 0 },
        { "time": "11:00", "level": 0 }
      ]
    }
  ],
  "steps": [
    { "time": "00:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "00:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "01:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "01:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "02:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "02:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "03:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "03:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "04:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "04:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "05:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "05:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "06:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "06:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "07:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "07:30", "levels": [0, 0, 0, 0, 0] },
    { "time": "08:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "08:30", "levels": [0, 0, 15, 5, 0] },
    { "time": "09:00", "levels": [20, 0, 40, 20, 0] },
    { "time": "09:30", "levels": [40, 20, 60, 40, 0] },
    { "time": "10:00", "levels": [100, 40, 80, 80, 0] },
    { "time": "10:30", "levels": [100, 80, 100, 90, 0] },
    { "time": "11:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "11:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "12:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "12:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "13:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "13:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "14:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "14:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "15:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "15:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "16:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "16:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "17:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "17:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "18:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "18:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "19:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "19:30", "levels": [100, 90, 100, 100, 0] },
    { "time": "20:00", "levels": [100, 90, 100, 100, 0] },
    { "time": "20:30", "levels": [100, 80, 100, 90, 0] },
    { "time": "21:00", "levels": [100, 40, 80, 80, 0] },
    { "time": "21:30", "levels": [40, 20, 60, 40, 0] },
    { "time": "22:00", "levels": [20, 0, 40, 20, 0] },
    { "time": "22:30", "levels": [0, 0, 15, 5, 0] },
    { "time": "23:00", "levels": [0, 0, 0, 0, 0] },
    { "time": "23:30", "levels": [0, 0, 0, 0, 0] }
  ]
}
```
