package io.ltr8.tson.service;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.DataExchange;
import io.ltr8.bind.DataProtocol;
import io.ltr8.tson.Tson;
import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.DeclaredType;
import io.ltr8.tson.schema.meta.DependentBody;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.IncompleteEntry;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RefBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.service.internal.Landed;
import io.ltr8.tson.service.schema.Exchange;
import io.ltr8.tson.service.schema.Protocol;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A protocol a schema declares joined to the Java interface that serves it: a resolved {@code !service.protocol}
 * entry held against the {@link DataProtocol} {@link DataBindContext#getProtocol} reads from the interface, checked
 * whole when it is built. Both sides are in the data view, so the join compares a request record with a request
 * record, a response with a response and an error set with an error set; the bridges answered what a Java exception
 * means before the join runs ({@code design/invoke.md}, "The join").
 *
 * <p><b>The startup rule.</b> {@link #join} reads schemas and analyses classes, so it runs on the startup thread,
 * after every schema it needs is resolved and before the {@code Tson} is shared. The result is immutable, and request
 * threads share it for reads only.
 *
 * <h2>What the join refuses</h2>
 *
 * <p>Bind has refused what is wrong with the interface on its own terms by the time the join compares anything, and
 * the join surfaces that refusal with the protocol named. What relates the two sides is the join's, and a mismatch is
 * a startup failure, never a 500 on the first call:
 *
 * <ul>
 *   <li>a schema exchange with no Java method, found by its name as {@link DataProtocol#exchange} reads it;</li>
 *   <li>two exchange keys that find one Java method, which bind's name rule makes possible ({@code place_order} and
 *       {@code placeOrder});</li>
 *   <li>a Java exchange no schema exchange names;</li>
 *   <li>a request or a response one side declares and the other does not;</li>
 *   <li>a request, a response or an error whose type and Java descriptor disagree;</li>
 *   <li>an error the schema names that the method does not declare, and a declared error the schema does not
 *       name.</li>
 * </ul>
 *
 * <h2>How types agree</h2>
 *
 * <p>A type the schema writes is landed as a reader lands it ({@code Landed}): past an extern to the foreign entry,
 * past every plain alias hop, each name looked up in the namespace of the entry that writes it. Then:
 *
 * <ul>
 *   <li><b>a record</b> agrees with a descriptor whose {@link DataClass#dataClass()} is the class the {@code Tson}'s
 *       bind context binds that entry to, asked by the declaring schema's identity and the entry's name there; a
 *       record nothing binds is refused. A request and an error are records on both sides, and one landing on
 *       anything else is refused;</li>
 *   <li><b>an array</b> agrees with an array descriptor whose element agrees with the element type, and <b>a map</b>
 *       with a map descriptor whose key and value agree with the key and value types;</li>
 *   <li><b>an atom or an enum</b> agrees with an atom descriptor whose data class the atom's family produces, asked
 *       of the family itself ({@code AtomType.boundTo}, through {@code AtomParsers}), which is the question the
 *       library asks of an atom field of a bound record, so {@code int32} agrees with {@code int} and not with
 *       {@code String};</li>
 *   <li><b>a choice</b> agrees with a closed union descriptor, one whose members are all known at startup, when each
 *       variant is a record bound to a member of the union and each member is the class of a variant.</li>
 * </ul>
 *
 * <p>Any other shape is refused, naming it, saying the join does not judge it and what to write instead: a tuple, a
 * namespace type, a record written in place, a reference, {@code void}, a dependent type, an application.
 *
 * <h2>What the join does not check</h2>
 *
 * <ul>
 *   <li><b>A record's fields.</b> The join compares the class a record binds to, and relies on the schema having
 *       been prepared for reading in bind mode for the fields against the class's components ({@code
 *       design/binding.md}): that is where a field with no component, or a component no field fills, is refused.</li>
 *   <li><b>Facets.</b> An array's {@code voidable}, {@code unique_items} and item bounds, a map's, and an atom's
 *       constraints ({@code min}, {@code max}, a {@code pattern}) are outside it: the family is asked whether it
 *       produces the class, never whether every value of the class is one the type admits.</li>
 *   <li><b>A bridge's conversion.</b> For a bridged request or response the exchange's descriptor carries the
 *       bridge, and the join compares its data class. The invoker takes and returns the bridged class, so a caller
 *       converts a request record with the bridge's {@code toObject} before invoking, and a response with its {@code
 *       toData} before writing it.</li>
 * </ul>
 *
 * @param <T> the interface joined
 */
public final class TsonServiceProtocol<T> {

    /** What to write instead of a shape the join does not judge, where nothing more particular applies. */
    private static final String ANY_JUDGED = "type the position by a record, an array, a map, an atom or a closed "
            + "choice of records, which the join judges";

    /** The type a protocol entry applies, as its value entry names it. */
    private static final String PROTOCOL_TYPE = "service.protocol";

    private final String schemaId;
    private final String name;
    private final DataProtocol<T> dataProtocol;
    private final Map<TsonExchangeName, DataExchange> exchanges;

    private TsonServiceProtocol(String schemaId, String name, DataProtocol<T> dataProtocol,
                                Map<TsonExchangeName, DataExchange> exchanges) {
        this.schemaId = schemaId;
        this.name = name;
        this.dataProtocol = dataProtocol;
        this.exchanges = Collections.unmodifiableMap(new LinkedHashMap<>(exchanges));
    }

    /**
     * Joins the entry {@code protocol} of the resolved schema {@code schemaId} to {@code type}.
     *
     * @param tson     a {@code Tson} binding {@code service.tn}'s records and the protocol's types, with {@code
     *                 schemaId} resolved
     * @param schemaId the identity of the schema declaring the protocol
     * @param protocol the name of the entry applying {@code service.protocol}
     * @param type     the interface serving it
     * @throws IllegalArgumentException if {@code schemaId} is not resolved, declares no such protocol, bind refuses
     *                                  {@code type} as a protocol, or the two sides disagree; the message names the
     *                                  schema, the exchange and the Java method, and says what is wrong
     */
    public static <T> TsonServiceProtocol<T> join(Tson tson, String schemaId, String protocol, Class<T> type) {
        Objects.requireNonNull(tson, "tson");
        Objects.requireNonNull(schemaId, "schemaId");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(type, "type");
        if (tson.schemaRegistry().get(schemaId).isEmpty()) {
            throw new IllegalArgumentException("'" + schemaId + "' is not resolved -- resolve it before joining its "
                    + "protocol '" + protocol + "' to " + type.getName());
        }
        TsonLinkedSchema linked = tson.loader().resolveLinked(schemaId);
        Protocol declared = declared(linked, schemaId, protocol, type);
        DataBindContext context = tson.dataBindContext();
        DataProtocol<T> data;
        try {
            data = context.getProtocol(type);
        } catch (DataBindException refused) {
            throw new IllegalArgumentException("'" + protocol + "' of '" + schemaId + "' cannot be joined to "
                    + type.getName() + ", which the bind context refuses as a protocol: " + refused.getMessage()
                    + " -- change the interface as that says, so bind reads it as a protocol", refused);
        }
        Map<TsonExchangeName, DataExchange> joined = byName(schemaId, protocol, declared, data);
        joined.forEach((exchange, java) -> new Agreement(schemaId, exchange, java, type, linked, context)
                .require(declared.exchanges().get(exchange.name())));
        return new TsonServiceProtocol<>(schemaId, protocol, data, joined);
    }

    /**
     * The protocol {@code protocol} names in {@code linked}. An entry applying {@code service.protocol} whose value
     * is held as a tree is refused rather than read as no protocol: its value bound to no class.
     */
    private static Protocol declared(TsonLinkedSchema linked, String schemaId, String protocol, Class<?> type) {
        Top entry = linked.schema().entries().get(protocol);
        if (entry instanceof ValueEntry(_, Protocol found)) {
            return found;
        }
        if (entry == null) {
            List<String> protocols = new ArrayList<>();
            linked.schema().entries().forEach((name, value) -> {
                if (value instanceof ValueEntry(String applied, _) && applied.equals(PROTOCOL_TYPE)) {
                    protocols.add(name);
                }
            });
            throw new IllegalArgumentException("'" + schemaId + "' declares no entry '" + protocol + "' to join to "
                    + type.getName() + " -- the protocols it declares are " + protocols + "; name one of them");
        }
        if (entry instanceof ValueEntry(String applied, Object unbound) && applied.equals(PROTOCOL_TYPE)) {
            throw new IllegalArgumentException("'" + protocol + "' of '" + schemaId + "' applies service.protocol, and "
                    + "its value did not bind to " + Protocol.class.getName() + " but is held as " + heldAs(unbound)
                    + " -- likely service.tn's records bind to no class here: bind them through "
                    + "TsonServiceSchema.bindingsWith, or key TsonServiceSchema.bindings under service.tn's identity in "
                    + "TsonBindings.bySchema, before joining it to " + type.getName());
        }
        String what = entry instanceof ValueEntry(String applied, _) ? "a value of '" + applied + "'" : "a type";
        throw new IllegalArgumentException("'" + protocol + "' of '" + schemaId + "' is " + what + ", not an entry "
                + "applying service.protocol, so it cannot be joined to " + type.getName() + " -- name an entry "
                + "applying service.protocol");
    }

    /** What a value that did not bind is held as, for a refusal: its class's simple name, or {@code null}. */
    private static String heldAs(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /**
     * Each schema exchange with the Java exchange its key finds, in the order the schema writes them, refusing a key
     * that finds none, two keys that find one, and a Java exchange no key finds.
     */
    private static Map<TsonExchangeName, DataExchange> byName(String schemaId, String protocol, Protocol declared,
                                                              DataProtocol<?> data) {
        Class<?> type = data.typeClass();
        Map<TsonExchangeName, DataExchange> joined = new LinkedHashMap<>();
        Map<DataExchange, TsonExchangeName> foundBy = new HashMap<>();
        for (String key : declared.exchanges().keySet()) {
            TsonExchangeName exchange = new TsonExchangeName(protocol, key);
            DataExchange java = data.exchange(key).orElseThrow(() -> new IllegalArgumentException("'" + exchange
                    + "' of '" + schemaId + "' has no method in " + type.getName() + " -- an exchange is found by its "
                    + "name with each _ removed and the character after it upper-cased, and " + type.getName()
                    + " declares " + javaNames(data)));
            TsonExchangeName earlier = foundBy.putIfAbsent(java, exchange);
            if (earlier != null) {
                throw new IllegalArgumentException("'" + earlier + "' and '" + exchange + "' of '" + schemaId
                        + "' both find " + method(type, java) + ": with each _ removed and the character after it "
                        + "upper-cased they are one name, and a protocol joined to an interface names each exchange "
                        + "once under that rule -- rename one");
            }
            joined.put(exchange, java);
        }
        for (DataExchange java : data.exchanges()) {
            if (!foundBy.containsKey(java)) {
                throw new IllegalArgumentException(method(type, java) + " is no exchange of '" + protocol + "' in '"
                        + schemaId + "', which declares " + declared.exchanges().keySet() + " -- declare it in the "
                        + "protocol, or take the method out of the interface");
            }
        }
        return joined;
    }

    private static List<String> javaNames(DataProtocol<?> data) {
        return data.exchanges().stream().map(DataExchange::name).toList();
    }

    private static String method(Class<?> type, DataExchange java) {
        return type.getName() + "." + java.name();
    }

    /** The identity of the schema declaring the protocol. */
    public String schemaId() {
        return schemaId;
    }

    /** The name of the entry applying {@code service.protocol}. */
    public String name() {
        return name;
    }

    /** The interface as bind describes it, the protocol {@link DataExchange#invoker()} and handlers come from. */
    public DataProtocol<T> dataProtocol() {
        return dataProtocol;
    }

    /** Each exchange by its name in the protocol, with the Java exchange serving it, in the order the schema writes them. */
    public Map<TsonExchangeName, DataExchange> exchanges() {
        return exchanges;
    }

    /** The Java exchange serving {@code name}, or empty where {@code name} is no exchange of this protocol. */
    public Optional<DataExchange> exchange(TsonExchangeName name) {
        return Optional.ofNullable(exchanges.get(name));
    }

    /** One schema exchange held against the Java exchange its key finds. */
    private static final class Agreement {

        private final String schemaId;
        private final TsonExchangeName exchange;
        private final DataExchange java;
        private final String method;
        private final TsonLinkedSchema linked;
        private final DataBindContext context;

        Agreement(String schemaId, TsonExchangeName exchange, DataExchange java, Class<?> type, TsonLinkedSchema linked,
                  DataBindContext context) {
            this.schemaId = schemaId;
            this.exchange = exchange;
            this.java = java;
            this.method = TsonServiceProtocol.method(type, java);
            this.linked = linked;
            this.context = context;
        }

        void require(Exchange declared) {
            requireRequest(declared.request());
            requireResponse(declared.response());
            requireErrors(declared.errors());
        }

        private void requireRequest(Optional<TypeRef> request) {
            if (request.isPresent() != java.request().isPresent()) {
                throw refusal(request.isPresent()
                        ? "the exchange takes '" + request.get().name() + "', and the method takes nothing -- give it "
                                + "one parameter, the request record"
                        : "the exchange takes nothing, and the method takes "
                                + java.request().get().typeClass().getName() + " -- drop the parameter, or declare "
                                + "the request in the exchange");
            }
            if (request.isPresent()) {
                requireRecord("the request '" + request.get().name() + "'", request.get(), java.request().get(),
                        "a request");
            }
        }

        private void requireResponse(Optional<TypeRef> response) {
            if (response.isPresent() != java.response().isPresent()) {
                throw refusal(response.isPresent()
                        ? "the exchange answers '" + response.get().name() + "', and the method returns void -- "
                                + "return the response"
                        : "the exchange declares no response, and the method returns "
                                + java.response().get().typeClass().getName() + " -- return void, or declare the "
                                + "response in the exchange");
            }
            if (response.isPresent()) {
                agree("the response '" + response.get().name() + "'", linked, response.get(), java.response().get());
            }
        }

        /**
         * The schema's errors and the method's declared errors as two sets of records: each error the schema names
         * has the one declared error bridging to its bound class, and each declared error bridges to one of them.
         */
        private void requireErrors(List<TypeRef> errors) {
            Map<Class<?>, TypeRef> named = new LinkedHashMap<>();
            for (TypeRef error : errors) {
                Class<?> bound = boundRecord("the error '" + error.name() + "'", error, "an error");
                TypeRef earlier = named.putIfAbsent(bound, error);
                if (earlier != null) {
                    throw refusal("the exchange names the errors '" + earlier.name() + "' and '" + error.name()
                            + "', which both bind to " + bound.getName() + ": the exchange names one error twice -- "
                            + "drop one");
                }
                if (java.errors().stream().noneMatch(declared -> declared.dataClass() == bound)) {
                    throw refusal("the exchange names the error '" + error.name() + "', bound to " + bound.getName()
                            + ", and the method declares no error bridging to it -- add a throws clause naming an "
                            + "exception whose ToData gives " + bound.getName());
                }
            }
            for (DataClass declared : java.errors()) {
                if (!named.containsKey(declared.dataClass())) {
                    throw refusal("the method declares " + declared.typeClass().getName() + ", which bridges to "
                            + declared.dataClass().getName() + ", and the exchange names no error bound to that class "
                            + "-- it names " + errors.stream().map(TypeRef::name).toList() + "; name it in the "
                            + "exchange's errors, or drop it from the throws clause");
                }
            }
        }

        /** A request or an error: a record on both sides, whose bound class is the descriptor's data class. */
        private void requireRecord(String where, TypeRef type, DataClass descriptor, String role) {
            Class<?> bound = boundRecord(where, type, role);
            if (bound != descriptor.dataClass()) {
                throw mismatch(where, Landed.of(linked, type.name()).pastAliases(), bound, descriptor);
            }
        }

        /** The class the bind context binds the record {@code type} lands on; {@code role} is a record. */
        private Class<?> boundRecord(String where, TypeRef type, String role) {
            if (type instanceof ParamRef) {
                throw notJudged(where, "a type parameter", ANY_JUDGED);
            }
            Landed at = Landed.of(linked, type.name()).pastAliases();
            if (!(at.definition() instanceof RecordBody)) {
                throw refusal(where + " lands on " + shape(at.definition()) + " '" + at.name() + "', and " + role
                        + " is a record, on the Java side as in the schema -- type it by a record holding the value");
            }
            return at.boundClass(context).orElseThrow(() -> unbound(where, at));
        }

        /** {@code type}, written in {@code scope}, against the descriptor bind gave the same position. */
        private void agree(String where, TsonLinkedSchema scope, TypeExpr type, DataClass descriptor) {
            switch (type) {
                case ParamRef _ -> throw notJudged(where, "a type parameter", ANY_JUDGED);
                case TypeRef named -> {
                    Landed at = Landed.of(scope, named.name()).pastAliases();
                    agree(where, at.in(), Optional.of(at), at.definition(), descriptor);
                }
                case TypeInline inPlace -> agree(where, scope, Optional.empty(), inPlace.of(), descriptor);
            }
        }

        /**
         * {@code body}, declared in {@code scope}, against {@code descriptor}. {@code named} is the entry {@code body}
         * is the definition of, empty for a body written in place.
         */
        private void agree(String where, TsonLinkedSchema scope, Optional<Landed> named, Top body,
                           DataClass descriptor) {
            DataClass java = descriptor instanceof DataClassAnnotated annotated ? annotated.valueClass() : descriptor;
            switch (body) {
                case RecordBody _ when named.isPresent() -> {
                    Landed at = named.get();
                    Class<?> bound = at.boundClass(context).orElseThrow(() -> unbound(where, at));
                    if (bound != java.dataClass()) {
                        throw mismatch(where, at, bound, java);
                    }
                }
                case ArrayBody array -> {
                    if (!(java instanceof DataClassArray javaArray)) {
                        throw kinds(where, body, named, java);
                    }
                    agree("the element of " + where, scope, array.elementType(), javaArray.arrayDataClass());
                }
                case MapBody map -> {
                    if (!(java instanceof DataClassMap javaMap)) {
                        throw kinds(where, body, named, java);
                    }
                    agree("the key of " + where, scope, map.keyType(), javaMap.keyDataClass());
                    agree("the value of " + where, scope, map.valueType(), javaMap.valueDataClass());
                }
                case ChoiceBody choice -> agreeChoice(where, scope, named, choice, java);
                case VoidType _, RefBody _ -> throw notJudged(where, shape(body), ANY_JUDGED);
                case Atom atom -> agreeAtom(where, named, atom, java);
                case RecordBody _ -> throw notJudged(where, "a record written in place", "declare the record as an "
                        + "entry of its own, so the bind context binds it by name");
                case null, default -> throw notJudged(where, shape(body), ANY_JUDGED);
            }
        }

        /**
         * An atom or an enum against an atom descriptor: the atom's family, as {@code AtomParsers} builds it for a
         * read, must produce the descriptor's data class ({@code AtomType.boundTo}), the class a bridged atom's
         * bridge takes. {@code boolean} is read by the vocabulary's own family rather than as the enum it is
         * declared, as the library's enum reader reads it, so it produces a Java {@code boolean}.
         */
        private void agreeAtom(String where, Optional<Landed> named, Atom atom, DataClass java) {
            if (!(java instanceof DataClassAtom bound)) {
                throw kinds(where, atom, named, java);
            }
            Optional<AtomType<?>> family;
            if (named.isEmpty()) {
                family = AtomParsers.forType(atom);
            } else if (atom instanceof EnumBody && named.get().name().equals("boolean")) {
                family = BuiltinTypeVocabulary.lookup("boolean");
            } else {
                family = AtomParsers.forRead(atom, named.get().in().enumForm(named.get().name()));
            }
            if (family.isEmpty()) {
                throw notJudged(where, shape(atom) + " no atom family reads", ANY_JUDGED);
            }
            if (family.get().boundTo(bound.dataClass()).isEmpty()) {
                throw refusal(where + " is " + shape(atom) + entry(where, named) + ", which cannot produce "
                        + bound.dataClass().getName() + ", the class the method's position describes -- change the "
                        + "method's type to one the atom produces, or the exchange's type to an atom producing it");
            }
        }

        /**
         * A choice against a closed union: each variant a record bound to a class of the union, and each member of the
         * union one of those classes, so a value the method returns is a value the choice admits and the reverse.
         */
        private void agreeChoice(String where, TsonLinkedSchema scope, Optional<Landed> named, ChoiceBody choice,
                                 DataClass java) {
            if (!(java instanceof DataClassUnion union)) {
                throw kinds(where, choice, named, java);
            }
            if (!closed(union)) {
                throw notJudged(where, "a choice joined to " + union.typeClass().getName() + ", an open union whose "
                        + "members are not all known at startup", "seal the union and make each member final, or "
                        + "declare it @Union(sealed = true) listing its members");
            }
            List<Class<?>> members = List.of(union.memberTypes());
            List<Class<?>> variants = new ArrayList<>();
            for (TypeExpr variant : choice.variants()) {
                String at = "the variant '" + variant.text() + "' of " + where;
                if (!(variant instanceof TypeRef reference) || reference instanceof ParamRef) {
                    throw notJudged(at, "a variant written in place", "declare each variant as an entry of its own");
                }
                Landed landed = Landed.of(scope, reference.name()).pastAliases();
                if (!(landed.definition() instanceof RecordBody)) {
                    throw notJudged(at, "a choice variant that is " + shape(landed.definition()), "make each "
                            + "variant a record, wrapping a value that is not one");
                }
                Class<?> bound = landed.boundClass(context).orElseThrow(() -> unbound(at, landed));
                if (!members.contains(bound)) {
                    throw refusal(at + " is bound to " + bound.getName() + ", and the union "
                            + union.typeClass().getName() + " that the method's position describes as has the members "
                            + members.stream().map(Class::getName).toList() + " -- add it to the union, or drop the "
                            + "variant from the choice");
                }
                variants.add(bound);
            }
            for (Class<?> member : members) {
                if (!variants.contains(member)) {
                    throw refusal(where + " is a choice of " + variants.stream().map(Class::getName).toList()
                            + ", and the union " + union.typeClass().getName() + " has the member " + member.getName()
                            + ", which is none of them, so a value of it is one the choice refuses -- add a variant "
                            + "bound to it, or drop it from the union");
                }
            }
        }

        /**
         * Whether every member of {@code union} is known now: bind declares it sealed, or its class is a sealed Java
         * type whose members, flattened to their leaves, are all final. A non-sealed member stands for implementations
         * loaded later, which no startup check can see.
         */
        private static boolean closed(DataClassUnion union) {
            return union.isSealed() || union.typeClass().isSealed() && Arrays.stream(union.memberTypes())
                    .allMatch(member -> Modifier.isFinal(member.getModifiers()));
        }

        private IllegalArgumentException unbound(String where, Landed at) {
            return refusal(where + " lands on the record '" + at.name() + "' of '" + at.identity() + "', which the "
                    + "bind context binds to no class -- bind it, by that schema and that name");
        }

        private IllegalArgumentException mismatch(String where, Landed at, Class<?> bound, DataClass java) {
            return refusal(where + " lands on the record '" + at.name() + "', which the bind context binds to "
                    + bound.getName() + ", and the method's position is " + java.dataClass().getName() + " -- they "
                    + "must be one class; change the method's type, or what the entry binds to");
        }

        private IllegalArgumentException kinds(String where, Top body, Optional<Landed> named, DataClass java) {
            return refusal(where + " is " + shape(body) + entry(where, named) + ", and the method's position, "
                    + java.typeClass().getName() + ", describes as " + kind(java) + " -- they must be one kind; change "
                    + "the method's type, or the exchange's");
        }

        /** The entry a position lands on, where that is not the name {@code where} already writes. */
        private static String entry(String where, Optional<Landed> named) {
            return named.map(at -> "'" + at.name() + "'").filter(name -> !where.endsWith(name))
                    .map(name -> " " + name).orElse("");
        }

        private IllegalArgumentException notJudged(String where, String shape, String remedy) {
            return refusal(where + " is " + shape + " -- a shape the join does not judge, so it is refused rather "
                    + "than passed unchecked; " + remedy);
        }

        private IllegalArgumentException refusal(String what) {
            return new IllegalArgumentException("'" + exchange + "' of '" + schemaId + "', joined to " + method
                    + ": " + what);
        }
    }

    private static String shape(Top body) {
        return switch (body) {
            case RecordBody _ -> "a record";
            case TupleBody _ -> "a tuple";
            case ArrayBody _ -> "an array";
            case MapBody _ -> "a map";
            case NamespaceBody _ -> "a namespace type";
            case ChoiceBody _ -> "a choice";
            case EnumBody _ -> "an enum";
            case RefBody _ -> "a reference";
            case VoidType _ -> "void";
            case Atom _ -> "an atom";
            case DeclaredType _ -> "a declared type";
            case DependentBody _ -> "a dependent type";
            case ApplyBody _ -> "an application";
            case Alias _ -> "an alias chain that closes on itself";
            case Extern _ -> "an extern";
            case ValueEntry _ -> "a value, not a type";
            case IncompleteEntry _ -> "an incomplete entry";
            case null -> "nothing its namespace holds";
        };
    }

    private static String kind(DataClass java) {
        return switch (java) {
            case DataClassRecord _ -> "a record";
            case DataClassArray _ -> "an array";
            case DataClassMap _ -> "a map";
            case DataClassAtom _ -> "an atom";
            case DataClassUnion _ -> "a union";
            case DataClassTuple _ -> "a tuple";
            default -> java.getClass().getSimpleName();
        };
    }
}
