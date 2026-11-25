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
private const val REMEMBERED_FEED_FLAG_456 = 0x810dc800064de4L
private const val FOR_YOU_PICKER_FLAG_456 = 0x810dc800004ddeL
private const val TITLE_SWITCHER_CLASS = "Lcom/instagram/actionbar/ActionBarTitleViewSwitcher;"

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
        description = "Opens Instagram directly into the Following feed on launch, adds For You to the Instagram logo dropdown alongside Following and Favorites, and keeps the Instagram logo in the Home header.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // 1. Patch each direct read of REMEMBERED_FEED_FLAG and FOR_YOU_PICKER_FLAG.
            var patchedReads = 0
            classDefForEach { classDef ->
                classDef.methods.forEach { method ->
                    val insns = method.implementation?.instructions?.toList() ?: return@forEach
                    val insertPoints = mutableListOf<Pair<Int, Int>>()

                    for (i in insns.indices) {
                        val insn = insns[i]
                        if (insn.opcode == Opcode.CONST_WIDE && insn is WideLiteralInstruction) {
                            val lit = insn.wideLiteral
                            if (lit == REMEMBERED_FEED_FLAG || lit == FOR_YOU_PICKER_FLAG || lit == REMEMBERED_FEED_FLAG_456 || lit == FOR_YOU_PICKER_FLAG_456 ){
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
                        val mutableMethod = mutableClassDefBy(classDef).methods.first {
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
                return-void
                """.trimIndent(),
            )

        listOf(
            "Lcom/instagram/mainactivity/InstagramMainActivity;",
            "Lcom/instagram/mainactivity/MainActivity;",
        ).forEach { activityType ->
            classDefByOrNull(activityType)?.let { activityClass ->
                mutableClassDefBy(activityClass).methods.firstOrNull {
                    it.name == "onCreate" &&
                        it.returnType == "V" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;")
                }?.addInstructions(
                    0,
                    """
                    if-nez p1, :piko_keep_feed
                    const/4 v0, 0x0
                    sput-object v0, $prefClassType->pikoSessionFeed:Ljava/lang/String;
                    :piko_keep_feed
                    """.trimIndent(),
                )
            }
        }

            // 3. Keep the Instagram logo in the Home header instead of replacing it with "Following"/"For You".
            val switcherClass = mutableClassDefBy(TITLE_SWITCHER_CLASS)
            val labelMethod = switcherClass.methods.firstOrNull {
                !AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Z", "Ljava/lang/String;")
            } ?: throw PatchException("Could not find label method in $TITLE_SWITCHER_CLASS")

            val logoMethod = switcherClass.methods.firstOrNull {
                !AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z")
            } ?: throw PatchException("Could not find logo method in $TITLE_SWITCHER_CLASS")

            val keepLogoHelper =
                ImmutableMethod(
                    TITLE_SWITCHER_CLASS,
                    "pikoKeepLogo",
                    listOf(ImmutableMethodParameter("Landroid/view/View;", null, "view")),
                    "I",
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

            keepLogoHelper.addInstructions(
                0,
                """
                invoke-virtual {p0}, Landroid/view/View;->getParent()Landroid/view/ViewParent;
                move-result-object v0
                const/4 v1, 0x0
                :loop
                if-eqz v0, :not_home
                const/4 v2, 0x6
                if-ge v1, v2, :not_home
                invoke-virtual {v0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
                move-result-object v2
                invoke-virtual {v2}, Ljava/lang/Class;->getName()Ljava/lang/String;
                move-result-object v2
                const-string v3, "instagram.features.feed.mainfeed.actionbar.MainFeedActionBar"
                invoke-virtual {v3, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v2
                if-nez v2, :is_home
                invoke-interface {v0}, Landroid/view/ViewParent;->getParent()Landroid/view/ViewParent;
                move-result-object v0
                add-int/lit8 v1, v1, 0x1
                goto :loop
                :is_home
                instance-of v0, p0, Landroid/widget/ViewAnimator;
                if-eqz v0, :show_logo
                check-cast p0, Landroid/widget/ViewAnimator;
                invoke-virtual {p0}, Landroid/widget/ViewAnimator;->getDisplayedChild()I
                move-result v0
                if-nez v0, :show_logo
                const/4 v0, 0x2
                return v0
                :show_logo
                const/4 v0, 0x1
                return v0
                :not_home
                const/4 v0, 0x0
                return v0
                """.trimIndent(),
            )
            keepLogoHelper.removeInstruction(keepLogoHelper.implementation!!.instructions.size - 1)
            switcherClass.methods.add(keepLogoHelper)

            labelMethod.addInstructions(
                0,
                """
                invoke-static/range {p0 .. p0}, $TITLE_SWITCHER_CLASS->pikoKeepLogo(Landroid/view/View;)I
                move-result v0
                if-eqz v0, :piko_show_name
                add-int/lit8 v0, v0, -0x1
                if-nez v0, :piko_logo_shown
                const/4 v0, 0x0
                invoke-virtual {p0, v0, v0, v0, p1}, $TITLE_SWITCHER_CLASS->${logoMethod.name}(Ljava/lang/String;Ljava/lang/String;ZZ)V
                :piko_logo_shown
                return-void
                :piko_show_name
                nop
                """.trimIndent(),
            )
        }
    }
