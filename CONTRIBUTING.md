# Contributing to AI-ATLAS

Thank you for your interest in contributing to AI-ATLAS. This guide explains how to get involved.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/<your-username>/ai-atlas.git`
3. Create a branch: `git checkout -b feature/your-feature` or `fix/your-fix`
4. Make your changes
5. Run the build: `./gradlew build`
6. Push and open a pull request

## Prerequisites

- Java 17+ (Java 21 auto-provisioned via Gradle toolchain)
- Gradle 8.12 (wrapper included, no install needed)

## Build & Test

```bash
./gradlew build                    # build all modules + run all tests
./gradlew :modules:processor:test  # processor tests only
./gradlew :demo:compileJava       # trigger annotation processing in demo
```

All tests must pass before submitting a pull request.

## Module Architecture

AI-ATLAS is a multi-module Gradle project with strict dependency rules:

| Module | Depends on | Rule |
|--------|-----------|------|
| `annotations` | nothing | ZERO external dependencies |
| `processor` | `annotations` (compile-only) | No runtime dependencies |
| `runtime` | `annotations` (api) | Spring Boot only |
| `gradle-plugin` | Gradle API only | No framework deps |
| `demo` | all of the above | Consumer/integration test |

**These boundaries are non-negotiable.** A PR that adds Spring to `annotations` or runtime deps to `processor` will be declined.

## What to Contribute

### Good first issues

- Look for issues labeled `good first issue`
- Documentation improvements
- Additional test coverage for edge cases

### Feature contributions

- New annotation attributes
- Additional code generators (e.g., gRPC stubs)
- Runtime interceptors and security features
- Gradle plugin enhancements

### Before starting large work

Open an issue first to discuss the approach. This prevents duplicate effort and ensures alignment with project goals.

## Code Style

- Java 21 features are welcome (records, sealed classes, pattern matching)
- Use JavaPoet for all code generation — no string concatenation
- All generated files must include `@Generated("ai.atlas.processor")`
- Processor tests must use Google compile-testing (never mock `ProcessingEnvironment`)
- No wildcard imports, no commented-out code, no unused imports

## Commit Messages

- Use imperative mood: "Add support for..." not "Added support for..."
- Keep the subject line under 72 characters
- One logical change per commit

## Pull Request Process

1. Ensure `./gradlew build` passes with zero warnings
2. Add tests for new functionality
3. Update documentation if you changed public API or annotation attributes
4. Fill in the pull request template
5. A maintainer will review your PR

## Reporting Issues

- Use GitHub Issues
- Include: Java version, Gradle version, Spring Boot version
- For processor bugs: include the annotated source that triggers the issue and the generated output (or compile error)

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
