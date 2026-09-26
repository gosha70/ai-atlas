/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Contract IR document: completeness, determinism, canonical form and reading (FR-001..FR-005). */
class ContractIrTest {

    /** The lifecycle fixture of the golden snapshot: inactive, deprecated, enum, collection, hint fields. */
    private static final Path LIFECYCLE_FIXTURE = Path.of("src/test/resources/golden/ir-rewire/fixtures/lifecycle");
    private static final String MAJOR_2 = "-A" + AgenticProcessor.OPT_API_MAJOR + "=2";

    /** A service whose lifecycle comes from the class and is partly overridden on a method. */
    private static final JavaFileObject CLASS_LIFECYCLE_SERVICE = JavaFileObjects.forSourceString(
            "shop.ReportService", """
            package shop;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            @AgenticExposed(description = "Reports", apiSince = 2, apiDeprecatedSince = 3,
                    apiReplacement = "v4 reports", channels = { AgenticExposed.Channel.AI })
            public class ReportService {
                @AgenticExposed(description = "Daily report", apiUntil = 4)
                public String daily() { return null; }
                @AgenticExposed(description = "Weekly report \\"quoted\\" — ünïcode")
                public String weekly() { return null; }
            }
            """);

    @Test
    void irRecordsEveryDeclarationIncludingElementsInactiveAtTheConfiguredMajor() throws Exception {
        ContractIr ir = IrJson.parse(compileLifecycle(), "api.ir.json");

        assertThat(ir.irVersion()).isEqualTo(ContractIr.IR_VERSION);
        assertThat(ir.apiBasePath()).isEqualTo("/api");
        assertThat(ir.apiMajor()).isEqualTo(2);
        assertThat(ir.entities()).extracting(Entity::className).containsExactly(
                "shop.Customer", "shop.Legacy", "shop.OrderLine", "shop.Product");

        Entity product = entity(ir, "shop.Product");
        assertThat(product.dtoName()).isEqualTo("ProductView");
        assertThat(product.dtoPackage()).isEqualTo("shop.generated");
        assertThat(product.displayName()).isEqualTo("product");
        assertThat(product.description()).isEqualTo("A catalog product");
        assertThat(product.includeTypeInfo()).isTrue();
        assertThat(product.fields()).extracting(Field::name).containsExactly(
                "id", "name", "cost", "legacyCode", "newCode", "status", "size", "tags", "statuses",
                "lines", "lineArray", "looseLines", "iterableLines", "primaryLine", "stock");

        // Inactive at apiMajor=2 in both directions, yet recorded with their lifecycle
        assertThat(field(product, "legacyCode").lifecycle())
                .isEqualTo(new ContractIr.FieldLifecycle(1, 2, 0, ""));
        assertThat(field(product, "newCode").lifecycle())
                .isEqualTo(new ContractIr.FieldLifecycle(3, Integer.MAX_VALUE, 0, ""));
        assertThat(entity(ir, "shop.Legacy").fields()).extracting(Field::name).containsExactly("id");
        assertThat(field(product, "status").lifecycle())
                .isEqualTo(new ContractIr.FieldLifecycle(1, Integer.MAX_VALUE, 2, "Use statuses"));

        Field status = field(product, "status");
        assertThat(status.javaType()).isEqualTo("shop.Status");
        assertThat(status.enumType()).isTrue();
        assertThat(status.allowedValues()).containsExactly("ACTIVE", "RETIRED");
        assertThat(status.openEnum()).isFalse();
        Field size = field(product, "size");
        assertThat(size.displayName()).isEqualTo("sizeCode");
        assertThat(size.enumType()).isFalse();
        assertThat(size.allowedValues()).containsExactly("S", "M", "L");
        assertThat(field(product, "cost").sensitive()).isTrue();
        assertThat(field(product, "primaryLine").checkCircularReference()).isFalse();
        assertThat(field(product, "primaryLine").reference())
                .isEqualTo(new TypeRef("shop.OrderLine", "shop.generated.OrderLineDto"));

        Field lines = field(product, "lines");
        assertThat(lines.javaType()).isEqualTo("java.util.List<shop.OrderLine>");
        assertThat(lines.collectionKind()).isEqualTo("COLLECTION");
        assertThat(lines.elementType()).isEqualTo("shop.OrderLine");
        assertThat(lines.typeHint()).isNull();
        assertThat(field(product, "lineArray").javaType()).isEqualTo("shop.OrderLine[]");
        assertThat(field(product, "lineArray").collectionKind()).isEqualTo("ARRAY");
        assertThat(field(product, "iterableLines").collectionKind()).isEqualTo("ITERABLE");
        assertThat(field(product, "stock").javaType()).isEqualTo("int");
        assertThat(field(product, "tags").reference()).isNull();

        // A field's type hint and the reference it resolves to
        Field loose = field(product, "looseLines");
        assertThat(loose.javaType()).isEqualTo("java.util.List<?>");
        assertThat(loose.typeHint()).isEqualTo("shop.OrderLine");
        assertThat(loose.reference()).isEqualTo(new TypeRef("shop.OrderLine", "shop.generated.OrderLineDto"));

        // A field referring to an entity whose DTO lives in a custom package
        assertThat(field(entity(ir, "shop.Customer"), "favourite").reference())
                .isEqualTo(new TypeRef("shop.Product", "shop.generated.ProductView"));
        assertThat(entity(ir, "shop.Customer").dtoPackage()).isEqualTo("shop.api");
    }

    @Test
    void irRecordsEveryOperationWithResolvedAttributesAndLifecycle() throws Exception {
        ContractIr ir = IrJson.parse(compileLifecycle(), "api.ir.json");

        assertThat(ir.operations()).extracting(Operation::id).containsExactly(
                "shop.AdminService#count(shop.Status)",
                "shop.AdminService#customers()",
                "shop.AdminService#label(java.lang.Long,boolean)",
                "shop.AdminService#refresh()",
                "shop.CatalogService#aiOnly(shop.Status)",
                "shop.CatalogService#find()",
                "shop.CatalogService#find(java.lang.Long)",
                "shop.CatalogService#findAll()",
                "shop.CatalogService#legacyLookup(java.lang.String)",
                "shop.CatalogService#listAll()",
                "shop.CatalogService#lookupV3(java.lang.String)",
                "shop.CatalogService#top(int)",
                "shop.ReportService#daily()",
                "shop.ReportService#weekly()");

        // Inactive at apiMajor=2: removed before it, and introduced after it
        assertThat(operation(ir, "shop.CatalogService#legacyLookup(java.lang.String)").lifecycle())
                .isEqualTo(new ContractIr.OperationLifecycle(1, 1, 0, ""));
        assertThat(operation(ir, "shop.CatalogService#lookupV3(java.lang.String)").lifecycle())
                .isEqualTo(new ContractIr.OperationLifecycle(3, Integer.MAX_VALUE, 0, ""));
        assertThat(operation(ir, "shop.CatalogService#listAll()").lifecycle())
                .isEqualTo(new ContractIr.OperationLifecycle(1, Integer.MAX_VALUE, 2, "findAll"));

        // Class-level lifecycle, partly overridden by the method
        assertThat(operation(ir, "shop.ReportService#daily()").lifecycle())
                .isEqualTo(new ContractIr.OperationLifecycle(2, 4, 3, "v4 reports"));
        assertThat(operation(ir, "shop.ReportService#weekly()").lifecycle())
                .isEqualTo(new ContractIr.OperationLifecycle(2, Integer.MAX_VALUE, 3, "v4 reports"));

        // Class-level returnType, resolved to the entity and its DTO
        Operation find = operation(ir, "shop.CatalogService#find(java.lang.Long)");
        assertThat(find.returns()).isEqualTo(new ContractIr.Return("shop.Product", "NONE", "shop.Product",
                new TypeRef("shop.Product", "shop.generated.ProductView")));
        assertThat(find.description()).isEqualTo("Browse the catalog");
        assertThat(find.toolName()).isEqualTo("find");
        assertThat(find.channels()).containsExactly("AI", "API");
        assertThat(find.rest()).isEqualTo(new ContractIr.Rest("POST", "/catalog-service/find"));
        assertThat(find.parameters()).containsExactly(
                new ContractIr.Parameter("id", "java.lang.Long", "", List.of()));
        assertThat(operation(ir, "shop.CatalogService#find()").toolName()).isEqualTo("findFeatured");
        assertThat(operation(ir, "shop.CatalogService#find()").rest())
                .isEqualTo(new ContractIr.Rest("GET", "/catalog-service/find"));
        assertThat(operation(ir, "shop.CatalogService#top(int)").returns().returnKind()).isEqualTo("ARRAY");
        assertThat(operation(ir, "shop.CatalogService#findAll()").returns().javaType())
                .isEqualTo("java.util.List<shop.Product>");

        // Method-level returnType on a List<?> method
        Operation customers = operation(ir, "shop.AdminService#customers()");
        assertThat(customers.returns()).isEqualTo(new ContractIr.Return("java.util.List<?>", "COLLECTION",
                "shop.Customer", new TypeRef("shop.Customer", "shop.api.CustomerDto")));

        // No returnType; void; enum parameter; tool name; channels without API
        Operation count = operation(ir, "shop.AdminService#count(shop.Status)");
        assertThat(count.toolName()).isEqualTo("countProducts");
        assertThat(count.returns()).isEqualTo(new ContractIr.Return("long", "NONE", null, null));
        assertThat(count.parameters()).containsExactly(
                new ContractIr.Parameter("status", "shop.Status", "", List.of("ACTIVE", "RETIRED")));
        assertThat(operation(ir, "shop.AdminService#refresh()").returns().javaType()).isEqualTo("void");
        assertThat(operation(ir, "shop.AdminService#label(java.lang.Long,boolean)").channels())
                .containsExactly("API");
        assertThat(operation(ir, "shop.CatalogService#aiOnly(shop.Status)").rest()).isNull();
        assertThat(operation(ir, "shop.ReportService#daily()").rest()).isNull();
    }

    @Test
    void sameSourcesAndOptionsProduceAByteIdenticalCanonicalDocument() throws Exception {
        String first = compileLifecycle();
        String second = compileLifecycle();

        assertThat(second.getBytes(StandardCharsets.UTF_8)).isEqualTo(first.getBytes(StandardCharsets.UTF_8));
        assertThat(IrJson.write(IrJson.parse(first, "api.ir.json"))).isEqualTo(first);

        assertThat(first).startsWith("{\n  \"irVersion\": 1,\n  \"apiBasePath\": \"/api\",\n  \"apiMajor\": 2,\n")
                .endsWith("}\n").doesNotContain("\r").doesNotContain("\t");
        assertThat(first.lines()).allSatisfy(line ->
                assertThat(line.length() - line.stripLeading().length()).isEven());
        assertThat(first).contains("\"description\": \"Weekly report \\\"quoted\\\" — ünïcode\"");
    }

    @Test
    void irCarriesNoEnvironmentData() throws IOException {
        String ir = compileLifecycle();

        assertThat(ir).doesNotContain(System.getProperty("user.dir"))
                .doesNotContain(System.getProperty("user.home"))
                .doesNotContain(System.getProperty("java.io.tmpdir"))
                .doesNotContain(File.separator + "Users" + File.separator)
                .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");
        String host = InetAddress.getLocalHost().getHostName();
        assertThat(ir).doesNotContain("\"" + host + "\"");
    }

    @Test
    void irIsWrittenWhenTheDeclarationsYieldNoOpenApiDocument() throws Exception {
        JavaFileObject noPublicMethods = JavaFileObjects.forSourceString("test.Quiet", """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Nothing public")
                public class Quiet {
                    void hidden() { }
                }
                """);
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(noPublicMethods);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        ContractIr ir = IrJson.parse(irOf(compilation), "api.ir.json");
        assertThat(ir.entities()).isEmpty();
        assertThat(ir.operations()).isEmpty();
        assertThat(ir.apiMajor()).isEqualTo(1);
    }

    @Test
    void emptyDocumentHasCanonicalEmptyArrays() {
        String json = IrJson.write(new ContractIr(ContractIr.IR_VERSION, "/api", 3, List.of(), List.of()));

        assertThat(json).isEqualTo("""
                {
                  "irVersion": 1,
                  "apiBasePath": "/api",
                  "apiMajor": 3,
                  "entities": [],
                  "operations": []
                }
                """);
    }

    @Test
    void irVersionAboveTheSupportedOneIsAnError() {
        String newer = IrJson.write(new ContractIr(ContractIr.IR_VERSION + 1, "/api", 1, List.of(), List.of()));

        assertThatThrownBy(() -> IrJson.parse(newer, ".atlas/api.ir.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining(".atlas/api.ir.json")
                .hasMessageContaining("irVersion " + (ContractIr.IR_VERSION + 1))
                .hasMessageContaining("supports irVersion " + ContractIr.IR_VERSION)
                .hasMessageContaining("written by a newer ai-atlas");
    }

    @Test
    void malformedBaselineIsAnErrorNamingTheFileAndTheFailure(@TempDir Path dir) throws IOException {
        Path notJson = Files.writeString(dir.resolve("broken.json"), "{ \"irVersion\": 1, ", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> IrJson.read(notJson))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageStartingWith("Contract baseline " + notJson + " is not valid Contract IR JSON: ");

        assertThatThrownBy(() -> IrJson.read(dir.resolve("missing.json")))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("missing.json").hasMessageContaining("cannot be read");

        assertMalformed("[]", "not a JSON object");
        assertMalformed("{\"apiBasePath\": \"/api\"}", "missing 'irVersion'");
        assertMalformed("{\"irVersion\": \"1\"}", "'irVersion' must be an integer");
        assertMalformed("{\"irVersion\": 0}", "'irVersion' must be at least 1");
        assertMalformed("{\"irVersion\": 1, \"apiBasePath\": \"/api\", \"apiMajor\": 1, "
                + "\"entities\": {}, \"operations\": []}", "'entities' must be an array");
        assertMalformed("{\"irVersion\": 1, \"apiBasePath\": \"/api\", \"apiMajor\": 1, "
                + "\"entities\": [1], \"operations\": []}", "every item of 'entities' must be an object");
        assertMalformed("{\"irVersion\": 1, \"apiBasePath\": 7, \"apiMajor\": 1, "
                + "\"entities\": [], \"operations\": []}", "'apiBasePath' must be a string");
    }

    @Test
    void everyAttributeIsValidatedWhenReadingADocument() throws IOException {
        String valid = compileLifecycle();

        assertMalformed(valid.replaceFirst("\"includeTypeInfo\": (true|false)", "\"includeTypeInfo\": 1"),
                "'includeTypeInfo' must be a boolean");
        assertMalformed(valid.replaceFirst("\"lifecycle\": \\{", "\"lifecycle\": 3, \"x\": {"),
                "'lifecycle' must be an object");
        assertMalformed(valid.replaceFirst("\"channels\": \\[", "\"channels\": [1, "),
                "every item of 'channels' must be a string");
        assertMalformed(valid.replaceFirst("\"allowedValues\": \\[\\]", "\"allowedValues\": 5"),
                "'allowedValues' must be an array");
        assertMalformed(valid.replaceFirst("\"typeHint\": null", "\"typeHint\": false"),
                "'typeHint' must be a string");
    }

    @Test
    void contentAfterTheDocumentAndDuplicateKeysAreMalformed() throws IOException {
        String valid = compileLifecycle();

        assertMalformed(valid + "{}", "Trailing token");
        assertMalformed(valid + "x", "Unrecognized token");
        assertMalformed(valid.replaceFirst("\"irVersion\": 1,", "\"irVersion\": 1, \"irVersion\": 1,"),
                "Duplicate field 'irVersion'");
        assertMalformed(valid.replaceFirst("\"sensitive\": (true|false),", "\"sensitive\": true, \"sensitive\": $1,"),
                "Duplicate field 'sensitive'");
    }

    @Test
    void typeUseAnnotationsDoNotChangeCanonicalTypes() {
        String annotated = irOf(javac().withProcessors(new AgenticProcessor()).compile(typeUseSources("@Nullable ")));
        String plain = irOf(javac().withProcessors(new AgenticProcessor()).compile(typeUseSources("")));

        assertThat(annotated).isEqualTo(plain).doesNotContain("Nullable")
                .contains("\"javaType\": \"java.util.List<java.lang.String>\"");
    }

    @Test
    void operationIsRecordedOnlyWhenItsMethodModelIsValid(@TempDir Path classOutput) throws Exception {
        JavaFileObject service = JavaFileObjects.forSourceString("test.Mixed", """
                package test;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                @AgenticExposed(description = "Mixed")
                public class Mixed {
                    public String good() { return null; }
                    @AgenticExposed(description = "Bad since", apiSince = 0)
                    public String badSince() { return null; }
                    @AgenticExposed(description = "Bad range", apiSince = 3, apiUntil = 2)
                    public String badRange() { return null; }
                }
                """);
        // compile-testing withholds the output of a failed compilation, so run javac directly
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        boolean success;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOutput));
            files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(classOutput));
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, Stream.of(
                    System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList());
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostic -> { }, null, null,
                    List.of(service));
            task.setProcessors(List.of(new AgenticProcessor()));
            success = task.call();
        }

        assertThat(success).isFalse();
        ContractIr ir = IrJson.read(classOutput.resolve(ContractIr.RESOURCE_PATH));
        assertThat(ir.operations()).extracting(Operation::id).containsExactly("test.Mixed#good()");
    }

    @Test
    void declarationsGeneratedInALaterRoundAreRecorded() throws Exception {
        JavaFileObject first = JavaFileObjects.forSourceString("shop.First", """
                package shop;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;
                @AgenticEntity
                public class First {
                    @AgenticField private Long id;
                    public Long getId() { return id; }
                }
                """);
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor(), new LaterRoundSourceProcessor())
                .compile(first);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        ContractIr ir = IrJson.parse(irOf(compilation), "api.ir.json");
        assertThat(ir.entities()).extracting(Entity::className).containsExactly("shop.First", "shop.late.Late");
        assertThat(ir.operations()).extracting(Operation::id).containsExactly("shop.late.LateService#late()");
        assertThat(compilation.generatedSourceFile("shop.late.generated.LateDto")).isPresent();
    }

    @Test
    void readingABaselineFileReturnsTheDocument(@TempDir Path dir) throws Exception {
        String json = compileLifecycle();
        Path baseline = Files.writeString(dir.resolve("api.ir.json"), json, StandardCharsets.UTF_8);

        assertThat(IrJson.read(baseline)).isEqualTo(IrJson.parse(json, "api.ir.json"));
        assertThat(IrJson.writeBytes(IrJson.read(baseline))).isEqualTo(Files.readAllBytes(baseline));
    }

    // --- helpers ---

    private static final JavaFileObject NULLABLE = JavaFileObjects.forSourceString("shop.Nullable", """
            package shop;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE_USE)
            public @interface Nullable { }
            """);

    /** An entity and a service whose field, parameter and return types carry {@code annotation}. */
    private static List<JavaFileObject> typeUseSources(String annotation) {
        return List.of(NULLABLE, JavaFileObjects.forSourceString("shop.Item", """
                        package shop;
                        import com.egoge.ai.atlas.annotations.AgenticEntity;
                        import com.egoge.ai.atlas.annotations.AgenticField;
                        import java.util.List;
                        @AgenticEntity
                        public class Item {
                            @AgenticField private %1$sString name;
                            @AgenticField private List<%1$sString> tags;
                            @AgenticField private String %1$s[] codes;
                            public String getName() { return name; }
                            public List<String> getTags() { return tags; }
                            public String[] getCodes() { return codes; }
                        }
                        """.formatted(annotation)),
                JavaFileObjects.forSourceString("shop.ItemService", """
                        package shop;
                        import com.egoge.ai.atlas.annotations.AgenticExposed;
                        @AgenticExposed(description = "Items")
                        public class ItemService {
                            @AgenticExposed(description = "Label")
                            public %1$sString label(%1$sLong id) { return null; }
                        }
                        """.formatted(annotation)));
    }


    private static void assertMalformed(String json, String detail) {
        assertThatThrownBy(() -> IrJson.parse(json, "baseline.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("baseline.json is not valid Contract IR JSON")
                .hasMessageContaining(detail);
    }

    /** Compiles the lifecycle fixture plus {@link #CLASS_LIFECYCLE_SERVICE} at apiMajor=2 and returns its IR text. */
    private static String compileLifecycle() throws IOException {
        List<JavaFileObject> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(LIFECYCLE_FIXTURE)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String relative = LIFECYCLE_FIXTURE.relativize(file).toString().replace(File.separatorChar, '.');
                sources.add(JavaFileObjects.forSourceString(relative.substring(0, relative.length() - ".java".length()),
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        sources.add(CLASS_LIFECYCLE_SERVICE);
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(MAJOR_2)
                .compile(sources);
        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        return irOf(compilation);
    }

    private static String irOf(Compilation compilation) {
        JavaFileObject file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, ContractIr.RESOURCE_PATH)
                .orElseThrow(() -> new AssertionError("no " + ContractIr.RESOURCE_PATH + " emitted"));
        try (var in = file.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Entity entity(ContractIr ir, String className) {
        return ir.entities().stream().filter(e -> e.className().equals(className)).findFirst()
                .orElseThrow(() -> new AssertionError("no entity " + className));
    }

    private static Field field(Entity entity, String name) {
        return entity.fields().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }

    private static Operation operation(ContractIr ir, String id) {
        return ir.operations().stream().filter(o -> o.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no operation " + id));
    }
}
