# The Bleed Valve: Station Client (Java Swing)

The player-facing half. Pure Java, no libraries: `java.net` sockets, `java.awt`/`javax.swing` for drawing.

## Run (IntelliJ)
1. Start `Server.main()` from the `server` project first.
2. Run `ClientMain.main()` here.
3. In the dialog, choose a station and press OK.

Or from a terminal:
```
javac -d out src/*.java
java -cp out ClientMain
```

To play properly, run **two clients**: one on `KOMM`, one on `ENG`. They can be on two laptops
on the same Wi-Fi (put the server machine's IP in the dialog) or two windows on one machine.
`ALL` gives you both stations as tabs, for testing alone.

## Controls
| Station | Keys | Buttons |
|---|---|---|
| Kommandant | `A`/`D` train the periscope · `W`/`S` raise/lower it · `Z` 1.5x/6x power · `R` stadimeter range · `X` mark the plot · `C` clear the plot · `SPACE` fire | recognition manual (click), torpedo computer −/+, PLOT>TDC, SIDE |
| Engineer | `Q`/`E` turn the hydrophone wheel · `T` tap in time with the propeller beat · `P` pumps | engine order, depth, helm, pumps, **click the damage board to send the repair party** |
| Both | `M` mutes · `F1` switches the server between REALISTIC and TRAINING | |

## The attack, step by step (realistic mode)
1. **See.** Surfaced, the bridge watch sees for you. Submerged, come to 12 m and raise the scope (`W`). Lower it (`S`) between looks: it can be seen.
2. **Identify.** Centre a ship, go to 6x (`Z`), and click the matching silhouette in the recognition manual. That sets her masthead height.
3. **Range.** Press `R`. The stadimeter measures the angle her mast subtends: height ÷ angle = range. The wrong manual entry gives the wrong range.
4. **Plot.** Press `X` to pencil a mark. Wait 20–30 s, take a new range, mark again. Two marks give her course and speed.
5. **Compute.** Press PLOT>TDC to set speed and angle on the bow, or set them by hand. The engineer can also count propeller RPM (`T`) and divide by the manual's rpm-per-knot for speed.
6. **Fire.** Centre her and press `SPACE`. The computer has already added the lead. Watch the stopwatch against the expected run time, and listen.
7. If the convoy zigzags after your marks, the solution is stale. Clear (`C`) and start again.

## Threads in this program
| Thread | Job |
|---|---|
| Listener | blocks on `readLine()`, receives every server message |
| Heartbeat | sends `PING` once a second |
| Sound | synthesises and mixes audio continuously |
| Swing EDT | owns every component and every pixel |

Swing is **not thread-safe**. The Listener thread never touches a component: it passes each
line to `SwingUtilities.invokeLater(...)`, which queues the work onto the EDT. Updating a label
straight from the Listener thread would be the same class of bug as two server threads writing
one variable without a lock — it would usually work, and occasionally corrupt the UI.

`NetworkClient.send()` is `synchronized` because two threads call it: the EDT (button presses)
and the Heartbeat thread. Without it, a `PING` could interleave halfway through a command line.

## Files
| File | Purpose |
|---|---|
| `ClientMain.java` | connect dialog, window, message routing, 30 Hz repaint timer |
| `NetworkClient.java` | socket, Listener thread, Heartbeat thread |
| `Snapshot.java` | parses one `STATE` message; immutable once built |
| `ShipClass.java` | recognition manual data and silhouettes |
| `Meter.java` | switchboard volt and amp meters |
| `KommandantPanel.java` | periscope station |
| `EngineerPanel.java` | depth / power / hydrophone station |
| `DamageBoard.java` | four compartments; click one to order a repair |
| `SoundEngine.java` | all audio, synthesised sample by sample on its own thread |
| `PeriscopeView.java` | the round optics with ship silhouettes |
| `AttackPlot.java` | the plotting table: your own marks, own track by dead reckoning |
| `HydrophoneDial.java` | listening wheel and signal meter |
| `Gauge.java`, `Bar.java`, `EventLog.java`, `Theme.java` | reusable instruments and styling |

## Sound without any sound files
There are no `.wav` files in this project. `SoundEngine` generates every sample arithmetically
and writes them to a `SourceDataLine`:

| Sound | How it is made |
|---|---|
| Motors | a sine at 42–78 Hz plus its second harmonic, amplitude tracking the engine order |
| Enemy propellers | one decaying thump per blade — the beat rate **is** the RPM the server reports |
| Depth charge | a noise crack, a 38 Hz thud, then a decaying bubble rumble |
| ASDIC ping | a 1150 Hz tone with a delayed echo |
| Hull creak | a pitch-sliding groan, triggered more often the deeper you go |

This is also a producer–consumer: the Swing thread pushes one-shot sounds into a queue, and the
audio thread drains it while mixing the continuous layers.

## Why there is no 3D engine
The client draws ship silhouettes from the two numbers the server sends per contact: bearing and
range. That is genuinely all a periscope gives you, so the simple drawing is also the accurate one,
and it keeps every socket and thread in the project inside Java.

## Why the periscope data looks odd
In realistic mode each contact arrives as a bearing, an angular height and an angular width, in
milliradians. That is all light through a lens can give. Range is not in the packet, so the
stadimeter is not decoration: it is the only way to get one.
