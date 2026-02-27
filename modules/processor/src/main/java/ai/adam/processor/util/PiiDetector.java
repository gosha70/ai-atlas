/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.util;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic detector for fields that may contain PII.
 * Emits compiler NOTE diagnostics for suspicious field names
 * that are NOT annotated with {@code @AgentVisible} (which would be
 * an intentional inclusion), helping developers catch accidental omissions
 * or confirm intentional exclusions.
 */
public final class PiiDetector {

    private static final Set<String> PII_PATTERNS = Set.of(
            "ssn", "socialSecurity", "social_security",
            "password", "passwd", "secret",
            "creditCard", "credit_card", "cardNumber", "card_number",
            "cvv", "cvc",
            "taxId", "tax_id", "taxIdentifier",
            "driverLicense", "driver_license",
            "passport", "passportNumber"
    );

    private static final Pattern PII_REGEX = Pattern.compile(
            "(?i)(ssn|password|passwd|credit.?card|card.?number|cvv|cvc|tax.?id|driver.?license|passport)",
            Pattern.CASE_INSENSITIVE
    );

    private PiiDetector() {
    }

    /**
     * Checks if a field name matches known PII patterns and emits a
     * NOTE-level diagnostic if it does.
     *
     * @param fieldName the field name to check
     * @param element   the element for diagnostic positioning
     * @param messager  the compiler messager for emitting diagnostics
     */
    public static void check(String fieldName, Element element, Messager messager) {
        if (PII_REGEX.matcher(fieldName).find()) {
            messager.printMessage(
                    Diagnostic.Kind.NOTE,
                    String.format(
                            "[ai-adam] Field '%s' matches PII pattern. "
                                    + "It is excluded from the generated DTO (not annotated with @AgentVisible). "
                                    + "If this is intentional, no action needed.",
                            fieldName
                    ),
                    element
            );
        }
    }
}
