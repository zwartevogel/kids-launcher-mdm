package com.kidslauncher.mdm.preferences;

import java.util.HashMap;
import java.util.Set;

import com.kidslauncher.mdm.R;
import com.kidslauncher.mdm.server.LockReason;
import com.kidslauncher.mdm.preferences.serialization.MapAbstractAppInfoStringPreferenceSerializer;
import com.kidslauncher.mdm.preferences.serialization.SetAbstractAppInfoPreferenceSerializer;
import com.kidslauncher.mdm.preferences.theme.ColorTheme;
import eu.jonahbauer.android.preference.annotations.Preference;
import eu.jonahbauer.android.preference.annotations.PreferenceGroup;
import eu.jonahbauer.android.preference.annotations.Preferences;

@Preferences(
        name = "com.kidslauncher.mdm.preferences.LauncherPreferences",
        makeFile = true,
        r = R.class,
        value = {
                @PreferenceGroup(name = "internal", prefix = "settings_internal_", suffix = "_key", value = {
                        // set after first launch
                        @Preference(name = "started", type = boolean.class, defaultValue = "false"),
                        @Preference(name = "started_time", type = long.class),
                        // see PREFERENCE_VERSION in com.kidslauncher.mdm.preferences.Preferences.kt
                        @Preference(name = "version_code", type = int.class, defaultValue = "-1"),
                }),
                @PreferenceGroup(name = "apps", prefix = "settings_apps_", suffix = "_key", value = {
                        @Preference(name = "hidden", type = Set.class, serializer = SetAbstractAppInfoPreferenceSerializer.class),
                        @Preference(name = "custom_names", type = HashMap.class, serializer = MapAbstractAppInfoStringPreferenceSerializer.class),
                }),
                @PreferenceGroup(name = "theme", prefix = "settings_theme_", suffix = "_key", value = {
                        @Preference(name = "color_theme", type = ColorTheme.class, defaultValue = "DEFAULT"),
                }),
                @PreferenceGroup(name = "display", prefix = "settings_display_", suffix = "_key", value = {
                        @Preference(name = "rotate_screen", type = boolean.class, defaultValue = "true"),
                }),
                @PreferenceGroup(name = "minimalist", prefix = "settings_minimalist_", suffix = "_key", value = {
                        @Preference(name = "apps", type = Set.class, serializer = SetAbstractAppInfoPreferenceSerializer.class),
                }),
                @PreferenceGroup(name = "mdm", prefix = "settings_mdm_", suffix = "_key", value = {
                        @Preference(name = "server_url", type = String.class),
                        // Bearer credential returned by POST /api/devices/enroll - the one-shot
                        // enrollment code used to get it is never itself persisted.
                        @Preference(name = "device_token", type = String.class),
                        @Preference(name = "enrolled", type = boolean.class, defaultValue = "false"),
                        @Preference(name = "kid_mode_policy", type = String.class),
                        // Current lock decision, persisted so LockActivity/HomeActivity can react
                        // via the usual SharedPreferences-listener pattern instead of a broadcast.
                        @Preference(name = "lock_reason", type = LockReason.class, defaultValue = "NONE"),
                        // Computed handoff flag: true once AppEnforcer has actually configured DPM
                        // lock-task state (server-authoritative via PolicyResponse.kioskDesired -
                        // there is no local toggle). HomeActivity reads this to decide whether to
                        // call startLockTask()/stopLockTask().
                        @Preference(name = "kiosk_enabled", type = boolean.class, defaultValue = "false"),
                        // Cached from the last successful policy sync (PolicyResponse.overridePinHash/
                        // Salt) so LockActivity can verify a locally-entered offline override PIN
                        // with zero network at all. Null means no PIN is configured for this device.
                        @Preference(name = "override_pin_hash", type = String.class),
                        @Preference(name = "override_pin_salt", type = String.class),
                        // Set locally by LockActivity the moment a correct offline override PIN is
                        // entered; AppEnforcer.apply() treats the policy as fully open while this is
                        // true and not yet expired. Cleared automatically on the next successful
                        // sync (real server contact restored) or once offline_override_expires_at
                        // has passed, whichever comes first - see AppEnforcer.isOfflineOverrideActive.
                        @Preference(name = "offline_override_active", type = boolean.class, defaultValue = "false"),
                        @Preference(name = "offline_override_expires_at", type = long.class, defaultValue = "0"),
                        // Purely local brute-force throttling for the offline PIN entry dialog -
                        // never synced to the server, since this must keep working with zero network.
                        @Preference(name = "offline_override_failed_attempts", type = int.class, defaultValue = "0"),
                        @Preference(name = "offline_override_locked_until", type = long.class, defaultValue = "0"),
                        // Set by LockActivity when the offline override is used; reported once via
                        // StatusReportRequest.offlineOverrideUsed on the next successful sync, then
                        // cleared - so the parent notices even though the event itself was offline.
                        @Preference(name = "offline_override_used_pending_report", type = boolean.class, defaultValue = "false"),
                        // Manual emergency kill-switch, gated behind the same Settings PIN as
                        // enroll/sync - unlike offline_override_active this does NOT auto-clear on
                        // the next successful sync or after any timer; it stays off until a parent
                        // deliberately re-enables it, so it's a safe escape hatch if a policy or
                        // build ever ships a breaking restriction. See AppEnforcer.apply and
                        // MdmSyncWorker's lock-reason computation.
                        @Preference(name = "restrictions_paused", type = boolean.class, defaultValue = "false"),
                        // JSON blob: per-package {lastInstalledTag, lastFailedTag} for apps
                        // tracked from GitHub Releases (see server.TrackedAppUpdateState) - stops
                        // an already-installed or already-failed release from being re-downloaded
                        // and re-attempted every 2-minute sync forever.
                        @Preference(name = "tracked_app_update_state", type = String.class),
                        // Throttles active location fixes (LocateCommands.currentLocation) - an
                        // active fetch shows Android's location-in-use indicator and visibly slows
                        // the sync it runs in, so it shouldn't fire on every single 2-minute/manual
                        // sync. 0 means "never fetched", always due.
                        @Preference(name = "last_active_location_fetch_at_ms", type = long.class, defaultValue = "0"),
                        // The actual last fix obtained (see server.LocateCommands), so a throttled-
                        // skip sync can hand this back without touching LocationManager at all -
                        // even the passive getLastKnownLocation() read triggers the location-in-use
                        // indicator, not just an active fetch.
                        @Preference(name = "cached_location_json", type = String.class),
                        // Admin-configured public DoT upstream ("cloudflare" | "quad9") for
                        // KidVpnService's on-device filter - cached from the last successful
                        // policy sync (PolicyResponse.dnsUpstreamProvider) so the VPN service can
                        // read it without needing to touch the network itself.
                        @Preference(name = "dns_upstream_provider", type = String.class, defaultValue = "\"cloudflare\""),
                        // BlockedEventLog's own persisted queue - see that class. Separate from
                        // tracked_app_update_state/cached_location_json above since it's a list,
                        // not a single blob, and gets drained (not just replaced) each sync.
                        @Preference(name = "blocked_dns_event_queue_json", type = String.class),
                        // Cached from the last successful policy sync (PolicyResponse.vpnFilterEnabled)
                        // so Application.onCreate's cold-start KidVpnService.start call - which runs
                        // before any policy has ever been fetched - knows whether to start the service
                        // at all. See AppEnforcer.applyVpnRestrictions, the only writer.
                        @Preference(name = "vpn_filter_enabled", type = boolean.class, defaultValue = "true"),
                        // Off by default - a parent has to explicitly opt in from Settings before
                        // this app starts advertising itself as a UnifiedPush distributor at all
                        // (see server.UnifiedPushRegistrationReceiver, enabled/disabled at runtime
                        // via PackageManager.setComponentEnabledSetting). Mirrors vpn_filter_enabled's
                        // "cached so a cold start before any Settings interaction knows what to do"
                        // shape, even though this one's purely local (no server-side equivalent).
                        @Preference(name = "unifiedpush_distributor_enabled", type = boolean.class, defaultValue = "false"),
                        // JSON blob: token -> {packageName, topic} for every app currently registered
                        // with this distributor (see server.UnifiedPushRelay) - same "small JSON blob
                        // in one preference" shape as tracked_app_update_state above. The topic is
                        // this app's own generated ntfy.sh topic name for that registration, not
                        // anything the registering app ever sees directly - it only ever gets the
                        // full https://ntfy.sh/<topic> endpoint URL via NEW_ENDPOINT.
                        @Preference(name = "unifiedpush_registrations", type = String.class),
                        // Highest kids-mdm-im journal `_id` already forwarded to the server (see
                        // server.JournalSync) - the provider's own pull-sync cursor, 0 means
                        // "never synced, start from the beginning". Only advanced after the
                        // server confirms a batch landed, so a failed upload can't lose entries.
                        @Preference(name = "journal_sync_since_id", type = long.class, defaultValue = "0"),
                        // Same cursor concept as journal_sync_since_id above, but for the browser
                        // fork's own history journal provider (see server.BrowserHistorySync).
                        // Independent value since the two providers/apps have unrelated _id
                        // sequences.
                        @Preference(name = "browser_history_sync_since_id", type = long.class, defaultValue = "0"),
                }),
        })
public final class LauncherPreferences$Config {
}
