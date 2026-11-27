package io.ltr8.tson.service.protocol;

import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Union;

import java.util.List;
import java.util.Map;

/**
 * The Java side of {@code shop.tn}'s protocols: the records its types bind to, the exceptions bridging its error
 * records through {@link ToData}, the interface serving {@code shop} and its implementation, and one interface per
 * agreement or refusal the join is held to. Everything is public, since bind takes its handles from the public
 * lookup.
 */
public final class Shop {

    private Shop() {
    }

    /** What {@code find_any} answers: the choice {@code found}, as a sealed union of its two records. */
    public sealed interface Found permits Item, ItemMissing {
    }

    /** A sealed union with a non-sealed member, whose implementations no startup check can see. */
    public sealed interface Loose permits Item, Unforeseen {
    }

    public non-sealed interface Unforeseen extends Loose {
    }

    /** A closed union with a member the choice {@code found} has no variant for. */
    public sealed interface Wider permits Item, ItemMissing, OutOfStock {
    }

    /** A union bind declares sealed by its annotation, listing both of {@code found}'s records. */
    @Union(value = {Item.class, ItemMissing.class}, sealed = true)
    public interface Listed {
    }

    /** A union bind declares sealed, listing only {@code item}, though {@link ItemMissing} implements it too. */
    @Union(value = {Item.class}, sealed = true)
    public interface Narrow {
    }

    /** {@code item_status}, an enum, as a Java enum. */
    public enum ItemStatus { open, closed }

    /**
     * A request class bridged to {@code item_ref}'s record through {@link ToData}: the exchange's request descriptor
     * carries the bridge, and its data class is {@link ItemRef}.
     */
    public static final class ItemQuery implements ToData<ItemRef> {

        private final String sku;

        public ItemQuery(ItemRef data) {
            this.sku = data.sku();
        }

        public String sku() {
            return sku;
        }

        @Override
        public ItemRef toData() {
            return new ItemRef(sku);
        }
    }

    public record Item(String sku, int quantity) implements Found, Loose, Wider, Listed, Narrow {
    }

    public record ItemRef(String sku) {
    }

    public record NewItem(String sku, int quantity) {
    }

    public record StockQuery(String prefix) {
    }

    public record ItemMissing(String sku) implements Found, Wider, Listed, Narrow {
    }

    public record OutOfStock(String sku) implements Wider {
    }

    public record Widget(String code) {
    }

    public static class ItemMissingException extends Exception implements ToData<ItemMissing> {

        private final ItemMissing data;

        public ItemMissingException(ItemMissing data) {
            super("no item " + data.sku());
            this.data = data;
        }

        @Override
        public ItemMissing toData() {
            return data;
        }
    }

    public static class OutOfStockException extends Exception implements ToData<OutOfStock> {

        private final OutOfStock data;

        public OutOfStockException(OutOfStock data) {
            super("out of " + data.sku());
            this.data = data;
        }

        @Override
        public OutOfStock toData() {
            return data;
        }
    }

    /** Serves {@code shop}, every method spelt in camelCase where the schema spells its exchange in snake_case. */
    public interface ShopApi {

        Item addItem(NewItem request) throws OutOfStockException;

        Item getItem(ItemRef request) throws ItemMissingException;

        List<Item> listItems(StockQuery request);

        Map<String, Integer> stock(StockQuery request);

        void removeItem(ItemRef request) throws ItemMissingException;

        int countItems();
    }

    public static class ShopService implements ShopApi {

        @Override
        public Item addItem(NewItem request) throws OutOfStockException {
            if (request.quantity() > 10) {
                throw new OutOfStockException(new OutOfStock(request.sku()));
            }
            return new Item(request.sku(), request.quantity());
        }

        @Override
        public Item getItem(ItemRef request) throws ItemMissingException {
            if (request.sku().startsWith("GONE")) {
                throw new ItemMissingException(new ItemMissing(request.sku()));
            }
            return new Item(request.sku(), 1);
        }

        @Override
        public List<Item> listItems(StockQuery request) {
            return List.of(new Item(request.prefix() + "-1", 1));
        }

        @Override
        public Map<String, Integer> stock(StockQuery request) {
            return Map.of(request.prefix(), 3);
        }

        @Override
        public void removeItem(ItemRef request) throws ItemMissingException {
            if (request.sku().startsWith("GONE")) {
                throw new ItemMissingException(new ItemMissing(request.sku()));
            }
        }

        @Override
        public int countItems() {
            return 7;
        }
    }

    /** Serves {@code lookup}. */
    public interface Lookup {

        Item getItem(ItemRef request) throws ItemMissingException;
    }

    /** {@code lookup}, with a method the protocol does not declare. */
    public interface LookupAndMore extends Lookup {

        Item other(ItemRef request);
    }

    public interface LookupTakingNothing {

        Item getItem() throws ItemMissingException;
    }

    public interface LookupReturningVoid {

        void getItem(ItemRef request) throws ItemMissingException;
    }

    public interface LookupTakingAnotherRecord {

        Item getItem(NewItem request) throws ItemMissingException;
    }

    public interface LookupAnsweringAnotherRecord {

        NewItem getItem(ItemRef request) throws ItemMissingException;
    }

    public interface LookupDeclaringNoError {

        Item getItem(ItemRef request);
    }

    public interface LookupDeclaringAnExtraError {

        Item getItem(ItemRef request) throws ItemMissingException, OutOfStockException;
    }

    /** Refused by bind: an exchange takes one record or nothing. */
    public interface LookupSpread {

        Item getItem(String sku, int quantity);
    }

    public interface CounterTakingARequest {

        int countItems(ItemRef request);
    }

    public interface RemoverAnswering {

        Item removeItem(ItemRef request) throws ItemMissingException;
    }

    public interface BySku {

        Item find(ItemRef request);
    }

    public interface ListerAnsweringOne {

        Item listItems(StockQuery request);
    }

    public interface ListerOfAnotherRecord {

        List<NewItem> listItems(StockQuery request);
    }

    public interface Book {

        Map<String, Item> itemsBySku();
    }

    public interface Finder {

        Found findAny(ItemRef request);
    }

    public interface LooseFinder {

        Loose findAny(ItemRef request);
    }

    public interface WiderFinder {

        Wider findAny(ItemRef request);
    }

    public interface ListedFinder {

        Listed findAny(ItemRef request);
    }

    public interface NarrowFinder {

        Narrow findAny(ItemRef request);
    }

    public interface FinderAnsweringOne {

        Item findAny(ItemRef request);
    }

    /** {@code lookup} with its request bridged: the method takes {@link ItemQuery}, which bridges to the record. */
    public interface BridgedLookup {

        Item getItem(ItemQuery request) throws ItemMissingException;
    }

    public interface CounterAnsweringText {

        String countItems();
    }

    public interface CounterAnsweringARecord {

        Item countItems();
    }

    public interface StockOfText {

        Map<String, String> stock(StockQuery request);
    }

    public interface StockByNumber {

        Map<Integer, Integer> stock(StockQuery request);
    }

    public interface StockAsList {

        List<Integer> stock(StockQuery request);
    }

    public interface Levels {

        List<Integer> levels();
    }

    public interface LevelsAsText {

        List<String> levels();
    }

    public interface Statuses {

        ItemStatus statusOf(ItemRef request);

        boolean inStock(ItemRef request);
    }

    public interface StatusesAsNumbers {

        int statusOf(ItemRef request);

        boolean inStock(ItemRef request);
    }

    public interface Pinger {

        Item ping();
    }

    public interface Linker {

        String link();
    }

    public interface Gadgets {

        Widget getGadget(ItemRef request);
    }
}
