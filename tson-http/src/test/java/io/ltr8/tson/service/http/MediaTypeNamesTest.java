package io.ltr8.tson.service.http;

import io.ltr8.tson.http.TsonMediaType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MediaTypeNames} holds the names {@link TsonMediaType} renders, written out because {@code tson-service}
 * takes no dependency on {@code tson-http}. This test is what keeps the two in step, and it lives in {@code
 * tson-http}'s test tree because that is the only place that sees both: the package-private names on the class path,
 * and {@code TsonMediaType} beside them.
 */
class MediaTypeNamesTest {

    @Test
    void eachNameIsWhatTsonMediaTypeRenders() {
        assertEquals(TsonMediaType.APPLICATION_TSON.toString(), MediaTypeNames.APPLICATION_TSON);
        assertEquals(TsonMediaType.APPLICATION_JSON.toString(), MediaTypeNames.APPLICATION_JSON);
        assertEquals(TsonMediaType.APPLICATION_TSON_JSON.toString(), MediaTypeNames.APPLICATION_TSON_JSON);
    }
}
