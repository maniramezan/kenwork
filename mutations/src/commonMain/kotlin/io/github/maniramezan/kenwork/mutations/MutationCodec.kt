package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo

/**
 * The endpoint + body pair a [MutationCodec] reconstructs from a persisted payload. Distinct from
 * [QueuedMutation] because it carries no [MutationRecord.id]/[MutationRecord.key] — those live on
 * the record itself and are reattached by [MutationQueue] after decoding.
 *
 * [bodyType] must describe [body] whenever [body] is non-null: it is what the client serializes the
 * body with, and a client refuses to send a typed body without it. Prefer the reified
 * `DecodedMutation(endpoint, body)` factory, which fills it in for you.
 */
public class DecodedMutation<B : Any>(
    public val endpoint: NetworkEndpoint,
    public val body: B?,
    public val bodyType: TypeInfo?,
)

/** Builds a [DecodedMutation], capturing [body]'s [TypeInfo] (or none when [body] is `null`). */
public inline fun <reified B : Any> DecodedMutation(
    endpoint: NetworkEndpoint,
    body: B?,
): DecodedMutation<B> = DecodedMutation(endpoint, body, if (body == null) null else typeInfo<B>())

/**
 * Bridges a specific mutation shape (one [NetworkEndpoint] implementation + body type) to and
 * from a JSON string, so it can survive in a durable [MutationStore] and be replayed after the
 * process restarts.
 *
 * Kenwork can't do this generically: [NetworkEndpoint] implementations are consumer-defined types
 * (typically a `data class`/`object` per route — see the cookbook), so only the consumer knows how
 * to serialize and reconstruct them. Implement one [MutationCodec] per mutation "shape" you want
 * to survive process death, e.g.:
 *
 * ```kotlin
 * object SetLikeStateCodec : MutationCodec<LikeBody> {
 *     override val id = "set-like-state"
 *     override fun encode(endpoint: NetworkEndpoint, body: LikeBody?): String =
 *         Json.encodeToString(Payload((endpoint as SetLikeState).videoId, body?.liked ?: false))
 *     override fun decode(payload: String): DecodedMutation<LikeBody> {
 *         val decoded = Json.decodeFromString<Payload>(payload)
 *         // The reified factory records LikeBody's TypeInfo so the body is serialized on replay.
 *         return DecodedMutation(SetLikeState(decoded.videoId), LikeBody(decoded.liked))
 *     }
 *     @Serializable private data class Payload(val videoId: Int, val liked: Boolean)
 * }
 * ```
 *
 * [id] must be stable across app versions/releases — it's persisted in every [MutationRecord] this
 * codec produces and is how [MutationQueue.restore] finds the codec to decode a record after
 * relaunch. Register codecs via the `MutationQueue` constructor (or implicitly the first time you
 * `enqueue` with one) so they're available before [MutationQueue.restore] runs.
 *
 * Pass `codec = null` to [MutationQueue.enqueue] for mutations you don't need to survive a
 * process death — they still get retry + coalescing, just no persistence.
 */
public interface MutationCodec<B : Any> {
    /** A stable identifier for this codec, persisted alongside every payload it produces. */
    public val id: String

    /** Encodes [endpoint] + [body] into a payload a later [decode] call can reconstruct. */
    public fun encode(
        endpoint: NetworkEndpoint,
        body: B?,
    ): String

    /** Reconstructs the endpoint + body previously written by [encode]. */
    public fun decode(payload: String): DecodedMutation<B>
}
