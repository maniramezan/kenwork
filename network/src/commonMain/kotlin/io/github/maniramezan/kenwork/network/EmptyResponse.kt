package io.github.maniramezan.kenwork.network

import kotlinx.serialization.Serializable

/**
 * A decodable placeholder for endpoints that return no meaningful body (e.g. `204 No Content`).
 * Mirrors SwiftyNetwork's `EmptyResponse`.
 */
@Serializable
public data object EmptyResponse
