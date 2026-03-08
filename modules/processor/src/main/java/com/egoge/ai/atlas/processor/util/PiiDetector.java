/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Heuristic detector for fields that may contain PII.
 * Emits compiler NOTE diagnostics for suspicious field names
 * that are NOT annotated with {@code @AgentVisible} (which would be
 * an intentional inclusion), helping developers catch accidental omissions
 * or confirm intentional exclusions.
 *
 * <p>Default patterns are loaded from the classpath resource
 * {@code META-INF/ai-atlas/pii-patterns.conf}. A custom patterns file
 * can replace the defaults via {@code -Aai.atlas.pii.patterns.file=path}.
 *
 * <p>Additional keywords can be added on top via
 * {@code -Aai.atlas.pii.patterns=keyword1,keyword2,...}. Custom keywords
 * are matched case-insensitively as substrings of the field name.
 */
public final class PiiDetector {

  private static final String DEFAULT_RESOURCE = "/META-INF/ai-atlas/pii-patterns.conf";

  /** Cache: avoids re-reading file and re-compiling regex per field. */
  private static final Map<String, Pattern> PATTERN_CACHE = new HashMap<>();

  private PiiDetector() {
  }

  /**
   * Checks if a field name matches known PII patterns and emits a
   * NOTE-level diagnostic if it does.
   *
   * @param fieldName        the field name to check
   * @param element          the element for diagnostic positioning
   * @param messager         the compiler messager for emitting diagnostics
   * @param customPatterns   comma-separated additional keywords (may be null)
   * @param patternsFilePath path to a custom patterns file (may be null)
   */
  public static void check(
      String fieldName,
      Element element,
      Messager messager,
      String customPatterns,
      String patternsFilePath) {
    Pattern pattern = buildPattern(customPatterns, patternsFilePath, messager);
    if (pattern != null && pattern.matcher(fieldName).find()) {
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

  private static Pattern buildPattern(String customPatterns, String patternsFilePath, Messager messager) {
    String cacheKey = Objects.toString(patternsFilePath, "") + "|" + Objects.toString(customPatterns, "");
    return PATTERN_CACHE.computeIfAbsent(cacheKey, k -> {
      List<String> basePatterns = loadPatterns(patternsFilePath, messager);
      String baseRegex = String.join("|", basePatterns);

      if (customPatterns != null && !customPatterns.isBlank()) {
        String customRegex = Arrays.stream(customPatterns.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));
        if (!customRegex.isEmpty()) {
          baseRegex = baseRegex.isEmpty() ? customRegex : baseRegex + "|" + customRegex;
        }
      }

      if (baseRegex.isEmpty()) {
        return null;
      }
      return Pattern.compile("(?i)(" + baseRegex + ")");
    });
  }

  private static List<String> loadPatterns(String patternsFilePath, Messager messager) {
    if (patternsFilePath != null && !patternsFilePath.isBlank()) {
      return loadFromFile(patternsFilePath, messager);
    }
    return loadFromClasspath(messager);
  }

  private static List<String> loadFromFile(String path, Messager messager) {
    try {
      return Files.readAllLines(Path.of(path)).stream()
          .map(String::trim)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .toList();
    } catch (IOException e) {
      messager.printMessage(Diagnostic.Kind.WARNING,
          "[ai-atlas] Could not read PII patterns file '" + path
              + "': " + e.getMessage() + ". Falling back to defaults.");
      return loadFromClasspath(messager);
    }
  }

  private static List<String> loadFromClasspath(Messager messager) {
    try (var stream = PiiDetector.class.getResourceAsStream(DEFAULT_RESOURCE)) {
      if (stream == null) {
        messager.printMessage(Diagnostic.Kind.WARNING,
            "[ai-atlas] Default PII patterns resource not found. No PII detection active.");
        return List.of();
      }
      return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .lines()
          .map(String::trim)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .toList();
    } catch (IOException e) {
      messager.printMessage(Diagnostic.Kind.WARNING,
          "[ai-atlas] Error reading default PII patterns: " + e.getMessage());
      return List.of();
    }
  }
}
