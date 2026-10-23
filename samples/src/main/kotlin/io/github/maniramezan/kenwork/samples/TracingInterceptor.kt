package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.RequestInterceptor
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Builds an app-owned tracing adapter; the published kenwork modules have no OpenTelemetry dependency. */
fun createTracingInterceptor(tracer: Tracer): RequestInterceptor =
    object : RequestInterceptor {
        override suspend fun <T> intercept(
            endpoint: NetworkEndpoint,
            attempt: Int,
            proceed: suspend () -> T,
        ): T {
            val span =
                tracer
                    .spanBuilder(endpoint.method.value)
                    .setAttribute("http.request.resend_count", attempt.toLong())
                    .startSpan()
            return try {
                withContext(span.asContextElement()) { proceed() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                span.recordException(error)
                span.setStatus(StatusCode.ERROR)
                throw error
            } finally {
                span.end()
            }
        }
    }
