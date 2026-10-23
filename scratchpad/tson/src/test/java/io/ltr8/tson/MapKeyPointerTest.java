package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A map key's step in a diagnostic's pointer, after #751 carried an annotated key into it.
 *
 * <p>{@code MapAbstractReader} now replaces the placeholder with {@code String.valueOf(key)} for every key that is
 * not a single token. That is right for an annotated text key and wrong for the other two cases it reaches: a
 * compound key is written as the decoded value's Java {@code toString}, which in bind mode names the bound class, and
 * an annotated scalar key is written as its decoded value where the same key unannotated is written as its spelling.
 */
class MapKeyPointerTest {

    private static final String ID = "https://example.test/keys.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              point => { x: int32  y: int32 }
              tally => { counts?: {int32 => int32}  grid?: {point => int32} }
            }
            """.formatted(ID);

    private static final String HEAD = "!!schema:\"" + ID + "\"\n";

    public record Point(int x, int y) {
    }

    public record Tally(Map<Integer, Integer> counts, Map<Point, Integer> grid) {
    }

    private static Tson tson() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(uri -> SCHEMA))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("point", Point.class, "tally", Tally.class))
                                .orElse(SchemaMetaNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));
        tson.resolve(SCHEMA);
        return tson;
    }

    private static List<String> paths(List<Diagnostic> problems) {
        return problems.stream().map(d -> d.path().orElse("<none>")).toList();
    }

    private static List<Diagnostic> bound(Tson tson, String document) {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        try {
            tson.objectReader().withDiagnostics(problems)
                    .read(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)), Tally.class);
        } catch (RuntimeException ignored) {
            // A collecting read may still throw once it cannot construct the root; the diagnostics are the result.
        }
        return problems.diagnostics();
    }

    /** The baseline: an unannotated key is written as its spelling, so {@code 0x10} stays {@code 0x10}. */
    @Test
    void baselineAPlainKeyIsWrittenAsItsSpelling() {
        assertEquals(List.of("/counts/0x10"), paths(tson().validate(HEAD + "!tally { counts: { 0x10 => oops } }")));
    }

    /** On a53c3f66: {@code /counts/16}. The annotation changes the pointer, though it does not change the key. */
    @Test
    void anAnnotatedKeyIsWrittenAsItsSpellingToo() {
        assertEquals(List.of("/counts/0x10"),
                paths(tson().validate(HEAD + "!tally { counts: { @doc:\"x\" 0x10 => oops } }")));
    }

    /**
     * On a53c3f66: {@code /grid/TsonRecord[fields={x=1, y=2}, typeRef=Optional[point], annotations=[]]}. On
     * 9cf9a54c it was {@code /grid/?}.
     */
    @Test
    void aCompoundKeyIsNoJavaRenderingInATree() {
        List<String> paths = paths(tson().validate(HEAD + "!tally { grid: { {x: 1 y: 2} => oops } }"));
        assertEquals(1, paths.size(), () -> "" + paths);
        assertFalse(paths.getFirst().contains("TsonRecord"), paths.getFirst());
    }

    /** On a53c3f66: {@code /grid/Point[x=1, y=2]}, naming the bound class in a path a server hands its client. */
    @Test
    void aCompoundKeyNamesNoBoundClass() {
        List<String> paths = paths(bound(tson(), HEAD + "!tally { grid: { {x: 1 y: 2} => oops } }"));
        assertEquals(1, paths.size(), () -> "" + paths);
        assertFalse(paths.getFirst().contains("Point"), paths.getFirst());
    }

    /** On a53c3f66 the message reads {@code duplicate key 'TsonRecord[fields={x=1, y=2}, …]' in '{point => int32}'}. */
    @Test
    void aDuplicateCompoundKeysMessageIsNoJavaRendering() {
        List<Diagnostic> problems = tson().validate(HEAD + "!tally { grid: { {x: 1 y: 2} => 1  {x: 1 y: 2} => 2 } }");
        assertEquals(List.of(Diagnostic.Code.DUPLICATE_MAP_KEY), problems.stream().map(Diagnostic::code).toList());
        assertFalse(problems.getFirst().message().contains("TsonRecord"), problems.getFirst().message());
        assertFalse(problems.getFirst().path().orElse("").contains("TsonRecord"), problems.getFirst().path().orElse(""));
    }
}
