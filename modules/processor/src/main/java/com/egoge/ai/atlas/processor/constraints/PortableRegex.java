/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;

/**
 * Translates a Java pattern written in FR-004a's portable subset into the ECMAScript form a
 * schema publishes, which accepts exactly the values Java's whole-string match accepts, with and
 * without the {@code u} flag. A pattern outside the subset is not publishable; the result names the
 * first unsupported construct.
 *
 * <p>The input must already compile as a Java regex: the translator relies on Java's own syntax
 * checks for balanced groups and well-formed quantifiers.
 */
public final class PortableRegex {

    /** {@code notBlank}'s published pattern, unanchored: one code unit above U+0020 (FR-004a). */
    public static final String NOT_BLANK_PATTERN = "[^\\u0000-\\u0020]";

    /** Java's {@code \d}, as class members. */
    static final String DIGIT_MEMBERS = "0-9";
    /** Java's {@code \w}, as class members. */
    static final String WORD_MEMBERS = "a-zA-Z_0-9";
    /** Java's {@code \s}, as class members. */
    static final String SPACE_MEMBERS = " \\t\\n\\x0B\\f\\r";
    /** The characters Java's {@code .} excludes, as class members. */
    static final String LINE_TERMINATOR_MEMBERS = "\\n\\r\\u0085\\u2028\\u2029";

    /** Metacharacters whose escaped form is in the subset and is emitted as itself. */
    private static final String ESCAPABLE = "\\.*+?()[]{}|^$/";
    private static final char FIRST_PRINTABLE = 0x20;
    private static final char LAST_PRINTABLE = 0x7E;
    private static final int HEX_RADIX = 16;
    private static final int X_ESCAPE_DIGITS = 2;
    private static final int U_ESCAPE_DIGITS = 4;
    private static final char FIRST_SURROGATE = 0xD800;
    private static final char LAST_SURROGATE = 0xDFFF;

    /**
     * The outcome of a translation.
     *
     * @param published   the anchored ECMAScript pattern {@code ^(?:t)$}, or {@code null}
     * @param unsupported why the pattern is not publishable, naming the first unsupported
     *                    construct, or {@code null}
     */
    public record Translation(String published, String unsupported) {

        /** Whether the pattern can be published in a schema. */
        public boolean publishable() {
            return published != null;
        }
    }

    private final String source;
    private int pos;
    private final StringBuilder out = new StringBuilder();

    private PortableRegex(String source) {
        this.source = source;
    }

    /**
     * Translates a pattern with its flags; a pattern with any flag is not publishable.
     *
     * @param pattern the pattern, which must compile as a Java regex with its flags
     * @return the translation
     */
    public static Translation translate(PatternConstraint pattern) {
        if (!pattern.flags().isEmpty()) {
            return new Translation(null, "flags " + pattern.flags() + " are not in the portable subset");
        }
        return translate(pattern.regex());
    }

    /**
     * Translates a flag-free pattern.
     *
     * @param regex the pattern, which must compile as a Java regex
     * @return the translation
     */
    public static Translation translate(String regex) {
        PortableRegex translator = new PortableRegex(regex);
        try {
            translator.sequence(false);
        } catch (Unsupported e) {
            return new Translation(null, e.getMessage());
        }
        return new Translation("^(?:" + translator.out + ")$", null);
    }

    /**
     * The negated form: exactly one Java code point not in {@code members}, in both ECMAScript
     * modes (FR-004a).
     *
     * @param members class members, BMP non-surrogates only
     * @return the four-alternative group
     */
    static String negated(String members) {
        return "(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])"
                + "|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]|[^" + members + "\\uD800-\\uDFFF])";
    }

    /** Signals the first construct outside the portable subset. */
    private static final class Unsupported extends Exception {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    private Unsupported unsupported(String construct, int at) {
        return new Unsupported(construct + " at index " + at + " is not in the portable subset");
    }

    // ------------------------------------------------------------ outside a class

    /** Translates up to the end, or up to the {@code )} closing the group when {@code inGroup}. */
    private void sequence(boolean inGroup) throws Unsupported {
        boolean quantifiable = false;
        while (pos < source.length()) {
            int start = pos;
            char c = source.charAt(pos);
            switch (c) {
                case ')' -> {
                    if (!inGroup) {
                        throw unsupported("unbalanced ')'", start);
                    }
                    return;
                }
                case '(' -> {
                    group();
                    quantifiable = true;
                }
                case '|' -> {
                    pos++;
                    out.append('|');
                    quantifiable = false;
                }
                case '*', '+', '?', '{' -> {
                    if (!quantifiable) {
                        throw unsupported("quantifier '" + c + "' without a preceding atom", start);
                    }
                    quantifier();
                    quantifiable = false;
                }
                case '^', '$' -> throw unsupported("anchor '" + c + "' inside the pattern", start);
                case '.' -> {
                    pos++;
                    out.append(negated(LINE_TERMINATOR_MEMBERS));
                    quantifiable = true;
                }
                case '[' -> {
                    characterClass();
                    quantifiable = true;
                }
                case '\\' -> {
                    escape();
                    quantifiable = true;
                }
                default -> {
                    int codePoint = literal();
                    // Java reads a lone ']' or '}' as a literal; ECMAScript's u mode rejects it unescaped
                    out.append(codePoint == ']' || codePoint == '}' ? "\\" + (char) codePoint : emit(codePoint));
                    quantifiable = true;
                }
            }
        }
        if (inGroup) {
            throw unsupported("unclosed group", pos);
        }
    }

    private void group() throws Unsupported {
        int start = pos;
        pos++;
        if (source.startsWith("?", pos)) {
            if (source.startsWith("?:", pos)) {
                pos += 2;
                out.append("(?:");
            } else if (source.startsWith("?=", pos) || source.startsWith("?!", pos)
                    || source.startsWith("?<=", pos) || source.startsWith("?<!", pos)) {
                throw unsupported("lookaround", start);
            } else if (source.startsWith("?>", pos)) {
                throw unsupported("atomic group", start);
            } else if (source.startsWith("?<", pos)) {
                throw unsupported("named group", start);
            } else {
                throw unsupported("inline flags", start);
            }
        } else {
            out.append('(');
        }
        sequence(true);
        pos++;
        out.append(')');
    }

    private void quantifier() throws Unsupported {
        int start = pos;
        char c = source.charAt(pos);
        if (c == '{') {
            int close = source.indexOf('}', pos);
            String body = close < 0 ? "" : source.substring(pos + 1, close);
            if (!body.matches("[0-9]+(,[0-9]*)?")) {
                throw unsupported("quantifier '{" + body + "'", start);
            }
            out.append(source, pos, close + 1);
            pos = close + 1;
        } else {
            out.append(c);
            pos++;
        }
        if (pos < source.length() && source.charAt(pos) == '?') {
            out.append('?');
            pos++;
        } else if (pos < source.length() && source.charAt(pos) == '+') {
            throw unsupported("possessive quantifier", start);
        }
        if (pos < source.length() && "*+?{".indexOf(source.charAt(pos)) >= 0) {
            throw unsupported("stacked quantifier '" + source.charAt(pos) + "'", pos);
        }
    }

    private void escape() throws Unsupported {
        int start = pos;
        if (pos + 1 >= source.length()) {
            throw unsupported("trailing '\\'", start);
        }
        char e = source.charAt(pos + 1);
        switch (e) {
            case 'd' -> {
                pos += 2;
                out.append('[').append(DIGIT_MEMBERS).append(']');
            }
            case 'w' -> {
                pos += 2;
                out.append('[').append(WORD_MEMBERS).append(']');
            }
            case 's' -> {
                pos += 2;
                out.append('[').append(SPACE_MEMBERS).append(']');
            }
            case 'D' -> {
                pos += 2;
                out.append(negated(DIGIT_MEMBERS));
            }
            case 'W' -> {
                pos += 2;
                out.append(negated(WORD_MEMBERS));
            }
            case 'S' -> {
                pos += 2;
                out.append(negated(SPACE_MEMBERS));
            }
            case '-' -> {
                // ECMAScript's u mode rejects '\-' outside a class
                pos += 2;
                out.append('-');
            }
            default -> out.append(singleEscape(start).text());
        }
    }

    // ------------------------------------------------------------ inside a class

    /** One class member: a single character with its emitted text, or an expanded set. */
    private record Member(int codePoint, String text, boolean set) {
    }

    private void characterClass() throws Unsupported {
        int start = pos;
        pos++;
        boolean negate = pos < source.length() && source.charAt(pos) == '^';
        if (negate) {
            pos++;
        }
        if (pos < source.length() && source.charAt(pos) == ']') {
            throw unsupported("']' as the first member of a class", pos);
        }
        StringBuilder members = new StringBuilder();
        boolean first = true;
        while (true) {
            if (pos >= source.length()) {
                throw unsupported("unclosed class", start);
            }
            char c = source.charAt(pos);
            if (c == ']') {
                pos++;
                break;
            }
            if (c == '-') {
                int dash = pos;
                pos++;
                if (!first && !(pos < source.length() && source.charAt(pos) == ']')) {
                    throw unsupported("ambiguous '-' in a class", dash);
                }
                members.append("\\-");
                first = false;
                continue;
            }
            Member low = classMember();
            first = false;
            if (!low.set() && pos + 1 < source.length() && source.charAt(pos) == '-'
                    && source.charAt(pos + 1) != ']') {
                int dash = pos;
                pos++;
                Member high = classMember();
                if (high.set()) {
                    throw unsupported("range ending in a class escape", dash);
                }
                if (low.codePoint() < FIRST_SURROGATE && high.codePoint() > LAST_SURROGATE) {
                    throw unsupported("range spanning U+D800-U+DFFF", dash);
                }
                members.append(low.text()).append('-').append(high.text());
            } else {
                if (low.set() && pos + 1 < source.length() && source.charAt(pos) == '-'
                        && source.charAt(pos + 1) != ']') {
                    throw unsupported("ambiguous '-' in a class", pos);
                }
                members.append(low.text());
            }
        }
        out.append(negate ? negated(members.toString()) : "[" + members + "]");
    }

    private Member classMember() throws Unsupported {
        int start = pos;
        char c = source.charAt(pos);
        if (c == '[') {
            throw unsupported("nested class (union)", start);
        }
        if (c == '&' && source.startsWith("&&", pos)) {
            throw unsupported("class intersection '&&'", start);
        }
        if (c == '\\') {
            if (pos + 1 >= source.length()) {
                throw unsupported("trailing '\\'", start);
            }
            char e = source.charAt(pos + 1);
            switch (e) {
                case 'd' -> {
                    pos += 2;
                    return new Member(-1, DIGIT_MEMBERS, true);
                }
                case 'w' -> {
                    pos += 2;
                    return new Member(-1, WORD_MEMBERS, true);
                }
                case 's' -> {
                    pos += 2;
                    return new Member(-1, SPACE_MEMBERS, true);
                }
                case 'D', 'W', 'S' -> throw unsupported("complemented escape '\\" + e + "' inside a class", start);
                case '-' -> {
                    pos += 2;
                    return new Member('-', "\\-", false);
                }
                default -> {
                    return singleEscape(start);
                }
            }
        }
        int codePoint = literal();
        // '^' past the start and '-' are literal here in Java; escape them so ECMAScript agrees
        String text = codePoint == '^' ? "\\^" : emit(codePoint);
        return new Member(codePoint, text, false);
    }

    // ------------------------------------------------------------ shared

    /**
     * An escape naming one character, valid inside and outside a class: an escaped metacharacter,
     * {@code \t \n \r \f}, {@code \xhh} or a non-surrogate {@code \}{@code uhhhh}.
     */
    private Member singleEscape(int start) throws Unsupported {
        char e = source.charAt(pos + 1);
        if (ESCAPABLE.indexOf(e) >= 0) {
            pos += 2;
            return new Member(e, "\\" + e, false);
        }
        switch (e) {
            case 't' -> {
                pos += 2;
                return new Member('\t', "\\t", false);
            }
            case 'n' -> {
                pos += 2;
                return new Member('\n', "\\n", false);
            }
            case 'r' -> {
                pos += 2;
                return new Member('\r', "\\r", false);
            }
            case 'f' -> {
                pos += 2;
                return new Member('\f', "\\f", false);
            }
            case 'x' -> {
                if (source.startsWith("{", pos + 2)) {
                    throw unsupported("'\\x{...}' escape", start);
                }
                int value = hex(pos + 2, X_ESCAPE_DIGITS, start, "'\\x' escape");
                String text = source.substring(pos, pos + 2 + X_ESCAPE_DIGITS);
                pos += 2 + X_ESCAPE_DIGITS;
                return new Member(value, text, false);
            }
            case 'u' -> {
                int value = hex(pos + 2, U_ESCAPE_DIGITS, start, "'\\u' escape");
                if (value >= FIRST_SURROGATE && value <= LAST_SURROGATE) {
                    throw unsupported("surrogate escape '" + source.substring(pos, pos + 2 + U_ESCAPE_DIGITS) + "'",
                            start);
                }
                String text = source.substring(pos, pos + 2 + U_ESCAPE_DIGITS);
                pos += 2 + U_ESCAPE_DIGITS;
                return new Member(value, text, false);
            }
            case '0' -> throw unsupported("octal escape", start);
            case 'p', 'P' -> throw unsupported("Unicode property '\\" + e + "{...}'", start);
            case 'Q' -> throw unsupported("quotation '\\Q...\\E'", start);
            case 'k' -> throw unsupported("named backreference", start);
            default -> {
                if (e >= '1' && e <= '9') {
                    throw unsupported("backreference '\\" + e + "'", start);
                }
                throw unsupported("escape '\\" + e + "'", start);
            }
        }
    }

    private int hex(int from, int digits, int start, String construct) throws Unsupported {
        if (from + digits > source.length()) {
            throw unsupported("malformed " + construct, start);
        }
        try {
            return Integer.parseInt(source.substring(from, from + digits), HEX_RADIX);
        } catch (NumberFormatException e) {
            throw unsupported("malformed " + construct, start);
        }
    }

    /** Consumes one literal character, which must be a BMP non-surrogate. */
    private int literal() throws Unsupported {
        int start = pos;
        int codePoint = source.codePointAt(pos);
        if (Character.isSupplementaryCodePoint(codePoint)) {
            throw unsupported(String.format("literal U+%X outside the BMP", codePoint), start);
        }
        if (codePoint >= FIRST_SURROGATE && codePoint <= LAST_SURROGATE) {
            throw unsupported(String.format("surrogate U+%04X", codePoint), start);
        }
        pos++;
        return codePoint;
    }

    /** A literal BMP character: itself when printable ASCII, else {@code \}{@code uXXXX}. */
    private static String emit(int codePoint) {
        return codePoint >= FIRST_PRINTABLE && codePoint <= LAST_PRINTABLE
                ? String.valueOf((char) codePoint) : String.format("\\u%04X", codePoint);
    }
}
