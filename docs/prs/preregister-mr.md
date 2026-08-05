Hi Stephan,

erst mal: wir haben uns lange nicht mehr bei dir gemeldet — und ganz herzlichen
Dank für den WRITE-Zugriff auf Morphium! Den wissen wir sehr zu schätzen.

Damit es klar ist: wir nutzen den Zugriff selbstverständlich nicht, um direkt auf
deine Branches zu pushen. Wir stellen wie gewohnt **Merge Requests** gegen das
Morphium-Hauptprojekt und überlassen dir Review und Merge — so wie auch hier.

## What
Adds a small public API to `ClassGraphCache`:

```java
public static void preRegister(String annotationName, List<String> classNames)
```

It pre-populates the annotation cache with a known list of class names **before**
the first `getClassesWithAnnotation(...)` call for that annotation. When an entry is
already present, the live ClassGraph scan for that annotation is skipped entirely.

## Why
Frameworks that know all relevant classes at build time should not pay for — or
even be able to rely on — a runtime ClassGraph scan. The concrete driver is
**Quarkus native image**: there is no live classpath at runtime, so ClassGraph
finds nothing. The quarkus-morphium extension collects all `@Entity`/`@Embedded`/
`@Capped`/driver/messaging class names via Jandex at build time and needs a way to
hand them to morphium so the cache is correct without a scan.

This is the annotation-cache counterpart to the existing `registerTypeIds()`
pre-registration hook in `AnnotationAndReflectionHelper` (PR #166): same idea —
inject build-time knowledge to avoid a runtime scan — but for a different, currently
unreachable cache (`ClassGraphCache.classesWithAnnotation`, which is private and only
filled lazily via a live scan). The two hooks are complementary, not overlapping.

## Behaviour notes
- Calling with an **empty list** is valid and intentional: it puts an empty entry
  into the cache, so `getClassesWithAnnotation()` returns empty without a live scan.
  A `WARN` is logged in that case so the situation is visible.
- Both arguments are null-checked (`Objects.requireNonNull`).
- The stored list is copied defensively (`List.copyOf`).
- No behaviour change for existing callers: if `preRegister()` is never called,
  `getClassesWithAnnotation()` works exactly as before (lazy `computeIfAbsent` scan).

## Scope
- One file changed: `morphium-core/.../ClassGraphCache.java` (+36 lines).
- No API removed, no signature changed, no dependency added (slf4j already present).

## Testing
Built `morphium-core` against this change and verified the downstream
quarkus-morphium extension now compiles and resolves the cache without a runtime
scan (it previously depended on this method existing). Happy to add a focused unit
test for `preRegister` (empty list / precedence over scan) if you'd like it in this PR.

## Eine Bitte / ein Gesprächsangebot
Wir würden uns freuen, bei Gelegenheit einmal mit dir zu sprechen, **wann und in
welcher Form** man `morphium-jakarta-data` als **optionales Modul** direkt in
Morphium integrieren könnte. Aktuell pflegen wir es als eigenständiges Projekt; eine
Integration als optionales Modul (analog zu den bestehenden Erweiterungspunkten)
würde die Versionierung und die Abhängigkeiten für Downstream-Projekte wie
quarkus-morphium und spring-boot-morphium deutlich vereinfachen. Ganz wie es für
dich und das Projekt am besten passt — wir richten uns gern nach dir.

Viele Grüße aus Stuttgart,
Heiko!
