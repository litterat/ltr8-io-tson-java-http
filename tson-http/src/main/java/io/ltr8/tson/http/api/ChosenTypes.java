package io.ltr8.tson.http.api;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.RefBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The startup rule for a bound request record holding a dependent field ({@code payload: kind!}, [TSON-SCHEMA]
 * §5.2): <b>every type a sender may name in the sibling must be one this server binds</b>, or the description does
 * not load.
 *
 * <p>A dependent field is read against the type its sibling names in the same body, so the sender chooses the type a
 * value is read against. In bind mode a chosen type no class binds is a {@code MissingBindingException} at the read,
 * which the status policy rightly calls this server's fault, a 500 -- and here the client chose it. So the question is
 * settled where this project settles every binding question, at startup, over what the sender can choose:
 *
 * <ul>
 *   <li>a sibling pinned on the request record ({@code kind?: meta.type_ref = point}) offers its pin alone;</li>
 *   <li>a discriminator offers the pin of each subtype of the request record;</li>
 *   <li>any other sibling offers every member its reference admits in the closure, as the reference's linked index
 *       ({@link RefBody#memberNames()}) holds them.</li>
 * </ul>
 *
 * <p>A chosen type needs a class where reading it builds one: a record or a tuple, an abstract record's subtypes, and
 * the same reached through an alias, a choice's variants, an array's element or a map's key and value. A record or
 * tuple written in place has no name and binds to the class its enclosing component declares, so only the types it
 * reaches are checked. Each such type must bind through the {@code Tson}'s own context ({@link
 * DataBindContext#getDescriptor(String)}, which also analyses the class) and must not be the kernel's vocabulary: a
 * kernel record binds to the schema model over TSON and to nothing over JSON, so a body naming one is a schema-model
 * value in an application's request, or a 500. {@code meta.type_ref} admits the kernel's records, so a free sibling
 * typed by it never passes; such a sibling is pinned, or made a discriminator over bound subtypes.
 *
 * <p>A request record nothing binds is read as a tree, where no type needs a class, and is not checked.
 */
final class ChosenTypes {

    /** How many unbindable types a refusal names before it summarises the rest. */
    private static final int NAMED = 6;

    private ChosenTypes() {
    }

    /**
     * Refuses {@code request} at {@code label} if it is bound and a dependent field of it lets a sender choose a
     * type this server cannot bind.
     *
     * @param entries the description's closure, the linked schema's entries
     * @param context the bind context the {@code Tson} reads with
     */
    static void requireBound(String label, TypeRef request, Function<String, Top> entries, DataBindContext context) {
        if (!(entries.apply(request.name()) instanceof RecordBody record) || !binds(context, request.name())) {
            return;
        }
        for (Map.Entry<String, RecordField> field : record.fields().entrySet()) {
            Optional<String> dependentOn = field.getValue().sibling();
            if (dependentOn.isEmpty()) {
                continue;
            }
            String sibling = dependentOn.get();
            String spelled = field.getValue().type().text();
            whyNot(request.name(), context).ifPresent(why -> {
                throw new IllegalArgumentException("'" + label + "' takes '" + request.name() + "', which holds the"
                        + " dependent field '" + field.getKey() + ": " + spelled + "', and " + why + " -- a"
                        + " dependent field binds to Object, which the bind context registers as an atom"
                        + " (TsonBindings does)");
            });
            Map<String, String> unbindable = new TreeMap<>();
            for (String chosen : offered(record, sibling, entries)) {
                Set<String> visited = new HashSet<>();
                for (String needed : needingClasses(chosen, entries, visited)) {
                    whyNot(needed, context).ifPresent(why -> unbindable.putIfAbsent(needed, why));
                }
            }
            if (!unbindable.isEmpty()) {
                throw new IllegalArgumentException(refusal(label, request.name(), field.getKey(), spelled, sibling,
                        unbindable));
            }
        }
    }

    /** Whether {@code context} maps {@code name} to a class at all -- before asking whether the class analyses. */
    private static boolean binds(DataBindContext context, String name) {
        try {
            context.nameBinder().resolve(name);
            return true;
        } catch (DataBindException unbound) {
            return false;
        }
    }

    /** The type names a sender may write in {@code sibling} of {@code record}. */
    private static List<String> offered(RecordBody record, String sibling, Function<String, Top> entries) {
        RecordField declared = record.fields().get(sibling);
        if (declared.role() == FieldRole.FIXED && declared.value().isPresent()) {
            return List.of(declared.value().get().text());
        }
        if (record.discriminators().contains(sibling)) {
            List<String> pins = new ArrayList<>();
            for (String subtype : record.chain().subtypes()) {
                if (entries.apply(subtype) instanceof RecordBody member) {
                    RecordField pinned = member.fields().get(sibling);
                    if (pinned != null && pinned.role() == FieldRole.FIXED && pinned.value().isPresent()) {
                        pins.add(pinned.value().get().text());
                    }
                }
            }
            return pins;
        }
        Top type = switch (declared.type()) {
            case TypeRef named -> entries.apply(named.name());
            case TypeInline inPlace -> inPlace.of();         // written in place, as meta.ref<service.exchange> is
        };
        while (type instanceof Alias(TypeRef target)) {
            type = entries.apply(target.name());
        }
        return type instanceof RefBody reference ? reference.memberNames() : List.of();
    }

    /** Every type reading a value of {@code name} builds a class for. */
    private static List<String> needingClasses(String name, Function<String, Top> entries, Set<String> visited) {
        if (!visited.add(name)) {
            return List.of();
        }
        return needingClasses(Optional.of(name), entries.apply(name), entries, visited);
    }

    /** Every type reading a value of {@code type} builds a class for, whether it is named or written in place. */
    private static List<String> needingClasses(TypeExpr type, Function<String, Top> entries, Set<String> visited) {
        return switch (type) {
            case TypeRef named -> needingClasses(named.name(), entries, visited);
            case TypeInline inPlace -> needingClasses(Optional.empty(), inPlace.of(), entries, visited);
        };
    }

    /**
     * Every type reading a value of {@code body} builds a class for. {@code name} is empty for a body written in
     * place, which has no name to bind: an in-place record or tuple builds the class its enclosing component declares.
     */
    private static List<String> needingClasses(Optional<String> name, Top body, Function<String, Top> entries,
                                               Set<String> visited) {
        List<String> needed = new ArrayList<>();
        switch (body) {
            case Alias(TypeRef target) -> needed.addAll(needingClasses(target.name(), entries, visited));
            case RecordBody record -> {
                if (name.isPresent()) {
                    needed.add(name.get());
                    record.chain().subtypes().forEach(sub -> needed.addAll(needingClasses(sub, entries, visited)));
                } else {
                    record.fields().values()
                            .forEach(field -> needed.addAll(needingClasses(field.type(), entries, visited)));
                }
            }
            case TupleBody tuple -> {
                name.ifPresent(needed::add);
                for (TupleElement element : tuple.elements()) {
                    needed.addAll(needingClasses(element.elementType(), entries, visited));
                }
            }
            case ChoiceBody choice -> choice.variants()
                    .forEach(variant -> needed.addAll(needingClasses(variant, entries, visited)));
            case ArrayBody array -> needed.addAll(needingClasses(array.elementType(), entries, visited));
            case MapBody map -> {
                needed.addAll(needingClasses(map.keyType(), entries, visited));
                needed.addAll(needingClasses(map.valueType(), entries, visited));
            }
            case null, default -> {
                // an atom, an enum, a reference, a dependent type: read to a host value, with no class to build
            }
        }
        return needed;
    }

    /** Why {@code name} cannot bind here, or empty where it binds. */
    private static Optional<String> whyNot(String name, DataBindContext context) {
        if (isKernelVocabulary(name)) {
            return Optional.of("the kernel's vocabulary");
        }
        try {
            context.getDescriptor(name);
            return Optional.empty();
        } catch (DataBindException unbound) {
            return Optional.of(binds(context, name) ? "its class cannot be analysed: " + unbound.getMessage()
                    : "no class binds it");
        }
    }

    private static boolean isKernelVocabulary(String name) {
        try {
            SchemaModelNameBinder.INSTANCE.resolve(name);
            return true;
        } catch (DataBindException notVocabulary) {
            return false;
        }
    }

    private static String refusal(String label, String request, String field, String spelled, String sibling,
                                  Map<String, String> unbindable) {
        StringBuilder named = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, String> type : unbindable.entrySet()) {
            if (shown == NAMED) {
                named.append(", and ").append(unbindable.size() - NAMED).append(" more");
                break;
            }
            named.append(shown++ == 0 ? "" : ", ").append('\'').append(type.getKey()).append("' (")
                    .append(type.getValue()).append(')');
        }
        return "'" + label + "' takes '" + request + "', whose dependent field '" + field + ": " + spelled
                + "' is read against the type a sender names in '" + sibling + "', and it may name types this"
                + " server cannot bind: " + named + " -- a body naming one would be a 500 the client chose; pin '"
                + sibling + "', make it a discriminator over bound subtypes, or bind every type its reference"
                + " admits";
    }
}
