package com.example.sompo

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface TelemetriaApi {
    @POST("telemetria")
    suspend fun enviar(
        @Header("Authorization") token: String,
        @Body payload: TelemetriaPayload
    ): Response<ResponseBody>
}
