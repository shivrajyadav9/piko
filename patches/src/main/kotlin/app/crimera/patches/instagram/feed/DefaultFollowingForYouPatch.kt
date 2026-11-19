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
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

private const val REMEMBERED_FEED_FLAG = 0x810e4c00064e2fL // 36325991576391215L
private const val FOR_YOU_PICKER_FLAG = 0x810e4c00004e29L // 36325991575997993L

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
            // 1. Patch each direct read of REMEMBERED_FEED_FLAG and FOR_YOU_PICKER_FLAG
            //    (avoids touching the core MobileConfig class or conflicting with Piko HookFlags).
            var patchedReads = 0
            classes.forEach { classDef ->
                classDef.methods.forEach { method ->
                    val insns = method.implementation?.instructions?.toList() ?: return@forEach
                    val insertPoints = mutableListOf<Pair<Int, Int>>()

                    for (i in insns.indices) {
                        val insn = insns[i]
                        if (insn.opcode == Opcode.CONST_WIDE && insn is WideLiteralInstruction) {
                            val lit = insn.wideLiteral
                            if (lit == REMEMBERED_FEED_FLAG || lit == FOR_YOU_PICKER_FLAG) {
                                val end = minOf(insns.size - 1, i + 6)
                                for (j in (i + 1) until end) {
                                    val call = insns[j]
                                    val next = insns[j + 1]
                                    val ref = (call as? ReferenceInstruction)?.reference as? MethodReference
                                    if (ref != null && ref.returnType == "Z" && next.opcode == Opcode.MOVE_RESULT) {
                                        val resReg = (next as OneRegisterInstruction).registerA
                                        insertPoints.add((j + 1) to resReg)
                                        break
                                    }
                                }
                            }
                        }
                    }

                    if (insertPoints.isNotEmpty()) {
                        val mutableMethod = proxy(classDef).mutableClass.methods.first {
                            it.name == method.name &&
                                it.returnType == method.returnType &&
                                it.parameterTypes == method.parameterTypes
                        }
                        for ((moveResultIdx, resReg) in insertPoints.asReversed()) {
                            val constInsn = if (resReg < 16) "const/4 v$resReg, 0x1" else "const/16 v$resReg, 0x1"
                            mutableMethod.addInstructions(moveResultIdx + 1, constInsn)
                            patchedReads++
                        }
                    }
                }
            }

            if (patchedReads == 0) {
                throw PatchException("Failed to find Home feed picker MobileConfig flag reads")
            }

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

            val getSessionFeedHelper =
                ImmutableMethod(
                    prefClassType,
                    "pikoGetSessionFeed",
                    emptyList(),
                    "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        1,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            getSessionFeedHelper.addInstructions(
                0,
                """
                sget-object v0, $prefClassType->pikoSessionFeed:Ljava/lang/String;
                if-nez v0, :piko_has_feed
                const-string v0, "FOLLOWING"
                :piko_has_feed
                return-object v0
                """.trimIndent(),
            )
            getSessionFeedHelper.removeInstruction(getSessionFeedHelper.implementation!!.instructions.size - 1)
            prefClass.methods.add(getSessionFeedHelper)

            getSavedFeedFingerprint.method.addInstructions(
                0,
                """
                invoke-static {}, $prefClassType->pikoGetSessionFeed()Ljava/lang/String;
                move-result-object v0
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
