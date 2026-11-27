package io.ltr8.tson.http;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.http.schema.deployment.TsonDeployment;
import io.ltr8.tson.service.TsonBindings;
import io.ltr8.unicode.RestrictionLevel;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * This project's own {@code deployment.tn} -- the schema a deployment descriptor is written against -- as source
 * text a server can publish, as the bindings that make it usable, and as the reader of a descriptor written in it.
 *
 * <p><b>The schema is published; a descriptor never is.</b> A server serves {@link #source} at {@link #ID}'s path
 * like any other schema, and publishes the {@link TsonDeployment.AcceptanceProfile} its descriptor derives, never the
 * descriptor. Nothing here registers a descriptor with a schema source, so no document can name one.
 *
 * <p><b>A processor is handed a descriptor.</b> There is no {@code load()} that searches a path, a classpath or an
 * environment variable, and there will not be one: a descriptor is diffable where an environment variable is not, but
 * a runtime that loads whatever it finds still lets a container image change a security policy with no code diff.
 * {@link #read} takes the source text and the caller says where it came from.
 *
 * <p>{@link #tson} builds a fresh {@link Tson} of its own each call, with this schema resolved and bound to its
 * records, so registering it never collides with an application's; a server whose own {@code Tson} reads or writes
 * a descriptor or a profile adds {@link #bindings} to its map instead.
 */
public final class TsonDeploymentSchema {

    /** The schema a descriptor names -- the {@code !!id} it declares and the URL it is served at. */
    public static final String ID = "https://tson.io/2026/40/io/ltr8/http/deployment.tn";

    private static final String SOURCE = readResource("/deployment.tn");

    private static final Map<String, Class<?>> BINDINGS = Map.of(
            "deployment.descriptor", TsonDeployment.class,
            "deployment.acceptance_profile", TsonDeployment.AcceptanceProfile.class,
            "deployment.listener", TsonDeployment.Listener.class,
            "policy.restriction_level", RestrictionLevel.class,
            "policy.processor", TsonDeployment.Policy.class,
            "policy.identifier_policy", TsonDeployment.Policy.Identifiers.class,
            "policy.token_policy", TsonDeployment.Policy.Tokens.class,
            "policy.limits", TsonDeployment.Policy.Limits.class);

    private TsonDeploymentSchema() {
    }

    /** This schema's own source text, for a server that publishes it at {@link #ID}'s path. */
    public static String source() {
        return SOURCE;
    }

    /**
     * The bindings for every type {@code deployment.tn} declares and the {@code policy.tn} types it uses, by
     * qualified name, for a server whose own {@link Tson} reads or writes a descriptor or a profile and should not
     * restate this schema's vocabulary.
     */
    public static Map<String, Class<?>> bindings() {
        return BINDINGS;
    }

    /**
     * A descriptor, read from {@code source}.
     *
     * <p>Takes text rather than a path, a name or a key: the caller decides where a descriptor comes from and that
     * decision is visible at the call site. A descriptor that is not valid against this schema, or whose policy
     * {@link TsonDeployment}'s records refuse, stops the read.
     */
    public static TsonDeployment read(String source) {
        return tson().objectReader().read(source, TsonDeployment.class);
    }

    /** A fresh {@link Tson} with this schema resolved and bound -- what {@link #read} reads through. */
    public static Tson tson() {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(uri -> SOURCE))
                .withDataBindContext(TsonBindings.of(BINDINGS)));
        tson.resolve(SOURCE);
        return tson;
    }

    private static String readResource(String path) {
        try (InputStream in = TsonDeploymentSchema.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
