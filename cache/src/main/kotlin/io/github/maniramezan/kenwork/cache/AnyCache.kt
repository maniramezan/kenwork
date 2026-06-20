package io.github.maniramezan.kenwork.cache

/**
 * A type-forwarding wrapper around an arbitrary [Cache].
 *
 * Provided for API symmetry with SwiftyNetwork's `AnyCache`. Unlike Swift, Kotlin generics are
 * not erased at the type level, so a `Cache<V>` is already usable as an abstract type — this
 * class is a thin convenience for callers that want a concrete, non-generic-parameter-leaking
 * cache handle. Implemented via interface delegation.
 */
public class AnyCache<V : Any>(
    wrapped: Cache<V>,
) : Cache<V> by wrapped
