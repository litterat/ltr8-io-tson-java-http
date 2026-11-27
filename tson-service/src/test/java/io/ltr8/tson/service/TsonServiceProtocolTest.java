package io.ltr8.tson.service;

import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.DataExchange;
import io.ltr8.bind.DataExchangeHandler;
import io.ltr8.tson.Tson;
import io.ltr8.tson.service.Shop.Item;
import io.ltr8.tson.service.Shop.ItemRef;
import io.ltr8.tson.service.Shop.NewItem;
import io.ltr8.tson.service.Shop.OutOfStock;
import io.ltr8.tson.service.Shop.OutOfStockException;
import io.ltr8.tson.service.Shop.ShopApi;
import io.ltr8.tson.service.Shop.ShopService;
import io.ltr8.tson.service.schema.Protocol;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.ltr8.tson.service.ShopExamples.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonServiceProtocol#join} held to {@code shop.tn}'s protocols ({@link ShopExamples}) and the interfaces in
 * {@link Shop}: each agreement the join accepts, and each refusal, its message naming the schema, the exchange, the
 * Java interface or method, and what is wrong.
 */
class TsonServiceProtocolTest {

    private static final Tson TSON = ShopExamples.tson();

    private static TsonExchangeName exchange(String protocol, String name) {
        return new TsonExchangeName(protocol, name);
    }

    private static DataExchange joined(String protocol, String name, Class<?> type) {
        return TsonServiceProtocol.join(TSON, SHOP, protocol, type).exchange(exchange(protocol, name)).orElseThrow();
    }

    private static String refusal(Tson tson, String schemaId, String protocol, Class<?> type) {
        return assertThrows(IllegalArgumentException.class,
                () -> TsonServiceProtocol.join(tson, schemaId, protocol, type)).getMessage();
    }

    private static String refusal(String protocol, Class<?> type) {
        return refusal(TSON, SHOP, protocol, type);
    }

    private static void assertNames(String message, String... parts) {
        for (String part : parts) {
            assertTrue(message.contains(part), () -> "'" + part + "' is not in: " + message);
        }
    }

    // ---- what the join accepts

    @Test
    void joinsEveryExchangeOfAProtocolInTheOrderTheSchemaWritesThem() {
        TsonServiceProtocol<ShopApi> shop = TsonServiceProtocol.join(TSON, SHOP, "shop", ShopApi.class);
        assertEquals(SHOP, shop.schemaId());
        assertEquals("shop", shop.name());
        assertSame(ShopApi.class, shop.dataProtocol().typeClass());
        assertEquals(List.of("add_item", "get_item", "list_items", "stock", "remove_item", "count_items"),
                shop.exchanges().keySet().stream().map(TsonExchangeName::name).toList());
        assertEquals(List.of("addItem", "getItem", "listItems", "stock", "removeItem", "countItems"),
                shop.exchanges().values().stream().map(DataExchange::name).toList());
        assertEquals(Optional.empty(), shop.exchange(exchange("lookup", "get_item")), "another protocol's exchange");
        assertThrows(UnsupportedOperationException.class, () -> shop.exchanges().clear());
    }

    @Test
    void aSnakeCaseExchangeFindsTheMethodSpeltInCamelCase() {
        assertEquals("getItem", joined("lookup", "get_item", Shop.Lookup.class).name());
    }

    @Test
    void anExchangeTakingARequestAndAnsweringAResponseAgreesWithTheRecordsItsTypesBind() {
        DataExchange addItem = joined("shop", "add_item", ShopApi.class);
        assertSame(NewItem.class, addItem.request().orElseThrow().dataClass());
        assertSame(Item.class, addItem.response().orElseThrow().dataClass());
    }

    @Test
    void anExchangeDeclaringNoResponseAgreesWithAVoidMethod() {
        DataExchange removeItem = joined("shop", "remove_item", ShopApi.class);
        assertSame(ItemRef.class, removeItem.request().orElseThrow().dataClass());
        assertEquals(Optional.empty(), removeItem.response());
    }

    @Test
    void anExchangeTakingNothingAgreesWithAMethodTakingNothing() {
        DataExchange countItems = joined("shop", "count_items", ShopApi.class);
        assertEquals(Optional.empty(), countItems.request());
        // int32's family produces the method's int, asked as the library asks an atom field (AtomType.boundTo).
        assertSame(int.class, assertInstanceOf(DataClassAtom.class, countItems.response().orElseThrow()).dataClass());
    }

    @Test
    void anExchangesErrorsAgreeWithTheMethodsDeclaredErrors() {
        DataExchange addItem = joined("shop", "add_item", ShopApi.class);
        assertEquals(List.of(OutOfStock.class), addItem.errors().stream().map(DataClass::dataClass).toList());
        assertEquals(List.of(OutOfStockException.class),
                addItem.errors().stream().map(DataClass::typeClass).toList());
    }

    @Test
    void aResponseThatIsAnArrayOfRecordsAgreesWithAListOfTheirClass() {
        DataClass items = joined("shop", "list_items", ShopApi.class).response().orElseThrow();
        assertSame(Item.class, assertInstanceOf(DataClassArray.class, items).arrayDataClass().dataClass());
    }

    @Test
    void aResponseThatIsAMapAgreesWhenItsKeyAndValueFamiliesProduceTheMapsClasses() {
        // {text => int32} against Map<String, Integer>: text produces String, and int32 produces Integer.
        DataClassMap stock = assertInstanceOf(DataClassMap.class,
                joined("shop", "stock", ShopApi.class).response().orElseThrow());
        assertSame(String.class, stock.keyDataClass().dataClass());
        assertSame(Integer.class, stock.valueDataClass().dataClass());
    }

    @Test
    void aResponseThatIsAnArrayOfAtomsAgreesWhenTheElementsFamilyProducesTheElementClass() {
        DataClassArray levels = assertInstanceOf(DataClassArray.class,
                joined("stocks", "levels", Shop.Levels.class).response().orElseThrow());
        assertSame(Integer.class, levels.arrayDataClass().dataClass());
    }

    @Test
    void anEnumAgreesWithAJavaEnumAndBooleanWithABoolean() {
        TsonServiceProtocol<Shop.Statuses> statuses = TsonServiceProtocol.join(TSON, SHOP, "statuses",
                Shop.Statuses.class);
        DataClass status = statuses.exchange(exchange("statuses", "status_of")).orElseThrow().response().orElseThrow();
        assertSame(Shop.ItemStatus.class, assertInstanceOf(DataClassAtom.class, status).typeClass());
        DataClass inStock = statuses.exchange(exchange("statuses", "in_stock")).orElseThrow().response().orElseThrow();
        assertSame(boolean.class, assertInstanceOf(DataClassAtom.class, inStock).dataClass());
    }

    @Test
    void aChoiceAgreesWithAUnionBindDeclaresSealedListingItsRecords() {
        DataClass found = joined("finder", "find_any", Shop.ListedFinder.class).response().orElseThrow();
        DataClassUnion union = assertInstanceOf(DataClassUnion.class, found);
        assertTrue(union.isSealed(), "declared sealed by @Union, the branch a Java-sealed hierarchy never takes");
    }

    @Test
    void aBridgedRequestJoinsByItsRecordAndTheInvokerTakesTheBridgedClass() throws Throwable {
        DataExchange getItem = joined("lookup", "get_item", Shop.BridgedLookup.class);
        DataClass request = getItem.request().orElseThrow();
        assertSame(ItemRef.class, request.dataClass(), "joined by the record item_ref binds to");
        assertSame(Shop.ItemQuery.class, request.typeClass());

        Object target = (Shop.BridgedLookup) query -> new Item(query.sku(), 1);
        Object converted = request.bridge().orElseThrow().toObject(new ItemRef("A-1"));
        assertInstanceOf(Shop.ItemQuery.class, converted, "the caller converts with the bridge's toObject");
        assertEquals(new Item("A-1", 1), (Object) getItem.invoker().invokeExact(target, converted));
        Object unconverted = new ItemRef("A-1");
        assertThrows(ClassCastException.class, () -> {
            Object ignored = (Object) getItem.invoker().invokeExact(target, unconverted);
        }, "the invoker takes the bridged class, never the record");
    }

    @Test
    void aResponseReachedThroughAnAliasIsTheRecordTheAliasReaches() {
        // get_item answers item_alias, an alias of item; the read asks the binder for item, and so does the join.
        assertSame(Item.class, joined("shop", "get_item", ShopApi.class).response().orElseThrow().dataClass());
    }

    @Test
    void aResponseReachedThroughAnExternIsTheForeignRecordItLandsOn() {
        // gadget is an extern of catalog.tn's widget, and only widget is bound.
        assertSame(Shop.Widget.class, joined("gadgets", "get_gadget", Shop.Gadgets.class).response().orElseThrow()
                .dataClass());
    }

    @Test
    void aResponseThatIsAChoiceOfRecordsAgreesWithASealedUnionOfTheirClasses() {
        DataClass found = joined("finder", "find_any", Shop.Finder.class).response().orElseThrow();
        assertSame(Shop.Found.class, assertInstanceOf(DataClassUnion.class, found).typeClass());
    }

    @Test
    void theJoinedInvokerDispatchesToAnImplementationAndADeclaredErrorBridgesToTheRecordTheSchemaNames()
            throws Throwable {
        TsonServiceProtocol<ShopApi> shop = TsonServiceProtocol.join(TSON, SHOP, "shop", ShopApi.class);
        DataExchange addItem = shop.exchange(exchange("shop", "add_item")).orElseThrow();
        Object target = new ShopService();
        Object request = new NewItem("A-1", 2);
        assertEquals(new Item("A-1", 2), (Object) addItem.invoker().invokeExact(target, request));

        DataExchangeHandler handler = shop.dataProtocol().handler(new ShopService());
        DataExchange countItems = shop.exchange(exchange("shop", "count_items")).orElseThrow();
        assertEquals(7, handler.handle(countItems, null));

        OutOfStockException thrown = assertThrows(OutOfStockException.class,
                () -> handler.handle(addItem, new NewItem("A-1", 11)));
        DataClass error = addItem.error(thrown).orElseThrow();
        Object record = error.bridge().orElseThrow().toData(thrown);
        assertEquals(new OutOfStock("A-1"), record);
        // The record is the class the schema's out_of_stock binds to, as the join checked.
        assertSame(TSON.dataBindContext().nameBinder().resolve(ShopExamples.TYPES_IDENTITY, "out_of_stock"),
                record.getClass());
        assertEquals(Optional.empty(), addItem.error(new IllegalStateException("undeclared")));
    }

    // ---- refusals: the schema and its entry

    @Test
    void aSchemaNotResolvedIsRefused() {
        String unresolved = ShopExamples.BASE + "nowhere.tn";
        assertNames(refusal(TSON, unresolved, "shop", ShopApi.class), "'" + unresolved + "' is not resolved",
                "'shop'", ShopApi.class.getName());
    }

    @Test
    void aSchemaDeclaringNoSuchEntryIsRefusedListingItsProtocols() {
        assertNames(refusal("nothing", ShopApi.class), "'" + SHOP + "' declares no entry 'nothing'",
                ShopApi.class.getName(), "[shop, lookup, twice,");
    }

    @Test
    void anEntryThatIsNoProtocolIsRefused() {
        assertNames(refusal("shelf", ShopApi.class), "'shelf' of '" + SHOP + "' is a type",
                "not an entry applying service.protocol", ShopApi.class.getName());
    }

    @Test
    void anEntryThatIsAValueOfAnotherTypeIsRefused() {
        assertNames(refusal("front", ShopApi.class), "'front' of '" + SHOP + "' is a value of 'shelf', not an entry "
                + "applying service.protocol");
    }

    @Test
    void aProtocolWhoseValueDidNotBindIsRefused() {
        Tson withoutLayer = ShopExamples.tsonWithoutLayer(ShopExamples.BINDINGS);
        assertEquals("'shop' of '" + SHOP + "' applies service.protocol, and its value did not bind to "
                + "io.ltr8.tson.service.schema.Protocol but is held as TsonRecord -- likely service.tn's records bind "
                + "to no class here: bind them through TsonServiceSchema.bindingsWith, or key "
                + "TsonServiceSchema.bindings under service.tn's identity in TsonBindings.bySchema, before joining it "
                + "to " + ShopApi.class.getName(), refusal(withoutLayer, SHOP, "shop", ShopApi.class));
    }

    // ---- refusals: the interface and the names

    @Test
    void anInterfaceBindRefusesIsRefusedWithBindsMessageAndTheProtocolNamed() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TsonServiceProtocol.join(TSON, SHOP, "lookup", Shop.LookupSpread.class));
        assertNames(refused.getMessage(), "'lookup' of '" + SHOP + "' cannot be joined to "
                + Shop.LookupSpread.class.getName(), "takes 2 parameters");
        assertInstanceOf(DataBindException.class, refused.getCause());
    }

    @Test
    void aSchemaExchangeWithNoJavaMethodIsRefused() {
        assertNames(refusal("shop", Shop.Lookup.class), "'shop.exchanges.add_item' of '" + SHOP + "' has no method "
                + "in " + Shop.Lookup.class.getName(), "declares [getItem]");
    }

    @Test
    void twoExchangeKeysFindingOneMethodAreRefused() {
        assertNames(refusal("twice", Shop.Lookup.class), "'twice.exchanges.get_item' and 'twice.exchanges.getItem'",
                "both find " + Shop.Lookup.class.getName() + ".getItem", "rename one");
    }

    @Test
    void aJavaExchangeNoSchemaExchangeNamesIsRefused() {
        assertNames(refusal("lookup", Shop.LookupAndMore.class), Shop.LookupAndMore.class.getName() + ".other is no "
                + "exchange of 'lookup' in '" + SHOP + "'", "declares [get_item]");
    }

    // ---- refusals: presence

    @Test
    void anExchangeTakingARequestRefusesAMethodTakingNothing() {
        assertNames(refusal("lookup", Shop.LookupTakingNothing.class), "'lookup.exchanges.get_item' of '" + SHOP
                + "', joined to " + Shop.LookupTakingNothing.class.getName() + ".getItem",
                "takes 'item_ref', and the method takes nothing");
    }

    @Test
    void anExchangeTakingNothingRefusesAMethodTakingARequest() {
        assertNames(refusal("counter", Shop.CounterTakingARequest.class), "'counter.exchanges.count_items'",
                Shop.CounterTakingARequest.class.getName() + ".countItems",
                "the exchange takes nothing, and the method takes " + ItemRef.class.getName());
    }

    @Test
    void anExchangeAnsweringAResponseRefusesAVoidMethod() {
        assertNames(refusal("lookup", Shop.LookupReturningVoid.class), "'lookup.exchanges.get_item'",
                Shop.LookupReturningVoid.class.getName() + ".getItem", "answers 'item', and the method returns void");
    }

    @Test
    void anExchangeDeclaringNoResponseRefusesAMethodReturningOne() {
        assertNames(refusal("remover", Shop.RemoverAnswering.class), "'remover.exchanges.remove_item'",
                Shop.RemoverAnswering.class.getName() + ".removeItem",
                "declares no response, and the method returns " + Item.class.getName());
    }

    // ---- refusals: type agreement

    @Test
    void aRequestBoundToAnotherClassIsRefused() {
        assertNames(refusal("lookup", Shop.LookupTakingAnotherRecord.class), "'lookup.exchanges.get_item'",
                "the request 'item_ref' lands on the record 'item_ref', which the bind context binds to "
                        + ItemRef.class.getName() + ", and the method's position is " + NewItem.class.getName());
    }

    @Test
    void aResponseBoundToAnotherClassIsRefused() {
        assertNames(refusal("lookup", Shop.LookupAnsweringAnotherRecord.class), "'lookup.exchanges.get_item'",
                "the response 'item' lands on the record 'item', which the bind context binds to "
                        + Item.class.getName() + ", and the method's position is " + NewItem.class.getName());
    }

    @Test
    void aRecordNothingBindsIsRefused() {
        Map<String, Class<?>> withoutItem = new HashMap<>(ShopExamples.BINDINGS);
        withoutItem.remove("item");
        assertNames(refusal(ShopExamples.tson(withoutItem), SHOP, "lookup", Shop.Lookup.class),
                "'lookup.exchanges.get_item'", Shop.Lookup.class.getName() + ".getItem",
                "the response 'item' lands on the record 'item' of '" + ShopExamples.TYPES_IDENTITY
                        + "', which the bind context binds to no class");
    }

    @Test
    void aRequestThatIsNoRecordIsRefused() {
        assertNames(refusal("by_sku", Shop.BySku.class), "'by_sku.exchanges.find'", Shop.BySku.class.getName()
                + ".find", "the request 'sku_text' lands on an atom 'text', and a request is a record");
    }

    @Test
    void aResponseOfAnotherKindIsRefused() {
        assertNames(refusal("lister", Shop.ListerAnsweringOne.class), "'lister.exchanges.list_items'",
                "the response 'item_list' is an array, and the method's position, "
                        + Item.class.getName() + ", describes as a record -- they must be one kind");
    }

    @Test
    void anArrayWhoseElementIsBoundToAnotherClassIsRefused() {
        assertNames(refusal("lister", Shop.ListerOfAnotherRecord.class), "'lister.exchanges.list_items'",
                "the element of the response 'item_list' lands on the record 'item', which the bind context binds to "
                        + Item.class.getName() + ", and the method's position is " + NewItem.class.getName());
    }

    @Test
    void aShapeTheJoinDoesNotJudgeIsRefused() {
        assertNames(refusal("book", Shop.Book.class), "'book.exchanges.items_by_sku'",
                Shop.Book.class.getName() + ".itemsBySku", "the response 'item_book' is a namespace type -- a shape "
                        + "the join does not judge, so it is refused rather than passed unchecked");
    }

    @Test
    void aChoiceJoinedToAnOpenUnionIsNotJudged() {
        assertNames(refusal("finder", Shop.LooseFinder.class), "'finder.exchanges.find_any'",
                Shop.LooseFinder.class.getName() + ".findAny", "the response 'found' is a choice joined to "
                        + Shop.Loose.class.getName() + ", an open union", "a shape the join does not judge");
    }

    @Test
    void aChoiceJoinedToAUnionWithAMemberNoVariantNamesIsRefused() {
        assertNames(refusal("finder", Shop.WiderFinder.class), "'finder.exchanges.find_any'",
                Shop.WiderFinder.class.getName() + ".findAny", "the union " + Shop.Wider.class.getName()
                        + " has the member " + OutOfStock.class.getName() + ", which is none of them");
    }

    @Test
    void anAtomWhoseFamilyCannotProduceTheMethodsClassIsRefused() {
        assertNames(refusal("counter", Shop.CounterAnsweringText.class), "'counter.exchanges.count_items'",
                Shop.CounterAnsweringText.class.getName() + ".countItems", "the response 'int32' is an atom, which "
                        + "cannot produce java.lang.String, the class the method's position describes -- change");
    }

    @Test
    void anAtomAgainstARecordIsRefused() {
        assertNames(refusal("counter", Shop.CounterAnsweringARecord.class), "the response 'int32' is an atom, and the "
                + "method's position, " + Item.class.getName() + ", describes as a record -- they must be one kind");
    }

    @Test
    void anEnumAgainstANumberIsRefused() {
        assertNames(refusal("statuses", Shop.StatusesAsNumbers.class), "'statuses.exchanges.status_of'",
                "the response 'item_status' is an enum, which cannot produce int");
    }

    @Test
    void anArrayWhoseAtomElementCannotProduceTheElementClassIsRefused() {
        assertNames(refusal("stocks", Shop.LevelsAsText.class), "the element of the response 'int_list' is an atom "
                + "'int32', which cannot produce java.lang.String");
    }

    @Test
    void aMapAgainstAnotherKindIsRefused() {
        assertNames(refusal("stock_only", Shop.StockAsList.class), "'stock_only.exchanges.stock'",
                "the response 'stock_levels' is a map, and the method's position, java.util.List, describes as an "
                        + "array -- they must be one kind");
    }

    @Test
    void aMapWhoseValueFamilyCannotProduceTheValueClassIsRefused() {
        assertNames(refusal("stock_only", Shop.StockOfText.class), "the value of the response 'stock_levels' is an "
                + "atom 'int32', which cannot produce java.lang.String");
    }

    @Test
    void aMapWhoseKeyFamilyCannotProduceTheKeyClassIsRefused() {
        assertNames(refusal("stock_only", Shop.StockByNumber.class), "the key of the response 'stock_levels' is an "
                + "atom 'text', which cannot produce java.lang.Integer");
    }

    @Test
    void aChoiceAgainstSomethingOtherThanAUnionIsRefused() {
        assertNames(refusal("finder", Shop.FinderAnsweringOne.class), "the response 'found' is a choice, and the "
                + "method's position, " + Item.class.getName() + ", describes as a record -- they must be one kind");
    }

    @Test
    void aVariantBoundToAClassThatIsNoMemberOfTheUnionIsRefused() {
        // ItemMissing implements Narrow, and @Union lists only Item: membership, not assignability, is what counts.
        assertNames(refusal("finder", Shop.NarrowFinder.class), "the variant 'item_missing' of the response 'found' "
                + "is bound to " + Shop.ItemMissing.class.getName() + ", and the union " + Shop.Narrow.class.getName()
                + " that the method's position describes as has the members [" + Item.class.getName() + "] -- add it "
                + "to the union, or drop the variant from the choice");
    }

    @Test
    void aVariantWrittenInPlaceIsNotJudged() {
        assertNames(refusal("inline_finder", Shop.Finder.class), "of the response 'found_inline' is a variant "
                + "written in place -- a shape the join does not judge", "declare each variant as an entry of its own");
    }

    @Test
    void aVariantThatIsNoRecordIsNotJudged() {
        assertNames(refusal("text_finder", Shop.Finder.class), "the variant 'text' of the response 'found_text' is a "
                + "choice variant that is an atom -- a shape the join does not judge", "make each variant a record");
    }

    @Test
    void voidAsAResponseTypeIsNotJudged() {
        assertNames(refusal("pinger", Shop.Pinger.class), "the response 'void' is void -- a shape the join does not "
                + "judge", "type the position by a record, an array, a map, an atom or a closed choice of records");
    }

    @Test
    void aReferenceAsAResponseTypeIsNotJudged() {
        assertNames(refusal("linker", Shop.Linker.class), "the response 'meta.type_ref' is a reference -- a shape "
                + "the join does not judge");
    }

    // ---- refusals: errors

    @Test
    void anErrorTheSchemaNamesAndTheMethodDoesNotDeclareIsRefused() {
        assertNames(refusal("lookup", Shop.LookupDeclaringNoError.class), "'lookup.exchanges.get_item'",
                Shop.LookupDeclaringNoError.class.getName() + ".getItem", "the exchange names the error "
                        + "'item_missing', bound to " + Shop.ItemMissing.class.getName() + ", and the method declares "
                        + "no error bridging to it");
    }

    @Test
    void anErrorTheMethodDeclaresAndTheSchemaDoesNotNameIsRefused() {
        assertNames(refusal("lookup", Shop.LookupDeclaringAnExtraError.class), "'lookup.exchanges.get_item'",
                Shop.LookupDeclaringAnExtraError.class.getName() + ".getItem", "the method declares "
                        + OutOfStockException.class.getName() + ", which bridges to " + OutOfStock.class.getName()
                        + ", and the exchange names no error bound to that class -- it names [item_missing]");
    }

    @Test
    void anExchangeNamingOneErrorTwiceIsRefused() {
        // missing_alias is an alias of item_missing, so both land on the record ItemMissing binds.
        assertNames(refusal("doubled", Shop.Lookup.class), "'doubled.exchanges.get_item'", "the exchange names the "
                + "errors 'item_missing' and 'missing_alias', which both bind to " + Shop.ItemMissing.class.getName()
                + ": the exchange names one error twice -- drop one");
    }

    @Test
    void anErrorThatIsNoRecordIsRefused() {
        assertNames(refusal("bad_error", Shop.Lookup.class), "'bad_error.exchanges.get_item'", "the error 'sku_text' "
                + "lands on an atom 'text', and an error is a record, on the Java side as in the schema");
    }

    @Test
    void anErrorNothingBindsIsRefused() {
        Map<String, Class<?>> withoutError = new HashMap<>(ShopExamples.BINDINGS);
        withoutError.remove("item_missing");
        assertNames(refusal(ShopExamples.tson(withoutError), SHOP, "lookup", Shop.Lookup.class),
                "'lookup.exchanges.get_item'", "the error 'item_missing' lands on the record 'item_missing' of '"
                        + ShopExamples.TYPES_IDENTITY + "', which the bind context binds to no class");
    }
}
