/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.processor.util;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Heuristic detector for fields that may contain PII.
 * Emits compiler NOTE diagnostics for suspicious field names
 * that are NOT annotated with {@code @AgentVisible} (which would be
 * an intentional inclusion), helping developers catch accidental omissions
 * or confirm intentional exclusions.
 *
 * <p>Default patterns can be extended via the processor option
 * {@code -Aai.atlas.pii.patterns=keyword1,keyword2,...}. Custom keywords
 * are matched case-insensitively as substrings of the field name.
 */
public final class PiiDetector {

  private static final String DEFAULT_REGEX =
      "(ssn|password|passwd|credit.?card|card.?number|cvv|cvc|tax.?id|driver.?license|passport|secret)";

  private static final Pattern DEFAULT_PII_PATTERN = Pattern.compile(
      "(?i)" + DEFAULT_REGEX
  );

  private PiiDetector() {
  }

  /**
   * Checks if a field name matches known PII patterns and emits a
   * NOTE-level diagnostic if it does.
   *
   * @param fieldName      the field name to check
   * @param element        the element for diagnostic positioning
   * @param messager       the compiler <tt>messager</tt> for emitting diagnostics
   * @param customPatterns comma-separated additional keywords (maybe null)
   */
  public static void check(
      String fieldName,
      Element element,
      Messager messager,
      String customPatterns) {
    Pattern pattern = buildPattern(customPatterns);
    if (pattern.matcher(fieldName).find()) {
      messager.printMessage(
          Diagnostic.Kind.NOTE,
          String.format(
              "[ai-atlas] Field '%s' matches PII pattern. "
                  + "It is excluded from the generated DTO (not annotated with @AgentVisible). "
                  + "If this is intentional, no action needed.",
              fieldName
          ),
          element
      );
    }
  }

  /**
   * Builds the combined PII regex from default patterns plus any
   * custom keywords supplied via processor options.
   */
  private static Pattern buildPattern(String customPatterns) {
    if (customPatterns == null || customPatterns.isBlank()) {
      return DEFAULT_PII_PATTERN;
    }
    String customRegex = Arrays.stream(customPatterns.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Pattern::quote)
        .collect(Collectors.joining("|"));
    if (customRegex.isEmpty()) {
      return DEFAULT_PII_PATTERN;
    }
    return Pattern.compile("(?i)(" + DEFAULT_REGEX + "|" + customRegex + ")");
  }
}
