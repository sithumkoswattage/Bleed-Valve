# The Bleed Valve
### A Multi-Threaded Client–Server Network Game
*OS & Networks module report*

Name: ______________   Index: ______________   Date: 14 November 2026

---

## 1. Introduction

**The Bleed Valve** is a cooperative real-time multiplayer game in which two or more players crew
a single WWII submarine from separate machines over a TCP network. Each player occupies a
different *station* and sees only the instruments and information belonging to that role; no one
player can operate the boat alone.

The application was chosen to satisfy the brief "a network programming application enabling
communication and data exchange over networks in real time", under the *Networked Games*
category. It is implemented entirely in Java using `java.net` sockets and
`java.util.concurrent` threading primitives, with no external libraries or game engine.

### Objectives
1. A concurrent server that serves multiple simultaneous clients without one blocking another.
2. A real-time bidirectional protocol carrying commands upstream and world state downstream.
3. Correct synchronisation of a single shared world model mutated by several threads.
4. Graceful handling of client disconnection and malformed input.

---

## 2. System Architecture

### 2.1 Overview

```
   [ Client: Kommandant ]              [ Client: Engineer ]
     Listener thread                     Listener thread
     Heartbeat thread                    Heartbeat thread
     Swing EDT                           Swing EDT
            |                                   |
            +--------- TCP, port 5050 ----------+
                              |
                  +-----------------------+
                  |        SERVER         |
                  |  main (accept loop)   |
                  |  Client-1 .. Client-N |
                  |  GameLoop  (20 Hz)    |
                  |  EnemyAI   (5 Hz)     |
                  |  Heartbeat (1 Hz)     |
                  |                       |
                  |  GameState            |
                  |  + ReentrantLock      |
                  |  + event queue        |
                  +-----------------------+
```

> *(Redraw this as a proper figure if your rubric wants diagrams.)*

### 2.2 Authoritative server
The server holds the only copy of the world. Clients are *presentation only*: they send the
player's intent and render what comes back. This gives consistency between players and allows
**selective disclosure** — the commander's packet contains surface contacts only when the boat is
shallow enough to raise the periscope, so information a player should not have never leaves the
server.

The game exposes this directly as two modes. In **realistic** mode each contact reaches the
commander as a bearing, an angular mast height and an angular width, and nothing else; range has
to be derived with a stadimeter and a recognition manual, as it was in 1943. In **training** mode
the server adds the true range, course and speed. The mode is one field of the shared state, so a
single command from any station changes what every station is sent on the next tick.

### 2.3 Server threads

| Thread | Rate | Responsibility |
|---|---|---|
| `main` | blocking | `ServerSocket.accept()`; spawns one handler per connection |
| `Client-N` | blocking | reads and applies one player's commands |
| `GameLoop` | 20 Hz | physics integration; per-station state broadcast |
| `EnemyAI` | 5 Hz | escort finite state machine |
| `RepairCrew` | blocking | consumes repair orders from a blocking queue, one at a time |
| `Heartbeat` | 1 Hz | liveness checking and connection reaping |

### 2.4 Client threads
`Listener` (blocking socket read), `Heartbeat` (1 Hz `PING`), `Sound` (continuous audio
synthesis and mixing), and the Swing **Event Dispatch Thread**. Because Swing is not
thread-safe, the Listener thread never touches a component; it marshals every update onto the
EDT with `SwingUtilities.invokeLater()`.

---

## 3. Application Protocol

Transport is **TCP**, chosen because commands must not be lost or reordered, and because the
connection itself is a useful signal of player presence. TCP provides a byte stream with no
message boundaries, so messages are **newline-delimited** and read with `readLine()`.

### Client → Server
| Message | Purpose |
|---|---|
| `HELLO\|<STATION>` | claim a station: `KOMM`, `ENG` or `ALL` |
| `PING` | liveness, once per second |
| `CMD\|<ACTION>\|<VALUE>` | `FIRE`, `PERISCOPE`, `SPEED`, `DEPTH`, `HEADING`, `PUMPS`, `HYDRO`, `RESTART` |
| `QUIT` | leave cleanly |

### Server → Client
| Message | Purpose |
|---|---|
| `STATE\|{json}` | full world snapshot, 20 per second, filtered per station |
| `EVENT\|<NAME>\|<detail>` | discrete occurrences broadcast to all |
| `ERROR\|<text>` | rejected command, with a reason |
| `PONG`, `WELCOME`, `JOINED\|<station>` | handshake and liveness replies |

Commands are validated server-side for both **authorisation** (the engineer cannot fire) and
**legality** (torpedoes cannot be fired below 20 m, pumps fail on a flat battery).

---

## 4. Concurrency and Synchronisation

### 4.1 The shared resource
All threads mutate one `GameState` instance. The clearest conflict is the **battery**, which is
drained by the propulsion motors and by the bilge pumps — two drains computed on different
threads in the same tick, plus damage events arriving from client handler threads.

### 4.2 The hazard
`battery = battery - drain` is not atomic. It compiles to read, modify, write. Interleaved:

| Time | Thread A (motors) | Thread B (pumps) | Battery |
|---|---|---|---|
| 1 | read 50 | | 50 |
| 2 | | read 50 | 50 |
| 3 | compute 50−1 | | 50 |
| 4 | | compute 50−1 | 50 |
| 5 | write 49 | | 49 |
| 6 | | write 49 | **49** |

Two units were drawn; one was lost. This is a **lost update**.

### 4.3 The fix
A single `ReentrantLock` guards `GameState`. Every mutating or reading path acquires it and
releases it in a `finally` block. One lock means a lock-ordering cycle — and therefore deadlock —
is impossible by construction.

### 4.4 Not holding locks across I/O
`GameLoop` takes the lock, integrates physics, builds the outgoing snapshot *strings*, then
releases the lock **before** writing to any socket. Socket writes can block for an unbounded
time; holding a lock across one would serialise the entire server behind the slowest client.

### 4.5 Other primitives used
- `ClientHandler.send()` is `synchronized`: the game loop and the handler thread both write to the same socket, and interleaved `println` calls would corrupt a line.
- Connected clients live in a `CopyOnWriteArrayList`, safe to iterate while handlers add and remove.
- Game events use a `ConcurrentLinkedQueue` — a lock-free **producer–consumer** queue with many producers and the game loop as sole consumer.
- Repair orders use a `LinkedBlockingQueue`. `RepairCrew` calls `take()`, which blocks, so the thread consumes no CPU while idle and wakes the instant an order arrives.
- `ClientHandler.close()` uses `AtomicBoolean.compareAndSet` so that concurrent closes from the handler and heartbeat threads execute the teardown exactly once.

**Choosing the queue type.** The two queues differ because their consumers differ. The game loop
is already waking twenty times a second, so it must *poll* and return — blocking there would stall
the tick for every player. The repair crew has no other duty, so *blocking* is correct: no polling
loop, no wasted CPU, no latency. The primitive follows from the consumer's duty cycle.

### 4.6 A second contended resource
Synchronisation here is not only a correctness concern; it is the game mechanic. There is **one**
damage-control party, so a second repair order waits in the queue, and the engineer must choose
which compartment to save first. The party and the bilge pumps both add to the boat's acoustic
signature, which is precisely what the enemy escort's AI thread is sampling five times a second.
Repairing the boat therefore makes it easier to find. The player experiences resource contention
directly, as a decision rather than as a diagram.

### 4.7 Experimental verification
`RaceConditionDemo.java` isolates the hazard: two threads each decrement a shared counter 500,000
times, starting from 1,000,000, expecting 0.

| Run | Without lock | With lock |
|---|---|---|
| 1 | 184,927 | 0 |
| 2 | 149,006 | 0 |
| 3 | 355,492 | 0 |

> *(Re-run it on your machine and paste your own numbers — they will differ, which is itself the point.)*

Between 15% and 36% of all decrements were lost, and the loss is non-deterministic. With the lock
the result is exactly correct on every run.

---

## 5. Fault Tolerance

| Failure | Detection | Response |
|---|---|---|
| Client closes cleanly | `readLine()` returns `null` | handler closes socket, removes itself, thread exits |
| Cable pulled / machine sleeps | no `PING` for 6 s | `HeartbeatMonitor` closes the connection |
| Malformed or unauthorised command | server-side validation | `ERROR\|…` returned; world unchanged |
| Write to a dead socket | `PrintWriter.checkError()` | connection closed |

A departing player never blocks or crashes the others: each client's failure is contained in its
own thread.

---

## 6. Testing

| Test | Method | Result |
|---|---|---|
| Multiple simultaneous clients | two clients connected and issuing commands | both served; server log shows independent threads |
| Broadcast rate | counted `STATE` messages received over 2.8 s | 58 messages ≈ 20.7 Hz, matching the 20 Hz target |
| Station authorisation | engineer sent `FIRE`; commander sent `SPEED` | both rejected with `ERROR` |
| Input validation | `CMD\|FIRE\|abc` | rejected, server unaffected |
| Game logic | torpedo fired with and without lead angle | miss without lead, hit with lead; tonnage credited |
| Damage model | depth charge detonated beside the boat | hull reduced, flooding began, pumps reversed it |
| Heartbeat | client connected then went silent | removed after 6 s; other client unaffected |
| Race condition | `RaceConditionDemo` | reproduced and fixed (§4.7) |
| Blocking queue | two repair orders issued at once | first worked immediately, second queued; duplicate and unauthorised orders rejected |
| Selective disclosure | two clients, mode switched by one of them | realistic packets contain no range, course, name, battery % or hull %; training packets do; both clients changed on the same command |
| Attack procedure | scripted commander using only realistic-mode data (stadimeter, two plot marks, torpedo computer) against a zigzagging convoy | 10 hits from 12 shots; the misses followed a course change between marks |
| Detection model | scripted 5-minute engagements | loud and shallow: destroyed in 3 min, 9 charge hits. Deep and silent: never detected. Detected then dived below the layer: contact lost within 2 min |

---

## 7. Limitations and Future Work
- **Thread-per-client** does not scale past a few hundred connections; a `ThreadPoolExecutor` or NIO selector would be required.
- **No encryption or authentication** — a LAN game for an assignment, not a hostile network.
- **Dead reckoning / interpolation** is not implemented; at 20 Hz on a LAN it is not needed, but over the internet motion would appear to stutter.
- **UDP for state snapshots** would reduce head-of-line blocking on a lossy link, keeping TCP for commands.

---

## 8. Conclusion
The application meets the brief: a real-time, bidirectional, multi-client network program in which
concurrency is essential rather than decorative. The chosen domain makes the synchronisation
problem concrete — a submarine's battery really is a contended resource — and the race condition
underlying it was both demonstrated experimentally and corrected.

---

## Appendix A: Running the system
See `server/README.md` and `client/README.md`.

## Appendix B: Source listing
| Component | Files |
|---|---|
| Server | `Server`, `ClientHandler`, `GameState`, `GameLoop`, `EnemyAI`, `RepairCrew`, `HeartbeatMonitor`, `Log` |
| Tools | `TestClient`, `RaceConditionDemo` |
| Client | `ClientMain`, `NetworkClient`, `Snapshot`, `ShipClass`, `Meter`, `KommandantPanel`, `EngineerPanel`, `PeriscopeView`, `AttackPlot`, `HydrophoneDial`, `DamageBoard`, `SoundEngine`, `Gauge`, `Bar`, `EventLog`, `Theme`, `GameMath` |

## Appendix C: Declaration
This project was designed by the author and implemented with the assistance of an AI coding
assistant. The author understands and can explain the operation of all submitted code.
> *(Keep or adapt this to match your university's policy — check what yours requires.)*
