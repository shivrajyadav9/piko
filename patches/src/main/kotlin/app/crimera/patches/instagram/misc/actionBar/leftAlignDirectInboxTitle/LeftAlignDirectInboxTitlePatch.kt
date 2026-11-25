/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.actionBar.leftAlignDirectInboxTitle

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.addInstagramBooleanSettingHelpers
import app.crimera.patches.instagram.utils.registerMyPatchesToggle
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

private const val IGDS_ACTION_BAR_CLASS = "Lcom/instagram/igds/components/actionbar/IgdsActionBar;"

private val inboxActionBarMarkFingerprint =
    Fingerprint(
        strings = listOf("PrebindActionBar"),
        filters = listOf(checkCast(IGDS_ACTION_BAR_CLASS)),
    )

private val igdsActionBarBindTitleFingerprint =
    Fingerprint(
        returnType = "V",
        filters =
            listOf(
                methodCall(name = "intValue", returnType = "I"),
                opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
            ),
        custom = { method, classDef ->
            classDef.type == IGDS_ACTION_BAR_CLASS &&
                method.implementation?.instructions?.any { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null && (
                        (ref.definingClass == "Ljava/lang/Math;" && ref.name == "max") ||
                            ref.name == "setCompoundDrawablesWithIntrinsicBounds"
                    )
                } == true
        },
    )

@Suppress("unused")
val leftAlignDirectInboxTitlePatch =
    bytecodePatch(
        name = "Left-align Direct inbox username",
        description = "Moves the username in the Direct messages inbox header from the center to the top-left corner.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val actionBarClass = igdsActionBarBindTitleFingerprint.classDef
        addInstagramBooleanSettingHelpers(actionBarClass, "pikoLeftAlignDirectSetting", "pikoGetLeftAlignDirectSetting", "pikoIsLeftAlignDirectEnabled", "piko_left_align_direct_inbox_title", defaultValue = true, restartRequired = false)
        
            // 1. Add helper on IgdsActionBar to tag the Direct inbox instance.
            val markHelper =
                ImmutableMethod(
                    IGDS_ACTION_BAR_CLASS,
                    "pikoMarkDirectInboxActionBar",
                    listOf(ImmutableMethodParameter("Landroid/view/View;", null, "view")),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        3,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            markHelper.addInstructions(
                0,
                """
                const v0, 0x70696b6f
                sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-virtual {p0, v0, v1}, Landroid/view/View;->setTag(ILjava/lang/Object;)V
                return-void
                """.trimIndent(),
            )
            markHelper.removeInstruction(markHelper.implementation!!.instructions.size - 1)
            actionBarClass.methods.add(markHelper)

            // 2. Add helper on IgdsActionBar to force START (0) alignment when tagged.
            val normalizeHelper =
                ImmutableMethod(
                    IGDS_ACTION_BAR_CLASS,
                    "pikoNormalizeTitleAlignment",
                    listOf(
                        ImmutableMethodParameter("Landroid/view/View;", null, "view"),
                        ImmutableMethodParameter("I", null, "alignment"),
                    ),
                    "I",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        4,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            normalizeHelper.addInstructions(
                0,
                """
                        invoke-static {}, Lcom/instagram/igds/components/actionbar/IgdsActionBar;->pikoIsLeftAlignDirectEnabled()Z
            move-result v0
            if-eqz v0, :keep
            const v0, 0x70696b6f
                invoke-virtual {p0, v0}, Landroid/view/View;->getTag(I)Ljava/lang/Object;
                move-result-object v1
                if-eqz v1, :keep
                const/4 p1, 0x0
                :keep
                return p1
                """.trimIndent(),
            )
            normalizeHelper.removeInstruction(normalizeHelper.implementation!!.instructions.size - 1)
            actionBarClass.methods.add(normalizeHelper)

            // 3. Tag the Direct inbox IgdsActionBar in PrebindActionBar.
            inboxActionBarMarkFingerprint.let { match ->
                val castIndex = match.instructionMatches.first().index
                val actionBarReg = match.instructionMatches.first().getInstruction<OneRegisterInstruction>().registerA
                match.method.addInstructions(
                    castIndex + 1,
                    "invoke-static/range {v$actionBarReg .. v$actionBarReg}, $IGDS_ACTION_BAR_CLASS->pikoMarkDirectInboxActionBar(Landroid/view/View;)V",
                )
            }

            // 4. Override alignment to START (0) in IgdsActionBar.bindTitle when tagged.
            igdsActionBarBindTitleFingerprint.let { match ->
                val invokeMatch = match.instructionMatches[0]
                val moveResultMatch = match.instructionMatches[1]
                val moveResultIndex = moveResultMatch.index
                val intValReg = moveResultMatch.getInstruction<OneRegisterInstruction>().registerA
                val bindMethod = match.method
                val p0Reg = bindMethod.implementation!!.registerCount - 2

                val hookSmali =
                    if (p0Reg < 16 && intValReg < 16) {
                        """
                        invoke-static {p0, v$intValReg}, $IGDS_ACTION_BAR_CLASS->pikoNormalizeTitleAlignment(Landroid/view/View;I)I
                        move-result v$intValReg
                        """.trimIndent()
                    } else {
                        val freeReg = invokeMatch.getInstruction<FiveRegisterInstruction>().registerC
                        if (freeReg >= 16 || freeReg == intValReg || intValReg >= 16) {
                            throw PatchException("Unsupported register layout in IgdsActionBar title binding (p0=$p0Reg, intVal=$intValReg, free=$freeReg)")
                        }
                        """
                        move-object/from16 v$freeReg, p0
                        invoke-static {v$freeReg, v$intValReg}, $IGDS_ACTION_BAR_CLASS->pikoNormalizeTitleAlignment(Landroid/view/View;I)I
                        move-result v$intValReg
                        """.trimIndent()
                    }

                bindMethod.addInstructions(moveResultIndex + 1, hookSmali)
            }
            finalize {
                registerMyPatchesToggle("pikoAddLeftAlignDirectToggle", "Left-align Direct inbox username", "Move the Direct inbox header username to the top-left corner", "Lcom/instagram/igds/components/actionbar/IgdsActionBar;", "pikoGetLeftAlignDirectSetting")
                    
            }
    }
