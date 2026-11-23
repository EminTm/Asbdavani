package com.asbai.predictor.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    val sharedClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 10
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
