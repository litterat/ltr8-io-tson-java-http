package io.ltr8.tson.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A response header's value as an endpoint declares it: an RFC 6570 level-1 template, literal text and simple
 * expressions {@code {name}}, a variable being ASCII letters, digits and {@code _} as a path template's is. A value
 * with no expression is a literal. Levels 2 to 4 -- an operator, several variables to an expression, a prefix, an
 * explode -- are refused, and so is a control character other than a tab, which no header value carries (RFC 9110
 * §5.5).
 */
final class HeaderTemplate {

    private HeaderTemplate() {
    }

    /**
     * The variables {@code template} names, in order.
     *
     * @throws IllegalArgumentException naming {@code label} and the header, if {@code template} is no level-1
     *                                  template
     */
    static List<String> variables(String label, String header, String template) {
        List<String> variables = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i);
                String name = close < 0 ? "" : template.substring(i + 1, close);
                if (close < 0 || !name.matches("[A-Za-z0-9_]+")) {
                    throw refused(label, header, template, "each expression is one variable of ASCII letters, digits "
                            + "and '_' in braces, '{id}', with no operator: a value is an RFC 6570 level-1 template");
                }
                variables.add(name);
                i = close + 1;
            } else if (c == '}') {
                throw refused(label, header, template, "a '}' closes no expression");
            } else if ((c < 0x20 && c != '\t') || c == 0x7F) {
                throw refused(label, header, template, String.format("U+%04X is a control character, which no "
                        + "header value carries", (int) c));
            } else {
                i++;
            }
        }
        return variables;
    }

    /**
     * {@code template} expanded (RFC 6570 §3.2.2): each variable replaced by {@code fields}' value for it,
     * percent-encoded but for the unreserved characters, and a variable {@code fields} has no value for (it answers
     * {@code null}) expanded to nothing, as an undefined variable is.
     */
    static String expand(String template, Function<String, String> fields) {
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i);
                String value = fields.apply(template.substring(i + 1, close));
                if (value != null) {
                    encode(value, out);
                }
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void encode(String value, StringBuilder out) {
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int octet = b & 0xFF;
            if ((octet >= 'A' && octet <= 'Z') || (octet >= 'a' && octet <= 'z') || (octet >= '0' && octet <= '9')
                    || octet == '-' || octet == '.' || octet == '_' || octet == '~') {
                out.append((char) octet);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(octet >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(octet & 0xF, 16)));
            }
        }
    }

    private static IllegalArgumentException refused(String label, String header, String template, String because) {
        return new IllegalArgumentException("'" + label + "' declares the response header '" + header + ": "
                + template + "', which is no level-1 template -- " + because);
    }
}
