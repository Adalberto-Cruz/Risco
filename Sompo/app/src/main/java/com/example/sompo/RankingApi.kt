package com.example.sompo

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface RankingApi {
    @GET("ranking")
    suspend fun buscarRanking(
        @Header("Authorization") token: String,
        @Query("limite") limite: Int = 20
    ): Response<RankingResponse>
}
