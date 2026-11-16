package io.github.maniramezan.kenwork.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.plugins.logging.LogLevel as KtorLogLevel
import io.ktor.client.plugins.logging.Logger as KtorLogger

/**
 * Builds the Ktor [HttpClient] described by [config]: the explicit [NetworkClientConfiguration.engine]
 * when one is supplied, otherwise an OkHttp engine carrying the OkHttp-specific options.
 */
internal fun buildHttpClient(config: NetworkClientConfiguration): HttpClient {
    val common: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(ContentNegotiation) { json(config.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMillis
            config.connectTimeoutMillis?.let { connectTimeoutMillis = it }
            config.socketTimeoutMillis?.let { socketTimeoutMillis = it }
        }
        // Ktor's request logger only has INFO-and-above output, so below INFO it would format
        // request lines for nothing. Skip the plugin then, so no pipeline interceptors run.
        val ktorLevel = config.logLevel.toKtorLogLevel()
        if (ktorLevel != KtorLogLevel.NONE) {
            install(Logging) {
                level = ktorLevel
                logger = KenworkKtorLogger
            }
        }
    }
    val explicitEngine = config.engine ?: return buildOkHttpClient(config, common)
    return HttpClient(explicitEngine, common)
}

private fun buildOkHttpClient(
    config: NetworkClientConfiguration,
    common: HttpClientConfig<*>.() -> Unit,
): HttpClient =
    HttpClient(OkHttp) {
        common()
        engine {
            config.engineInterceptors.forEach { addInterceptor(it) }
            config {
                config.okHttpCache?.let { cache(it) }
                config.sslPinning?.let { certificatePinner(it.toCertificatePinner()) }
                config.okHttpConfig?.invoke(this)
            }
        }
    }

/** Routes Ktor's request logger through [KenworkLogger] at debug level. */
private object KenworkKtorLogger : KtorLogger {
    override fun log(message: String) {
        KenworkLogger.debug(message, LogCategory.NETWORK)
    }
}

private fun LogLevel.toKtorLogLevel(): KtorLogLevel =
    when (this) {
        LogLevel.OFF, LogLevel.ERROR, LogLevel.WARNING -> KtorLogLevel.NONE
        LogLevel.INFO -> KtorLogLevel.INFO
        LogLevel.DEBUG -> KtorLogLevel.BODY
    }
