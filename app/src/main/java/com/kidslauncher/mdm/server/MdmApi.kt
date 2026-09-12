package com.kidslauncher.mdm.server

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.kidslauncher.mdm.server.dto.CommandResultRequest
import com.kidslauncher.mdm.server.dto.DnsBlocklistCategory
import com.kidslauncher.mdm.server.dto.DnsEventReport
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.EnrollResponse
import com.kidslauncher.mdm.server.dto.InstallProgressReport
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.TrackedAppUpdate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** Shared JSON config for both Retrofit and the cached-policy preference blob. The server's JSON
 * is snake_case (Rust's serde default, no per-field renames); this maps it to/from idiomatic
 * camelCase Kotlin properties without needing an `@SerialName` on every field. */
@OptIn(ExperimentalSerializationApi::class)
val ServerJson: Json = Json {
    ignoreUnknownKeys = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/**
 * Device-facing kid-phone-server REST endpoints. Enrollment is unauthenticated (the enrollment
 * code itself is the one-shot credential); policy/status require the bearer token issued at
 * enrollment, attached by [createMdmApi] via a header rather than threaded through every call.
 */
interface MdmApi {

    @POST("api/devices/enroll")
    suspend fun enroll(@Body request: EnrollRequest): Response<EnrollResponse>

    @GET("api/devices/policy")
    suspend fun getPolicy(): Response<PolicyResponse>

    @POST("api/devices/status")
    suspend fun sendStatus(@Body report: StatusReportRequest): Response<Unit>

    @POST("api/devices/command-result")
    suspend fun sendCommandResult(@Body request: CommandResultRequest): Response<Unit>

    @GET("api/devices/apps")
    suspend fun getTrackedAppUpdates(): Response<List<TrackedAppUpdate>>

    /** Fire-and-forget from the caller's side (see [InstallProgressReport]'s own doc comment) -
     * called on a throttled schedule during [downloadTrackedApp], not on every chunk. */
    @POST("api/devices/apps/progress")
    suspend fun reportInstallProgress(@Body report: InstallProgressReport): Response<Unit>

    /** Only called when [PolicyResponse.dnsFilterVersion] differs from the last-fetched value -
     * see [DnsFilterEngine] - since this can be a large (~100k+ domain) payload. */
    @GET("api/devices/dns-blocklist")
    suspend fun getDnsBlocklist(): Response<List<DnsBlocklistCategory>>

    @POST("api/devices/dns-events")
    suspend fun sendDnsEvents(@Body events: List<DnsEventReport>): Response<Unit>

    /** [url] is [TrackedAppUpdate.downloadUrl] as sent by the server (e.g.
     * "/api/devices/apps/5/download") - a per-app path, since there can be many tracked apps
     * (including the launcher itself - it's just another tracked app server-side now). [Url]
     * resolves it against the same base URL/auth interceptor as every other call here. */
    @Streaming
    @GET
    suspend fun downloadTrackedApp(@Url url: String): Response<ResponseBody>
}

/** [token] is omitted for the enroll-only call (no token exists yet); pass it for every
 * subsequent authenticated call. Uses the device's normal network path.
 *
 * LOCAL-DEVIATION: upstream routed this through the embedded tsnet SOCKS5 proxy when a tailnet
 * connection was up. This deployment reaches its server over the public internet on a real domain
 * with a valid certificate, so tsnet is removed entirely - this is the plain fallback path
 * upstream already used whenever tsnet had not connected. */
fun createMdmApi(baseUrl: String, token: String? = null): MdmApi {
    val clientBuilder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)

    if (token != null) {
        clientBuilder.addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            )
        }
    }

    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    return Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(clientBuilder.build())
        .addConverterFactory(ServerJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MdmApi::class.java)
}
