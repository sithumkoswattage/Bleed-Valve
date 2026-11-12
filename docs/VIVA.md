# Viva Cheat Sheet: The Bleed Valve

Read this once before the demo. Everything here is true of the code you are handing in.

---

## The 30-second pitch
A co-op WWII U-boat simulator. Two players sit at different stations on different machines and
crew one submarine against a server-controlled convoy and escort. The server is authoritative:
it holds the only copy of the world, runs the physics, and tells each client only what that
crew member is entitled to know.

---

## The six server threads (know these cold)

| Thread | Where | What it does |
|---|---|---|
| `main` | `Server.java` | `accept()` loop. Blocks until a player connects, then spawns a handler thread and immediately goes back to waiting. |
| `Client-N` | `ClientHandler.java` | One per player. Blocks on `readLine()`, parses commands, applies them to the shared state. |
| `GameLoop` | `GameLoop.java` | Fixed 20 Hz tick. Advances physics, then broadcasts a per-station `STATE` to everyone. |
| `EnemyAI` | `EnemyAI.java` | 5 Hz. The escorts' brains: patrol → search → attack run → turn → attack again. |
| `RepairCrew` | `RepairCrew.java` | Blocks on a `LinkedBlockingQueue` of repair orders and works them one at a time. |
| `Heartbeat` | `HeartbeatMonitor.java` | 1 Hz. Closes any connection silent for over 6 seconds. |

Plus, in each client: **Listener**, **Heartbeat**, **Sound**, and Swing's **EDT**.

---

## Likely questions and the answers

**Q: Why one thread per client instead of handling them in a loop?**
`readLine()` *blocks*. A single-threaded server reading player 1 would freeze players 2, 3 and 4
until player 1 sent something. One thread per client means each player's blocking read only
blocks that player. The `main` thread never reads at all, so it is always free to accept a new
connection.

**Q: What exactly is shared between your threads?**
One `GameState` object. The client handlers write to it (orders), the game loop reads and
mutates it (physics), and the AI thread steers the destroyer inside it. Three different
categories of thread touching the same fields is the definition of a shared-state problem.

**Q: How do you prevent race conditions?**
A single `ReentrantLock` on `GameState`. Every method that touches the fields takes it:
`applyCommand()` locks internally, and the game loop and AI wrap their work in
`lock.lock()` / `finally lock.unlock()`.

**Q: Show me a race condition that would actually happen here.**
The battery. The electric motors drain it, and the bilge pumps drain it, and those two drains are
computed on different threads. `battery = battery - drain` is three machine operations —
read, subtract, write. If the motor thread reads 50, the pump thread reads 50, both subtract and
both write, one of the two drains vanishes. The boat gets free electricity.
Run `RaceConditionDemo.java` — it does exactly this with two threads draining 500,000 each from
1,000,000. **Without the lock the answer comes out around 150,000–350,000 instead of 0. With the
lock it is exactly 0, every time.**

**Q: Why `ReentrantLock` and not `synchronized`?**
Either would work here. `ReentrantLock` makes the lock a visible object you can see being
acquired and released, it supports `tryLock()` with a timeout if I ever needed to avoid blocking,
and `finally unlock()` makes the release explicit. "Reentrant" means the same thread can take it
again without deadlocking itself, which matters because `applyCommand()` can call helpers that
also lock.

**Q: Where could you have deadlocked, and why don't you?**
Deadlock needs two or more locks taken in different orders. There is exactly **one** lock in the
whole server, so a cycle is impossible by construction. That was a deliberate design choice.

**Q: Your game loop sends to every client. Does it hold the lock while doing that?**
No, and that is the important part. It takes the lock, updates physics, builds the snapshot
*strings*, then **releases the lock** and only then writes to the sockets. A socket write can
block for a long time if one player's network is slow. Holding a lock across a blocking network
write would freeze every other thread in the server behind the slowest player.

**Q: Two threads write to the same client socket. Isn't that a problem?**
It would be, so `ClientHandler.send()` is `synchronized`. The game loop writes `STATE` while the
handler thread may be writing a reply. Without synchronisation two `println` calls can interleave
and produce one corrupt line on the wire.

**Q: TCP or UDP, and why?**
TCP. Commands must not be lost or reordered — "fire torpedo" arriving twice or never is a bug you
cannot recover from. TCP also gives me a connection, which is how I detect that a player has gone.
The usual argument for UDP is that state snapshots are disposable, and at 20 Hz on a LAN TCP is
comfortably fast enough, so the extra complexity of two sockets was not worth it.

**Q: How do you frame messages on a TCP stream?**
TCP is a byte stream with no message boundaries, so I delimit with newlines and read with
`BufferedReader.readLine()`. Every message is one line.

**Q: What happens if a player pulls their network cable?**
TCP will not notice for a long time, so I do not rely on it. Each client sends `PING` every
second; the server records the arrival time. `HeartbeatMonitor` closes any connection silent for
more than 6 seconds. `ClientHandler.close()` uses `compareAndSet` on an `AtomicBoolean`, so it is
safe to call from the handler thread and the heartbeat thread at the same time and only runs once.

**Q: Why does the client have threads at all?**
Three. The **Listener** blocks on `readLine()` — if that ran on the UI thread the window would
freeze. The **Heartbeat** sends `PING`. The **EDT** owns the UI. Swing is not thread-safe, so the
Listener never touches a component: it passes every line through
`SwingUtilities.invokeLater(...)`, which queues the work onto the EDT. Touching a label from the
Listener thread would be the same category of bug as an unsynchronised shared variable.

**Q: Why is the server authoritative? Why not let clients compute their own view?**
Two reasons. Consistency: if each client ran its own physics they would drift apart within
seconds. Information control: the commander only receives surface contacts when the boat is above
15 m, and the server simply does not put them in his packet. If the client filtered the data, the
data would still be on his machine — which is how you cheat in a real game.

**Q: What do the REALISTIC and TRAINING modes show about your design?**
Selective disclosure, enforced by the server. In realistic mode the commander's packet holds a
bearing, an angular mast height and an angular width for each ship — what a lens delivers — and
*no* range, course, speed or name. The client cannot display what it was never sent. Training mode
adds the true values. One `CMD|TRAINING` from any station flips it for everyone, because the mode
is a field in the shared `GameState`, read under the lock by the game loop when it builds each
station's snapshot. Events are filtered the same way: "torpedo hit" is training-only; a real crew
gets "detonation after 62 seconds".

**Q: Why not send everything and let the client hide it?**
Because then the data is on the player's machine, and hiding is only a promise. Filtering at the
source is the only version of fog-of-war that a modified client cannot defeat.

**Q: Producer-consumer anywhere?**
Three places.
1. Game events (`TORPEDO_HIT`, `CHARGES_DROPPED`) are produced by whichever thread causes them — game loop, AI, or a client handler — into a `ConcurrentLinkedQueue`. The game loop is the single consumer and drains it each tick. Lock-free, so producers never block.
2. **Repair orders.** The engineer's handler thread produces into a `LinkedBlockingQueue`; the `RepairCrew` thread consumes. This one uses `take()`, which **blocks** — the crew thread sleeps using no CPU until an order arrives, instead of a polling loop asking "anything yet?" ten times a second.
3. In the client, the Swing thread pushes one-shot sounds into a queue that the audio thread drains while it mixes.

**Q: Why a `LinkedBlockingQueue` for repairs but a `ConcurrentLinkedQueue` for events?**
Different consumers. The game loop is *already* waking 20 times a second, so it should poll and
move on — blocking would stall the tick. The repair crew has nothing else to do, so blocking is
exactly right: it costs no CPU and wakes instantly. The queue type follows from whether the
consumer has other work.

**Q: Where is the resource contention the player actually feels?**
The battery and the damage party. The motors and the bilge pumps both drain the battery from
different threads. There is one repair party, so ordering a second compartment puts it in the
queue behind the first. And running the pumps or hammering in a flooded compartment makes noise,
which is what the escort is listening for. Every repair decision is therefore also a stealth
decision — that is the game.

**Q: Your sound has no audio files. What is it doing?**
Synthesising every sample. A depth charge is a noise burst times an exponential decay plus a
38 Hz thud; a propeller is one decaying thump per blade where the beat rate is literally the
RPM the server reported; a ping is a tone with a delayed echo. The audio thread fills a buffer
and writes it to a `SourceDataLine` at 22 kHz.

**Q: Is the server CPU-bound or I/O-bound?**
I/O-bound. Almost all the time the threads are blocked in `accept()`, `readLine()`, or
`Thread.sleep()`, which costs no CPU. The physics is trivial arithmetic on a handful of objects.

**Q: How would you scale this to 100 players?**
Thread-per-client stops being sensible somewhere in the hundreds, because each thread costs about
1 MB of stack. I would move to a thread pool (`ExecutorService`) or non-blocking NIO with a
selector. For a 2–6 player co-op game, thread-per-client is the clearer and more appropriate
design, and it makes the concurrency visible rather than hiding it in a framework.

---

## Live demo script (rehearse this once)

1. Start `Server.main()`. Point at the console: four background threads announce themselves.
2. Start client 1, pick **KOMM**. Start client 2, pick **ENG**. Server log shows two more threads.
3. **ENG**: stay surfaced, `FULL`, steer about `025`. Say why: diesels are fast and recharge, but visible for 4 km.
4. **KOMM**: hold `D` to train the scope onto the convoy. Training mode labels each ship with its true range and course.
5. Press `F1`. The labels vanish **on both clients at once**. Say: *the server has stopped sending them.*
6. **ENG**: `PERISCOPE 12`, `SLOW`. **KOMM**: `W` to raise the scope, `Z` for 6x, click the matching ship in the manual, `R` for a range, `X` to mark. Wait, range and mark again, PLOT>TDC, `SPACE`. Press `F1` back on if you want the hit confirmed on screen.
7. The escort starts an attack run. Charges land, a compartment is holed, water comes in.
8. **ENG**: click the damaged compartment on the damage board → the party starts work. Order a second one → it queues, because there is only one party.
9. **ENG**: dive to **120 m** and go `STOP` with pumps off. Acoustics reads *silent, below the layer*. Watch the escort lose contact and wander off — while your flooding keeps rising. Say the line out loud: *pumping would save the boat and give away the boat.*
10. Kill one client's window. Server log: "heartbeat timeout". The other player keeps playing.
11. Run `RaceConditionDemo` and show the numbers.

**If something breaks mid-demo:** say what you expected, what happened, and which thread is
involved. Diagnosing it out loud scores better than a demo that happens to work.

---

## Honest disclosure
If you are asked how much you wrote: say you designed the system and used an AI assistant for
implementation, and that you can explain any part of it. Then do. That answer costs you far less
than being caught out claiming otherwise.
