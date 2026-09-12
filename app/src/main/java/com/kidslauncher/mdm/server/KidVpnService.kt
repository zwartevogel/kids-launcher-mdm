package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_VPN_FILTER
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.VPN_FILTER_NOTIFICATION_ID
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.pcap4j.packet.IpSelector
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.UnknownPacket
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Section

private const val LOG_TAG = "KidVpnService"

// RFC 5737 documentation-only range - guaranteed never routable on any real network, so it can't
// collide with anything the underlying WiFi/cellular network actually uses. Matches DNS66's own
// choice of fake DNS-server address for exactly this reason (see this repo's CLAUDE.md for the
// full design writeup - DNS66's real architecture, verified against its actual source rather than
// assumed, is what this whole approach is adapted from).
private const val VPN_INTERFACE_ADDRESS = "192.0.2.1"
private const val FAKE_DNS_SERVER = "192.0.2.2"
private const val MTU = 32767

/**
 * The launcher's on-device ad/content DNS filter - replaces the retired DoT-to-Pi Private DNS
 * approach (see CLAUDE.md) and the standalone Tailscale app's role in device-wide traffic.
 *
 * Architecture (adapted from DNS66's real implementation, not invented from scratch - see
 * CLAUDE.md): rather than capturing all device traffic (`0.0.0.0/0`), this advertises a single
 * fake DNS server address ([FAKE_DNS_SERVER], from a documentation-only IP range that can never
 * collide with a real network) via [Builder.addDnsServer] plus a host route just for that address.
 * Android's system resolver then directs all normal DNS resolution there, while every other kind
 * of traffic never matches any route this service adds and flows over the real network completely
 * untouched - no general-purpose NAT/TCP relay engine needed at all, which is what makes this
 * approach dramatically simpler (and lower-risk) than full-capture would be. The tradeoff: an app
 * that hardcodes its own arbitrary DNS server or does its own embedded DoH resolution bypasses
 * this - see [AppEnforcer.applyPrivateDnsLock]'s simplified form for why Private DNS stays locked
 * to Opportunistic (closing the most common version of that gap), and CLAUDE.md for the rest.
 *
 * Every DNS query that arrives is checked against [DnsFilterEngine]; blocked domains get a
 * synthesized NODATA response (empty answer, NOERROR + a negative-cache SOA - the same technique
 * DNS66 itself uses, chosen because it's well-handled by virtually every DNS client without the
 * retry storms NXDOMAIN sometimes provokes), allowed ones get forwarded to the admin-configured
 * public DoT resolver and the real response relayed back.
 */
class KidVpnService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        // Must be the first thing this service does, unconditionally - see CommandListenerService's
        // own established gotcha for this same API-34 requirement (this is also a Service).
        startForeground(VPN_FILTER_NOTIFICATION_ID, buildNotification())
        DnsFilterEngine.loadFromDisk(applicationContext)
        startVpn()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (pfd == null) startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("Kid Phone Filter")
                .addAddress(VPN_INTERFACE_ADDRESS, 32)
                .addDnsServer(FAKE_DNS_SERVER)
                .addRoute(FAKE_DNS_SERVER, 32)
                .setMtu(MTU)
                .setBlocking(true)
            val established = builder.establish()
            if (established == null) {
                // Happens if the user has since revoked VPN permission, or another VPN grabbed it
                // first - nothing more this service can do until re-invoked. Not thrown as an
                // exception since this is an expected, recoverable state, not a bug.
                Log.w(LOG_TAG, "VPN establish() returned null - permission revoked or another VPN active")
                return
            }
            pfd = established
            scope.launch { readLoop(established) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to establish VPN", e)
        }
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        // Multiple in-flight queries (each its own coroutine, see handlePacket) can complete and
        // write a response at the same time - guards against interleaved writes corrupting a
        // packet, even though a single write() call for a small buffer is usually atomic on its
        // own; cheap insurance given how hard a corrupted-packet bug would be to diagnose without
        // a way to reproduce it on demand.
        val writeLock = Any()
        val buffer = ByteArray(MTU)
        while (true) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "VPN interface read failed, stopping read loop", e)
                return
            }
            if (length <= 0) continue
            val packetData = buffer.copyOfRange(0, length)
            scope.launch { handlePacket(packetData, output, writeLock) }
        }
    }

    private fun handlePacket(data: ByteArray, output: FileOutputStream, writeLock: Any) {
        try {
            val ipPacket = IpSelector.newPacket(data, 0, data.size) as? IpV4Packet ?: return
            val udpPacket = ipPacket.payload as? UdpPacket ?: return
            if (udpPacket.header.dstPort.valueAsInt() != 53) return
            val query = udpPacket.payload?.rawData ?: return

            val message = try {
                Message(query)
            } catch (e: Exception) {
                return
            }
            val question = message.question ?: return
            val domain = question.name.toString(true)

            val category = DnsFilterEngine.classify(domain)
            val responseBytes = if (category != null) {
                BlockedEventLog.record(applicationContext, domain, category)
                buildBlockedResponse(message)
            } else {
                val upstream = LauncherPreferences.mdm().dnsUpstreamProvider() ?: "cloudflare"
                DnsFilterEngine.resolveUpstream(query, upstream, ::protect)
            }
            if (responseBytes == null) return

            val responsePacket = buildResponsePacket(ipPacket, udpPacket, responseBytes)
            synchronized(writeLock) {
                output.write(responsePacket.rawData)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to handle a DNS packet", e)
        }
    }

    /** NODATA response (NOERROR, empty answer, a negative-cache SOA in the authority section) -
     * see this class's doc comment for why this shape rather than NXDOMAIN. */
    private fun buildBlockedResponse(request: Message): ByteArray {
        val response = Message(request.header.id)
        response.header.setFlag(Flags.QR.toInt())
        if (request.header.getFlag(Flags.RD.toInt())) {
            response.header.setFlag(Flags.RD.toInt())
        }
        response.header.setFlag(Flags.RA.toInt())
        response.addRecord(request.question, Section.QUESTION)
        val zone = request.question.name
        response.addRecord(
            SOARecord(
                zone, DClass.IN, 300L,
                Name.fromConstantString("localhost."),
                Name.fromConstantString("admin.localhost."),
                1L, 300L, 300L, 300L, 300L,
            ),
            Section.AUTHORITY,
        )
        return response.toWire()
    }

    private fun buildResponsePacket(reqIp: IpV4Packet, reqUdp: UdpPacket, dnsResponse: ByteArray): IpV4Packet {
        val udpPayload = UnknownPacket.Builder().rawData(dnsResponse)
        val udpBuilder = UdpPacket.Builder(reqUdp)
            .srcPort(reqUdp.header.dstPort)
            .dstPort(reqUdp.header.srcPort)
            .srcAddr(reqIp.header.dstAddr)
            .dstAddr(reqIp.header.srcAddr)
            .payloadBuilder(udpPayload)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
        return IpV4Packet.Builder(reqIp)
            .srcAddr(reqIp.header.dstAddr)
            .dstAddr(reqIp.header.srcAddr)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
            .payloadBuilder(udpBuilder)
            .build()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_VPN_FILTER)
            .setSmallIcon(R.drawable.baseline_bug_report_24)
            .setContentTitle(getString(R.string.notification_vpn_filter_title))
            .setContentText(getString(R.string.notification_vpn_filter_text))
            .setOngoing(true)
            .setSilent(true)
            .build()

    override fun onRevoke() {
        // The user (or another VPN app taking over) revoked our VPN permission directly - stop
        // cleanly rather than leaving a dangling foreground service with a dead tunnel.
        stopSelfCleanly()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopSelfCleanly()
        super.onDestroy()
    }

    private fun stopSelfCleanly() {
        scope.cancel()
        try {
            pfd?.close()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to close VPN interface", e)
        }
        pfd = null
    }

    companion object {
        /**
         * Call once at app startup - safe to call repeatedly, Android no-ops a redundant start
         * (matches [CommandListenerService.start]'s own established pattern). Unconditional, not
         * gated on enrollment/device-owner state: [startVpn]'s `establish()` already fails soft
         * (logs and returns) if VPN consent isn't available yet - e.g. on first-ever launch, before
         * [AppEnforcer.applyVpnRestrictions] has had a chance to grant it via
         * `setAlwaysOnVpnPackage` on the first sync. From that point on Android's own always-on-VPN
         * management takes over keeping this service running/restarted as needed, independent of
         * this call - this just closes the gap between a fresh app launch and the first successful
         * sync, so filtering doesn't need to wait on that.
         */
        fun start(context: Context) {
            val intent = Intent(context, KidVpnService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the service if running - safe to call whether or not it's currently up. Used by
         * [AppEnforcer.applyVpnRestrictions] when a parent turns filtering off for this device. */
        fun stop(context: Context) {
            context.stopService(Intent(context, KidVpnService::class.java))
        }
    }
}
