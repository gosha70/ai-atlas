# AI-ATLAS Demo Video Script

**Target length**: 2 minutes
**Format**: Screen recording with voiceover (QuickTime / OBS / Loom)
**Resolution**: 1920x1080, IDE in presentation mode (larger font)

---

## Scene 1: The Problem (15s)

**Show**: `demo/src/main/java/ai/atlas/demo/entity/Order.java` in IDE

**Say**: "This is a typical JPA entity — an Order with 9 fields. Five of them are PII: customer name, email, SSN, credit card number, and shipping address. If we expose this to an AI agent, all that sensitive data leaks. AI-ATLAS fixes this at compile time."

**Highlight**: The 5 unannotated PII fields vs the 4 `@AgentVisible` fields.

---

## Scene 2: The Annotations (15s)

**Show**: Scroll to show `@AgentVisibleClass` on the class, `@AgentVisible` on `id`, `status`, `totalAmountCents`, `itemCount`

**Say**: "Two annotations are all you need. `@AgentVisibleClass` on the entity, `@AgentVisible` on each field you want to expose. Everything else is structurally excluded — it can't appear in the generated code."

**Show**: `demo/src/main/java/ai/atlas/demo/service/OrderService.java` — `@AgenticExposed` annotation

**Say**: "Mark your service with `@AgenticExposed`, and the processor generates everything."

---

## Scene 3: Build and Generated Code (30s)

**Run in terminal**:
```bash
./gradlew :demo:compileJava
```

**Show in IDE** (expand `demo/build/generated/sources/annotationProcessor/java/main/`):

1. **OrderDto.java** — "A Java record with only the 4 safe fields. No customer name, no SSN, no credit card. The `fromEntity()` factory maps from the full entity."

2. **OrderServiceMcpTool.java** — "An MCP tool class with `@Tool` annotations, ready for AI agent consumption."

3. **OrderServiceRestController.java** — "A Spring REST controller with POST endpoints, returning DTOs."

4. **openapi.json** — "An OpenAPI 3.0 spec describing the generated endpoints and schemas — also PII-free."

---

## Scene 4: Live Demo — REST API (20s)

**Run in terminal**:
```bash
./gradlew :demo:bootRun
```

Wait for startup, then:

```bash
curl -s -X POST "http://localhost:8080/api/v1/order-service/find-by-id?id=1" | python3 -m json.tool
```

**Show output**: `{"id": 1, "status": "PENDING", "totalAmountCents": 9999, "itemCount": 3}`

**Say**: "Four fields. No PII. The credit card number, SSN, and customer details simply don't exist in the response — they were never in the DTO to begin with."

```bash
curl -s -X POST "http://localhost:8080/api/v1/order-service/find-by-status?status=PENDING" | python3 -m json.tool
```

**Say**: "Same safety guarantee for list endpoints."

---

## Scene 5: MCP Tool (15s)

**Show**: Browser with MCP Inspector at `http://localhost:8080/sse`

**Say**: "AI agents connect via MCP over SSE. The same two tools — findById and findByStatus — are auto-registered. The agent gets the same PII-safe DTOs."

**Demo**: Click `findById`, enter `id: 1`, show the response.

---

## Scene 6: PII Detection (10s)

**Show**: Terminal build output with the PII warnings:
```
Note: [ai-atlas] Field 'creditCardNumber' matches PII pattern. It is excluded...
Note: [ai-atlas] Field 'customerSsn' matches PII pattern. It is excluded...
```

**Say**: "The processor also warns you about fields matching PII patterns — SSN, credit card, password — confirming they're excluded. You can customize these patterns with your own config file."

---

## Scene 7: Wrap-Up (15s)

**Show**: README on GitHub

**Say**: "AI-ATLAS. Annotate your entities, build, and get PII-safe MCP tools, REST endpoints, and OpenAPI specs — all generated at compile time. No runtime filtering, no blacklists, no manual DTO wiring. Check it out on GitHub."

**Show**: GitHub URL and Maven coordinates:
```
ai.atlas:annotations:1.1.0
```

---

## Recording Tips

- Use IDE presentation mode (IntelliJ: View > Appearance > Presentation Mode)
- Terminal font size: 16pt+
- Record audio separately if possible (cleaner edit)
- Add soft background music (royalty-free, low volume)
- Add a 2-second intro with the AI-ATLAS logo (`docs/images/AI-ATLAS_Logo.png`)
- Export at 1080p, upload to YouTube as unlisted first for review
