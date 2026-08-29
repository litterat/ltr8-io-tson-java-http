package io.ltr8.tson.http.helidon.demo;

/** What to try once the server is up. Printed rather than documented, so it is never out of date. */
final class Demo {

    private Demo() {
    }

    static void announce(String adapter, int port) {
        String base = "http://localhost:" + port;
        System.out.println("""

                TSON order server -- %s -- on %s

                A valid order (201, doubled quantity):
                  curl -s %s/orders -H 'Content-Type: application/tson' --data-binary '
                  !!schema:"%s"
                  !order { sku: "ABC-1"  quantity: 3 }'

                An invalid one (400, every problem at once -- not just the first):
                  curl -s %s/orders -H 'Content-Type: application/tson' --data-binary '
                  !!schema:"%s"
                  !order { }'

                A body that is not TSON (415):
                  curl -s -o /dev/null -w '%%{http_code}\\n' %s/orders -H 'Content-Type: application/json' -d '{}'

                The schema this server validates against, published at its own identity path:
                  curl -s %s/2026/34/app/order-1.tn

                The schema its error bodies conform to:
                  curl -s %s/2026/34/ltr8/http/problem-1.tn

                Note the schema's identity is %s, not localhost:
                [TSON-DATA] §2.2.1 forbids a port in an identifying URI, so a name and a
                fetch location are separate things. This server publishes the document at the
                identity's *path*; a client maps the host to wherever it really lives.

                Ctrl-C to stop.
                """.formatted(adapter, base,
                base, OrderServer.SCHEMA_ID,
                base, OrderServer.SCHEMA_ID,
                base, base, base, OrderServer.SCHEMA_ID));
    }
}
