/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static com.google.testing.compile.Compiler.javac;

/** Sources and compilation helpers for {@link ConstraintModelTest}. */
final class ConstraintFixtures {

    static final String SERVICE = "t.Svc";
    static final String ENTITY = "t.Item";
    static final String HEADER = """
            package t;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.*;
            import jakarta.validation.groups.Default;
            import java.math.BigDecimal;
            import java.util.List;
            """;

    static String message(Diagnostic<? extends JavaFileObject> diagnostic) {
        return diagnostic.getMessage(Locale.ROOT);
    }

    static JavaFileObject service(String parameter) {
        return JavaFileObjects.forSourceString(SERVICE, HEADER + """
                @AgenticExposed(description = "Svc")
                public class Svc {
                    public interface Custom { }
                    public String op(%s) { return ""; }
                }
                """.formatted(parameter));
    }

    /** An entity with one field, declared as {@code <annotations> <Type> <name>}, and its getter. */
    static JavaFileObject entity(String field) {
        String[] tokens = field.split(" ");
        String type = tokens[tokens.length - 2];
        String name = tokens[tokens.length - 1];
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return JavaFileObjects.forSourceString(ENTITY, HEADER + """
                @AgenticEntity(description = "Item")
                public class Item {
                    @AgenticField(description = "The value")
                    private %s;
                    public %s %s() { return %s; }
                }
                """.formatted(field, type, getter, name));
    }

    static Compilation compile(JavaFileObject source) {
        return javac().withProcessors(new AgenticProcessor()).compile(source);
    }

    static ContractIr ir(Compilation compilation) {
        JavaFileObject file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, ContractIr.RESOURCE_PATH)
                .orElseThrow(() -> new AssertionError("no " + ContractIr.RESOURCE_PATH + " emitted"));
        try (var in = file.openInputStream()) {
            return IrJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), ContractIr.RESOURCE_PATH);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IrJson.IrReadException e) {
            throw new AssertionError(e);
        }
    }

    /** {@code value} as the body of a Java string literal; non-ASCII as Unicode escapes. */
    static String javaLiteral(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private ConstraintFixtures() {
    }
}
