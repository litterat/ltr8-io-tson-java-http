package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

class ApiSchemaProbeTest {
    @Test
    void resolves() throws IOException {
        String source;
        try (InputStream in = ApiSchemaProbeTest.class.getResourceAsStream("/api-1.tn")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var problems = Tson.builder().build().validateSchema(source);
        System.out.println("PROBE api-1.tn -> " + (problems.isEmpty() ? "RESOLVES" : problems));
    }
}
