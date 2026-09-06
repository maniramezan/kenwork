# GraphQL over the HTTP client

kenwork has no GraphQL schema generator, normalized entity cache, subscription transport, or
GraphQL-specific error model. Its typed POST API can carry a GraphQL request. Treat transport
success and operation success separately: a response may contain both `data` and `errors`,
including partial data. See the [GraphQL response specification](https://spec.graphql.org/October2021/#sec-Response).

The following Android `network` example preserves the response envelope for the application's
own partial-data policy. The application must enable the Kotlin serialization plugin for its DTOs.

```kotlin
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkClient
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.request
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class GraphQLRequest(
    val query: String,
    val operationName: String,
    val variables: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GraphQLResponse(
    val data: JsonObject? = null,
    val errors: List<JsonObject>? = null,
    val extensions: JsonElement? = null,
)

object GraphQLEndpoint : NetworkEndpoint {
    override val baseUrl = "https://api.example.com"
    override val path = "graphql"
    override val method = HttpMethod.POST
    override val headers = mapOf("Accept" to "application/graphql-response+json, application/json")
}

suspend fun currentUser(client: NetworkClient): GraphQLResponse = client.request(
    GraphQLEndpoint,
    body = GraphQLRequest(
        query = "query CurrentUser { viewer { id name } }",
        operationName = "CurrentUser",
    ),
)
```

Inspect `errors` before treating the operation as complete. Retain partial `data` where your UI
policy permits it; generated or handwritten operation DTOs can replace `JsonObject` when schema
types are known. HTTP failures still use `NetworkError`; errors inside an HTTP-success envelope
do not automatically trigger kenwork retries or OAuth refresh.

Send user input in `variables` instead of interpolating it into query text. Do not enable POST
retries globally just because some operations are queries: the same endpoint also carries
mutations. Decide replay safety per operation and use server idempotency support for writes.
If caching results, include operation, variables, and account scope in the key. kenwork's cache
stores whole values and does not normalize GraphQL entities or invalidate related operations.

For subscriptions, persisted-query negotiation, schema code generation, or normalized caching,
evaluate a dedicated GraphQL client. Keep those contracts outside the generic HTTP layer until
there is a concrete consumer requirement and tests for partial-data and replay behavior.
