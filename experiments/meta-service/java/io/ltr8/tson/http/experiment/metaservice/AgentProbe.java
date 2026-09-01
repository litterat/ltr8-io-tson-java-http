package io.ltr8.tson.http.experiment.metaservice;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent's two resolved layers -- {@code agent-1.tn}, the plan a surface grammar reads to, and
 * {@code agent-vm-1.tn}, the agent it compiles to -- resolve at Revision 34 and read, up to one gap.
 *
 * <p>Read here: a plan whose arguments are references only ({@code arg} recursing through {@code record} to a
 * {@code selector}); a compiled agent whose pool holds names, a type and a method, and whose {@code code} mixes
 * bare mnemonics with labelled operands -- the {@code @disjoint} choice read tag-free, an enum beside a record.
 * Not readable yet: any constant. {@code constant => unknown}, core's escape hatch, has no compiled reader, so a
 * plan or an agent carrying one reports {@code NOT_IMPLEMENTED} at the constant -- the same gap that gates
 * {@code rpc-1.tn}'s payload slot. Pinned to flip.
 */
class AgentProbe {

    static final String PLAN_ID = "https://tson.io/2026/34/ltr8/http/agent-1.tn";
    static final String AGENT_ID = "https://tson.io/2026/34/ltr8/http/agent-vm-1.tn";
    static final String ORDERS_ID = "https://schemas.example.com/2026/34/experiment/meta-service/orders-1.tn";

    static String read(String file) {
        try {
            return Files.readString(Path.of(System.getProperty("experiments.dir", "../experiments"))
                    .resolve("meta-service").resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Tson tson() {
        Map<String, String> lib = new LinkedHashMap<>();
        lib.put(PLAN_ID, read("agent-1.tn"));
        lib.put(AGENT_ID, read("agent-vm-1.tn"));
        return Tson.builder().schemaSource(TsonSchemaSource.ofMap(lib)).build();
    }

    @Test
    void bothLayersResolve() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(read("agent-1.tn")));
        assertEquals(List.of(), tson.validateSchema(read("agent-vm-1.tn")));
    }

    /** A plan of two steps, the second's input a reference into the first's response, the return a reference. */
    @Test
    void aPlanOfReferencesReads() {
        String plan = """
            !!schema:"%s"
            !plan {
              interface: "%s"
              steps: [
                { name: placed  method: place_order
                  input: { order => { ref: { step: input  segments: [ { field: order } ] } } } }
                { name: fetched  method: get_order
                  input: { id => { ref: { step: placed  segments: [ { field: id } ] } } } }
              ]
              return: { ref: { step: fetched } }
            }""".formatted(PLAN_ID, ORDERS_ID);
        TsonValue value = tson().treeReader().read(plan);

        assertEquals("placed", value.at("/steps/1/input/id/ref/step").asString().orElseThrow());
        assertEquals("id", value.at("/steps/1/input/id/ref/segments/0/field").asString().orElseThrow());
        assertEquals("fetched", value.at("/return/ref/step").asString().orElseThrow());
    }

    /**
     * A compiled agent: names, a type and a method in the pool; mnemonics beside labelled operands. Tagged,
     * because the reader does not yet admit an untagged value at a disjoint choice -- pinned below.
     */
    @Test
    void aCompiledAgentReads() {
        String agent = """
            !!schema:"%s"
            !agent {
              version:   2
              interface: "%s"
              pool:      [ { type: order_ref }  { name: id }  { method: get_order } ]
              max_stack: 3
              slots:     1
              code:      [ !op { make: 0 }  !op { load: 0 }  !op { get: 1 }  !op { set: 1 }  !op { call: { method: 2 } }
                           !simple_op STORE  !op { load: 0 }  !simple_op RET ]
              debug:     { 0 => fetched }
            }""".formatted(AGENT_ID, ORDERS_ID);
        TsonValue value = tson().treeReader().read(agent);

        assertEquals("STORE", value.at("/code/5").asString().orElseThrow());
        assertEquals("RET", value.at("/code/7").asString().orElseThrow());
        assertEquals(2, value.at("/code/4/call/method").asInt().orElseThrow());
        assertEquals("get_order", value.at("/pool/2/method").asString().orElseThrow());
    }

    /**
     * <b>Open, upstream:</b> {@code instruction} is declared {@code @disjoint}, the resolver accepts the assertion
     * (an enum beside a record: string class beside brace class), and §5.4 lets a disjoint choice be read
     * tag-free -- but the reader still demands the tag. The gap is specific: {@code (text | integer)} IS read
     * untagged, so tag-free dispatch exists; it is the <em>enum</em> variant it does not cover -- {@code (flag |
     * rec)} refuses an untagged {@code A} exactly as {@code instruction} refuses a bare {@code RET}. So the VM's
     * "two discrimination classes, so the choice is tag-free" is true of the schema and not yet of the
     * implementation. Written to fail the day it is.
     */
    @Test
    void anUntaggedInstructionIsStillRefusedAtADisjointChoice() {
        String agent = """
            !!schema:"%s"
            !agent { version: 2  interface: "%s"  pool: [ { method: get_order } ]  max_stack: 1  slots: 0
                     code: [ { call: { method: 0 } }  RET ] }""".formatted(AGENT_ID, ORDERS_ID);
        List<Diagnostic> problems = tson().validate(agent);
        assertTrue(problems.stream().anyMatch(d -> d.message().contains("'instruction' is a choice")
                && d.message().contains("requires an explicit type annotation")), () -> "" + problems);
    }

    /** The gap: a constant anywhere -- an `or`, a CONST pool entry, a literal argument -- is unreadable today. */
    @Test
    void aConstantIsTheLibrarysGap() {
        String withConstant = """
            !!schema:"%s"
            !plan {
              interface: "%s"
              steps: [ { name: a  method: place_order  input: { order => { value: { sku: A-100  quantity: 2 } } } } ]
              return: { ref: { step: a } }
            }""".formatted(PLAN_ID, ORDERS_ID);
        var problems = TsonDiagnosticsReceiver.collecting();
        tson().treeReader().withDiagnostics(problems).read(withConstant);

        List<Diagnostic> reported = problems.diagnostics();
        assertEquals(1, reported.size(), () -> "" + reported);
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, reported.getFirst().code());
        assertEquals("/steps/0/input/order/value", reported.getFirst().path().orElseThrow());
    }

    /** A malformed step name is refused by the `name` role -- the identifier grammar as a pattern. */
    @Test
    void aNameThatIsNotAnIdentifierIsRefused() {
        String plan = """
            !!schema:"%s"
            !plan { interface: "%s"
                    steps: [ { name: "1st step"  method: get_order  input: { id => { ref: { step: x } } } } ]
                    return: { ref: { step: x } } }""".formatted(PLAN_ID, ORDERS_ID);
        List<Diagnostic> problems = tson().validate(plan);
        assertTrue(problems.stream().anyMatch(d -> d.path().orElse("").equals("/steps/0/name")), () -> "" + problems);
    }
}
