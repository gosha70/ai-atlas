# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, please report them via email to: **[security@egoge.com](mailto:i.am.goga@gmail.com)**

Include the following in your report:

- Description of the vulnerability
- Steps to reproduce
- Affected module(s): `annotations`, `processor`, `runtime`, `gradle-plugin`
- Potential impact (e.g., PII leak, code injection in generated source)
- Suggested fix (if any)

## What Qualifies

AI-ATLAS is a security-focused framework. We take these categories seriously:

- **PII leakage**: Any path where unannotated fields appear in generated DTOs, MCP tool responses, or REST controller responses
- **Code injection**: Annotation attribute values that could inject arbitrary code into generated Java source
- **Bypass of compile-time guarantees**: Scenarios where the whitelist model (`@AgentVisible`) can be circumvented
- **Runtime safety net failures**: Conditions where `DtoResponseBodyAdvice` or `PiiAuditInterceptor` fail silently

## Response Timeline

- **Acknowledgment**: Within 48 hours
- **Initial assessment**: Within 5 business days
- **Fix timeline**: Depends on severity; critical PII leaks are treated as highest priority

## Disclosure

We follow coordinated disclosure. We will work with you to understand the issue, develop a fix, and agree on a disclosure timeline before any public announcement.
