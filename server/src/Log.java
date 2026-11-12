/** Prints every log line with the time and the name of the thread that wrote it. */
public class Log {
    public static void info(String msg) {
        System.out.printf("%tT [%s] %s%n", System.currentTimeMillis(), Thread.currentThread().getName(), msg);
    }
}
