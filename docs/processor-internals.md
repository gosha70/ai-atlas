# Processor Internals

How the AI-ATLAS annotation processor works under the hood. This document is for contributors and advanced users who need to understand or extend the code generation pipeline.

## Processing Phases

The `AgenticProcessor` (JSR 269 `AbstractProcessor`) runs in three sequential phases during `compileJava`:

```
Phase 1: Entity Processing          Phase 2: Service Processing        Phase 3: OpenAPI Generation
┌──────────────────────┐            ┌──────────────────────┐           ┌──────────────────────┐
│ @AgentVisibleClass   │            │ @AgenticExposed      │           │ Aggregate all models │
│ ├─ Validate type     │            │ ├─ Collect methods   │           │ ├─ Build schemas     │
│ ├─ FieldScanner.scan │            │ ├─ Resolve returns   │           │ ├─ Build paths       │
│ ├─ PII warnings      │            │ ├─ Build ServiceModel│           │ └─ Write openapi.json│
│ ├─ Build EntityModel │            │ ├─ McpToolGenerator  │           └──────────────────────┘
│ └─ DtoGenerator      │            │ └─ RestController    │
└──────────────────────┘            │    Generator         │
                                    └──────────────────────┘
```

### Phase 1: Entity Processing

For each class annotated with `@AgentVisibleClass`:

1. **Validate** — reject interfaces, enums, non-class elements (emit warnings/errors)
2. **Scan fields** — `FieldScanner.scan()` walks the superclass chain top-down, collecting `@AgentVisible` fields. Subclass fields override same-named superclass fields.
3. **PII warnings** — `PiiDetector.check()` runs on all fields **not** annotated with `@AgentVisible`, emitting NOTE diagnostics for suspicious names
4. **Build model** — construct an `EntityModel` record with DTO name, package, display name, description, and the ordered list of `FieldModel` records
5. **Generate DTO** — `DtoGenerator.generate()` produces a Java record via JavaPoet
6. **Register** — store the `EntityModel` in `entityRegistry` (keyed by qualified class name) for Phase 2 lookups

### Phase 2: Service Processing

For each class or method annotated with `@AgenticExposed`:

1. **Collect methods** — type-level annotation exposes all public methods; method-level exposes only that method
2. **Resolve return types** — read `returnType` attribute via `MirroredTypeException` handling (required by JSR 269 for `Class<?>` attributes)
3. **Detect collections** — check if the method return type extends `List`, `Collection`, `Set`, or `Iterable`
4. **Map to DTOs** — look up the return entity's `EntityModel` in the registry to find the DTO class name
5. **Build model** — construct a `ServiceModel` with `MethodModel` entries for each exposed method
6. **Generate** — invoke `McpToolGenerator.generate()` and `RestControllerGenerator.generate()`

### Phase 3: OpenAPI Generation

After all entities and services are processed:

1. Collect all `EntityModel` and `ServiceModel` instances from registries
2. Build OpenAPI schema definitions from entity DTOs (including enum constraints)
3. Build path definitions from service methods
4. Serialize to JSON via Jackson and write to `META-INF/openapi/openapi.json` using the Filer API

## Internal Models

### EntityModel

```java
record EntityModel(
    ClassName sourceClassName,     // Original entity (e.g., com.example.Order)
    String dtoName,                // Generated DTO name (e.g., "OrderDto")
    String dtoPackageName,         // Generated DTO package
    String displayName,            // From @AgentVisibleClass.name()
    String classDescription,       // From @AgentVisibleClass.description()
    boolean includeTypeInfo,       // From @AgentVisibleClass.includeTypeInfo()
    List<FieldModel> fields        // Ordered @AgentVisible fields
)
```

### FieldModel

```java
record FieldModel(
    String name,                   // Java field name
    String displayName,            // From @AgentVisible.name() or field name
    TypeName typeName,             // JavaPoet type
    String description,            // From @AgentVisible.description()
    boolean sensitive,             // From @AgentVisible.sensitive()
    boolean checkCircularReference,// From @AgentVisible.checkCircularReference()
    boolean enumType,              // Auto-detected if field type is enum
    List<String> enumValues        // Enum constants or @AgentVisible.allowedValues()
)
```

### ServiceModel

```java
record ServiceModel(
    ClassName serviceClassName,
    List<MethodModel> methods
)

record MethodModel(
    String methodName,
    String toolName,               // From @AgenticExposed.toolName()
    String description,            // From @AgenticExposed.description()
    TypeName returnType,           // Actual return type (e.g., List<Order>)
    ClassName returnEntityType,    // Resolved entity class
    ClassName returnDtoType,       // Resolved DTO class
    boolean collectionReturn,      // List/Collection/Set/Iterable
    List<ParameterModel> parameters
)

record ParameterModel(String name, TypeName typeName, String description)
```

## Code Generators

All generators use [JavaPoet](https://github.com/palantir/javapoet) (Palantir fork) for type-safe Java source generation. No string concatenation or template engines.

### DtoGenerator

Produces a Java record with:
- Record components for each `@AgentVisible` field
- `@Generated("ai.atlas.processor")` annotation
- `CLASS_NAME`, `CLASS_DESCRIPTION`, `INCLUDE_TYPE_INFO` static constants
- `FIELD_METADATA` map (`Map<String, FieldMeta>`) with per-field metadata
- Nested `FieldMeta` record
- `fromEntity(Entity)` null-safe factory method using getter conventions (`isX()` for boolean, `getX()` for others)

### McpToolGenerator

Produces a Spring `@Service` class with:
- Constructor injection of the original service
- `@Tool`-annotated wrapper methods with `@ToolParam` parameters
- DTO mapping: single results via `Dto.fromEntity()`, collections via `.stream().map(Dto::fromEntity).toList()`

### RestControllerGenerator

Produces a Spring `@RestController` with:
- `@RequestMapping("/api/v1/{service-kebab-case}")`
- Methods with no parameters use `@GetMapping`, with parameters use `@PostMapping`
- Parameters annotated with `@RequestParam`
- Same DTO mapping logic as MCP tools

### OpenApiGenerator

Produces `META-INF/openapi/openapi.json` (OpenAPI 3.0.3) using swagger-models:
- Schema definitions from entity DTOs with property types and enum constraints
- Path definitions from service methods with request/response bodies
- Java-to-OpenAPI type mapping (Long→int64, Integer→int32, etc.)

## Key Implementation Details

### MirroredTypeException Handling

JSR 269 requires special handling when reading `Class<?>` annotation attributes at compile time:

```java
private ClassName resolveReturnEntityType(AgenticExposed annotation) {
    try {
        annotation.returnType(); // always throws
        return null;
    } catch (MirroredTypeException e) {
        TypeMirror mirror = e.getTypeMirror();
        TypeElement element = (TypeElement) processingEnv.getTypeUtils().asElement(mirror);
        return element != null ? ClassName.get(element) : null;
    }
}
```

This is not a workaround — it is the standard JSR 269 pattern for compile-time type resolution.

### Superclass Chain Walking

`FieldScanner.scan()` processes the inheritance hierarchy top-down:

1. Collect all TypeElements from `Object` down to the annotated class
2. Process superclasses first — their fields appear earlier in the DTO
3. Track field names in a `LinkedHashSet` to detect duplicates
4. Subclass fields override same-named superclass fields

### Incremental Processing

The processor declares `AGGREGATING` incremental type (in `META-INF/gradle/incremental.annotation.processors`). This means Gradle reprocesses all annotated types when any annotated type changes — necessary because OpenAPI generation aggregates across all entities and services.

### PII Pattern Loading

`PiiDetector` loads patterns from:
1. A custom file if `-Aai.atlas.pii.patterns.file` is set
2. Otherwise, the classpath resource `META-INF/ai-atlas/pii-patterns.conf`

Compiled patterns are cached per `(filePath, customPatterns)` key to avoid repeated I/O.

## Testing

All processor tests use Google [compile-testing](https://github.com/google/compile-testing):

```java
Compilation compilation = javac()
    .withProcessors(new AgenticProcessor())
    .withOptions("-Aai.atlas.pii.patterns=salary")
    .compile(sourceFile);

assertThat(compilation).succeeded();
assertThat(compilation).hadNoteContaining("Generated DTO");
```

The `ProcessingEnvironment` is never mocked — tests run actual `javac` compilation against in-memory source files.

### Test Categories

| Category | What it tests |
|----------|--------------|
| DTO generation | Record structure, field inclusion/exclusion, `fromEntity()` mapping |
| MCP tools | `@Tool`/`@ToolParam` annotations, service delegation |
| REST controllers | Endpoint paths, HTTP methods, parameter handling |
| OpenAPI | Schema properties, enum constraints, path definitions |
| PII warnings | Default patterns, custom patterns, file-based patterns |
| Inheritance | Superclass field walking, override behavior |
| Edge cases | Interfaces, enums, abstract classes, inner classes |
