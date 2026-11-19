/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.feed

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

private val stringFlagCheckFingerprint =
    Fingerprint(
        strings = listOf("__fbt_null__"),
        returnType = "Ljava/lang/String;",
    )

private val booleanFlagCheckFingerprint =
    Fingerprint(
        classFingerprint = stringFlagCheckFingerprint,
        returnType = "Z",
        parameters = listOf("L", "J", "Z"),
    )

private val savedFeedPrefClassFingerprint =
    Fingerprint(
        strings = listOf("last_selected_feed_type"),
    )

private val getSavedFeedFingerprint =
    Fingerprint(
        classFingerprint = savedFeedPrefClassFingerprint,
        returnType = "Ljava/lang/String;",
        parameters = emptyList(),
    )

private val setSavedFeedFingerprint =
    Fingerprint(
        classFingerprint = savedFeedPrefClassFingerprint,
        returnType = "V",
        parameters = listOf("Ljava/lang/String;"),
    )

@Suppress("unused")
val defaultFollowingForYouPatch =
    bytecodePatch(
        name = "Start Home on Following & For You menu",
        description = "Opens Instagram directly into the Following feed on launch and adds For You to the Instagram logo dropdown alongside Following and Favorites.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // 1. Enable Instagram's native For You + Following picker MobileConfig flags for 452.1.0.53.64:
            //    0x810e4c00064e2fL (36325991576391215L) = remembered feed / switcher
            //    0x810e4c00004e29L (36325991575997993L) = For You logo picker
            val flagClass = booleanFlagCheckFingerprint.classDef
            val flagClassType = flagClass.type

            val flagHelper =
                ImmutableMethod(
                    flagClassType,
                    "pikoCheckHomeFeedFlag",
                    listOf(ImmutableMethodParameter("J", null, "specifier")),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        5,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            flagHelper.addInstructions(
                0,
                """
                const-wide v0, 0x810e4c00064e2fL
                cmp-long v2, p0, v0
                if-eqz v2, :match
                const-wide v0, 0x810e4c00004e29L
                cmp-long v2, p0, v0
                if-eqz v2, :match
                const/4 v0, 0x0
                return v0
                :match
                const/4 v0, 0x1
                return v0
                """.trimIndent(),
            )
            flagHelper.removeInstruction(flagHelper.implementation!!.instructions.size - 1)
            flagClass.methods.add(flagHelper)

            booleanFlagCheckFingerprint.method.addInstructions(
                0,
                """
                invoke-static/range {p2 .. p3}, $flagClassType->pikoCheckHomeFeedFlag(J)Z
                move-result v0
                if-eqz v0, :piko_feed_skip
                const/4 v0, 0x1
                return v0
                :piko_feed_skip
                nop
                """.trimIndent(),
            )

            // 2. Default every cold launch to FOLLOWING while letting the user switch to For You
            //    or Favorites during the session via the Instagram logo dropdown.
            val prefClass = savedFeedPrefClassFingerprint.classDef
            val prefClassType = prefClass.type

            prefClass.fields.add(
                ImmutableField(
                    prefClassType,
                    "pikoSessionFeed",
                    "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    null,
                ).toMutable(),
            )

            getSavedFeedFingerprint.method.addInstructions(
                0,
                """
                sget-object v0, $prefClassType->pikoSessionFeed:Ljava/lang/String;
                if-nez v0, :piko_has_feed
                const-string v0, "FOLLOWING"
                :piko_has_feed
                return-object v0
                """.trimIndent(),
            )

            setSavedFeedFingerprint.method.addInstructions(
                0,
                """
                sput-object p1, $prefClassType->pikoSessionFeed:Ljava/lang/String;
                """.trimIndent(),
            )
        }
    }
