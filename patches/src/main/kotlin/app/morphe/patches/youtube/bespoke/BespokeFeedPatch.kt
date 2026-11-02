package app.morphe.patches.youtube.bespoke

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

/**
 * Bespoke feed: a HackerTube-style discovery feed with an on-device recommender.
 *
 * Phase 0: this patch only adds an informational row to the Feed settings screen,
 * to prove the fork builds, releases and installs end to end.
 */
@Suppress("unused")
val bespokeFeedPatch = bytecodePatch(
    name = "Bespoke feed",
    description = "Adds a curated feed of videos recommended on Hacker News, ranked on your device.",
) {
    dependsOn(settingsPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            NonInteractivePreference("bespoke_feed_status"),
        )
    }
}
