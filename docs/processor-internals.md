# Processor Internals

How the AI-ATLAS annotation processor works under the hood. This document is for contributors and advanced users who need to understand or extend the code generation pipeline.

## Processing Phases

The `AgenticProcessor` (JSR 269 `AbstractProcessor`) runs in three sequential phases during `compileJava`:

```
Phase 1: Entity Processing          Phase 2: Service Processing        Phase 3: OpenAPI Generation
┌──────────────────────┐            ┌──────────────────────┐           ┌──────────────────────┐
│ @AgenticEntity   │            │ @AgenticExposed      │           │ Aggregate all models │
│ ├─ Validate type     │            │ ├─ Collect methods   │           │ ├─ Build schemas     │
│ ├─ FieldScanner.scan │            │ ├─ Resolve returns   │           │ ├─ Build paths       │
│ ├─ PII warnings      │            │ ├─ Build ServiceModel│           │ └─ Write openapi.json│
│ ├─ Build EntityModel │            │ ├─ McpToolGenerator  │           └──────────────────────┘
│ └─ DtoGenerator      │            │ └─ RestController    │
└──────────────────────┘            │    Generator         │
                                    └──────────────────────┘
```

### Phase 1: Entity Processing

For each class annotated with `@AgenticEntity`:

1. **Validate** — reject interfaces, enums, non-class elements (emit warnings/errors)
2. **Scan fields** — `FieldScanner.scanAll()` walks the superclass chain top-down, collecting every valid `@AgenticField` field whatever its lifecycle. Subclass fields override same-named superclass fields. For fields whose type is another `@AgenticEntity` entity, the scanner records the cross-reference (including collection element types) for DTO-to-DTO mapping. Raw/wildcard collection fields fall back to `@AgenticField(type = ...)` hints. The scanner does not filter by `apiMajor`.
3. **Record in the Contract IR** — `IrBuilder` records the entity with all its fields; the IR is then projected at `apiMajor` (`ContractProjection`), which keeps only the active fields and resolves deprecation. A NOTE names each field excluded as not active for `apiMajor`.
4. **PII warnings** — `PiiDetector.check()` runs on all fields **not** annotated with `@AgenticField`, emitting NOTE diagnostics for suspicious names
5. **Build model** — the projection supplies the `EntityModel` record with DTO name, package, display name, description, and the ordered list of active `FieldModel` records
6. **Generate DTO** — `DtoGenerator.generate()` produces a Java record via JavaPoet
7. **Register** — store the `EntityModel` in `entityRegistry` (keyed by qualified class name) for Phase 2 lookups

### Phase 2: Service Processing

For each class or method annotated with `@AgenticExposed`:

1. **Collect methods** — type-level annotation exposes all public methods; method-level exposes only that method
2. **Resolve return types** — read `returnType` attribute via `MirroredTypeException` handling (required by JSR 269 for `Class<?>` attributes)
3. **Detect return kind** — classify the return type as `NONE`, `COLLECTION`, `ITERABLE`, or `ARRAY`. Wildcard/raw return types are resolved via the `returnType` attribute.
4. **Validate return type** — `ReturnTypeValidator` checks that the method's return type is assignable to the declared `returnType` entity, emitting a compile warning on mismatches
5. **Read channels** — extract the `channels` attribute (`AI`, `API`, or both) to determine which generators to invoke
6. **Map to DTOs** — look up the return entity's `EntityModel` in the registry to find the DTO class name
7. **Record in the Contract IR** — each method whose model is valid is recorded by `IrBuilder`; invalid methods are left out, as invalid fields are
8. **Build model** — the projection of the IR at `apiMajor` supplies a `ServiceModel` with a `MethodModel` for each active exposed method, in declaration order
9. **Generate** — invoke `McpToolGenerator` (if channels include `AI`) and `RestControllerGenerator` (if channels include `API`)

### Phase 3: OpenAPI Generation

After all entities and services are processed:

1. Collect all `EntityModel` and `ServiceModel` instances from registries
2. Build OpenAPI schema definitions from entity DTOs (including enum constraints)
3. Build path definitions from service methods
4. Take each operation's `operationId` from the projection, which assigns them over all its active API operations
5. Serialize to JSON via Jackson and write to `META-INF/openapi/openapi.json` using the Filer API

The Contract IR, `META-INF/ai-atlas/api.ir.json`, is written in the final round, so it includes entities and services that another processor generates in a later round.

## Internal Models

### EntityModel

```java
record EntityModel(
    ClassName sourceClassName,     // Original entity (e.g., com.example.Order)
    String dtoName,                // Generated DTO name (e.g., "OrderDto")
    String dtoPackageName,         // Generated DTO package
    String displayName,            // From @AgenticEntity.name()
    String classDescription,       // From @AgenticEntity.description()
    boolean includeTypeInfo,       // From @AgenticEntity.includeTypeInfo()
    List<FieldModel> fields        // Ordered @AgenticField fields
)
```

### FieldModel

```java
record FieldModel(
    String name,                   // Java field name
    String displayName,            // From @AgenticField.name() or field name
    TypeName typeName,             // JavaPoet type
    String description,            // From @AgenticField.description()
    boolean sensitive,             // From @AgenticField.sensitive()
    boolean checkCircularReference,// From @AgenticField.checkCircularReference()
    boolean enumType,              // Auto-detected if field type is enum
    List<String> enumValues,       // Enum constants or @AgenticField.allowedValues()
    CollectionKind collectionKind, // NONE, COLLECTION, ITERABLE, or ARRAY
    TypeName elementTypeName,      // Element type for collections/arrays (null if NONE)
    TypeName hintTypeName          // From @AgenticField(type = ...), null if void.class
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
    ReturnKind returnKind,         // NONE, COLLECTION, ITERABLE, or ARRAY
    List<ParameterModel> parameters,
    Set<String> channels           // From @AgenticExposed.channels() — "AI", "API", or both
)

record ParameterModel(String name, TypeName typeName, String description)
```

## Code Generators

All generators use [JavaPoet](https://github.com/palantir/javapoet) (Palantir fork) for type-safe Java source generation. No string concatenation or template engines.

### DtoGenerator

Produces a Java record with:
- Record components for each `@AgenticField` field
- `@Generated("com.egoge.ai.atlas.processor")` annotation
- `CLASS_NAME`, `CLASS_DESCRIPTION`, `INCLUDE_TYPE_INFO` static constants
- `FIELD_METADATA` map (`Map<String, FieldMeta>`) with per-field metadata
- Nested `FieldMeta` record
- `fromEntity(Entity)` null-safe factory method using getter conventions (`isX()` for boolean, `getX()` for others)
- Entity cross-reference fields map to referenced DTOs (e.g., `List<Address>` → `List<AddressDto>`) with `fromEntity()` stream mapping
- Cycle detection via visited-set tracking for bidirectional JPA relationships

### McpToolGenerator

Produces a Spring `@Service` class with:
- Constructor injection of the original service
- `@Tool`-annotated wrapper methods with `@ToolParam` parameters
- DTO mapping: single results via `Dto.fromEntity()`, collections via `.stream().map(Dto::fromEntity).toList()`, iterables via `StreamSupport`, arrays via `Arrays.stream()`
- Only methods with `channels` containing `AI` are included; API-only methods are skipped

### RestControllerGenerator

Produces a Spring `@RestController` with:
- `@RequestMapping("/api/v1/{service-kebab-case}")`
- Methods with no parameters use `@GetMapping`, with parameters use `@PostMapping`
- Parameters annotated with `@RequestParam`: method arguments are query parameters, in the controller and in the OpenAPI document alike
- Same DTO mapping logic as MCP tools
- `void` methods generate `void` endpoints that call the service as a statement
- Only methods with `channels` containing `API` are included; AI-only methods are skipped
- `AgenticProcessor` reports a compile error on every method whose (HTTP method, path) another method in the compilation also maps to

### OpenApiGenerator

Produces `META-INF/openapi/openapi.json` (OpenAPI 3.0.3) using swagger-models:
- Schema definitions from entity DTOs with property types and enum constraints
- One operation per controller mapping (only methods with `API` channel); operations sharing a path are merged into one path item
- Method arguments as query parameters (`in: query`, `required: true`), matching the controller's `@RequestParam`; no `requestBody`
- `operationId`, assigned by `ContractProjection` so the generator and the contract gate share one derivation, is the method name when unique in the document; otherwise `{Service}_{method}_{httpMethod}`, plus the smallest free `_2`, `_3`, … if taken
- Response `200` content per return type: DTO (or array of DTO) as `application/json`, `String` as `text/plain`, numbers/booleans (or arrays of them) as `application/json`, `void` with no content, anything else as a JSON object
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

`FieldScanner.scanAll()` processes the inheritance hierarchy top-down:

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
| Entity cross-references | DTO-to-DTO mapping, cycle detection, collection variants |
| Channel filtering | AI-only/API-only method exclusion from generators |
| Return type validation | Wildcard/raw returns, incompatible return type warnings |
| Edge cases | Interfaces, enums, abstract classes, inner classes |
