import java.lang.management.ManagementFactory;
import java.util.regex.Pattern;

/**
 * Standalone measurement for UPSTREAM.md #21 -- the per-character cost of TsonDataEmitter.quotedString's
 * control-character test. Run with: java QuotedStringAllocation.java
 */
public class QuotedStringAllocation {
    private static final Pattern CONTROL_CHAR = Pattern.compile("[\\x00-\\x1f]");

    static long alloc() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    static int viaRegex(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (CONTROL_CHAR.matcher(String.valueOf(c)).matches()) {
                n++;
            }
        }
        return n;
    }

    static int viaCompare(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 0x1f) {
                n++;
            }
        }
        return n;
    }

    public static void main(String[] args) {
        // A realistic payload: the schema URI a self-describing reply names, plus a SKU.
        String uri = "https://schemas.example.com/2026/33/app/order-1.tn";
        String sku = "ABC-1";
        int reps = 200_000;

        int sink = 0;
        for (int i = 0; i < 20_000; i++) { sink += viaRegex(uri) + viaCompare(uri); }

        long a = alloc();
        for (int i = 0; i < reps; i++) { sink += viaRegex(uri) + viaRegex(sku); }
        long regex = alloc() - a;

        a = alloc();
        for (int i = 0; i < reps; i++) { sink += viaCompare(uri) + viaCompare(sku); }
        long compare = alloc() - a;

        int chars = uri.length() + sku.length();
        System.out.printf("%d chars per iteration (a URI + a SKU), %d iterations, sink=%d%n", chars, reps, sink);
        System.out.printf("  regex   : %,d bytes total = %.1f bytes/char%n", regex, regex / (double) (reps * chars));
        System.out.printf("  compare : %,d bytes total = %.1f bytes/char%n", compare, compare / (double) (reps * chars));
        System.out.printf("  a reply naming its schema (~%d quoted chars) costs ~%.0f bytes of garbage%n",
                chars, regex / (double) reps);
    }
}
