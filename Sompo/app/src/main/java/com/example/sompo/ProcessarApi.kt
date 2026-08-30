package com.example.sompo

import retrofit2.Response
import retrofit2.http.Header
import retrofit2.http.POST

interface ProcessarApi {
    @POST("processar")
    suspend fun processar(@Header("Authorization") token: String): Response<Map<String, Any?>>
}
