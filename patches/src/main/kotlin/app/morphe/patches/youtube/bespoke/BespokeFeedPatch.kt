package app.morphe.patches.youtube.bespoke

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.newIntent
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

/**
 * Must match BespokeFeed.BESPOKE_FEED_INTENT in the extension.
 */
internal const val BESPOKE_FEED_INTENT = "bespoke_feed_intent"

/**
 * Bespoke feed: a HackerTube-style discovery feed of videos recommended on Hacker News.
 *
 * The feed screen is hosted by the same activity as the Morphe settings
 * (see YouTubeActivityHook.initialize), so no extra bytecode hooks are needed.
 * Phase 1 entry point: a row in Settings > Morphe > Feed.
 */
@Suppress("unused")
val bespokeFeedPatch = bytecodePatch(
    name = "Bespoke feed",
    description = "Adds a curated feed of videos recommended on Hacker News (HackerTube data).",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            IntentPreference(
                key = "bespoke_feed_open",
                intent = newIntent(BESPOKE_FEED_INTENT),
            ),
        )
    }
}
