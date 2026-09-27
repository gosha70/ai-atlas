/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.tools.JavaFileObject;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.egoge.ai.atlas.processor.constraints.ConstraintFixtures.compile;
import static com.egoge.ai.atlas.processor.constraints.ConstraintFixtures.entity;
import static com.egoge.ai.atlas.processor.constraints.ConstraintFixtures.ir;
import static com.egoge.ai.atlas.processor.constraints.ConstraintFixtures.javaLiteral;
import static com.egoge.ai.atlas.processor.constraints.ConstraintFixtures.message;
import static com.egoge.ai.atlas.processor.constraints.ConstraintFixtures.service;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The constraint model (FR-001..FR-004a): Bean Validation read by name, intersected independently
 * of order, overridden per key by {@code @AgenticConstraints}, checked for contradictions, and
 * patterns translated into the portable subset.
 */
class ConstraintModelTest {

    /** The negated form's fixed alternatives around the class members {@code X} (FR-004a). */
    private static final String NEGATED_PREFIX = "(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[\\uD800-\\uDBFF]"
            + "(?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]|[^";
    private static final String NEGATED_SUFFIX = "\\uD800-\\uDFFF])";

    // ------------------------------------------------------------ FR-002 mapping

    @Test
    void minAndMaxAreInclusiveBounds() {
        EffectiveConstraints c = constraints("@Min(5) @Max(9) int x");
        assertThat(c).isEqualTo(new EffectiveConstraints("5", false, "9", false, null, null, null, null,
                List.of(), false));
    }

    @Test
    void decimalBoundsKeepTheirInclusivity() {
        assertThat(constraints("@DecimalMin(\"1.50\") @DecimalMax(value = \"2.5\", inclusive = false) BigDecimal x"))
                .isEqualTo(new EffectiveConstraints("1.5", false, "2.5", true, null, null, null, null,
                        List.of(), false));
        assertThat(constraints("@DecimalMin(value = \"0.5\", inclusive = false) Double x"))
                .isEqualTo(new EffectiveConstraints("0.5", true, null, false, null, null, null, null,
                        List.of(), false));
    }

    @Test
    void signConstraintsAreBoundsAtZero() {
        assertThat(constraints("@Positive long x").lower()).isEqualTo(endpoint("0", true));
        assertThat(constraints("@PositiveOrZero long x").lower()).isEqualTo(endpoint("0", false));
        assertThat(constraints("@Negative long x").upper()).isEqualTo(endpoint("0", true));
        assertThat(constraints("@NegativeOrZero long x").upper()).isEqualTo(endpoint("0", false));
    }

    @Test
    void sizeIsALengthOnAStringAndItemsOnACollectionOrArray() {
        assertThat(constraints("@Size(min = 2, max = 5) String x")).isEqualTo(new EffectiveConstraints(null,
                false, null, false, 2, 5, null, null, List.of(), false));
        assertThat(constraints("@Size(min = 1, max = 3) List<String> x")).isEqualTo(new EffectiveConstraints(null,
                false, null, false, null, null, 1, 3, List.of(), false));
        assertThat(constraints("@Size(max = 4) String[] x")).isEqualTo(new EffectiveConstraints(null,
                false, null, false, null, null, null, 4, List.of(), false));
    }

    @Test
    void sizeDefaultBoundsAreNotRecorded() {
        assertThat(constraints("@Size String x")).isEqualTo(EffectiveConstraints.NONE);
        assertThat(constraints("@Size(max = 7) String x").minLength()).isNull();
        assertThat(constraints("@Size(min = 7) List<String> x").maxItems()).isNull();
    }

    @Test
    void patternAddsItsRegexAndSortedFlags() {
        assertThat(constraints("@Pattern(regexp = \"[a-z]+\", flags = {Pattern.Flag.MULTILINE,"
                + " Pattern.Flag.CASE_INSENSITIVE}) String x").patterns())
                .containsExactly(new PatternConstraint("[a-z]+", List.of("CASE_INSENSITIVE", "MULTILINE")));
    }

    @Test
    void notBlankIsItsOwnKeyAndRequired() {
        ContractIr.Parameter p = parameter("@NotBlank String x");
        assertThat(p.constraints()).isEqualTo(new EffectiveConstraints(null, false, null, false, null, null,
                null, null, List.of(), true));
        assertThat(p.constraints().patterns()).isEmpty();
        assertThat(p.required()).isTrue();
    }

    @Test
    void notEmptyIsAMinimumOfOne() {
        assertThat(constraints("@NotEmpty String x").minLength()).isEqualTo(1);
        assertThat(constraints("@NotEmpty List<String> x").minItems()).isEqualTo(1);
        assertThat(constraints("@NotEmpty int[] x").minItems()).isEqualTo(1);
    }

    @Test
    void otherConstraintsAreIgnored() {
        assertThat(constraints("@Email @Future @Digits(integer = 3, fraction = 0) String x"))
                .isEqualTo(EffectiveConstraints.NONE);
    }

    @Test
    void onlyTheDefaultGroupCounts() {
        assertThat(constraints("@Min(value = 5, groups = Svc.Custom.class) int x")).isEqualTo(EffectiveConstraints.NONE);
        assertThat(constraints("@Min(value = 5, groups = {Svc.Custom.class, Default.class}) int x").minimum())
                .isEqualTo("5");
        assertThat(constraints("@Min(value = 5, groups = Default.class) int x").minimum()).isEqualTo("5");
    }

    @Test
    void repeatedAndListedPatternsCountOneByOne() {
        List<PatternConstraint> expected = List.of(new PatternConstraint("[a-z]+", List.of()),
                new PatternConstraint("a.*", List.of()));
        assertThat(constraints("@Pattern(regexp = \"a.*\") @Pattern(regexp = \"[a-z]+\") String x").patterns())
                .isEqualTo(expected);
        assertThat(constraints("@Pattern.List({@Pattern(regexp = \"a.*\"), @Pattern(regexp = \"[a-z]+\")})"
                + " String x").patterns()).isEqualTo(expected);
        assertThat(constraints("@Min.List({@Min(3), @Min(8)}) int x").minimum()).isEqualTo("8");
    }

    @Test
    void fieldConstraintsAreRecorded() {
        ContractIr ir = ir(compile(entity("@Size(max = 50) @NotBlank String name")));
        ContractIr.Field field = ir.entities().get(0).fields().get(0);
        assertThat(field.constraints()).isEqualTo(new EffectiveConstraints(null, false, null, false, null, 50,
                null, null, List.of(), true));
    }

    // ------------------------------------------------------------ FR-003 intersection and precedence

    @Test
    void intersectionIsIndependentOfOrder() {
        assertThat(constraints("@Min(10) @Positive int x").lower()).isEqualTo(endpoint("10", false));
        assertThat(constraints("@Positive @Min(10) int x").lower()).isEqualTo(endpoint("10", false));
        assertThat(constraints("@Max(5) @Negative int x").upper()).isEqualTo(endpoint("0", true));
        assertThat(constraints("@Negative @Max(5) int x").upper()).isEqualTo(endpoint("0", true));
        assertThat(constraints("@PositiveOrZero @Positive BigDecimal x").lower()).isEqualTo(endpoint("0", true));
        assertThat(constraints("@Size(min = 2, max = 9) @Size(min = 4, max = 12) String x"))
                .isEqualTo(new EffectiveConstraints(null, false, null, false, 4, 9, null, null, List.of(), false));
    }

    @Test
    void notBlankAndPatternAreBothKept() {
        EffectiveConstraints c = constraints("@NotBlank @Pattern(regexp = \"[a-z ]+\") String x");
        assertThat(c.notBlank()).isTrue();
        assertThat(c.patterns()).containsExactly(new PatternConstraint("[a-z ]+", List.of()));
    }

    @Test
    void overrideReplacesOnlyTheKeysItSets() {
        assertThat(constraints("@Min(1) @Max(100) @AgenticConstraints(maximum = \"50\") int x"))
                .isEqualTo(new EffectiveConstraints("1", false, "50", false, null, null, null, null,
                        List.of(), false));
        assertThat(constraints("@Size(min = 1, max = 10) @AgenticConstraints(maxLength = 5) String x"))
                .isEqualTo(new EffectiveConstraints(null, false, null, false, 1, 5, null, null, List.of(), false));
        assertThat(constraints("@Pattern(regexp = \"a+\") @Pattern(regexp = \"b+\") @NotBlank"
                + " @AgenticConstraints(pattern = \"[ab]+\") String x"))
                .isEqualTo(new EffectiveConstraints(null, false, null, false, null, null, null, null,
                        List.of(new PatternConstraint("[ab]+", List.of())), true));
        assertThat(constraints("@AgenticConstraints(minimum = \"0\", exclusiveMinimum = true) Integer x").lower())
                .isEqualTo(endpoint("0", true));
    }

    @Test
    void requirednessResolution() {
        assertThat(parameter("Integer x").required()).isTrue();
        assertThat(parameter("int x").required()).isTrue();
        assertThat(parameter("@NotNull Integer x").required()).isTrue();
        assertThat(parameter("@AgenticParam(required = Requiredness.OPTIONAL) Integer x").required()).isFalse();
        assertThat(parameter("@AgenticParam(required = Requiredness.REQUIRED) Integer x").required()).isTrue();
        assertThat(parameter("@AgenticParam(description = \"Page size\") Integer x").description())
                .isEqualTo("Page size");
    }

    // ------------------------------------------------------------ FR-004 errors and warnings

    @Test
    void contradictoryBeanValidationBoundsError() {
        assertErrorOnParameter("@Min(10) @Max(5) int x", "no value satisfies both >= 10 and <= 5");
    }

    @Test
    void exclusiveBoundsLeavingNoIntegerError() {
        assertErrorOnParameter("@DecimalMin(value = \"4\", inclusive = false)"
                + " @DecimalMax(value = \"5\", inclusive = false) int x", "no value satisfies both > 4 and < 5");
    }

    @Test
    void equalBoundsWithAnExclusiveOneError() {
        assertErrorOnParameter("@DecimalMin(value = \"5\", inclusive = false) @DecimalMax(\"5\") BigDecimal x",
                "no value satisfies both > 5 and <= 5");
        assertThat(compile(service("@Min(5) @Max(5) int x")).status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    @Test
    void contradictionIntroducedByTheOverrideErrors() {
        assertErrorOnParameter("@Min(10) @AgenticConstraints(maximum = \"5\") int x",
                "no value satisfies both >= 10 and <= 5");
    }

    @Test
    void lengthAndItemRangeErrors() {
        assertErrorOnParameter("@AgenticConstraints(minLength = -5) String x", "Negative minLength -5");
        assertErrorOnParameter("@Size(min = 5, max = 2) String x", "minLength 5 is above maxLength 2");
        assertErrorOnParameter("@AgenticConstraints(minItems = 3, maxItems = 1) List<String> x",
                "minItems 3 is above maxItems 1");
    }

    @Test
    void patternsThatDoNotCompileError() {
        assertErrorOnParameter("@Pattern(regexp = \"[a\") String x", "does not compile as a Java regex");
        assertErrorOnParameter("@AgenticConstraints(pattern = \"(\") String x", "does not compile as a Java regex");
    }

    @Test
    void exclusiveFlagWithoutItsBoundErrors() {
        assertErrorOnParameter("@AgenticConstraints(exclusiveMinimum = true) Integer x",
                "exclusiveMinimum = true) on parameter 'x' without a minimum");
        assertErrorOnParameter("@Max(3) @AgenticConstraints(exclusiveMaximum = true) Integer x",
                "exclusiveMaximum = true) on parameter 'x' without a maximum");
    }

    @Test
    void attributesOnTheWrongTypeError() {
        assertErrorOnParameter("@AgenticConstraints(minLength = 1) Integer x", "Length constraints on parameter 'x'");
        assertErrorOnParameter("@AgenticConstraints(minItems = 1) String x", "Item constraints on parameter 'x'");
        assertErrorOnParameter("@AgenticConstraints(maxLength = 1) int x", "Length constraints on parameter 'x'");
        assertErrorOnParameter("@AgenticConstraints(maxItems = 1) long x", "Item constraints on parameter 'x'");
        assertErrorOnParameter("@AgenticConstraints(minimum = \"1\") String x", "Bounds on parameter 'x'");
        assertErrorOnParameter("@AgenticConstraints(minimum = \"one\") Integer x", "is not a decimal number");
    }

    @Test
    void optionalContradictingTheTypeOrBeanValidationErrors() {
        assertErrorOnParameter("@AgenticParam(required = Requiredness.OPTIONAL) int x", "primitive");
        assertErrorOnParameter("@NotNull @AgenticParam(required = Requiredness.OPTIONAL) Integer x",
                "contradicts its @NotNull, @NotBlank or @NotEmpty");
        assertErrorOnParameter("@NotEmpty @AgenticParam(required = Requiredness.OPTIONAL) String x",
                "contradicts its @NotNull, @NotBlank or @NotEmpty");
    }

    @Test
    void errorsOnAFieldAreReportedOnTheField() {
        JavaFileObject source = entity("@Min(9) @Max(1) Integer count");
        Compilation compilation = compile(source);
        CompilationSubject.assertThat(compilation).hadErrorContaining("field 'count'").inFile(source)
                .onLineContaining("Integer count");
    }

    @Test
    void looserInputOverrideWarnsNamingTheKeyAndBothValues() {
        Compilation compilation = compile(service("@Min(10) @AgenticConstraints(minimum = \"5\") Integer x"));
        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation).hadWarningContaining("minimum >= 10 → >= 5");

        CompilationSubject.assertThat(compile(service("@Size(max = 5) @AgenticConstraints(maxLength = 8) String x")))
                .hadWarningContaining("maxLength 5 → 8");
        CompilationSubject.assertThat(compile(service("@Pattern(regexp = \"a+\")"
                + " @AgenticConstraints(pattern = \"b+\") String x"))).hadWarningContaining("patterns ['a+'] → ['b+']");
    }

    @Test
    void tighterOrEquivalentOverridesAndOutputOverridesDoNotWarn() {
        assertNoOverrideWarning(compile(service("@Min(5) @AgenticConstraints(minimum = \"10\") Integer x")));
        // On an int, > 9 and >= 10 admit the same values
        assertNoOverrideWarning(compile(service("@Min(10) @AgenticConstraints(minimum = \"9\","
                + " exclusiveMinimum = true) int x")));
        assertNoOverrideWarning(compile(entity("@Min(10) @AgenticConstraints(minimum = \"5\") Integer count")));
    }

    // ------------------------------------------------------------ FR-004a translation

    @Test
    void subsetTableTranslatesAsSpecified() {
        assertTranslation("abc -", "abc -");
        assertTranslation("é\u0001", "\\u00E9\\u0001");
        assertTranslation("\\\\\\.\\*\\+\\?\\(\\)\\[\\]\\{\\}\\|\\^\\$\\/", "\\\\\\.\\*\\+\\?\\(\\)\\[\\]\\{\\}\\|\\^\\$\\/");
        assertTranslation("a\\-b", "a-b");
        assertTranslation("[a\\-z]", "[a\\-z]");
        assertTranslation("\\t\\n\\r\\f\\x41\\u00e9", "\\t\\n\\r\\f\\x41\\u00e9");
        assertTranslation("\\d", "[0-9]");
        assertTranslation("\\w", "[a-zA-Z_0-9]");
        assertTranslation("\\s", "[ \\t\\n\\x0B\\f\\r]");
        assertTranslation("\\D", negated("0-9"));
        assertTranslation("\\W", negated("a-zA-Z_0-9"));
        assertTranslation("\\S", negated(" \\t\\n\\x0B\\f\\r"));
        assertTranslation(".", negated("\\n\\r\\u0085\\u2028\\u2029"));
        assertTranslation("[abc]", "[abc]");
        assertTranslation("[^abc]", negated("abc"));
        assertTranslation("[a-f0-9]", "[a-f0-9]");
        assertTranslation("[\\d_]", "[0-9_]");
        assertTranslation("[^\\s,]", negated(" \\t\\n\\x0B\\f\\r,"));
        assertTranslation("[\\w.]", "[a-zA-Z_0-9.]");
        assertTranslation("(a)(?:b)|c", "(a)(?:b)|c");
        assertTranslation("a*b+c?d{2}e{2,}f{2,3}", "a*b+c?d{2}e{2,}f{2,3}");
        assertTranslation("a*?b+?c??d{2}?e{2,}?f{2,3}?", "a*?b+?c??d{2}?e{2,}?f{2,3}?");
        assertTranslation("[-a]", "[\\-a]");
        assertTranslation("[a-]", "[a\\-]");
        assertTranslation("a]}", "a\\]\\}");
        assertTranslation("", "");
    }

    @Test
    void negatedFormHasTheFourAlternatives() {
        assertThat(PortableRegex.translate("[^a]").published()).isEqualTo("^(?:(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"
                + "|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]"
                + "|[^a\\uD800-\\uDFFF]))$");
    }

    @Test
    void translationsAgreeWithJavaOnBmpInputs() {
        List<String> patterns = List.of("[A-Z]+", "\\s+", "\\S+", ".", "[^a]{2}", "\\d+", "\\w+", "[\\d_]+",
                "[^\\s,]+", "(ab|c)*d?", "[-a]+", "a\\-b");
        List<String> inputs = List.of("", "ABC", "xABCy", " \t", " ", "a", "ab", "٣", "é", "4_2", "a,b",
                "\n", "abcd", "cc", "a-b", "-a", " ");
        for (String regex : patterns) {
            Pattern original = Pattern.compile(regex);
            Pattern published = Pattern.compile(PortableRegex.translate(regex).published());
            for (String input : inputs) {
                // matches(), not find(): Java's '$' also matches before a final line terminator
                assertThat(published.matcher(input).matches()).as("%s on %s", regex, input)
                        .isEqualTo(original.matcher(input).matches());
            }
        }
    }

    static Stream<Arguments> excludedConstructs() {
        return Stream.of(
                Arguments.of("[\\S]", "complemented escape '\\S' inside a class"),
                Arguments.of("[\\D_]", "complemented escape '\\D' inside a class"),
                Arguments.of("[\\uD7FF-\\uE000]", "range spanning U+D800-U+DFFF"),
                Arguments.of("(?i)a", "inline flags"),
                Arguments.of("(?i:a)", "inline flags"),
                Arguments.of("a*+", "possessive quantifier"),
                Arguments.of("(?>a)", "atomic group"),
                Arguments.of("(?=a)a", "lookaround"),
                Arguments.of("(?!b)a", "lookaround"),
                Arguments.of("a(?<=a)", "lookaround"),
                Arguments.of("a(?<!b)", "lookaround"),
                Arguments.of("(a)\\1", "backreference"),
                Arguments.of("(?<n>a)", "named group"),
                Arguments.of("a^b", "anchor '^'"),
                Arguments.of("^a", "anchor '^'"),
                Arguments.of("a$", "anchor '$'"),
                Arguments.of("\\ba", "escape '\\b'"),
                Arguments.of("\\p{L}+", "Unicode property"),
                Arguments.of("\\Qa\\E", "quotation"),
                Arguments.of("[a[b]]", "nested class"),
                Arguments.of("[a-z&&[^b]]", "class intersection"),
                Arguments.of("\\01", "octal escape"),
                Arguments.of("\\x{41}", "'\\x{...}' escape"),
                Arguments.of("😀", "outside the BMP"),
                Arguments.of("\\uD83D", "surrogate escape"));
    }

    @ParameterizedTest
    @MethodSource("excludedConstructs")
    void excludedConstructsAreNotPublishable(String regex, String construct) {
        Pattern.compile(regex);
        PortableRegex.Translation translation = PortableRegex.translate(regex);
        assertThat(translation.publishable()).isFalse();
        assertThat(translation.unsupported()).contains(construct);
    }

    @ParameterizedTest
    @MethodSource("excludedConstructs")
    void unpublishablePatternsWarnNamingTheConstructAndStayInTheIr(String regex, String construct) {
        Compilation compilation = compile(service("@Pattern(regexp = \"" + javaLiteral(regex) + "\") String x"));
        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation).hadWarningContaining("The pattern '" + regex + "'");
        CompilationSubject.assertThat(compilation).hadWarningContaining(construct);
        assertThat(parameterOf(compilation).constraints().patterns())
                .containsExactly(new PatternConstraint(regex, List.of()));
    }

    @Test
    void flaggedPatternsAreNotPublishable() {
        PortableRegex.Translation translation = PortableRegex.translate(
                new PatternConstraint("a", List.of("CASE_INSENSITIVE")));
        assertThat(translation.publishable()).isFalse();
        assertThat(translation.unsupported()).contains("flags [CASE_INSENSITIVE]");
        CompilationSubject.assertThat(compile(service("@Pattern(regexp = \"a\", flags = Pattern.Flag.CASE_INSENSITIVE)"
                + " String x"))).hadWarningContaining("flags [CASE_INSENSITIVE]");
    }

    @Test
    void publishablePatternsDoNotWarn() {
        Compilation compilation = compile(service("@Pattern(regexp = \"[\\\\d_]+\") String x"));
        assertThat(compilation.warnings()).noneMatch(d -> message(d).contains("cannot be published"));
    }

    @Test
    void notBlankIsPublishedAsOneCodeUnitAboveSpace() {
        assertThat(PortableRegex.NOT_BLANK_PATTERN).isEqualTo("[^\\u0000-\\u0020]");
        Pattern notBlank = Pattern.compile(PortableRegex.NOT_BLANK_PATTERN);
        assertThat(Stream.of("a\nb", " a ", " ", " ")).allMatch(s -> notBlank.matcher(s).find());
        assertThat(Stream.of("", " ", "\n\t", "\u0000")).noneMatch(s -> notBlank.matcher(s).find());
    }

    // ------------------------------------------------------------ helpers

    private static void assertTranslation(String regex, String translated) {
        Pattern.compile(regex);
        PortableRegex.Translation translation = PortableRegex.translate(regex);
        assertThat(translation.unsupported()).as(regex).isNull();
        assertThat(translation.published()).as(regex).isEqualTo("^(?:" + translated + ")$");
    }

    private static String negated(String members) {
        return NEGATED_PREFIX + members + NEGATED_SUFFIX;
    }

    private static Endpoint endpoint(String value, boolean exclusive) {
        return new Endpoint(new java.math.BigDecimal(value), exclusive);
    }

    private static void assertErrorOnParameter(String declaration, String message) {
        JavaFileObject source = service(declaration);
        Compilation compilation = compile(source);
        CompilationSubject.assertThat(compilation).failed();
        CompilationSubject.assertThat(compilation).hadErrorContaining(message).inFile(source).onLineContaining("op(");
    }

    private static void assertNoOverrideWarning(Compilation compilation) {
        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(compilation.warnings()).noneMatch(d -> message(d).contains("looser"));
    }

    private static EffectiveConstraints constraints(String parameter) {
        return parameter(parameter).constraints();
    }

    private static ContractIr.Parameter parameter(String declaration) {
        Compilation compilation = compile(service(declaration));
        CompilationSubject.assertThat(compilation).succeeded();
        return parameterOf(compilation);
    }

    private static ContractIr.Parameter parameterOf(Compilation compilation) {
        return ir(compilation).operations().get(0).parameters().get(0);
    }

}
