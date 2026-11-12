import java.util.concurrent.locks.ReentrantLock;

/**
 * For the report: why GameState needs a lock.
 *
 * The engine and the bilge pumps both drain the SAME battery from two threads.
 * Battery starts at 1,000,000 units; each thread drains 500,000. Correct answer: 0.
 *
 *   java RaceConditionDemo
 */
public class RaceConditionDemo {
    // volatile = every read/write really goes to memory (so the JIT can't "optimise the bug away").
    // It does NOT make battery - 1 atomic: that still needs the lock.
    static volatile int battery;
    static final ReentrantLock lock = new ReentrantLock();

    static void drainUnsafe() { battery = battery - 1; }   // READ, SUBTRACT, WRITE: can be interrupted midway

    static void drainSafe() {
        lock.lock();
        try { battery = battery - 1; }
        finally { lock.unlock(); }
    }

    static int run(Runnable drainOnce) throws InterruptedException {
        battery = 1_000_000;
        Runnable crew = () -> { for (int i = 0; i < 500_000; i++) drainOnce.run(); };
        Thread engine = new Thread(crew, "Engine");
        Thread pumps = new Thread(crew, "Pumps");
        engine.start(); pumps.start();
        engine.join(); pumps.join();   // wait for both to finish
        return battery;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Expected battery after both drains: 0\n");
        for (int i = 1; i <= 3; i++) {
            System.out.printf("Run %d  WITHOUT lock: %,d   WITH lock: %,d%n",
                    i, run(RaceConditionDemo::drainUnsafe), run(RaceConditionDemo::drainSafe));
        }
        System.out.println("\nWithout the lock, drains are lost when both threads read the same value at once.");
    }
}
