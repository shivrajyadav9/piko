/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.feed

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.addInstagramBooleanSettingHelpers
import app.crimera.patches.instagram.utils.registerMyPatchesToggle
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

var resolvedPrefClassType: String? = null
        execute {
val prefClass = mutableClassDefBy(savedFeedPrefClassFingerprint.classDef)
            val prefClassType = prefClass.type
            resolvedPrefClassType = prefClassType
            addInstagramBooleanSettingHelpers(prefClass, "pikoDefaultFollowingSetting", "pikoGetDefaultFollowingSetting", "pikoIsDefaultFollowingEnabled", "piko_default_following_for_you", defaultValue = true, restartRequired = true)

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
                         mutableMethod.addInstructions(
                                moveResultIdx + 1,
                                """
                                  invoke-static/range {v$resReg .. v$resReg}, $prefClassType->pikoOrDefaultFollowingEnabled(Z)Z
                                  move-result v$resReg
                                """,
                            )
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
         
            prefClass.staticFields.add(
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
        val orDefaultFollowingHelper =
            ImmutableMethod(
                prefClassType, "pikoOrDefaultFollowingEnabled", listOf(ImmutableMethodParameter("Z", null, null)), "Z", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)).toMutable()
            orDefaultFollowingHelper.addInstructions(0, """
            if-nez p0, :piko_ret_true
invoke-static {}, $prefClassType->pikoIsDefaultFollowingEnabled()Z
move-result p0
return p0
:piko_ret_true
const/4 v0, 0x1
return v0
""".trimIndent())
            orDefaultFollowingHelper.removeInstruction(orDefaultFollowingHelper.implementation!!.instructions.size - 1)
prefClass.methods.add(orDefaultFollowingHelper)

            getSavedFeedFingerprint.method.addInstructions(
                0,
                """
            invoke-static {}, $prefClassType->pikoIsDefaultFollowingEnabled()Z
            move-result v0
            if-eqz v0, :piko_stock_get
                invoke-static {}, $prefClassType->pikoGetSessionFeed()Ljava/lang/String;
                move-result-object v0
                return-object v0
            :piko_stock_get
nop
                """.trimIndent(),
            )

            setSavedFeedFingerprint.method.addInstructions(
                0,
                """
            invoke-static {}, $prefClassType->pikoIsDefaultFollowingEnabled()Z
            move-result v0
            if-eqz v0, :piko_stock_set
               sput-object p1, $prefClassType->pikoSessionFeed:Ljava/lang/String;
               return-void
:piko_stock_set
nop
                """.trimIndent(),
            )

       val hookedResetMethods = mutableSetOf<String>()
       listOf("Lcom/instagram/mainactivity/InstagramMainActivity;", "Lcom/instagram/mainactivity/MainActivity;").forEach { activityType ->
           val found = mutableSetOf<String>()
           var cur = classDefByOrNull(activityType)
           while (cur != null && found.size < 3) {
              
               val cls = cur
                               val mutCur = mutableClassDefBy(cls)
                        listOf(
                            Triple("onCreate", listOf("Landroid/os/Bundle;"), "oncreate"),
                            Triple("onDestroy", emptyList(), "ondestroy"),
                            Triple("finish", emptyList(), "finish"),
                            
                        ).forEach { (methodName, params, tag) ->
                            if (found.contains(methodName)) return@forEach
                            mutCur.methods.firstOrNull {
                                it.implementation != null && it.name == methodName && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == params
                                }?.let { method ->
                                found.add(methodName)
                                if (hookedResetMethods.add("${cls.type}->$methodName")) {
                                    method.addInstructions(
                                        0,
                                        """
                                        instance-of v0, p0, Lcom/instagram/mainactivity/InstagramMainActivity;
                                        if-eqz v0, :piko_skip_$tag
                                        const/4 v0, 0x0
                                        sput-object v0, $prefClassType->pikoSessionFeed:Ljava/lang/String;
                                        :piko_skip_$tag
                                        nop
                                        
                                "".trimIndent(),
                     )
                     }
                     } }
                     cur = cls.superclass?.let { classDefByOrNull(it) }
                     } }
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
            invoke-static {}, $prefClassType->pikoIsDefaultFollowingEnabled()Z
            move-result v0
            if-eqz v0, :piko_show_name
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

    finalize {
        resolvedPrefClassType?.let { owner ->
            registerMyPatchesToggle(
                "pikoAddDefaultFollowingToggle",
                "Start Home on Following & For You",
                "Open Home on Following and add For You to the logo dropdown (requires restart)",
                owner,
                "pikoGetDefaultFollowingSetting",
            )
        }
    }
    }
