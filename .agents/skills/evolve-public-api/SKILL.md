---
name: evolve-public-api
description: Change kenwork's public API without breaking binary compatibility for apps that already depend on it. Use whenever adding, removing, renaming, or changing a public/protected class, function, property, constructor, parameter, default value, sealed subtype, or enum constant in a published module (network, network-core, cache, repository, mutations, testing), or when moving public top-level declarations between files.
---

# Evolving kenwork's public API

kenwork ships as AARs/JARs on Maven Central. A consumer's *other* dependencies were compiled
against older kenwork signatures, so the JVM descriptors must keep resolving. Source-compatible
is not enough.

## 1. Classify the change

| Change | Safe? | What to do |
|---|---|---|
| New class, new function, new top-level declaration in a **new** file | Yes | KDoc + tests |
| New parameter on an existing function/constructor (even with a default) | **No** | Add a hidden shim (step 2) |
| Changing a default value | Yes (ABI) | Document the behavior change in `MIGRATION.md` |
| Changing a parameter or return type | **No** | Add a new overload, deprecate the old one normally |
| Removing/renaming a public declaration | **No** | Deprecate (WARNING → ERROR → HIDDEN) across releases |
| Moving a public **top-level function/property** to another file | **No** | Its JVM facade (`FooKt`) changes; keep it in place |
| Moving a class, or `internal`/`private` code, between files | Yes | — |
| New subtype of a public `sealed` type / new `enum` constant | Source-breaking for exhaustive `when` | Needs a major-version decision; ask first |
| `data class` ⇄ regular class, or reordering data-class properties | **No** | Changes `componentN`/`copy`; don't |
| `public inline` function body change | Inlined into callers | Only reference public/`@PublishedApi` API from it |
| New `init { require(...) }` validation | ABI-safe, behavior change | `MIGRATION.md` entry |

## 2. The hidden-shim pattern (adding a parameter)

Keep the previous signature linkable but invisible to new source:

```kotlin
public class Foo(
    public val a: Int = 0,
    public val b: String = "",
    public val newThing: Bar? = null,          // new, last, defaulted
) {
    /** Binary-compatibility shim for callers compiled before `newThing` existed. */
    @Deprecated("Binary-compatibility shim; use the primary constructor.", level = DeprecationLevel.HIDDEN)
    public constructor(a: Int = 0, b: String = "") : this(a = a, b = b, newThing = null)
}
```

- Keep the old parameter list **exactly** (names, order, types, defaults), so the default-argument
  bridge (`<init>(..., int, DefaultConstructorMarker)`) is regenerated too.
- Add new parameters **last**, with defaults.
- Existing examples to copy: `NetworkClientConfiguration` (constructor), `NetworkEvent`
  (constructor plus hand-written `componentN`/`copy` after leaving `data class`), `KenworkLogger`
  (method overloads), and `mockNetworkClient` (top-level function).
- If there's more than one historical shape, keep one shim per released shape.

## 3. Verify

1. Grep usages across the repo (`samples/`, `docs/`, the README, KDoc) and update them to the new
   API.
2. Add a test that calls the *new* surface. Binary shims can't be called from Kotlin source, so
   don't try; review the shim's parameter list against the previous release tag instead:
   `git show <last-tag>:path/to/File.kt`.
3. Run `./gradlew check`.
4. If behavior changed, add a bullet under the newest "Upgrading kenwork" section of
   `MIGRATION.md`, and update `docs/cookbook.md`/`docs/parity.md` when relevant.
5. Use the right Conventional Commit type: `feat:` for additions, `feat!:` plus a
   `BREAKING CHANGE:` footer only for an intentional, agreed break.
