package com.example.afrina.core.network

import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class ControlPlaneHealthDto(
    val status: String,
    val apiVersion: String,
    val vpsProfile: String,
    val activeSandboxes: Int,
)

@JsonClass(generateAdapter = true)
data class RemoteCommandProposalDto(
    val proposalId: String,
    val projectId: String,
    val agentRole: String,
    val command: String,
    val requiresHumanApproval: Boolean,
)

@JsonClass(generateAdapter = true)
data class ApprovalDecisionDto(
    val approved: Boolean,
    val reason: String? = null,
)

/**
 * Provider-independent HTTP contract for the future self-hosted Linux VPS Control Plane.
 * Phase 0 defines the typed API contract without requiring a live VPS connection.
 */
interface ControlPlaneApiContract {
    @GET("v1/system/health")
    suspend fun getSystemHealth(): ControlPlaneHealthDto

    @POST("v1/projects/{projectId}/builds")
    suspend fun triggerBuild(
        @Path("projectId") projectId: String,
        @Body payload: Map<String, String>,
    ): RemoteCommandProposalDto

    @POST("v1/approvals/{proposalId}")
    suspend fun submitApproval(
        @Path("proposalId") proposalId: String,
        @Body decision: ApprovalDecisionDto,
    )
}

object SecureHttpClientFactory {
    fun createClient(enableBodyLogs: Boolean = false): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (enableBodyLogs) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("X-Afrina-Signature")
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }
}
