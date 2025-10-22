package com.example.machinevisor

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @Multipart
    @POST("/upload")
    suspend fun uploadImage(
        @Part image: MultipartBody.Part,
        @Part("pitch_deg") pitchDeg: Double,
        @Part("roll_deg") rollDeg: Double,
        @Part("seq") seq: Int,
        @Part("details") details: Boolean
    ): Response<UploadResponse>
}

data class UploadResponse(
    val status: String?,
    val saved_as: String?,
    val size_bytes: Long?,
    val content_type: String?,
    val pitch_deg: Double?,
    val roll_deg: Double?,
    val seq: Int?,
    val details: Boolean?,
    val analysis: Any?
)

object ApiClient {
    @Volatile private var retrofit: Retrofit? = null

    fun get(baseUrl: String): ApiService {
        val existing = retrofit
        if (existing != null && existing.baseUrl().toString() == baseUrl) {
            return existing.create(ApiService::class.java)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val rt = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        retrofit = rt
        return rt.create(ApiService::class.java)
    }
}


