package gnu.client.common;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class GnuLog {
    private static final File LOG_FILE = new File("/tmp/gnu_debug.log");

    private GnuLog() {}

    public static synchronized void log(String message) {
        String line = "[GNUClient] " + message;
        System.out.println(line);
        try (FileWriter fw = new FileWriter(LOG_FILE, true);
                BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(line);
            bw.newLine();
        } catch (Exception ignored) {
        }
    }

    public static synchronized void logError(String prefix, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        log(prefix + ": " + sw.toString());
    }
}
