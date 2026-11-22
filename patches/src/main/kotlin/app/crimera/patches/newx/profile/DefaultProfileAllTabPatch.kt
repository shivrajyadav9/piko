package app.crimera.patches.newx.profile

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val ALL_POSTS_TAB_SWITCH = "x_lite_profile_all_posts_tab_enabled"
private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val BUILT_IN_SETTINGS = "Lapp/morphe/extension/newx/settings/BuiltInSettings;"

private val defaultProfileAllTabResourcesPatch = resourcePatch {
    execute {
        document("res/values/strings.xml").use { doc ->
            val resources = doc.getElementsByTagName("resources").item(0)
            val existingNames = mutableSetOf<String>()
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val attr = nodes.item(i).attributes?.getNamedItem("name")?.nodeValue
                if (attr != null) existingNames.add(attr)
            }
            fun addStringIfMissing(name: String, value: String) {
                if (name in existingNames) return
                val el = doc.createElement("string")
                el.setAttribute("name", name)
                el.textContent = value
                resources.appendChild(el)
                existingNames.add(name)
            }
            addStringIfMissing("piko_my_patches_category_title", "My Patches")
            addStringIfMissing("piko_my_patches_category_summary", "Custom patches")
            addStringIfMissing("piko_my_patches_default_profile_all_title", "Default profile tab to All")
            addStringIfMissing(
                "piko_my_patches_default_profile_all_summary",
                "Open profiles on All instead of Tweets/Posts",
            )
        }
    }
}

@Suppress("unused")
val defaultProfileAllTabPatch = bytecodePatch(
    name = "Default profile tab to All",
    description = "Sets the default selection in the profile Posts/Tweets dropdown menu to All instead of Tweets/Posts. Toggleable in Piko Settings > My Patches.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)
    dependsOn(defaultProfileAllTabResourcesPatch)

    var resolvedHelperClass: String? = null

    execute {
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
                val helperClassType = cls.type
                resolvedHelperClass = helperClassType
                val mutableCls = mutableClassDefBy(cls)

                mutableCls.fields.add(
                    ImmutableField(
                        helperClassType,
                        "pikoProfileAllRegistered",
                        "Z",
                        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                        null,
                        null,
                        null,
                    ).toMutable(),
                )

                fun addStaticHelper(
                    name: String,
                    params: List<String>,
                    returnType: String,
                    registerCount: Int,
                    smali: String,
                ) {
                    val impl = MutableMethodImplementation(registerCount)
                    impl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
                    val helper = ImmutableMethod(
                        helperClassType,
                        name,
                        params.map { ImmutableMethodParameter(it, null, null) },
                        returnType,
                        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                        null,
                        null,
                        impl,
                    ).toMutable()
                    helper.addInstructions(0, smali)
                    helper.removeInstruction(helper.implementation!!.instructions.size - 1)
                    mutableCls.methods.add(helper)
                }

                addStaticHelper(
                    name = "pikoRegisterProfileAllSettings",
                    params = emptyList(),
                    returnType = "V",
                    registerCount = 5,
                    smali = """
                        sget-boolean v0, $helperClassType->pikoProfileAllRegistered:Z
                        if-nez v0, :done
                        const/4 v0, 0x1
                        sput-boolean v0, $helperClassType->pikoProfileAllRegistered:Z
                        const-string v0, "newx.my_patches"
                        const-string v1, "piko_my_patches_category_title"
                        const-string v2, "piko_my_patches_category_summary"
                        const-string v3, "ic_vector_wrench"
                        const/16 v4, 0x32
                        invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerCategory(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                        const-string v0, "newx.my_patches"
                        const-string v1, "newx.my_patches.default_profile_all"
                        const-string v2, "piko_my_patches_default_profile_all_title"
                        const-string v3, "piko_my_patches_default_profile_all_summary"
                        const/16 v4, 0x1e
                        invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerToggle(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                        const-string v0, "newx.my_patches.default_profile_all"
                        const/4 v1, 0x1
                        const/4 v2, 0x0
                        invoke-static {v0, v1, v2}, $SETTINGS_REGISTRY->configureToggle(Ljava/lang/String;ZZ)V
                        :done
                        return-void
                    """.trimIndent(),
                )

                addStaticHelper(
                    name = "pikoPickDefaultProfileTab",
                    params = emptyList(),
                    returnType = tabEnum,
                    registerCount = 2,
                    smali = """
                        const-string v0, "newx.my_patches.default_profile_all"
                        const/4 v1, 0x1
                        invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                        move-result v0
                        if-eqz v0, :use_posts
                        sget-object v0, $tabEnum->All:$tabEnum
                        return-object v0
                        :use_posts
                        sget-object v0, $tabEnum->Posts:$tabEnum
                        return-object v0
                    """.trimIndent(),
                )

                val targetMethod = mutableCls.methods.first {
                    it.name == matchedMethodName && it.parameterTypes == matchedParamTypes
                }
                targetMethod.addInstructions(
                    matchedInsnIdx + 1,
                    """
                        invoke-static {}, $helperClassType->pikoPickDefaultProfileTab()$tabEnum
                        move-result-object v$matchedReg
                    """.trimIndent(),
                )
                patchedDefault = true
            }
        }
        if (!patchedDefault) {
            throw PatchException("DefaultProfileAllTabPatch: could not find default Posts tab selection")
        }

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

    finalize {
        val helperClassType = resolvedHelperClass ?: return@finalize
        classDefByOrNull(BUILT_IN_SETTINGS)?.let { builtInDef ->
            mutableClassDefBy(builtInDef).methods
                .firstOrNull { it.name == "register" && it.parameters.isEmpty() }
                ?.addInstructions(
                    0,
                    "invoke-static {}, $helperClassType->pikoRegisterProfileAllSettings()V",
                )
        }
    }
}
