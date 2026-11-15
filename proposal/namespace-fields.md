# A protocol and an http projection as records with a namespace field

**Status:** adopted. `service.tn` declares `service.protocol` and `service.http` as records with a namespace field, read
by `TsonApiDescription` through `Protocol` and `Http` (`design/service-description.md`, `design/http-projection.md`),
without `version` and `base` and with every document in the full form. Measured by
`tson-http/src/test/java/io/ltr8/tson/http/probe/NamespaceFieldProbeTest.java`; what follows is the record of the
measurement. It concerns this project's own vocabulary, `service.tn`, and asks nothing of tson-java or the spec.

**Since adopted:** `service.resources` is a plain map keyed by a text family, `{service.path_template =>
service.resource}`, rather than a namespace, since nothing references a path (`design/http-projection.md`);
`service.exchanges` stays a namespace. Below, `resources` as a namespace is the shape measured.

## Summary

`service.protocol` and `service.http` stop being namespace types and become ordinary records: declared fields, plus one
field typed by a namespace (`exchanges`, `resources`) that holds the keyed map they are today. A qualified name walks
through that field, `orders.exchanges.place_order`, which the resolver already judges. What an http projection claims
about the protocols behind it, `@service.binds` and `@service.not_bound`, become fields, judged at schema load like any
other reference, where today they are annotations on the projection's entry that the resolver does not judge and
`TsonApiDescription` checks by hand. The kernel and the spec are left as they are: the walk through a record value's
field is Revision 40 as published (`UpstreamGapsTest.aReferenceThroughARecordValuesFieldIsJudged`).

## What it changes in `service.tn`

The two namespace declarations are renamed for what they hold, and two records take the old names, all of them
members of the layer's one root map `service` and naming each other by their paths from it:

```
service => !schema {
  exchanges => !meta.namespace { key_type: service.exchange_name  value_type: service.exchange }
  protocol  => {
    version?:   text
    exchanges:  service.exchanges
  }

  resources => !meta.namespace { key_type: service.path_template  value_type: service.resource }
  http      => {
    binds?:     [meta.ref<service.protocol>]
    not_bound?: [service.exemption]
    base?:      uri_reference
    resources:  service.resources
  }
}
```

`service.binds => @meta.annotation [meta.ref<service.protocol>]` and `service.not_bound => @meta.annotation
[service.exemption]` are deleted. `service.exchange`, `service.resource`, `service.endpoint`, `service.binding`,
`service.operation`, `service.document` and `service.exemption` are unchanged; a binding's and an exemption's
`exchange` stay `meta.ref<service.exchange>`, now spelled through the field. `uri_reference` is `net.tn`'s,
which the layer already imports. `version` and `base` are new facts with no reader yet (see Open questions); they are
shown because a record is the shape that can hold them, and a namespace value cannot
(`UpstreamGapsTest.aNamespaceTypeDeclaresOnlyItsFacets`).

`orders-1.tn` (`tson-http/src/test/resources/io/ltr8/tson/http/api/orders/`), written in full:

```
!schema {
  @doc:"The orders protocol."
  orders => !service.protocol {
    exchanges: {
      @service.summary:"Place an order."
      @doc:"Accept an order and confirm it with the quantity doubled."
      place_order  => { request: new_order    response: order       errors: [sku_not_found] }
      @service.safe
      @doc:"One order, by reference."
      get_order    => { request: order_ref    response: order       errors: [order_not_found] }
      @service.safe
      @doc:"A page of orders, filtered by status."
      list_orders  => { request: order_query  response: order_page }
      @service.idempotent
      @doc:"Cancel an order. Cancelling twice is the same as cancelling once."
      cancel_order => { request: order_ref    errors: [order_not_found] }
    }
  }

  @doc:"Refunds, offered beside orders."
  orders_v2 => !service.protocol {
    exchanges: {
      @service.idempotent
      refund => { request: order_ref }
    }
  }
}
```

A protocol writing no `version` may keep today's text exactly, the map standing in for its one required field (below,
`thePositionalFormStandsInForTheNamespaceField`), so `orders-1.tn` need not change at all.

`orders-api-1.tn`, which must change, since it writes the claim:

```
!schema {
  orders_http => !service.http {
    binds: [orders]
    resources: {
      "/orders" => {
        @service.summary:"Place an order."
        @doc:"""
          Accepts a new order and confirms it. Repeating the request with the same Idempotency-Key returns the
          original confirmation rather than placing a second order.
          """
        POST => !service.binding { exchange: orders.exchanges.place_order  status: 201
                                   body: order  headers: { idempotency_key => "Idempotency-Key" } }
        @service.summary:"Search orders."
        GET  => !service.binding { exchange: orders.exchanges.list_orders }
      }
      "/orders/{id}" => {
        GET    => !service.binding { exchange: orders.exchanges.get_order }
        DELETE => !service.binding { exchange: orders.exchanges.cancel_order  status: 204 }
      }
    }
  }
}
```

Each document's `@doc` changes its prose to match: "`binds: [orders]` is a claim the reader holds it to" for
"`@service.binds:[orders]`, written before the projection's entry", and the three-segment spelling throughout.

## What is measured

Each point is a test of `NamespaceFieldProbeTest`, run against a minimal layer written in the probe (the shape above,
declared at the probe's root rather than under `service`, with `http_verb` cut to `GET POST` and `binding` to
`exchange` and `status`), not against `service.tn`. So the documents and messages below spell the probe's bare names,
where the layer would write `!service.protocol` and report `'service.protocol'`.

**Resolution** (`aProtocolAndAnHttpProjectionAsRecordsResolve`). The layer and a schema applying it, `orders =>
!protocol { version: "1"  exchanges: { place_order => { request: order } } }` and `orders_http => !http { binds:
[orders]  resources: { "/orders" => { endpoints: { POST => { exchange: orders.exchanges.place_order  status: 201 } } }
} }`, both resolve with no diagnostic.

**A claim is judged at load** (`aClaimAsAFieldIsJudgedAtLoad`). Each is one `SCHEMA_ERROR` from `validateSchema`:

- `binds: [no_such]`: *'orders_http' at /binds/0 names 'no_such', and 'no_such' is no entry of this schema or its
  imports -- the first segment names the entry a value is reached through, and a member is reached through its entry*
- `binds: [order]`, a type: *'orders_http' at /binds/0 wants a 'protocol', and 'order' is an entry whose body is built
  by 'meta.record', which is no 'protocol'*
- `binds: [orders_http]`, a value of another record type: *… and 'orders_http' is an entry whose body is built by
  'http', which is no 'protocol'*
- `not_bound: [ { exchange: orders.exchanges.nope … } ]`: *'orders_http' at /not_bound/0/exchange names
  'orders.exchanges.nope', and 'orders.exchanges' has no key 'nope' -- it holds place_order*

The annotation form loads all of these clean (`UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged`).

**The exchange reference walks the field, and only so** (`anExchangeReferenceWalksThroughTheExchangesField`). Each
message begins *'orders_http' at /resources/~1orders/endpoints/POST/exchange*, then:

- `orders.exchanges.nope`: *names 'orders.exchanges.nope', and 'orders.exchanges' has no key 'nope' -- it holds
  place_order*
- `orders.place_order`, the field skipped: *names 'orders.place_order', and 'orders' is a 'protocol', which has no
  field 'place_order' -- it declares version, exchanges*
- `orders.exchanges`, the namespace itself: *wants an 'exchange', and 'orders.exchanges' is an 'exchanges', which is
  no 'exchange'*

So the spelling is decided: the field name is a segment, and the resolver's refusal of the two-segment form names the
fields a protocol declares, which tells an author the remedy.

**The positional form** (`thePositionalFormStandsInForTheNamespaceField`). `service.protocol` and `service.http` each
have one bare required field, so [TSON-SCHEMA] §5.6 lets a value writing nothing else write the map alone: `!protocol {
place_order => … }` and `!http { "/orders" => … }` resolve and bind as the full form does. The reference still spells
the field the document did not write, `orders.exchanges.place_order`. A projection writing `binds` cannot use it: a
brace holding a field and then a key is a parse error, *expected a record field's ':', found '=>'* (`VALIDATION_ERROR`).

**A path key is no qualified-name segment** (`aPathKeyIsNoQualifiedNameSegment`). A segment is an identifier
([TSON-DATA] §7.7) and a path key starts with `/`. At `r: meta.ref<resource>`, `orders_http.resources."/orders"` is a
`VALIDATION_ERROR`, *adjacent values must be separated by whitespace, a comma, or both*; the bare
`orders_http.resources./orders` is *unrecognised character U+002F*. Read in a data document, the slot first reports
`ATOM_FORM_INVALID`, *'meta.ref<resource>': 'orders_http.resources.' is no reference -- 'orders_http.resources.' has an
empty segment -- each segment between the dots is a name*, then the adjacency error. Nothing references an endpoint
today, so nothing is lost; the proposal does not make a resource addressable.

**What a namespace-typed field binds to** (`aNamespaceTypedFieldBindsToAMapAnAnnotatedMapOrANamespace`). Written
`zeta`, `@safe alpha`, `mid`:

| Component | Arrives as | Key order | `@safe` on `alpha` |
|---|---|---|---|
| `Map<String, Exchange>` | `java.util.HashMap` | lost | lost |
| `AnnotatedMap<String, Exchange>` | `AnnotatedMap` | kept | kept |
| `Namespace<Exchange>` | `Namespace`, `type()` `exchanges` | kept | kept |

`TsonApiDescription` reads `@service.safe` and `@service.idempotent` from an exchange's key and walks routes in written
order, so the component is `Namespace<Exchange>` (or `AnnotatedMap`), never `Map`.

**What a judged reference carries** (`aClaimFieldArrivesAsADeliveredValueRefAndAnAnnotationsAsUndelivered`,
`anEndpointsExchangeArrivesAsAValueRefOfThreeSegments`). In the schema's own value entry, `binds: [orders]` arrives as a
`ValueRef` `orders`, delivered when the schema links: `get()` is the bound `Protocol`. The element of an annotation
`@claims:[orders]` is a `ValueRef` of the same name and `equals` it, a reference's identity being its name, but is
never delivered (`isResolved()` false). So the reader could take the protocol from the field rather than find it by
name among the entries, and no longer has to refuse a bad name. The same reference read in a *data* document governed
by the schema is delivered at the read, the `Exchange` value it reaches, through the tree reader and the object reader
alike. The endpoint's exchange arrives as `orders.exchanges.place_order`, its `Exchange` delivered. An absent
`not_bound` (or `binds`) arrives as `null`, so the bound record normalises it, as `api.Binding` does; `base` arrives as
an `io.ltr8.net.Iri`.

## What changes in Java

- **`ExchangeName.of`** splits on the *last* `.`, so `orders.exchanges.place_order` would become owner
  `orders.exchanges`, name `place_order`, and every binding would then fail `boundExchange` as naming a protocol the
  projection does not bind. It becomes a split of the `ValueRef`'s name into its three segments, the first the
  protocol's entry, the second `exchanges`, the third the key. The record keeps `(owner, name)`, so the
  per-(protocol, exchange) claim (`Set<ExchangeName>` in `requireComplete`) and `Route.exchange()` are unchanged in
  kind. Its `toString`, today the reference spelling `orders.get_order`, either becomes `orders.exchanges.get_order`,
  staying the reference spelling, or stays two-segment as an identity label (Open questions).
- **`TsonApiDescription`**. `applying(entries, type)` matches `ValueEntry(String, Namespace<?>)`; a protocol and a
  projection now hold bound records, so it matches `ValueEntry("service.protocol", Protocol p)` and
  `ValueEntry("service.http", Http h)`. The protocol map becomes `Map<String, Protocol>`, reached by
  `p.exchanges().get(name)` and `p.exchanges().getAnnotations(name)` for an exchange's facts and documentation. `read`
  takes the `Http` and walks `h.resources()`. `bound()` reads `h.binds()` rather than `claims.values("service.binds",
  …)`, and `requireComplete` reads `h.notBound()` rather than `claims.values("service.not_bound", …)`;
  `entries.getAnnotations(name)` drops out of the claim path entirely. The name checks there ("no entry of the
  description applying service.protocol", an exemption naming a key no protocol holds) become unreachable for a schema
  the resolver loaded and can go, or stay as a net with a comment saying so. What stays the reader's is relating,
  unchanged: a binding's or an exemption's exchange belonging to a protocol *this* projection binds, and the claim held
  per `ExchangeName`.
- **`TsonApiSchema.bindings()`** gains `"service.protocol" -> Protocol.class` and `"service.http" -> Http.class`, two
  new records in `io.ltr8.tson.http.api`, each `@Typename` its qualified name; its Javadoc's "`service.protocol` and
  `service.http` need no class: a value of a namespace type is a `Namespace`" goes. `service.exemption` stays bound.
  `Exchange`'s Javadoc ("read from the entry's `Namespace`") and `Exemption`'s (held in an annotation's value, checked
  by the reader) are rewritten.
- **`TsonApiCoverage`** keys on `Route.key()`, the declared verb and path template, and names no exchange, so it is
  unchanged.
- **Tests.** `ProtocolMapTest` measures a protocol as the map itself and is rewritten for the record; the
  `TsonApiDescriptionTest` cases for an unknown `@service.binds` name or exemption become schema-load refusals;
  `OrdersExamples` and the demos' `orders-api-1.tn` follow the documents.
  `UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged` stays, since the gap is still upstream's, but stops
  describing `service.tn`.
- **Notes.** `design/service-description.md` loses the invariant "`service.protocol` and `service.http` are namespace
  types, so a value of either is the map alone; a projection's claims … are annotations on its entry" and the paragraph
  "An entry applying a namespace type is a value, never a type … A namespace value is the map alone"; the first stays
  true of the library, but no longer explains this layer. The "Naming is the resolver's … except a reference inside an
  annotation's value" invariant loses its exception for this layer. `CLAUDE.md`'s trap "An entry applying a namespace
  type is a value, never a type, and the map alone: an http projection's `@service.binds` and `@service.not_bound` are
  annotations on its entry, whose names the reader checks" is rewritten to the record form; the trap "An entry has two
  annotation positions that land in different places" stays, since `@doc` on an entry still has two positions, but no
  claim depends on it. `UPSTREAM.md` item 4 loses its "Hit" in this project, and the spec entry "a namespace value with
  declared fields beside its keys" loses its motivating case.

## Costs

- **One extra segment in every exchange reference**: `orders.exchanges.place_order` for `orders.place_order`, in every
  binding, exemption, data-document reference into a protocol (`experiments/agent/`), and every message and
  test naming one. The positional form does not remove it: the walk spells the field whether or not the document wrote
  it, so a document using the positional form names a field it never wrote.
- **One extra nesting level per document that writes a field**: a projection with `binds` always, a protocol with a
  `version`. A protocol-only or web-service-only document using the positional form keeps today's text.
- **The positional form becomes a versioning trap in two more places.** As with `service.resource`, adding a second
  required field to `service.protocol` or `service.http` withdraws the form and breaks every document written in it, so
  new fields there are optional or a new type.
- **Documents to rewrite**: `orders-api-1.tn` in the test resources and `demo/schemas/orders-api-1.tn` (claim and
  references); `orders-1.tn`, `orders-api-inline-1.tn` and `tson-http-jdk/src/demo/resources/validate-api-1.tn` only if
  the full form is preferred over the positional one. Nothing is published, so each is edited in place
  (`design/schema-authoring.md`).
- **No referent.** The judged reference arrives as a bare name in a schema value, so the reader still looks a
  protocol up by name. The gain is the refusal at load, nothing more.
- **Wording.** The resolver reads a value of a record type as "an entry whose body is built by 'http'", a record type
  phrased as though it were a constructor; a refusal of `binds: [orders_http]` reads oddly. Cosmetic, and upstream's.

Nothing measured fails: every shape the proposal needs resolves, binds and is judged.

## Not proposed

- **A kernel rule letting a qualified name skip the member field** (`orders.place_order` reaching through
  `exchanges`): it would make a name's meaning depend on which field of a record is the namespace one, a choice the
  kernel has no way to state, and the resolver's current refusal is exact and names the remedy.
- **A mixed brace of `name: value` fields and `key => value` entries**: it is a change to the data grammar that every
  reader would carry for one vocabulary's convenience, and the record with a namespace field already says the same
  thing within the grammar as it stands.

## Order of work, if adopted

1. `service.tn`: the four declarations, the two annotation declarations deleted, its `@doc` rewritten.
2. `Protocol` and `Http` records in `io.ltr8.tson.http.api`, bound in `TsonApiSchema.bindings()`.
3. `ExchangeName.of` over segments; `TsonApiDescription` reading the records and their fields.
4. The description documents and their `@doc`s; `OrdersExamples`; `ProtocolMapTest` and `TsonApiDescriptionTest`.
5. `design/service-description.md`, `CLAUDE.md`'s traps, `UPSTREAM.md` item 4's Hit and the namespace-fields spec
   entry; the `experiments/` READMEs naming `orders.place_order`.
6. `NamespaceFieldProbeTest` stays as the record of why, or folds into `UpstreamGapsTest` once the layer depends on
   the walk through a field.

## Open questions

- **`ExchangeName`'s string form**: the three-segment reference spelling, so a message can be pasted back into a
  document, or the two-segment `(protocol, exchange)` label it is today, which is shorter and no longer a valid name?
- **`version` and `base`**: adopt them with the record, or leave the records with the namespace field and the claims
  alone until something reads them? `base` would need a rule against the path template (whether `Route.path()` is
  joined to it, and what `TsonApiCoverage` claims), and `version` a relation to `TsonSchemaVersions`.
- **Full form or positional form in the examples**: the positional form keeps three documents unchanged but teaches a
  reference spelling that names a field the reader cannot see.
- **The reader's name checks**: delete them as unreachable, or keep them as a second net, as
  `UpstreamGapsTest.aReferenceInsideAnAnnotationValueIsNotJudged` foresaw for the day the annotation gap closes?
