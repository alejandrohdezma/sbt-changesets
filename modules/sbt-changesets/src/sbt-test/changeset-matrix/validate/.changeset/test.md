---
"module-a": patch
"module-b": patch
"module-d": patch
"module-e": patch
"module-f": patch
---

Shared dependency bump that affects module-a, module-b, the Java module-d, the test-scoped module-e, and module-f (published under a different artifactId via `moduleName`) without touching their source files.
