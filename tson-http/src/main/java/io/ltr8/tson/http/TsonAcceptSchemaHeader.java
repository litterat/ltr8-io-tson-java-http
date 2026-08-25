package io.ltr8.tson.http;

import io.ltr8.tson.schema.TsonCanonicalIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The {@code TSON-Accept-Schema} request field: which schema versions a client will accept <b>back</b>.
 *
 * <h2>Why this is not {@code TSON-Schema}</h2>
 *
 * <p>{@link TsonSchemaHeader} says what the body of <em>this</em> message is — it is the schema layer's
 * {@code Content-Type}. This says what the client wants in the reply, so it is the schema layer's
 * {@code Accept}. HTTP keeps those in separate fields for a reason a single field cannot work around: one
 * message often asks both at once, a POST sending a v1 order and wanting a v2 confirmation being the ordinary
 * case.
 *
 * <p>Overloading {@code TSON-Schema} is tempting on a GET, where there is no body for it to describe and the
 * meaning would be unambiguous by vacuity. That gives a field whose meaning depends on the method, which is
 * worse than two fields and something HTTP field semantics avoid.
 *
 * <h2>The field</h2>
 *
 * <p>An RFC 9651 sf-list of sf-strings, each optionally carrying {@code ;q=} — the same shape and the same
 * meaning as {@code Accept}'s quality values, because a client may understand several versions and prefer one:
 *
 * <pre>{@code
 * TSON-Accept-Schema: "https://schemas.example.com/2026/33/app/order-2.tn",
 *                     "https://schemas.example.com/2026/33/app/order-1.tn";q=0.5
 * }</pre>
 *
 * <p><b>Absence means the server chooses</b>, as {@code Accept}'s absence means "anything". That keeps every
 * existing client working and makes this additive: a client that says nothing gets whatever the server
 * prefers, which is normally its newest version. {@code q=0} explicitly refuses a version.
 *
 * <p>The reply names what was actually chosen in its own {@code TSON-Schema} and in the body's {@code !!schema},
 * so a response stays self-describing without this field having a response form. When nothing acceptable can
 * be served, the answer is {@code 406}, mirroring {@code Accept}.
 *
 * <p>Matching is by <b>canonical identity</b> (§2.2.1), so a client may write the same version with a
 * different scheme or a {@code ?sha256=} pin and still be understood.
 */
public final class TsonAcceptSchemaHeader {

    /** The field name. Registered-style spelling, no {@code X-} (RFC 6648), matching {@code TSON-Schema}. */
    public static final String NAME = "TSON-Accept-Schema";

    /** One entry: a schema reference and the quality the client attached to it. */
    public record Acceptable(String schemaReference, double quality) {

        public Acceptable {
            if (quality < 0 || quality > 1) {
                throw new IllegalArgumentException("a quality value is between 0 and 1, not " + quality);
            }
        }
    }

    private TsonAcceptSchemaHeader() {
    }

    /**
     * Parses the field, in the order written.
     *
     * @return the versions it names, empty if the message carried no such header — which means "any"
     * @throws TsonHttpException 400 if the value is present but malformed
     */
    public static List<Acceptable> parse(String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) {
            return List.of();
        }
        List<Acceptable> parsed = new ArrayList<>();
        for (String member : splitTopLevel(fieldValue)) {
            String reference = member;
            double quality = 1.0;
            int semicolon = member.indexOf(';', endOfQuotedString(member, fieldValue));
            if (semicolon >= 0) {
                reference = member.substring(0, semicolon);
                quality = quality(member.substring(semicolon + 1), fieldValue);
            }
            String uri = TsonSchemaHeader.parse(reference).orElseThrow(() -> malformed(fieldValue,
                    "an empty schema reference names no version"));
            parsed.add(new Acceptable(uri, quality));
        }
        return List.copyOf(parsed);
    }

    /**
     * Chooses from {@code available} the version this client most prefers, or empty if it will take none of
     * them — which is a 406, not a fallback.
     *
     * <p>An empty {@code acceptable} means the client said nothing, so {@code preferred} is used. Ties in
     * quality are broken by the order the client wrote them, which is what a client's own ordering is for.
     *
     * @param acceptable what the client will take, from {@link #parse}
     * @param available  the schema identities this endpoint can produce
     * @param preferred  what to serve when the client expressed no preference
     */
    public static Optional<String> choose(List<Acceptable> acceptable, List<String> available,
                                          String preferred) {
        if (acceptable.isEmpty()) {
            return Optional.of(preferred);
        }
        List<Acceptable> ranked = new ArrayList<>(acceptable);
        // Stable, so equal qualities keep the client's own order.
        ranked.sort(Comparator.comparingDouble(Acceptable::quality).reversed());
        for (Acceptable wanted : ranked) {
            if (wanted.quality() == 0) {
                continue;   // q=0 is a refusal, not a weak preference.
            }
            for (String candidate : available) {
                if (sameIdentity(wanted.schemaReference(), candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** {@code references} as a field value, in the order given, each quoted as an sf-string. */
    public static String format(List<String> references) {
        List<String> quoted = new ArrayList<>();
        references.forEach(reference -> quoted.add(TsonSchemaHeader.format(reference)));
        return String.join(", ", quoted);
    }

    private static boolean sameIdentity(String wanted, String candidate) {
        try {
            return TsonCanonicalIdentity.canonicalize(wanted).equals(
                    TsonCanonicalIdentity.canonicalize(candidate));
        } catch (RuntimeException notAnIdentity) {
            // A reference that is not a legal identity names no version this server serves; it is simply not
            // a match. Refusing the whole header for one bad member would deny a client its other choices.
            return false;
        }
    }

    /** Splits on commas outside quoted strings -- a schema URI may not contain one, but an escape may. */
    private static List<String> splitTopLevel(String fieldValue) {
        List<String> members = new ArrayList<>();
        boolean inQuotes = false;
        int start = 0;
        for (int i = 0; i < fieldValue.length(); i++) {
            char c = fieldValue.charAt(i);
            if (c == '\\' && inQuotes) {
                i++;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                members.add(fieldValue.substring(start, i).trim());
                start = i + 1;
            }
        }
        members.add(fieldValue.substring(start).trim());
        members.removeIf(String::isBlank);
        if (members.isEmpty()) {
            throw malformed(fieldValue, "a present field names at least one version");
        }
        return members;
    }

    private static int endOfQuotedString(String member, String fieldValue) {
        boolean inQuotes = false;
        for (int i = 0; i < member.length(); i++) {
            char c = member.charAt(i);
            if (c == '\\' && inQuotes) {
                i++;
            } else if (c == '"') {
                inQuotes = !inQuotes;
                if (!inQuotes) {
                    return i;
                }
            }
        }
        throw malformed(fieldValue, "each member is a quoted sf-string");
    }

    private static double quality(String parameters, String fieldValue) {
        for (String parameter : parameters.split(";")) {
            String[] halves = parameter.split("=", 2);
            if (halves.length == 2 && halves[0].trim().equals("q")) {
                try {
                    double quality = Double.parseDouble(halves[1].trim());
                    if (quality < 0 || quality > 1) {
                        throw malformed(fieldValue, "a quality value is between 0 and 1");
                    }
                    return quality;
                } catch (NumberFormatException notANumber) {
                    throw malformed(fieldValue, "'" + halves[1].trim() + "' is not a quality value");
                }
            }
        }
        return 1.0;
    }

    private static TsonHttpException malformed(String fieldValue, String why) {
        return new TsonHttpException(TsonHttpException.BAD_REQUEST,
                TsonHttpException.TYPES + "malformed-schema-header", "Malformed " + NAME + " header",
                "the " + NAME + " header '" + fieldValue + "' is not a valid RFC 9651 sf-list: " + why,
                List.of(), null);
    }
}
