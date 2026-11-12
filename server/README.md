# The Bleed Valve: Game Server (Java)

A multi-threaded TCP server for a co-op WWII U-boat game. Pure Java, no libraries.

## Run (IntelliJ)
1. Open the project. Run `Server.main()`. It listens on port **5050**.
2. Run `ClientMain.main()` from `client/`, or `TestClient.main()` with arguments `127.0.0.1 5050 ALL`.
3. Run `RaceConditionDemo.main()` for the lock demo used in the report.

From a terminal:
```
javac -d out src/*.java
java -cp out Server
java -cp out TestClient 127.0.0.1 5050 KOMM
```
For LAN play use the server machine's IP, and allow Java through the firewall.

## Threads
| Thread | Job |
|---|---|
| main | `accept()` loop: one new `ClientHandler` thread per player |
| Client-N | reads one player's commands and applies them to GameState |
| GameLoop | 20 Hz: physics, then broadcasts a per-station STATE |
| EnemyAI | 5 Hz: escort state machine, patrol → search → attack run |
| RepairCrew | blocks on a queue of repair orders and works them one at a time |
| Heartbeat | kicks players silent for more than 6 s |

Synchronisation:
- Shared `GameState` guarded by one `ReentrantLock`. One lock means deadlock is impossible.
- Events: `ConcurrentLinkedQueue`, many producers, GameLoop is the sole consumer.
- Repair orders: `LinkedBlockingQueue`. `take()` **blocks**, so the crew thread sleeps using no CPU until work arrives.
- Sockets are written to **after** releasing the lock. `ClientHandler.send()` is `synchronized`, because two threads write to each socket.

## Protocol (TCP, one message per line)
**Client → Server**
| Message | Meaning |
|---|---|
| `HELLO\|KOMM` / `HELLO\|ENG` / `HELLO\|ALL` | pick a station (ALL = debug/solo) |
| `PING` | heartbeat, send every 1 s (reply: `PONG`) |
| `CMD\|FIRE\|<bearing>` | KOMM: fire (needs depth ≤ 20 m, a loaded tube, an intact bow room) |
| `CMD\|PERISCOPE\|<bearing>` | KOMM: rotate the periscope |
| `CMD\|SCOPE\|<0/1>` | KOMM: lower / raise the periscope (15 m or shallower) |
| `CMD\|TRAINING\|<0/1>` | any station: switch the whole server between realistic and training |
| `CMD\|SPEED\|<0-3>` | ENG: stop / slow / half / full |
| `CMD\|DEPTH\|<m>` | ENG: target depth |
| `CMD\|HEADING\|<deg>` | ENG: steer |
| `CMD\|PUMPS\|<0/1>` | ENG: bilge pumps |
| `CMD\|HYDRO\|<bearing>` | ENG: aim the hydrophone |
| `CMD\|REPAIR\|<0-3>` | ENG: send the damage party to a compartment |
| `CMD\|RESTART` | start a new patrol |
| `QUIT` | leave |

**Server → Client**
| Message | Meaning |
|---|---|
| `STATE\|{json}` | 20×/s, filtered per station **and per mode** (see below). |
| `EVENT\|<NAME>\|<detail>` | TORPEDO_FIRED/HIT/MISS, CHARGES_DROPPED, DEPTH_CHARGE_HIT/NEAR, ATTACK_RUN, DESTROYER_HUNTING, DESTROYER_LOST_CONTACT, CHARGES_EXHAUSTED, REINFORCEMENT, REPAIR_ORDERED/START/DONE, PUMPS_FAILED, GAME_OVER, RESET |
| `ERROR\|<text>` | the command was rejected |

## Realistic and training modes: selective disclosure
The server decides what each crew member is allowed to know, and withholds the rest.

| | REALISTIC (1943 instruments) | TRAINING |
|---|---|---|
| Commander's contacts | bearing, angular mast height, angular width, bow direction. **No range, course, speed or name.** | adds true range, course, speed, name, AI state |
| Engineer's electrics | battery volts and amps | adds battery %, hull %, flooding % |
| Damage board | rounded to coarse steps ("holed", "knee-deep") | exact percentages |
| Events | only what can be heard or felt: detonations, splashes, damage and repair reports | adds hit/miss, escort state changes, reinforcements |

In realistic mode the true figures never leave the server, so no client can show them. Contacts are
sent only when someone can see: the bridge watch when surfaced, or a raised periscope at 15 m or less.
`asdic` and `screws` (0..1) go to every station: they are what the crew hears through the hull.

## Gameplay model
- **Speed.** Submerged, the electric motors give 0 / 3 / 6 / 8 knots. On the surface the diesels give 2.2× that and recharge the battery — fast, but visible for 4 km.
- **Detection.** The escorts hear noise. Speed, pumps and hammering in a flooded compartment all add to it.
  - Slant range, not map distance: depth is real separation.
  - **Below 80 m** (the thermocline) sound bends away: detection range × 0.28.
  - **Silent running** (stopped, pumps off, no repairs) × 0.45.
  - An escort doing over 8 knots is nearly deaf to its own propellers (× 0.25) — the gap you escape through.
  - Periscope depth or surfaced overrides all of it: you can simply be seen.
- **Periscope.** A raised scope is visible out to 1.3–2.6 km depending on your speed. Lower it between looks.
- **Zigzag.** The convoy alters course together every 80–150 s (45–85 s once attacked), so a firing solution goes stale.
- **Attack runs.** PATROL → SEARCH → RUN_IN → OVER → TURN → RUN_IN. The escort keeps coming back until it loses contact or runs out of its 30 charges.
- **Damage.** Four compartments. Bow room kills the tubes, control room makes the planes sluggish, engine room caps speed. Damaged compartments flood, and flooding past 50% drags the boat down by itself.
- **Repairs.** One damage party, so a second order queues. The party mends the hole; the pumps clear the water; both compete for the battery.
- **Win / lose.** Sink all three merchants to win. You lose if the hull goes, the boat floods, you are crushed, or the convoy escapes.
