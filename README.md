# Bleed-Valve

A co-op multiplayer simulation of WW2 U-boat warfare. Each player has to do his part in order to
keep the driving force of the Kriegsmarine dominant by sinking Allied ships and evading destruction.

Built for the OS & Networks module: a **multi-threaded TCP client–server game** in pure Java,
no external libraries and no game engine.

---

## Quick start

1. **Server** — open `server/` in IntelliJ, run `Server.main()`. It listens on port **5050**.
2. **Client** — open `client/`, run `ClientMain.main()`. Pick a station, press OK.
3. Run a **second client** and pick the other station. On two laptops, put the server machine's
   IP in the dialog instead of `127.0.0.1`.

Playing alone? Choose station **ALL** for both stations as tabs.

```
javac -d out src/*.java        # in server/ and in client/
java -cp out Server
java -cp out ClientMain
```

---

## The game

| Station | Role | Sees | Controls |
|---|---|---|---|
| **KOMM** | Der Kommandant | surface contacts, but only above 15 m | periscope (`A`/`D`), torpedoes (`SPACE`) |
| **ENG** | Leitstand / Maschinenraum | depth, power, hydrophone | engine order, depth, helm, pumps (`P`), hydrophone (`Q`/`E`) |

Sink all three merchants to win. You lose if the hull is destroyed, the boat floods, you are
crushed, or the convoy escapes.

### Historical accuracy
The game runs in two modes, switched with `F1`:
- **REALISTIC** gives each station only what a 1943 Type VII crew had: a periscope with a
  stadimeter, a recognition manual, a plotting table, a torpedo computer, a hydrophone you count
  propeller beats on, a voltmeter and ammeter, and shouted damage reports. No ranges, no courses,
  no percentages, no hit notifications.
- **TRAINING** overlays the true picture so you can learn the procedure and see how wrong your
  estimates were.

The server enforces the difference: in realistic mode the true numbers are never transmitted.
The attack procedure is in `client/README.md`.

Deliberate departures from history: time is compressed (a real approach took an hour), the plot
does the navigator's arithmetic for you, and air supply is not modelled.

### How to survive
The escort hunts by sound, and it does not give up after one pass — it turns and comes back.
- **A raised periscope can be seen.** Look, then lower it.
- **Noise finds you:** speed, bilge pumps, and the repair party hammering all give you away.
- **Depth hides you:** below **80 m** sound bends away and detection range drops to a quarter.
- **Silent running** (stopped, pumps off, no repairs) halves it again.
- **It is deaf when it charges you** at over 8 knots. That is your moment to move.
- **But:** surfacing is the only way to recharge, and the pumps are the only way to stop
  flooding. Both will be heard. Every repair decision is also a stealth decision.

---

## Why this project, for the module

| Concept | Where it lives |
|---|---|
| Sockets | `ServerSocket` accept loop; `Socket` per client; newline-framed TCP protocol |
| Threads | 6 server threads + 4 client threads (see `docs/VIVA.md`) |
| Shared state | one `GameState` guarded by a `ReentrantLock` |
| Race condition | the battery: motors and pumps drain it from different threads |
| Producer–consumer | events (`ConcurrentLinkedQueue`, non-blocking) and repair orders (`LinkedBlockingQueue`, blocking) |
| Contended resource | one damage-control party; a second repair order waits in the queue |
| Fault tolerance | `PING` heartbeat, 6 s timeout, idempotent connection teardown |

---

## Repository layout
```
server/   Java game server + TestClient + RaceConditionDemo
client/   Java Swing station client
docs/     REPORT.md (write-up), VIVA.md (defence notes)
assets/   Blender sources and rendered art
```

Read `docs/VIVA.md` before the demo.
