package io.ltr8.tson.http.api;

import io.ltr8.annotation.Typename;

/**
 * An encoding an operation reads and writes its payloads in: TSON text ([TSON-DATA]), or TSON's JSON encoding
 * ([TSON-JSON]). An encoding rather than a media type because that is the contract a client relies on -- {@code
 * application/tson+json} and {@code application/json} are two labels for the one JSON encoding, and which a
 * response carries is {@code Accept}'s to negotiate, not the description's to fix.
 */
@Typename(name = "encoding")
public enum Encoding {
    TSON, JSON
}
