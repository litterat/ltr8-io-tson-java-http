package io.ltr8.tson.http;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The header directives a data document declares -- {@code !!id} and {@code !!schema} ([TSON-DATA] §2.2) --
 * read from the front of a stream <b>without consuming it</b>, so the body can still be read normally
 * afterwards.
 *
 * <p><b>What it is for.</b> Routing a request to the right schema version means knowing which schema the
 * document names before choosing how to read it. §7.1 designs for exactly this -- "classification requires at
 * most two directives of lookahead and no value parsing, so streams, previews, and content sniffers can classify
 * a document from its opening bytes" -- but tson-java exposes no API for it ({@code UPSTREAM.md} #9), so this
 * is a small, deliberately strict scanner rather than a use of the real parser.
 *
 * <p><b>Strict, and a miss is not an error.</b> It recognises the directive forms §2.2/§3.3 permit at the front
 * of a data document and stops at the first thing that is not one. Anything it cannot read confidently comes
 * back absent rather than guessed at, and the caller decides what that means -- {@link TsonSchemaVersions}
 * refuses the request, which is the safe reading. It never reports a schema the document does not name.
 *
 * <p><b>It is not a validator.</b> A document that peeks clean can still be malformed; the real read is what
 * says. This only answers "which schema does this claim to be governed by".
 */
public final class TsonDocumentPeek {

    /** How far into a document to look. Two directives and a URL each; a header past this is not a header. */
    public static final int DEFAULT_LIMIT = 8192;

    private final InputStream body;
    private final Optional<String> id;
    private final Optional<String> schema;

    private TsonDocumentPeek(InputStream body, Optional<String> id, Optional<String> schema) {
        this.body = body;
        this.id = id;
        this.schema = schema;
    }

    /** Peeks at {@code body}, buffering it if it does not already support {@code mark}. */
    public static TsonDocumentPeek of(InputStream body) {
        return of(body, DEFAULT_LIMIT);
    }

    /** {@link #of(InputStream)} looking at most {@code limit} bytes ahead. */
    public static TsonDocumentPeek of(InputStream body, int limit) {
        InputStream markable = body.markSupported() ? body : new BufferedInputStream(body, limit * 2);
        byte[] head;
        try {
            markable.mark(limit + 1);
            head = markable.readNBytes(limit);
            markable.reset();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Scanner scanner = new Scanner(new String(head, StandardCharsets.UTF_8));
        scanner.scan();
        return new TsonDocumentPeek(markable, scanner.id, scanner.schema);
    }

    /**
     * The stream to read the document from -- the same bytes, from the start. Use this rather than the stream
     * handed to {@link #of}, which may not be the one that was buffered.
     */
    public InputStream body() {
        return body;
    }

    /** The {@code !!schema} this document declares, absent if it declares none this could read. */
    public Optional<String> schema() {
        return schema;
    }

    /** The {@code !!id} this document declares, absent if it declares none this could read. */
    public Optional<String> id() {
        return id;
    }

    /**
     * Scans the leading directives. Hand-written because the real one is not reachable: {@code
     * TsonDataParser.peekDirectiveName} is package-private in an unexported package.
     */
    private static final class Scanner {

        private final String text;
        private int at;
        private Optional<String> id = Optional.empty();
        private Optional<String> schema = Optional.empty();

        Scanner(String text) {
            // §7.1: a byte order mark is stripped before parsing and is never part of the content.
            this.text = text.startsWith("﻿") ? text.substring(1) : text;
        }

        void scan() {
            skipWhitespace();
            // §2.2 admits only !!id and !!schema at the front of a data document, !!id first. Anything else
            // ends the header, and this stops rather than hunting further in.
            while (text.startsWith("!!", at)) {
                at += 2;
                int colon = text.indexOf(':', at);
                if (colon < 0) {
                    return;
                }
                String name = text.substring(at, colon).trim();
                at = colon + 1;
                Optional<String> argument = argument();
                if (argument.isEmpty()) {
                    return;
                }
                switch (name) {
                    case "id" -> id = argument;
                    case "schema" -> schema = argument;
                    // A directive this does not know is not a data-document header directive; stop rather
                    // than skip it, since the shape past it is no longer something this understands.
                    default -> {
                        return;
                    }
                }
                skipWhitespace();
            }
        }

        /** A directive's argument: a single-line token (§3.3), quoted or bare. */
        private Optional<String> argument() {
            while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) {
                at++;
            }
            if (at >= text.length()) {
                return Optional.empty();
            }
            return text.charAt(at) == '"' ? quoted() : bare();
        }

        private Optional<String> quoted() {
            at++;
            StringBuilder value = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '\\' && at < text.length()) {
                    // Enough for a URI, which is all a header argument carries here. A value needing more
                    // than this is one to hand back as absent rather than half-decode.
                    char escaped = text.charAt(at++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        default -> {
                            return Optional.empty();
                        }
                    }
                } else if (c == '"') {
                    // A blank argument is not a reference. Reporting it would route on "", which is a worse
                    // error message than "declares no schema" for the same malformed document.
                    return value.isEmpty() ? Optional.empty() : Optional.of(value.toString());
                } else if (c == '\n' || c == '\r') {
                    // A directive argument is single-line by grammar; an unterminated one is not readable.
                    return Optional.empty();
                } else {
                    value.append(c);
                }
            }
            // Ran out of the peek window mid-token: absent, not a truncated guess.
            return Optional.empty();
        }

        private Optional<String> bare() {
            int start = at;
            while (at < text.length() && !Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            // Ran to the end of the window with no terminator: cannot tell whether it was whole.
            if (at == text.length() || at == start) {
                return Optional.empty();
            }
            return Optional.of(text.substring(start, at));
        }

        private void skipWhitespace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
