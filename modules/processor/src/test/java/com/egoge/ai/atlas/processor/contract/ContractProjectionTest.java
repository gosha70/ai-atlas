/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The projection of the Contract IR at a major, which every generator consumes (FR-006, FR-007). */
class ContractProjectionTest {

    private static final Path FIXTURES = Path.of("src/test/resources/golden/ir-rewire/fixtures");
    private static final String OPENAPI_PATH = "META-INF/openapi/openapi.json";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lifecycle", "shared-find", "rest-openapi-catalog", "rest-openapi-overloaded",
            "rest-openapi-taken-id"})
    void everyCanonicalTypeOfTheFixturesRoundTrips(String fixture) {
        ContractIr ir = ir(compile(fixture));

        List<String> types = typeStrings(ir);
        assertThat(types).isNotEmpty();
        assertThat(types).allSatisfy(type ->
                assertThat(ContractProjection.parseType(type).toString()).isEqualTo(type));
    }

    @Test
    void projectedModelsCarryTheTypesTheIrRecords() {
        ContractIr ir = ir(compile("lifecycle"));
        ContractProjection projection = ContractProjection.of(ir, 2);

        EntityModel product = projection.entity("shop.Product");
        assertThat(product.sourceClassName()).isEqualTo(ClassName.get("shop", "Product"));
        assertThat(product.dtoClassName()).isEqualTo(ClassName.get("shop.generated", "ProductView"));
        assertThat(field(product, "lines").typeName()).isEqualTo(
                ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get("shop", "OrderLine")));
        assertThat(field(product, "lines").elementTypeName()).isEqualTo(ClassName.get("shop", "OrderLine"));
        assertThat(field(product, "lineArray").typeName())
                .isEqualTo(ArrayTypeName.of(ClassName.get("shop", "OrderLine")));
        assertThat(field(product, "looseLines").hintTypeName()).isEqualTo(ClassName.get("shop", "OrderLine"));
        assertThat(field(product, "looseLines").elementTypeName()).isNull();
        assertThat(field(product, "stock").typeName()).isEqualTo(TypeName.INT);
        assertThat(field(product, "lines").collectionKind()).isEqualTo(FieldModel.CollectionKind.COLLECTION);

        MethodModel customers = projection.method("shop.AdminService#customers()");
        assertThat(customers.returnEntityType()).isEqualTo(ClassName.get("shop", "Customer"));
        assertThat(customers.returnDtoType()).isEqualTo(ClassName.get("shop.api", "CustomerDto"));
        assertThat(customers.returnKind()).isEqualTo(ServiceModel.ReturnKind.COLLECTION);
        assertThat(projection.method("shop.AdminService#refresh()").returnType()).isEqualTo(TypeName.VOID);
    }

    @Test
    void projectionHoldsOnlyElementsActiveAtTheMajorWithDeprecationResolved() {
        ContractIr ir = ir(compile("lifecycle"));

        ContractProjection v1 = ContractProjection.of(ir, 1);
        assertThat(fieldNames(v1.entity("shop.Product"))).contains("legacyCode").doesNotContain("newCode");
        FieldModel statusV1 = field(v1.entity("shop.Product"), "status");
        assertThat(statusV1.deprecatedSinceVersion()).isZero();
        assertThat(statusV1.deprecatedMessage()).isEmpty();
        assertThat(v1.method("shop.CatalogService#legacyLookup(java.lang.String)")).isNotNull();
        assertThat(v1.method("shop.CatalogService#lookupV3(java.lang.String)")).isNull();
        MethodModel listAllV1 = v1.method("shop.CatalogService#listAll()");
        assertThat(listAllV1.apiDeprecatedSince()).isZero();
        assertThat(listAllV1.apiReplacement()).isEmpty();

        ContractProjection v2 = ContractProjection.of(ir, 2);
        assertThat(fieldNames(v2.entity("shop.Product"))).doesNotContain("legacyCode", "newCode");
        FieldModel statusV2 = field(v2.entity("shop.Product"), "status");
        assertThat(statusV2.deprecatedSinceVersion()).isEqualTo(2);
        assertThat(statusV2.deprecatedMessage()).isEqualTo("Use statuses");
        assertThat(v2.method("shop.CatalogService#legacyLookup(java.lang.String)")).isNull();
        MethodModel listAllV2 = v2.method("shop.CatalogService#listAll()");
        assertThat(listAllV2.apiDeprecatedSince()).isEqualTo(2);
        assertThat(listAllV2.apiReplacement()).isEqualTo("findAll");

        // An entity with nothing active is still projected, with no fields
        assertThat(v2.entity("shop.Legacy").fields()).isEmpty();
        assertThat(v1.entity("shop.Legacy").fields()).isNotEmpty();

        // Services list only their active operations, in IR order
        ServiceModel catalogV3 = ContractProjection.of(ir, 3).services().stream()
                .filter(s -> s.serviceClassName().simpleName().equals("CatalogService")).findFirst().orElseThrow();
        assertThat(catalogV3.methods()).extracting(MethodModel::methodName)
                .contains("lookupV3").doesNotContain("legacyLookup");
    }

    @Test
    void operationKeyOfAProjectedOperationIsItsIrIdentity() {
        ContractIr ir = ir(compile("lifecycle"));
        ContractProjection projection = ContractProjection.of(ir, 2);

        for (Operation op : ir.operations()) {
            MethodModel method = projection.method(op.id());
            if (method != null) {
                ClassName service = (ClassName) ContractProjection.parseType(op.service());
                assertThat(ContractProjection.operationKey(service, method)).isEqualTo(op.id());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"shared-find", "rest-openapi-catalog", "rest-openapi-overloaded", "rest-openapi-taken-id"})
    void projectedOperationIdsAreThoseOfTheGeneratedOpenApiDocument(String fixture) throws IOException {
        Compilation compilation = compile(fixture);
        ContractIr ir = ir(compilation);
        JsonNode paths = new ObjectMapper().readTree(text(compilation, OPENAPI_PATH)).get("paths");

        Map<String, String> documented = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            var path = it.next();
            path.getValue().fields().forEachRemaining(op ->
                    documented.put(op.getKey() + " " + path.getKey(), op.getValue().get("operationId").asText()));
        }
        ContractProjection projection = ContractProjection.of(ir, ir.apiMajor());
        Map<String, String> projected = new TreeMap<>();
        for (Operation op : ir.operations()) {
            String operationId = projection.operationIds().get(op.id());
            if (operationId != null) {
                projected.put(op.rest().httpMethod().toLowerCase(Locale.ROOT) + " "
                        + ir.apiBasePath() + "/v" + ir.apiMajor() + op.rest().path(), operationId);
            }
        }

        assertThat(projected).isEqualTo(documented);
        if ("shared-find".equals(fixture)) {
            assertThat(projection.operationIds()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "a.InventoryService#find()", "InventoryService_find_get",
                    "b.PricingService#find()", "PricingService_find_get"));
        }
    }

    @Test
    void projectedOperationIdsAreThoseOfTheGeneratedOpenApiDocumentAcrossRounds() throws IOException {
        JavaFileObject first = JavaFileObjects.forSourceString("shop.FirstService", """
                package shop;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class FirstService {
                    @AgenticExposed(description = "First late operation", channels = { AgenticExposed.Channel.API })
                    public String late() { return null; }
                }
                """);
        Compilation compilation = javac()
                .withProcessors(new AgenticProcessor(), new LaterRoundSourceProcessor()).compile(first);

        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        ContractIr ir = ir(compilation);
        List<String> documented = new ObjectMapper().readTree(text(compilation, OPENAPI_PATH))
                .findValuesAsText("operationId");
        assertThat(documented).containsExactlyInAnyOrderElementsOf(
                ContractProjection.of(ir, ir.apiMajor()).operationIds().values());
        assertThat(documented).containsExactlyInAnyOrder("FirstService_late_get", "LateService_late_get");
    }

    @Test
    void parsesEveryCanonicalTypeForm() {
        ClassName outer = ClassName.get("a.b", "Outer");
        assertThat(ContractProjection.parseType("a.b.Outer.Inner")).isEqualTo(outer.nestedClass("Inner"));
        assertThat(((ClassName) ContractProjection.parseType("a.b.Outer.Inner")).simpleNames())
                .containsExactly("Outer", "Inner");
        assertThat(ContractProjection.parseType("T")).isEqualTo(TypeVariableName.get("T"));
        assertThat(ContractProjection.parseType("? super a.b.Outer"))
                .isEqualTo(WildcardTypeName.supertypeOf(outer));
        assertThat(ContractProjection.parseType("a.b.Outer<java.lang.String>.Inner<java.lang.Long>"))
                .isEqualTo(ParameterizedTypeName.get(outer, ClassName.get(String.class))
                        .nestedClass("Inner", List.of(ClassName.get(Long.class))));

        for (String type : List.of("boolean", "byte", "short", "int", "long", "char", "float", "double", "void",
                "int[][]", "java.lang.String[]", "java.util.List<?>", "java.util.List<? extends a.b.Outer>",
                "java.util.Map<java.lang.String, java.util.List<a.b.Outer.Inner[]>>",
                "java.util.List<java.lang.String>[]", "a.b.Outer<java.lang.String>.Inner<java.lang.Long>",
                "lower.pkg.lowercaseClass")) {
            assertThat(ContractProjection.parseType(type).toString()).as(type).isEqualTo(type);
        }
    }

    @Test
    void aStringThatIsNoCanonicalTypeIsRejected() {
        for (String type : List.of("", "java.util.List<", "java.util.Map<a.B,a.C>", "int[", "a..B", "a.B>")) {
            assertThatThrownBy(() -> ContractProjection.parseType(type)).as(type)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- helpers ---

    private static Compilation compile(String fixture) {
        Path root = FIXTURES.resolve(fixture);
        List<JavaFileObject> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString().replace(File.separatorChar, '.');
                sources.add(JavaFileObjects.forSourceString(relative.substring(0, relative.length() - ".java".length()),
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return javac().withProcessors(new AgenticProcessor()).compile(sources);
    }

    private static ContractIr ir(Compilation compilation) {
        try {
            return IrJson.parse(text(compilation, ContractIr.RESOURCE_PATH), ContractIr.RESOURCE_PATH);
        } catch (IrJson.IrReadException e) {
            throw new AssertionError(e);
        }
    }

    private static String text(Compilation compilation, String resource) {
        JavaFileObject file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, resource)
                .orElseThrow(() -> new AssertionError("no " + resource + " emitted"));
        try (var in = file.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> typeStrings(ContractIr ir) {
        List<String> types = new ArrayList<>();
        for (Entity entity : ir.entities()) {
            types.add(entity.className());
            for (Field field : entity.fields()) {
                types.add(field.javaType());
                types.add(field.elementType());
                types.add(field.typeHint());
            }
        }
        for (Operation op : ir.operations()) {
            types.add(op.service());
            op.parameters().stream().map(Parameter::javaType).forEach(types::add);
            types.add(op.returns().javaType());
            types.add(op.returns().returnType());
        }
        return types.stream().filter(Objects::nonNull).toList();
    }

    private static List<String> fieldNames(EntityModel entity) {
        return entity.fields().stream().map(FieldModel::name).toList();
    }

    private static FieldModel field(EntityModel entity, String name) {
        return entity.fields().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }
}
