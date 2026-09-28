/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.ChannelProjectionFixtures.GeneratedClasses;
import com.egoge.ai.atlas.processor.ChannelProjectionFixtures.LaterRoundProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.AI_ONLY;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.API_ONLY;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.FLAG_ON;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.JSON;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.ORDER_SRC;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.components;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.fieldMetadataKeys;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.fieldNames;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.method;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.methodNames;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.openApi;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.shop;
import static com.egoge.ai.atlas.processor.ChannelProjectionFixtures.source;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code ai.atlas.projections=true}, each response is projected to the fields eligible for the
 * channel serving it: REST controllers and the OpenAPI document keep the API projection under each
 * entity's DTO name, and MCP tools return the AI projection, a separate {@code …AiDto} record only
 * where the projections differ, directly or through a reference.
 *
 * <p>The generated classes are loaded and called: REST responses are serialized with Jackson, as
 * Spring MVC would, and MCP results go through Spring AI's own {@link MethodToolCallbackProvider}.
 */
class ChannelProjectionGenerationTest {

    private static final String PARAMETERS = "-parameters";

    private static Compilation compileShop() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON, PARAMETERS)
                .compile(shop(true));
        assertThat(compilation).succeeded();
        return compilation;
    }

    // ------------------------------------------------------------ each channel's shape

    @Test
    void anApiOnlyFieldIsOnRestAndOpenApiOnlyAndAnAiOnlyFieldOnMcpOnly() throws Exception {
        Compilation compilation = compileShop();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(components(classes.load("shop.generated.OrderDto")))
                .containsExactly("id", "status", "marginCents", "actions", "customer");
        assertThat(components(classes.load("shop.generated.OrderAiDto")))
                .containsExactly("id", "status", "agentSummary", "actions", "customer");

        JsonNode schemas = openApi(compilation).path("components").path("schemas");
        assertThat(fieldNames(schemas.path("OrderDto").path("properties")))
                .containsExactly("id", "status", "marginCents", "actions", "customer");
        assertThat(fieldNames(schemas)).doesNotContain("OrderAiDto", "OrderActionAiDto", "ShipmentAiDto");

        JsonNode rest = JSON.valueToTree(classes.rest("find", 7L));
        assertThat(rest.path("marginCents").asInt()).isEqualTo(1234);
        assertThat(rest.has("agentSummary")).isFalse();

        JsonNode mcp = classes.mcp("find", "{\"id\": 7}");
        assertThat(mcp.has("marginCents")).isFalse();
        assertThat(mcp.path("agentSummary").asText()).isEqualTo("Shipped yesterday");
    }

    @Test
    void anEntityWhoseFieldsAreAllAiOnlyHasNoApiSchemaAndLeavesRestUnchanged() throws Exception {
        List<JavaFileObject> sources = new ArrayList<>(shop(true));
        sources.add(JavaFileObjects.forSourceString("shop.Insight", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                @AgenticEntity(description = "An insight for agents")
                public class Insight {
                    @AgenticField(description = "Text", channels = Channel.AI) private String text;
                    public String getText() { return text; }
                }
                """));
        sources.add(JavaFileObjects.forSourceString("shop.InsightService", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                public class InsightService {
                    @AgenticExposed(description = "An insight", returnType = Insight.class, channels = Channel.AI)
                    public Insight insight(Long id) { return null; }
                }
                """));
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON, PARAMETERS)
                .compile(sources);

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("shop.generated.InsightDto")).isEmpty();
        assertThat(source(compilation, "shop.generated.InsightAiDto")).contains("record InsightAiDto(");
        JsonNode openApi = openApi(compilation);
        JsonNode shopOnly = openApi(compileShop());
        assertThat(fieldNames(openApi.path("components").path("schemas")))
                .doesNotContain("InsightDto", "InsightAiDto")
                .containsExactlyElementsOf(fieldNames(shopOnly.path("components").path("schemas")));
        assertThat(openApi.path("components").path("schemas")).isEqualTo(shopOnly.path("components").path("schemas"));
        assertThat(openApi.path("paths")).isEqualTo(shopOnly.path("paths"));
        assertThat(JSON.valueToTree(new GeneratedClasses(compilation).rest("find", 7L)).path("marginCents").asInt())
                .isEqualTo(1234);
    }

    @Test
    void eachRecordsFieldMetadataHoldsOnlyItsOwnFields() throws Exception {
        GeneratedClasses classes = new GeneratedClasses(compileShop());

        assertThat(fieldMetadataKeys(classes.load("shop.generated.OrderDto")))
                .containsExactlyInAnyOrder("id", "status", "marginCents", "actions", "customer");
        assertThat(fieldMetadataKeys(classes.load("shop.generated.OrderAiDto")))
                .containsExactlyInAnyOrder("id", "status", "agentSummary", "actions", "customer");
    }

    @Test
    void anUnannotatedFieldIsOnNeitherChannel() throws Exception {
        GeneratedClasses classes = new GeneratedClasses(compileShop());

        assertThat(JSON.valueToTree(classes.rest("find", 7L)).toString()).doesNotContain("customerSsn", "123-45");
        assertThat(classes.mcp("find", "{\"id\": 7}").toString()).doesNotContain("customerSsn", "123-45");
    }

    @Test
    void theSameEntityGetsEachChannelsProjectionFromAiOnlyApiOnlyAndBothChannelMethods() throws Exception {
        Compilation compilation = compileShop();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        Class<?> tool = classes.load("shop.generated.OrderServiceMcpTool");
        Class<?> controller = classes.load("shop.generated.OrderServiceRestController");
        assertThat(methodNames(tool)).contains("forAgent", "find").doesNotContain("forApi");
        assertThat(methodNames(controller)).contains("forApi", "find").doesNotContain("forAgent");
        assertThat(method(tool, "forAgent").getReturnType().getSimpleName()).isEqualTo("OrderAiDto");
        assertThat(method(tool, "find").getReturnType().getSimpleName()).isEqualTo("OrderAiDto");
        assertThat(method(controller, "forApi").getReturnType().getSimpleName()).isEqualTo("OrderDto");
        assertThat(method(controller, "find").getReturnType().getSimpleName()).isEqualTo("OrderDto");

        JsonNode agent = classes.mcp("forAgent", "{\"id\": 7}");
        JsonNode both = classes.mcp("find", "{\"id\": 7}");
        assertThat(fieldNames(agent)).containsExactly("id", "status", "agentSummary", "actions", "customer");
        assertThat(both).isEqualTo(agent);
        JsonNode api = JSON.valueToTree(classes.rest("forApi", 7L));
        JsonNode apiBoth = JSON.valueToTree(classes.rest("find", 7L));
        assertThat(fieldNames(api)).containsExactly("id", "status", "marginCents", "actions", "customer");
        assertThat(apiBoth).isEqualTo(api);
    }

    // ------------------------------------------------------------ recursion and splitting

    @Test
    void nestedEntitiesCollectionsIterablesAndArraysAreProjectedRecursively() throws Exception {
        Compilation compilation = compileShop();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        // OrderAction.performedBy is API-only, so OrderAction splits; Shipment splits only through it
        assertThat(components(classes.load("shop.generated.OrderActionAiDto"))).containsExactly("id", "type", "order");
        assertThat(source(compilation, "shop.generated.OrderActionAiDto")).contains("OrderAiDto order");
        assertThat(source(compilation, "shop.generated.ShipmentAiDto"))
                .contains("List<OrderActionAiDto> actions", "List<OrderActionAiDto> history");
        assertThat(source(compilation, "shop.generated.ShipmentDto"))
                .contains("List<OrderActionDto> actions", "List<OrderActionDto> history");
        assertThat(source(compilation, "shop.generated.OrderAiDto"))
                .contains("List<OrderActionAiDto> actions", "CustomerDto customer");

        JsonNode mcpShipment = classes.mcp("shipment", "{\"id\": 5}");
        assertThat(mcpShipment.path("actions").get(0).has("performedBy")).isFalse();
        assertThat(mcpShipment.path("history").get(0).has("performedBy")).isFalse();
        assertThat(mcpShipment.path("history").get(0).path("order").has("agentSummary")).isTrue();
        JsonNode restShipment = JSON.valueToTree(classes.rest("shipment", 5L));
        assertThat(restShipment.path("actions").get(0).path("performedBy").asText()).isEqualTo("clerk-17");
        assertThat(restShipment.path("history").get(0).path("order").has("marginCents")).isTrue();

        // Collection, iterable and array returns are projected element by element
        for (String operation : List.of("list", "iterate", "array")) {
            JsonNode mcp = classes.mcp(operation, "{}");
            assertThat(mcp.isArray()).as(operation).isTrue();
            assertThat(mcp.get(0).has("agentSummary")).as(operation).isTrue();
            assertThat(mcp.get(0).path("actions").get(0).has("performedBy")).as(operation).isFalse();
            JsonNode rest = JSON.valueToTree(classes.rest(operation));
            assertThat(rest.get(0).has("marginCents")).as(operation).isTrue();
            assertThat(rest.get(0).path("actions").get(0).has("performedBy")).as(operation).isTrue();
        }
    }

    @Test
    void aReferenceCycleTerminatesOnBothChannels() throws Exception {
        GeneratedClasses classes = new GeneratedClasses(compileShop());

        // Order -> actions -> OrderAction -> order is the same Order: the revisit maps to null
        JsonNode mcp = classes.mcp("find", "{\"id\": 7}");
        assertThat(mcp.path("actions").get(0).path("order").isNull()).isTrue();
        JsonNode rest = JSON.valueToTree(classes.rest("find", 7L));
        assertThat(rest.path("actions").get(0).path("order").isNull()).isTrue();
    }

    @Test
    void anEntityThatDoesNotDifferGeneratesOneRecordSharedByBothChannels() throws Exception {
        Compilation compilation = compileShop();
        GeneratedClasses classes = new GeneratedClasses(compilation);

        assertThat(compilation.generatedSourceFile("shop.generated.CustomerAiDto")).isEmpty();
        assertThat(method(classes.load("shop.generated.OrderServiceMcpTool"), "customer").getReturnType()
                .getSimpleName()).isEqualTo("CustomerDto");
        assertThat(classes.mcp("customer", "{\"id\": 3}").path("name").asText()).isEqualTo("Alice");
        assertThat(JSON.valueToTree(classes.rest("customer", 3L)).path("name").asText()).isEqualTo("Alice");
    }

    @Test
    void anEntityWithNoDeclarationInItsReferenceGraphGeneratesNoAiRecord() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .compile(shop(false));

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedFiles()).extracting(JavaFileObject::getName)
                .noneMatch(name -> name.contains("AiDto"));
    }

    @Test
    void theVersionProjectionAppliesBeforeTheChannelProjection() throws Exception {
        JavaFileObject note = JavaFileObjects.forSourceString("shop.Note", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                @AgenticEntity(description = "A note")
                public class Note {
                    @AgenticField(description = "Text") private String text;
                    @AgenticField(description = "Hint for agents", sinceVersion = 2, channels = Channel.AI)
                    private String hint;
                    public String getText() { return text; }
                    public String getHint() { return hint; }
                }
                """);
        JavaFileObject notes = JavaFileObjects.forSourceString("shop.NoteService", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                public class NoteService {
                    @AgenticExposed(description = "A note", returnType = Note.class)
                    public Note note(Long id) { return new Note(); }
                }
                """);

        Compilation v1 = javac().withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, "-Aai.atlas.api.major=1").compile(note, notes);
        Compilation v2 = javac().withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, "-Aai.atlas.api.major=2").compile(note, notes);

        // At v1 the AI-only field is not active, so the projections agree and there is one record
        assertThat(v1).succeeded();
        assertThat(v1.generatedSourceFile("shop.generated.NoteAiDto")).isEmpty();
        assertThat(source(v1, "shop.generated.NoteDto")).doesNotContain("hint");
        assertThat(v2).succeeded();
        assertThat(source(v2, "shop.generated.NoteAiDto")).contains("String hint");
        assertThat(source(v2, "shop.generated.NoteDto")).doesNotContain("hint");
    }

    @Test
    void anEntityFromALaterRoundThatRefersToASplitEntitySplitsToo() {
        LaterRoundProcessor receipts = new LaterRoundProcessor(Map.of("shop.late.Receipt", """
                package shop.late;
                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;
                @AgenticEntity(description = "A receipt")
                public class Receipt {
                    @AgenticField(description = "Number") private String number;
                    @AgenticField(description = "The order") private shop.Order order;
                    public String getNumber() { return number; }
                    public shop.Order getOrder() { return order; }
                }
                """, "shop.late.ReceiptService", """
                package shop.late;
                import com.egoge.ai.atlas.annotations.AgenticExposed;
                public class ReceiptService {
                    @AgenticExposed(description = "A receipt", returnType = Receipt.class)
                    public Receipt receipt(Long id) { return null; }
                }
                """));
        Compilation compilation = javac().withProcessors(new AgenticProcessor(), receipts)
                .withOptions(FLAG_ON).compile(shop(true));

        assertThat(compilation).succeeded();
        assertThat(source(compilation, "shop.late.generated.ReceiptAiDto")).contains("OrderAiDto order");
        assertThat(source(compilation, "shop.late.generated.ReceiptDto")).contains("OrderDto order");
        assertThat(source(compilation, "shop.late.generated.ReceiptServiceMcpTool"))
                .contains("public ReceiptAiDto receipt(");
    }

    // ------------------------------------------------------------ naming

    @Test
    void aiDtoNameOverridesTheDerivedName() throws Exception {
        List<JavaFileObject> sources = new ArrayList<>(shop(true));
        sources.set(0, JavaFileObjects.forSourceString("shop.Order", ORDER_SRC.formatted(API_ONLY, AI_ONLY)
                .replace("@AgenticEntity(description = \"A customer order\")",
                        "@AgenticEntity(description = \"A customer order\", dtoName = \"OrderView\","
                                + " aiDtoName = \"OrderForAgents\")")));
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON, PARAMETERS)
                .compile(sources);

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("shop.generated.OrderViewAiDto")).isEmpty();
        assertThat(source(compilation, "shop.generated.OrderForAgents")).contains("public record OrderForAgents(");
        assertThat(source(compilation, "shop.generated.OrderActionAiDto")).contains("OrderForAgents order");
        GeneratedClasses classes = new GeneratedClasses(compilation);
        assertThat(method(classes.load("shop.generated.OrderServiceMcpTool"), "find").getReturnType()
                .getSimpleName()).isEqualTo("OrderForAgents");
        assertThat(classes.mcp("find", "{\"id\": 7}").has("agentSummary")).isTrue();
    }

    @Test
    void theDerivedNameOfACustomDtoNameAppendsAiDto() {
        List<JavaFileObject> sources = new ArrayList<>(shop(true));
        sources.set(0, JavaFileObjects.forSourceString("shop.Order", ORDER_SRC.formatted(API_ONLY, AI_ONLY)
                .replace("@AgenticEntity(description = \"A customer order\")",
                        "@AgenticEntity(description = \"A customer order\", dtoName = \"OrderSummary\")")));
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .compile(sources);

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedSourceFile("shop.generated.OrderSummaryAiDto")).isPresent();
    }
}
