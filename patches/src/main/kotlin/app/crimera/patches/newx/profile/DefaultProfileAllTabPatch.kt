package app.crimera.patches.newx.profile

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val ALL_POSTS_TAB_SWITCH = "x_lite_profile_all_posts_tab_enabled"

@Suppress("unused")
val defaultProfileAllTabPatch = bytecodePatch(
    name = "Default profile tab to All",
    description = "Sets the default selection in the profile Posts/Tweets dropdown menu to All instead of Tweets/Posts.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
        // 1. Locate profile tab enum (Lcom/x/profile/l0;) containing All, Posts, and Highlights
        var profileTabEnum: String? = null
        classDefForEach { cls ->
            if (profileTabEnum != null) return@classDefForEach
            if (!cls.type.startsWith("Lcom/x/profile/") || cls.superclass != "Ljava/lang/Enum;") return@classDefForEach
            val fieldNames = cls.fields.map { it.name }.toSet()
            if ("All" in fieldNames && "Posts" in fieldNames && "Highlights" in fieldNames) {
                profileTabEnum = cls.type
            }
        }
        val tabEnum = profileTabEnum
            ?: throw PatchException("DefaultProfileAllTabPatch: could not find profile tab enum")

        // 2. Replace default Posts tab with All where default tab is checked against allTabs.contains(Posts)
        var patchedDefault = false
        classDefForEach { cls ->
            if (patchedDefault || cls.type == tabEnum) return@classDefForEach
            var matchedMethodName: String? = null
            var matchedParamTypes: List<CharSequence>? = null
            var matchedInsnIdx = -1
            var matchedReg = -1

            for (m in cls.methods) {
                val insns = m.implementation?.instructions?.toList() ?: continue
                for (i in 0 until insns.size - 1) {
                    if (insns[i].opcode != Opcode.SGET_OBJECT) continue
                    val fRef = (insns[i] as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                    if (fRef.definingClass != tabEnum || fRef.name != "Posts") continue
                    val nextRef = (insns[i + 1] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (nextRef.definingClass == "Ljava/util/List;" && nextRef.name == "contains") {
                        matchedMethodName = m.name
                        matchedParamTypes = m.parameterTypes
                        matchedInsnIdx = i
                        matchedReg = (insns[i] as OneRegisterInstruction).registerA
                        break
                    }
                }
                if (matchedInsnIdx >= 0) break
            }

            if (matchedInsnIdx >= 0) {
                val mutableCls = mutableClassDefBy(cls)
                val targetMethod = mutableCls.methods.first {
                    it.name == matchedMethodName && it.parameterTypes == matchedParamTypes
                }
                targetMethod.replaceInstruction(
                    matchedInsnIdx,
                    "sget-object v$matchedReg, $tabEnum->All:$tabEnum",
                )
                patchedDefault = true
            }
        }
        if (!patchedDefault) {
            throw PatchException("DefaultProfileAllTabPatch: could not find default Posts tab selection")
        }

        // 3. Ensure x_lite_profile_all_posts_tab_enabled feature switch is always true
        classDefForEach { cls ->
            if (!cls.type.startsWith("Lcom/x/")) return@classDefForEach
            val hasSwitch = cls.methods.any { m ->
                m.implementation?.instructions?.any { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string == ALL_POSTS_TAB_SWITCH
                } == true
            }
            if (!hasSwitch) return@classDefForEach

            val mutableCls = mutableClassDefBy(cls)
            for (method in mutableCls.methods) {
                val insns = method.implementation?.instructions?.toList() ?: continue
                val edits = mutableListOf<Pair<Int, Int>>()
                for (i in insns.indices) {
                    val str = ((insns[i] as? ReferenceInstruction)?.reference as? StringReference)?.string ?: continue
                    if (str != ALL_POSTS_TAB_SWITCH) continue
                    for (j in i + 1 until minOf(i + 10, insns.size - 1)) {
                        val mRef = (insns[j] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        if (mRef.name == "getBoolean" && mRef.returnType == "Z" && insns[j + 1].opcode == Opcode.MOVE_RESULT) {
                            val reg = (insns[j + 1] as OneRegisterInstruction).registerA
                            edits.add((j + 2) to reg)
                            break
                        }
                    }
                }
                for ((idx, reg) in edits.sortedByDescending { it.first }) {
                    method.addInstructions(idx, "const/4 v$reg, 0x1")
                }
            }
        }
    }
}
