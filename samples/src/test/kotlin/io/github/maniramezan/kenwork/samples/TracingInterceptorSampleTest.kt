package io.github.maniramezan.kenwork.samples

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TracingInterceptorSampleTest {
    @Test
    fun `attempt context follows suspension across dispatchers and restores its parent`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            provider(exporter).use { provider ->
                val tracer = provider.get("kenwork.samples")
                val interceptor = createTracingInterceptor(tracer)
                val original = Span.current().spanContext
                val parent = tracer.spanBuilder("parent").startSpan()
                try {
                    withContext(parent.asContextElement()) {
                        val observed =
                            interceptor.intercept(GetVideo(42), 0) {
                                withContext(Dispatchers.Default) {
                                    yield()
                                    Span.current().spanContext
                                }
                            }
                        val attempt = exporter.finishedSpanItems.single()

                        assertEquals(attempt.spanId, observed.spanId)
                        assertEquals(parent.spanContext.traceId, observed.traceId)
                        assertEquals(parent.spanContext.spanId, attempt.parentSpanId)
                        assertEquals(parent.spanContext, Span.current().spanContext)
                    }
                    assertEquals(original, Span.current().spanContext)
                } finally {
                    parent.end()
                }
            }
        }

    @Test
    fun `failed attempts record their exception and end the span`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            provider(exporter).use { provider ->
                val interceptor = createTracingInterceptor(provider.get("kenwork.samples"))
                val failure = IllegalStateException("request failed")

                val thrown =
                    assertFailsWith<IllegalStateException> {
                        interceptor.intercept<Unit>(GetVideo(42), 0) { throw failure }
                    }

                assertEquals(failure.message, thrown.message)
                val attempt = exporter.finishedSpanItems.single()
                assertEquals(StatusCode.ERROR, attempt.status.statusCode)
                assertEquals("exception", attempt.events.single().name)
            }
        }

    @Test
    fun `cancellation propagates and ends the span without recording a request failure`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            provider(exporter).use { provider ->
                val interceptor = createTracingInterceptor(provider.get("kenwork.samples"))
                val cancelled = CancellationException("caller stopped")

                val thrown =
                    assertFailsWith<CancellationException> {
                        interceptor.intercept<Unit>(GetVideo(42), 0) { throw cancelled }
                    }

                assertEquals(cancelled.message, thrown.message)
                val attempt = exporter.finishedSpanItems.single()
                assertEquals(StatusCode.UNSET, attempt.status.statusCode)
                assertEquals(emptyList(), attempt.events)
            }
        }

    private fun provider(exporter: InMemorySpanExporter): SdkTracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
}
