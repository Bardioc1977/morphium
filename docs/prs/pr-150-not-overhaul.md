# PR #150 Follow-up: Comprehensive not() Operator Overhaul

## Context

PR #150 (`proposal: MongoField.not() -- return MongoField<T> for fluent chaining`) is open
on `sboesebeck/morphium`. The maintainer wants a **comprehensive not() overhaul** instead of
just the return-type change. PR #152 (the simple not()+regex fix) was already merged.

**Plan:** Close PR #150 with a comment, then create a new branch `pr/not-overhaul` from
`origin/develop` with all fixes, and open a fresh PR.

## Bugs to Fix (4 total)

### Bug 1: `addSimple()` — `not().eq(val)` produces invalid query

**Current behavior:** `query.f("field").not().eq("hello")` produces:
```json
{"field": {"$not": "hello"}}
```
This is **invalid MongoDB syntax** — `$not` requires a document expression, not a raw value.

**Correct behavior:** Should produce:
```json
{"field": {"$ne": "hello"}}
```
Semantically, `NOT equals(val)` is `not-equals(val)`.

**Location:** `MongoFieldImpl.java`, method `addSimple()` (line 179-187)

**Current code:**
```java
private void addSimple(Object val) {
    if (not) {
        fe.setValue(UtilsMap.of("$not", val));
    } else {
        fe.setValue(val);
    }
    fe.setField(mapper.getMorphium().getARHelper().getMongoFieldName(query.getType(), fldStr));
    query.addChild(fe);
}
```

**Fix:** When `not=true`, produce a `{$ne: val}` child expression:
```java
private void addSimple(Object val) {
    fe.setField(mapper.getMorphium().getARHelper().getMongoFieldName(query.getType(), fldStr));
    if (not) {
        // not().eq(val) -> {field: {$ne: val}}
        FilterExpression child = new FilterExpression();
        child.setField("$ne");
        child.setValue(val);
        fe.addChild(child);
    } else {
        fe.setValue(val);
    }
    query.addChild(fe);
}
```

### Bug 2: `matches(Pattern)` — double `$not` children collide in HashMap

**Current behavior:** `query.f("field").not().matches(Pattern.compile("OPEN.*", Pattern.CASE_INSENSITIVE))`
calls `add("$regex", ...)` then `add("$options", ...)`. Each `add()` call creates a
FilterExpression child with field `"$not"`. In `FilterExpression.dbObject()`, children are
serialized into a HashMap keyed by field name, so the second `$not` child **overwrites** the first.

Result: `{field: {$not: {$options: "i"}}}` (lost the `$regex`!)

**Correct behavior:**
```json
{"field": {"$not": {"$regex": "OPEN.*", "$options": "i"}}}
```

**Location:** `MongoFieldImpl.java`, methods `matches(Pattern p)` (line 278-297) and
`matches(String ptrn, String options)` (line 300-308)

**Fix:** Add a helper method `addNotExpression()` and use it in both matches() overloads:

```java
/**
 * Adds a {field: {$not: {operators...}}} expression.
 * Used when not=true and multiple operators must be grouped under one $not.
 */
private void addNotExpression(Map<String, Object> operators) {
    fe.setField(mapper.getMorphium().getARHelper().getMongoFieldName(query.getType(), fldStr));
    FilterExpression notChild = new FilterExpression();
    notChild.setField("$not");
    notChild.setValue(operators);
    fe.addChild(notChild);
    query.addChild(fe);
}
```

Then rewrite `matches(Pattern p)`:
```java
@SuppressWarnings("CommentedOutCode")
@Override
public Query<T> matches(Pattern p) {
    String options = buildPatternOptions(p);
    if (not) {
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("$regex", p.toString());
        if (!options.isEmpty()) {
            ops.put("$options", options);
        }
        addNotExpression(ops);
        return query;
    }
    add("$regex", p.toString());
    if (!options.isEmpty()) {
        add("$options", options);
    }
    return query;
}
```

And `matches(String ptrn, String options)`:
```java
@Override
public Query<T> matches(String ptrn, String options) {
    if (not) {
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("$regex", ptrn);
        if (options != null && !options.isEmpty()) {
            ops.put("$options", options);
        }
        addNotExpression(ops);
        return query;
    }
    add("$regex", ptrn);
    if (options != null && !options.isEmpty()) {
        add("$options", options);
    }
    return query;
}
```

Add the `LinkedHashMap` import:
```java
import java.util.LinkedHashMap;
```

### Bug 3: `matches(Pattern)` — bitwise OR (`|`) instead of AND (`&`) for flag check

**Current code (lines 287-291):**
```java
if ((p.flags() | Pattern.CASE_INSENSITIVE) != 0) {
    options = options + "i";
} else if ((p.flags() | Pattern.MULTILINE) != 0) {
    options = options + "m";
}
```

**Problem:** `|` (OR) with any non-zero value always produces non-zero.
`Pattern.CASE_INSENSITIVE = 2`, so `(anything | 2) != 0` is **always true**.
This means:
- The `"i"` option is ALWAYS added when `p.flags() != 0`, even for MULTILINE-only patterns
- The `"m"` option is NEVER added (dead code in `else if`)

**Fix:** Use `&` (AND) and change `else if` to `if` so both flags can be set:

```java
private String buildPatternOptions(Pattern p) {
    if (p.flags() == 0) return "";
    String options = "";
    if ((p.flags() & Pattern.CASE_INSENSITIVE) != 0) {
        options += "i";
    }
    if ((p.flags() & Pattern.MULTILINE) != 0) {
        options += "m";
    }
    return options;
}
```

Extract this as a private helper method used by `matches(Pattern)`. This replaces the inline
flag check that was previously inside `matches(Pattern p)`.

### Bug 4: `not()` returns `Query<T>` instead of `MongoField<T>` (BREAKING)

**Current code:**

Interface `MongoField.java` (line 97):
```java
Query<T> not();
```

Implementation `MongoFieldImpl.java` (lines 42-45):
```java
@Override
public Query<T> not() {
    not = true;
    return query;
}
```

**Problem:** Since `not()` returns `Query<T>`, you can't fluently chain:
```java
query.f("field").not().eq("val")  // COMPILE ERROR: Query<T> has no eq()
```
You must awkwardly split it:
```java
var field = query.f("field");
field.not();
field.eq("val");
```

**Fix:**

Interface `MongoField.java`:
```java
MongoField<T> not();
```

Implementation `MongoFieldImpl.java`:
```java
@Override
public MongoField<T> not() {
    not = true;
    return this;
}
```

**BREAKING CHANGE:** Code that calls `not()` and immediately uses the `Query<T>` return
value will break. However, this pattern is extremely unlikely since `not()` on its own
(without a subsequent operator) has no effect. The typical workaround pattern:
```java
var f = query.f("field");
f.not();
f.matches(...);
```
...still works because `f.not()` return value is just discarded.

## Files Changed Summary

| File | Changes |
|------|---------|
| `MongoField.java` | `not()` return type: `Query<T>` -> `MongoField<T>` |
| `MongoFieldImpl.java` | `not()` returns `this`, `addSimple()` fix, `addNotExpression()` helper, `buildPatternOptions()` helper, `matches(Pattern)` rewrite, `matches(String,String)` rewrite |
| `RawQueryTests.java` | `assert` -> JUnit assertions |
| `RegexTests.java` (inmem) | `assert` -> JUnit assertions |
| `NotOperatorTests.java` | **NEW** comprehensive test class |

## New Test Class: NotOperatorTests.java

**Path:** `morphium-core/src/test/java/de/caluga/test/mongo/suite/inmem/NotOperatorTests.java`

```java
package de.caluga.test.mongo.suite.inmem;

import de.caluga.morphium.driver.Doc;
import de.caluga.morphium.driver.inmem.QueryHelper;
import de.caluga.morphium.query.Query;
import de.caluga.test.mongo.suite.data.UncachedObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Tag("inmemory")
public class NotOperatorTests extends MorphiumInMemTestBase {

    // --- Query structure tests (verify the generated BSON) ---

    @Test
    public void notEqProducesNe() {
        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.strValue);
        field.not();
        Query<UncachedObject> q = field.eq("hello");
        Map<String, Object> qo = q.toQueryObject();
        // Should produce {str_value: {$ne: "hello"}}
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("str_value");
        assertNotNull(fieldExpr, "Field expression should not be null");
        assertEquals("hello", fieldExpr.get("$ne"), "not().eq() should produce $ne");
        assertNull(fieldExpr.get("$not"), "Should NOT have $not wrapper for equality");
    }

    @Test
    public void notGtProducesNotGt() {
        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.counter);
        field.not();
        Query<UncachedObject> q = field.gt(10);
        Map<String, Object> qo = q.toQueryObject();
        // Should produce {counter: {$not: {$gt: 10}}}
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("counter");
        assertNotNull(fieldExpr, "Field expression should not be null");
        @SuppressWarnings("unchecked")
        Map<String, Object> notExpr = (Map<String, Object>) fieldExpr.get("$not");
        assertNotNull(notExpr, "$not should be present");
        assertEquals(10, notExpr.get("$gt"), "$gt value should be 10");
    }

    @Test
    public void notLtProducesNotLt() {
        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.counter);
        field.not();
        Query<UncachedObject> q = field.lt(5);
        Map<String, Object> qo = q.toQueryObject();
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("counter");
        @SuppressWarnings("unchecked")
        Map<String, Object> notExpr = (Map<String, Object>) fieldExpr.get("$not");
        assertNotNull(notExpr, "$not should be present");
        assertEquals(5, notExpr.get("$lt"));
    }

    @Test
    public void notMatchesPatternProducesNotRegex() {
        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.strValue);
        field.not();
        Query<UncachedObject> q = field.matches(Pattern.compile("OPEN.*"));
        Map<String, Object> qo = q.toQueryObject();
        // Should produce {str_value: {$not: {$regex: "OPEN.*"}}}
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("str_value");
        assertNotNull(fieldExpr);
        @SuppressWarnings("unchecked")
        Map<String, Object> notExpr = (Map<String, Object>) fieldExpr.get("$not");
        assertNotNull(notExpr, "$not should be present");
        assertEquals("OPEN.*", notExpr.get("$regex"));
    }

    @Test
    public void notMatchesPatternWithFlagsPreservesOptions() {
        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.strValue);
        field.not();
        Query<UncachedObject> q = field.matches(Pattern.compile("open.*", Pattern.CASE_INSENSITIVE));
        Map<String, Object> qo = q.toQueryObject();
        // Should produce {str_value: {$not: {$regex: "open.*", $options: "i"}}}
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("str_value");
        @SuppressWarnings("unchecked")
        Map<String, Object> notExpr = (Map<String, Object>) fieldExpr.get("$not");
        assertNotNull(notExpr, "$not should be present");
        assertEquals("open.*", notExpr.get("$regex"), "$regex should be inside $not");
        assertEquals("i", notExpr.get("$options"), "$options should be inside $not alongside $regex");
    }

    @Test
    public void notMatchesStringProducesNotRegex() {
        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.strValue);
        field.not();
        Query<UncachedObject> q = field.matches("^CLOSED.*", "i");
        Map<String, Object> qo = q.toQueryObject();
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("str_value");
        @SuppressWarnings("unchecked")
        Map<String, Object> notExpr = (Map<String, Object>) fieldExpr.get("$not");
        assertNotNull(notExpr);
        assertEquals("^CLOSED.*", notExpr.get("$regex"));
        assertEquals("i", notExpr.get("$options"));
    }

    @Test
    public void notInProducesNotIn() {
        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.counter);
        field.not();
        Query<UncachedObject> q = field.in(List.of(1, 2, 3));
        Map<String, Object> qo = q.toQueryObject();
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("counter");
        @SuppressWarnings("unchecked")
        Map<String, Object> notExpr = (Map<String, Object>) fieldExpr.get("$not");
        assertNotNull(notExpr, "$not should wrap $in");
        assertEquals(List.of(1, 2, 3), notExpr.get("$in"));
    }

    // --- Fluent API test ---

    @Test
    public void fluentNotChaining() {
        // After the return-type fix, this should compile and work:
        // query.f("field").not().eq("val") — not() returns MongoField<T>
        Query<UncachedObject> q = morphium.createQueryFor(UncachedObject.class)
                .f(UncachedObject.Fields.strValue).not().eq("hello");
        Map<String, Object> qo = q.toQueryObject();
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("str_value");
        assertNotNull(fieldExpr);
        assertEquals("hello", fieldExpr.get("$ne"));
    }

    // --- InMemory driver execution tests ---

    @Test
    public void notEqQueryExecution() {
        morphium.store(new UncachedObject("hello", 1));
        morphium.store(new UncachedObject("world", 2));
        morphium.store(new UncachedObject("hello", 3));

        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.strValue);
        field.not();
        List<UncachedObject> results = field.eq("hello").asList();
        assertEquals(1, results.size(), "not().eq('hello') should return only 'world'");
        assertEquals("world", results.get(0).getStrValue());
    }

    @Test
    public void notGtQueryExecution() {
        morphium.store(new UncachedObject("a", 5));
        morphium.store(new UncachedObject("b", 15));
        morphium.store(new UncachedObject("c", 25));

        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.counter);
        field.not();
        List<UncachedObject> results = field.gt(10).asList();
        // not(counter > 10) -> counter <= 10 -> only "a" (counter=5)
        assertEquals(1, results.size());
        assertEquals(5, results.get(0).getCounter());
    }

    @Test
    public void notMatchesPatternQueryExecution() {
        morphium.store(new UncachedObject("OPEN_order", 1));
        morphium.store(new UncachedObject("OPEN_other", 2));
        morphium.store(new UncachedObject("CLOSED_order", 3));
        morphium.store(new UncachedObject("CANCELLED", 4));

        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.strValue);
        field.not();
        List<UncachedObject> results = field.matches(Pattern.compile("OPEN.*")).asList();
        assertEquals(2, results.size(), "NOT regex should exclude matching docs");
    }

    @Test
    public void notMatchesCaseInsensitiveQueryExecution() {
        morphium.store(new UncachedObject("Open_order", 1));
        morphium.store(new UncachedObject("open_other", 2));
        morphium.store(new UncachedObject("CLOSED_order", 3));

        var field = morphium.createQueryFor(UncachedObject.class).f(UncachedObject.Fields.strValue);
        field.not();
        List<UncachedObject> results = field.matches(Pattern.compile("open.*", Pattern.CASE_INSENSITIVE)).asList();
        // Case-insensitive NOT LIKE "open.*" -> only CLOSED_order
        assertEquals(1, results.size(), "Case-insensitive NOT regex should exclude both open variants");
        assertEquals("CLOSED_order", results.get(0).getStrValue());
    }

    @Test
    public void patternMultilineOnlyDoesNotAddCaseInsensitive() {
        // Regression for Bug 3: Pattern.MULTILINE should add "m", not "i"
        morphium.store(new UncachedObject("line1\nOPEN_order", 1));
        morphium.store(new UncachedObject("CLOSED_only", 2));

        var q = morphium.createQueryFor(UncachedObject.class)
                .f(UncachedObject.Fields.strValue)
                .matches(Pattern.compile("^OPEN.*", Pattern.MULTILINE));
        Map<String, Object> qo = q.toQueryObject();
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldExpr = (Map<String, Object>) qo.get("str_value");
        // Should have $options: "m" (not "i")
        assertEquals("m", fieldExpr.get("$options"),
                "MULTILINE flag should produce 'm' option, not 'i'");
    }
}
```

## Assert-to-JUnit Conversions

### File: `morphium-core/src/test/java/de/caluga/test/mongo/suite/inmem/RawQueryTests.java`

Line 29: Change:
```java
assert (lst.size() == 1);
```
To:
```java
assertEquals(1, lst.size());
```

(The `assertEquals` import already exists in the file.)

### File: `morphium-core/src/test/java/de/caluga/test/mongo/suite/inmem/RegexTests.java`

Add imports:
```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

Convert ALL `assert` statements to JUnit assertions:

| Line | Current | Replacement |
|------|---------|-------------|
| 18 | `assert (q.f(...).matches("VALUE.*").countAll() == 50);` | `assertEquals(50, q.f(...).matches("VALUE.*").countAll());` |
| 20 | `assert (lst.size() > 1);` | `assertTrue(lst.size() > 1);` |
| 22 | `assert (o.getStrValue().endsWith("9"));` | `assertTrue(o.getStrValue().endsWith("9"));` |
| 31 | `assert (q.f(...).matches(Pattern.compile("VALUE.*")).countAll() == 50);` | `assertEquals(50, q.f(...).matches(Pattern.compile("VALUE.*")).countAll());` |
| 33 | `assert (lst.size() > 1);` | `assertTrue(lst.size() > 1);` |
| 35 | `assert (o.getStrValue().endsWith("9"));` | `assertTrue(o.getStrValue().endsWith("9"));` |
| 44 | `assert (q.f(...).matches("value 9$", "i").countAll() == 1);` | `assertEquals(1, q.f(...).matches("value 9$", "i").countAll());` |
| 46 | `assert (lst.size() > 1);` | `assertTrue(lst.size() > 1);` |
| 49 | `assert (o.getStrValue().endsWith("9"));` | `assertTrue(o.getStrValue().endsWith("9"));` |
| 57 | `assert (q.f(...).matches(Pattern.compile(...)).countAll() == 1);` | `assertEquals(1, q.f(...).matches(Pattern.compile(...)).countAll());` |
| 59 | `assert (lst.size() > 1);` | `assertTrue(lst.size() > 1);` |
| 62 | `assert (o.getStrValue().endsWith("9"));` | `assertTrue(o.getStrValue().endsWith("9"));` |
| 70 | `assert (q.f(...).matches(Pattern.compile(...)).countAll() == 2);` | `assertEquals(2, q.f(...).matches(Pattern.compile(...)).countAll());` |
| 72 | `assert (q.f(...).matches("^value [12]3$", "i").countAll() == 2);` | `assertEquals(2, q.f(...).matches("^value [12]3$", "i").countAll());` |

## Git Workflow

```bash
cd /Volumes/Entwicklung/workspaces/porsche/morphium-workspace/morphium

# 1. Fetch latest
git fetch origin

# 2. Create new branch from origin/develop
git checkout -b pr/not-overhaul origin/develop

# 3. Implement all 4 bug fixes + tests + assert conversions
# 4. Verify

# Targeted tests
mvn -pl morphium-core test \
    -Dtest="de.caluga.test.mongo.suite.inmem.NotOperatorTests,de.caluga.test.mongo.suite.inmem.RegexTests,de.caluga.test.mongo.suite.inmem.RawQueryTests" \
    -DfailIfNoTests=false

# Full inmemory regression
./runtests.sh --tags inmemory --skip

# 5. Commit and push
git add morphium-core/src/main/java/de/caluga/morphium/query/MongoField.java \
       morphium-core/src/main/java/de/caluga/morphium/query/MongoFieldImpl.java \
       morphium-core/src/test/java/de/caluga/test/mongo/suite/inmem/NotOperatorTests.java \
       morphium-core/src/test/java/de/caluga/test/mongo/suite/inmem/RawQueryTests.java \
       morphium-core/src/test/java/de/caluga/test/mongo/suite/inmem/RegexTests.java

git commit -m "fix: comprehensive not() operator overhaul — 4 bug fixes

- addSimple: not().eq(val) now produces {$ne: val} instead of invalid {$not: val}
- matches: addNotExpression() helper prevents $not HashMap key collision
- matches(Pattern): fix bitwise OR to AND for flag checking (| -> &)
- not() returns MongoField<T> for fluent chaining (BREAKING)

Add NotOperatorTests (13 tests), convert assert to JUnit in RegexTests/RawQueryTests."

# 6. Push to fork
git push fork pr/not-overhaul
```

## PR #150 Close Comment (draft, present to user before sending)

> Hi Stephan,
>
> Thanks for the feedback! You're right that a comprehensive overhaul makes more sense
> than just the return-type change. I'm closing this PR in favour of a new one that
> addresses all the not() issues together.
>
> Cheers!

## New PR (draft, present to user before creating)

**Title:** `fix: comprehensive not() operator overhaul`

**Body:**
> Hi Stephan,
>
> Following your feedback on #150, here's a comprehensive overhaul of the `not()` operator
> that fixes all known issues:
>
> **Bug fixes:**
> 1. **`addSimple()`**: `not().eq(val)` produced invalid `{$not: val}` — now correctly
>    produces `{$ne: val}`
> 2. **`matches()` double-add**: Two `$not` FilterExpression children with same key collided
>    in HashMap serialization — new `addNotExpression()` helper groups all operators under
>    one `$not`
> 3. **`matches(Pattern)` flag check**: Bitwise OR (`|`) instead of AND (`&`) caused
>    CASE_INSENSITIVE to always be set and MULTILINE to never be set — fixed both
> 4. **`not()` return type**: Changed from `Query<T>` to `MongoField<T>` for fluent
>    chaining: `query.f("field").not().eq("val")` (BREAKING, but no valid use of the
>    old return type exists)
>
> **Tests:**
> - New `NotOperatorTests` class with 13 tests covering query structure and InMem execution
> - Converted `assert` statements to JUnit assertions in `RegexTests` and `RawQueryTests`
>
> Cheers!

**Create with:**
```bash
gh pr create \
    --head Bardioc1977:pr/not-overhaul \
    --base develop \
    --repo sboesebeck/morphium \
    --title "fix: comprehensive not() operator overhaul" \
    --body "$(cat <<'EOF'
<PR body from above>
EOF
)"
```

**IMPORTANT:** Do NOT create the PR or send any comments without explicit user approval.
