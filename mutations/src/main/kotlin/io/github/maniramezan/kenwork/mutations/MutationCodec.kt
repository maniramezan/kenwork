package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo

/**
 * The endpoint + body pair a [MutationCodec] reconstructs from a persisted payload. Distinct from
 * [QueuedMutation] because it carries no [MutationRecord.id]/[MutationRecord.key] — those live on
 * the record itself and are reattached by [MutationQueue] after decoding.
 */
public class DecodedMutation<B : Any>(
    public val endpoint: NetworkEndpoint,
    public val body: B?,
    public val bodyType: TypeInfo?,
)

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
 * object LikeVideoCodec : MutationCodec<Unit> {
 *     override val id = "like-video"
 *     override fun encode(endpoint: NetworkEndpoint, body: Unit?): String =
 *         Json.encodeToString(LikeVideoPayload((endpoint as LikeVideo).videoId))
 *     override fun decode(payload: String): DecodedMutation<Unit> {
 *         val decoded = Json.decodeFromString<LikeVideoPayload>(payload)
 *         return DecodedMutation(LikeVideo(decoded.videoId), null, null)
 *     }
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
