package app.morphe.patches.youtube.bespoke

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.youtube.video.series.BrowseFragmentFingerprint
import app.morphe.patches.youtube.video.series.BrowseRouteFingerprint
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.hookNavigationButtonCreated
import app.morphe.patches.youtube.misc.navigation.PivotBarDispatchTouchEventFingerprint
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.newIntent
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

/**
 * Must match BespokeFeed.BESPOKE_FEED_INTENT in the extension.
 */
private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/bespoke/BespokeFeed;"

internal const val BESPOKE_FEED_INTENT = "bespoke_feed_intent"

/**
 * Bespoke feed: a HackerTube-style discovery feed of videos recommended on Hacker News.
 *
 * The feed screen is hosted by the same activity as the Morphe settings
 * (see YouTubeActivityHook.initialize), so no extra bytecode hooks are needed.
 * Entry points: the Shorts tab of the navigation bar is replaced by a HackerTube tab,
 * and a row in Settings > Morphe > Feed.
 */
@Suppress("unused")
val bespokeFeedPatch = bytecodePatch(
    name = "Bespoke feed",
    description = "Replaces the Shorts tab with a HackerTube feed of videos recommended on Hacker News.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        navigationBarHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            IntentPreference(
                key = "bespoke_feed_open",
                intent = newIntent(BESPOKE_FEED_INTENT),
            ),
        )

        hookNavigationButtonCreated(EXTENSION_CLASS)


        // Show the feed inside YouTube's main screen, as a browse page, so the player and
        // miniplayer work like on any other tab. The HackerTube tab opens a page whose
        // browse fragment view is replaced by the feed (see BespokeFeed.wrapBrowseView).
        BrowseFragmentFingerprint.let { browse ->
            val fragment = browse.classDef
            val create = browse.method
            val diagnostic = browse.instructionMatches.single().index
            val endpointIndex = create.indexOfFirstInstructionReversedOrThrow(
                diagnostic,
                fieldAccess(definingClass = "this", type = "L"),
            )
            val endpointField = create.getInstruction(endpointIndex).getReference<FieldReference>()!!
            val routeMethod = BrowseRouteFingerprint(endpointField.type).matchAll()
                .map { it.instructionMatches.first().getMethodCalled() }
                .distinctBy { it.toString() }
                .single()

            fragment.methods.add(
                ImmutableMethod(
                    fragment.type,
                    "patch_bespokeFeedView",
                    listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
                    "Landroid/view/View;",
                    AccessFlags.PUBLIC.value,
                    null,
                    null,
                    MutableMethodImplementation(3),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $endpointField
                            invoke-static { v0 }, $routeMethod
                            move-result-object v0
                            invoke-static { p0, p1, v0 }, $EXTENSION_CLASS->wrapBrowseView(Ljava/lang/Object;Landroid/view/View;Ljava/lang/String;)Landroid/view/View;
                            move-result-object v0
                            return-object v0
                        """
                    )
                }
            )

            // The page view (with its toolbar) is the result of the last View -> View call.
            val contentIndex = create.indexOfFirstInstructionReversedOrThrow(
                methodCall(
                    parameters = listOf("Landroid/view/View;"),
                    returnType = "Landroid/view/View;",
                )
            )
            val instance = create.getInstruction(contentIndex).registersUsed.first()
            val view = create.getInstruction<OneRegisterInstruction>(contentIndex + 1).registerA
            if (view == instance) {
                throw PatchException("Bespoke feed page wrapper overwrites the fragment register")
            }

            create.addInstructions(
                contentIndex + 2,
                """
                    invoke-virtual { v$instance, v$view }, ${fragment.type}->patch_bespokeFeedView(Landroid/view/View;)Landroid/view/View;
                    move-result-object v$view
                """
            )
        }

        // Taps on the HackerTube (Shorts) tab are consumed by the navigation bar
        // before YouTube sees them, and open the feed instead.
        PivotBarDispatchTouchEventFingerprint.method.let { original ->
            val method = if (original.implementation!!.registerCount <= original.numberOfParameterRegisters) {
                original.cloneParameters()
            } else {
                original
            }
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->navigationBarTouched(Landroid/view/MotionEvent;)Z
                    move-result v0
                    if-eqz v0, :not_consumed
                    return v0
                    :not_consumed
                    nop
                """
            )
        }
    }
}
