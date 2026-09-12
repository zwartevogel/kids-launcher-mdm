package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "UnifiedPushRelay"
private const val NTFY_HOST = "ntfy.sh"
private const val BASE64_ENCODING = "base64"
private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
private const val MAX_RECONNECT_DELAY_MS = 60_000L

// ntfy's server (server.go: unifiedPushTopicPrefix/unifiedPushTopicLength) only recognizes a
// topic as UnifiedPush traffic - and so only ever sets a rate visitor for it, which publishing
// to it requires - if the topic ID is EXACTLY this shape: the literal prefix "up" (no separator)
// followed by random characters totaling 14 characters. Confirmed against ntfy's actual server
// source, not guessed - see UnifiedPushRelay's class doc comment for how this was found.
private const val NTFY_UP_TOPIC_PREFIX = "up"
private const val NTFY_UP_TOPIC_LENGTH = 14
private const val NTFY_UP_TOPIC_CHARSET =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

@Serializable
data class UnifiedPushRegistration(val packageName: String, val topic: String)

@Serializable
private data class NtfyEnvelope(
    val id: String? = null,
    val event: String = "",
    val topic: String? = null,
    val message: String? = null,
    val encoding: String? = null,
)

/**
 * Owns a single multiplexed WebSocket to ntfy.sh - confirmed via ntfy's own docs
 * (docs.ntfy.sh/subscribe/api/) that a comma-separated topic list shares one connection
 * ("reduce the number of connections you have to maintain," ntfy's own words), and each incoming
 * message carries a `topic` field identifying its source. That's what makes "one foreground
 * service, one connection, many registered apps" actually work, rather than one connection per
 * app - see [CommandListenerService], the only intended owner of this object's lifecycle.
 *
 * Each registration gets its own randomly-generated topic (see [generateTopic]), never reused,
 * never derived from the app's identity - the registering app only ever sees the resulting
 * `https://ntfy.sh/<topic>?up=1` endpoint URL, not the generation scheme, so ntfy.sh itself can't
 * correlate two registrations as the same app/device.
 *
 * The `?up=1` suffix on that endpoint (see [register]'s own doc comment) is what makes ntfy
 * auto-detect a genuinely binary UnifiedPush payload (WebPush is RFC 8291 binary) on the publish
 * side and base64-encode it into the JSON envelope itself, rather than mangling it as lossy UTF-8
 * text - the base64/`encoding` handling in [decodePayload] mirrors ntfy's own official Android app
 * exactly (`io.heckel.ntfy.util.decodeBytesMessage`, confirmed by reading that app's real source,
 * not guessed) to consume exactly that envelope shape; anything without `encoding: "base64"` is
 * treated as plain UTF-8 text bytes.
 *
 * LOCAL-DEVIATION: upstream noted here that this deliberately bypassed the embedded tailnet
 * proxy, since ntfy.sh is on the public internet. tsnet is removed in this fork, so every network
 * call in the app takes the normal path and there is no longer a distinction to make.
 */
object UnifiedPushRelay {
    private val client = OkHttpClient.Builder()
        // A subscribe WebSocket is meant to stay open indefinitely, same reasoning as
        // CommandListenerService's SSE client.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Live device testing (2026-08-15) showed this connection getting a clean server-side
        // close (code 1000) roughly every 5s, cause not yet confirmed - sending our own pings
        // is a standard mitigation for exactly this class of premature-close behavior and costs
        // nothing if it turns out to be unrelated.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var running = false

    /** `id` of the most recent event (open/keepalive/message) seen on any topic - see [connect]'s
     * `since` handling for why this is tracked. Deliberately an event ID, not a Unix timestamp:
     * ntfy's `since` treats a timestamp as inclusive, so watermarking on a message's own `time`
     * caused that same message to be replayed again on every subsequent reconnect forever
     * (confirmed live 2026-08-15 - Molly logged a fresh "New message" in lockstep with every ~5s
     * reconnect). `since=<id>` is ntfy's own documented mechanism for exact, non-repeating
     * continuation (docs.ntfy.sh/subscribe/api/#fetch-cached-messages). */
    private var lastEventId: String? = null

    /** Call once when the owning service starts (and the parent has enabled this in Settings) -
     * safe to call repeatedly. */
    fun start(context: Context) {
        if (running) return
        running = true
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        connect(context)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "distributor stopped")
        webSocket = null
    }

    /** Registers [packageName] under [token], generating a fresh topic and reconnecting the
     * shared WebSocket to include it. Returns the `https://ntfy.sh/<topic>?up=1` endpoint URL the
     * caller should hand back to the registering app via NEW_ENDPOINT.
     *
     * The `?up=1` suffix is load-bearing, not decorative: it's ntfy's own flag (see
     * docs.ntfy.sh/publish and unifiedpush.org/users/distributors/ntfy/) marking this topic as
     * UnifiedPush traffic, which is what makes the *publish* side - the registering app's own
     * push/account server, POSTing the raw WebPush-encrypted binary payload straight to this
     * opaque endpoint URL per spec - get auto-detected and base64-encoded server-side into the
     * `encoding: "base64"` envelope field [decodePayload] already knows how to consume. Without
     * it, ntfy treats the POST body as plain text, silently mangling any non-UTF8 binary payload
     * in transit before it ever reaches [decodePayload] - the connector library's WebPush
     * decryption then fails downstream with zero visible error on this end, since registration
     * itself (which never touches payload bytes) works fine either way.
     *
     * The topic itself must be shaped exactly like [generateTopic] produces - see that function's
     * own doc comment. A topic that doesn't match silently never gets a rate visitor set on
     * ntfy's side at all, which is a *harder* failure than the `?up=1` one above: every publish
     * to it - including a registering app's real ones - gets hard-rejected with a 507
     * ("cannot publish to UnifiedPush topic without previously active subscriber"), regardless of
     * this app's own connection state, not just malformed for a genuinely binary payload. */
    fun register(context: Context, token: String, packageName: String): String {
        val topic = generateTopic()
        val registrations = load().toMutableMap()
        registrations[token] = UnifiedPushRegistration(packageName, topic)
        save(registrations)
        if (running) connect(context)
        return "https://$NTFY_HOST/$topic?up=1"
    }

    /** Generates a topic ID in exactly the shape ntfy's server requires to recognize it as
     * UnifiedPush traffic: the literal prefix `"up"` (no separator - `"up-<uuid>"`, this app's
     * original scheme, was 39 characters and silently never matched) followed by random
     * characters for a total length of 14, drawn from the same mixed-case-alphanumeric charset
     * ntfy's own `util.RandomStringPrefix` uses. Confirmed directly against ntfy's server source
     * (github.com/binwiederhier/ntfy, `server/server.go`'s `unifiedPushTopicPrefix`/
     * `unifiedPushTopicLength` constants and the `maybeSetRateVisitors` check that gates on them),
     * not guessed - a topic that doesn't match this exactly never gets ntfy's per-topic rate
     * visitor set, which every UnifiedPush-flagged publish to it requires. [SecureRandom] because
     * this string is the entire unguessability guarantee for the endpoint - anyone who can guess
     * or observe it can publish arbitrary push payloads to the registered app. */
    private fun generateTopic(): String {
        val random = SecureRandom()
        val suffixLength = NTFY_UP_TOPIC_LENGTH - NTFY_UP_TOPIC_PREFIX.length
        val suffix = (1..suffixLength)
            .map { NTFY_UP_TOPIC_CHARSET[random.nextInt(NTFY_UP_TOPIC_CHARSET.length)] }
            .joinToString("")
        return "$NTFY_UP_TOPIC_PREFIX$suffix"
    }

    fun unregister(context: Context, token: String) {
        val registrations = load().toMutableMap()
        if (registrations.remove(token) == null) return
        save(registrations)
        if (running) connect(context)
    }

    private fun load(): Map<String, UnifiedPushRegistration> {
        val raw = LauncherPreferences.mdm().unifiedpushRegistrations() ?: return emptyMap()
        return try {
            ServerJson.decodeFromString(raw)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to decode UnifiedPush registrations", e)
            emptyMap()
        }
    }

    private fun save(registrations: Map<String, UnifiedPushRegistration>) {
        LauncherPreferences.mdm().unifiedpushRegistrations(ServerJson.encodeToString(registrations))
    }

    /** (Re)opens the shared WebSocket against the current full set of registered topics - called
     * on every register/unregister, not just on failure, since ntfy has no way to add/remove a
     * topic from an already-open subscription. Closing with no registrations left rather than
     * holding an empty connection open is deliberate - nothing to receive, no reason to hold a
     * socket for it. */
    private fun connect(context: Context) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)

        val topics = load().values.map { it.topic }
        webSocket?.close(1000, "reconnecting with updated topic list")
        webSocket = null
        if (topics.isEmpty()) return

        // Whatever is causing the reconnect churn seen in live testing (see [client]'s own doc
        // comment), a bare resubscribe on every reconnect silently drops any message ntfy
        // received during the gap between the old socket closing and the new one opening - ntfy
        // has no way to know we missed anything, so it just moves on. `since` (ntfy's own replay
        // param, docs.ntfy.sh/subscribe/api/#poll-for-messages) asks it to replay anything
        // published at or after our last-seen event instead, so a reconnect - however brief -
        // can't silently swallow a real push the way it currently does.
        val sinceParam = lastEventId?.let { "?since=$it" }.orEmpty()
        val url = "wss://$NTFY_HOST/${topics.joinToString(",")}/ws$sinceParam"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(LOG_TAG, "ntfy relay connected (${topics.size} topic(s))")
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(context, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(LOG_TAG, "ntfy relay closing: code=$code reason=$reason")
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(LOG_TAG, "ntfy relay closed, reconnecting: code=$code reason=$reason")
                    scheduleReconnect(context)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(
                        LOG_TAG,
                        "ntfy relay connection failed, reconnecting: response=${response?.code} " +
                            "${response?.message}, headers=${response?.headers}",
                        t,
                    )
                    scheduleReconnect(context)
                }
            },
        )
    }

    private fun scheduleReconnect(context: Context) {
        if (!running) return
        handler.postDelayed({ connect(context) }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun handleMessage(context: Context, text: String) {
        val envelope = try {
            ServerJson.decodeFromString<NtfyEnvelope>(text)
        } catch (e: Exception) {
            // ntfy's own stream also sends non-"message" events ("open"/"keepalive") on the same
            // connection - anything that doesn't parse as a full envelope is expected, not an error.
            return
        }
        // Advance the replay watermark on every event, not just "message" ones - a keepalive/open
        // event still proves we were caught up as of that point, and using it means a subsequent
        // reconnect's `since` doesn't needlessly re-fetch messages we already saw.
        envelope.id?.let { lastEventId = it }

        if (envelope.event != "message") return
        val topic = envelope.topic ?: return

        val entry = load().entries.find { it.value.topic == topic }
        if (entry == null) {
            Log.w(LOG_TAG, "Received a message for an unregistered topic, ignoring")
            return
        }
        val (token, registration) = entry

        val intent = Intent(ACTION_MESSAGE)
            .setPackage(registration.packageName)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_BYTES_MESSAGE, decodePayload(envelope))
        context.sendBroadcast(intent)
    }

    private fun decodePayload(envelope: NtfyEnvelope): ByteArray {
        val message = envelope.message ?: return ByteArray(0)
        if (envelope.encoding != BASE64_ENCODING) return message.toByteArray(Charsets.UTF_8)
        return try {
            Base64.decode(message, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            message.toByteArray(Charsets.UTF_8)
        }
    }
}
