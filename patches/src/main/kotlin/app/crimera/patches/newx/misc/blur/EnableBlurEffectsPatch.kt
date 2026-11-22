package app.crimera.patches.newx.misc.blur

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

private const val HAZE_CONFIG_CLASS = "Lcom/x/android/haze/b;"
private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val BUILT_IN_SETTINGS = "Lapp/morphe/extension/newx/settings/BuiltInSettings;"

private val hazeModeFingerprint =
    Fingerprint(
        strings = listOf("x_lite_infra_haze_blur_enabled", "x_lite_infra_haze_adaptive_performance_enabled"),
    )
private const val FLOATING_NAV_SWITCH = "android_x_lite_glass_bottom_nav_enabled"

private val BLUR_FEATURE_SWITCHES =
    setOf(
        
        "x_android_home_timeline_new_posts_pill_blur_enabled",
        "xchat_liquid_glass_convo_header_enabled",
    )

private val enableBlurResourcesPatch =
    resourcePatch {
        execute {
            document("res/values/strings.xml").use { document ->
                val resources = document.getElementsByTagName("resources").item(0)
               val existingNames = mutableSetOf<String>()
               val nodes = document.getElementsByTagName("string")
               for (i in 0 until nodes.length) {
                   val attr = nodes.item(i).attributes?.getNamedItem("name")?.nodeValue
                   if (attr != null) existingNames.add(attr)
               }
                listOf(
                    "piko_my_patches_category_title" to "My Patches",
                    "piko_my_patches_category_summary" to "Custom patches",
                    "piko_my_patches_enable_blur_title" to "Enable blur effects",
                    "piko_my_patches_enable_blur_summary" to "Enable Haze and Glass blur effects on all devices",
                ).forEach { (name, value) ->
                   if (name in existingNames) return@forEach
                    val stringElement = document.createElement("string")
                    stringElement.setAttribute("name", name)
                    stringElement.textContent = value
                    resources.appendChild(stringElement)
                }
            }
        }
    }
@Suppress("unused")
val enableBlurEffectsPatch =
    bytecodePatch(
        name = "Enable blur effects",
        description = "Enables Haze and Liquid Glass blur effects on all devices with an on/off toggle inside a 'My Patches' folder in Piko Settings.",
    ) {
        compatibleWith(COMPATIBILITY_NEW_X)

dependsOn(enableBlurResourcesPatch)
        execute {
            val hazeClass = hazeModeFingerprint.classDef
            val hazeClassType = hazeClass.type

            hazeClass.fields.add(
                ImmutableField(
                    hazeClassType,
                    "pikoSettingsRegistered",
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    null,
                ).toMutable(),
            )

            // 1. Idempotent helper that registers the "My Patches" category & "Enable blur effects" toggle in Piko Settings.
            val registerHelper =
                ImmutableMethod(
                    hazeClassType,
                    "pikoRegisterMyPatches",
                    emptyList(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        6,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            registerHelper.addInstructions(
                0,
                """
                sget-boolean v0, $hazeClassType->pikoSettingsRegistered:Z
                if-eqz v0, :piko_do_reg
                return-void
                :piko_do_reg
                const/4 v0, 0x1
                sput-boolean v0, $hazeClassType->pikoSettingsRegistered:Z
                const-string v0, "newx.my_patches"
const-string v1, "piko_my_patches_category_title"
const-string v2, "piko_my_patches_category_summary"
const-string v3, "ic_vector_wrench"
                const/16 v4, 0x32
                invoke-static/range {v0 .. v4}, $SETTINGS_REGISTRY->registerCategory(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches"
                const-string v1, "newx.my_patches.enable_blur"
            const-string v2, "piko_my_patches_enable_blur_title"
            const-string v3, "piko_my_patches_enable_blur_summary"
                const/16 v4, 0xa
                invoke-static/range {v0 .. v4}, $SETTINGS_REGISTRY->registerToggle(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches.enable_blur"
                const/4 v1, 0x1
                const/4 v2, 0x0
                invoke-static {v0, v1, v2}, $SETTINGS_REGISTRY->configureToggle(Ljava/lang/String;ZZ)V
                return-void
                """.trimIndent(),
            )
            registerHelper.removeInstruction(registerHelper.implementation!!.instructions.size - 1)
            hazeClass.methods.add(registerHelper)

            // 2. Helper that reads the toggle from Piko Settings (defaults to true).
            val isEnabledHelper =
                ImmutableMethod(
                    hazeClassType,
                    "pikoIsBlurEnabled",
                    emptyList(),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        2,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            isEnabledHelper.addInstructions(
                0,
                """
                const-string v0, "newx.my_patches.enable_blur"
                const/4 v1, 0x1
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                move-result v0
                return v0
                """.trimIndent(),
            )
            isEnabledHelper.removeInstruction(isEnabledHelper.implementation!!.instructions.size - 1)
            hazeClass.methods.add(isEnabledHelper)

            // 4. Hook com/x/android/haze/b->a() at index 0 to return Blur (when ON) or TintOnly (when OFF).
            hazeModeFingerprint.method.addInstructions(
                0,
                """
                invoke-static {}, $hazeClassType->pikoIsBlurEnabled()Z
                move-result v0
                if-eqz v0, :piko_blur_off
                sget-object v0, Ldev/chrisbanes/haze/i0;->d:Ldev/chrisbanes/haze/j0;
                new-instance v1, Lcom/x/android/haze/c;
                invoke-direct {v1, v0}, Lcom/x/android/haze/c;-><init>(Ldev/chrisbanes/haze/j0;)V
                return-object v1
                :piko_blur_off
                sget-object v0, Lcom/x/android/haze/d;->a:Lcom/x/android/haze/d;
                return-object v0
                """.trimIndent(),
            )

            // 5. Override Glass & New Posts Pill blur feature switches to follow the same toggle.
            classDefForEach { classDef ->
                classDef.methods.forEach { method ->
                    val insns = method.implementation?.instructions?.toList() ?: return@forEach
                    val insertPoints = mutableListOf<Triple<Int, Int, Boolean>>()

                    for (i in insns.indices) {
                        val insn = insns[i]
                        if (insn.opcode == Opcode.CONST_STRING) {
                            val str = ((insn as ReferenceInstruction).reference as? StringReference)?.string
                            if (str in BLUR_FEATURE_SWITCHES || str == FLOATING_NAV_SWITCH) {
                                val end = minOf(insns.size - 1, i + 6)
                                for (j in (i + 1) until end) {
                                    val call = insns[j]
                                    val next = insns[j + 1]
                                    val ref = (call as? ReferenceInstruction)?.reference as? MethodReference
                                    if (ref != null && ref.name == "getBoolean" && ref.returnType == "Z" && next.opcode == Opcode.MOVE_RESULT) {
                                        val resReg = (next as OneRegisterInstruction).registerA
                                        insertPoints.add(Triple(j + 1, resReg, str == FLOATING_NAV_SWITCH))
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
                        for ((moveResultIdx, resReg, forceTrue) in insertPoints.asReversed()) {
                            mutableMethod.addInstructions(
                                moveResultIdx + 1,
                                if (forceTrue) "const/4 v$resReg, 0x1" else """
                                invoke-static {}, $hazeClassType->pikoIsBlurEnabled()Z
                                move-result v$resReg
                                """.trimIndent(),
                            )
                        }
                    }
                }
            }
classDefByOrNull("Lcom/x/compose/core/u0;")?.let { u0ClassDef ->
mutableClassDefBy(u0ClassDef).methods.firstOrNull {
it.name == "a" &&
it.parameterTypes == listOf("Landroidx/compose/runtime/Composer;") &&
it.returnType == "Lcom/x/device/clustering/a;"
}?.addInstructions(
0,
"""
invoke-static {}, $hazeClassType->pikoIsBlurEnabled()Z
move-result v0
if-eqz v0, :piko_u0_off
sget-object v0, Lcom/x/device/clustering/a;->PROGRESSIVE_BLUR:Lcom/x/device/clustering/a;
return-object v0
:piko_u0_off
sget-object v0, Lcom/x/device/clustering/a;->NONE:Lcom/x/device/clustering/a;
return-object v0
""".trimIndent(),
)
}
classDefByOrNull("Lcom/x/compose/core/c0;")?.let { c0ClassDef ->
mutableClassDefBy(c0ClassDef).methods.firstOrNull {
it.name == "a" &&
it.parameterTypes == listOf("Landroidx/compose/runtime/Composer;") &&
it.returnType == "Z"
}?.addInstructions(
0,
"""
invoke-static {}, $hazeClassType->pikoIsBlurEnabled()Z
move-result v0
return v0
""".trimIndent(),
)
}
        }
finalize {
val hazeClassType = hazeModeFingerprint.classDef.type
classDefByOrNull(BUILT_IN_SETTINGS)?.let { builtIn ->
val mutableBuiltIn = mutableClassDefBy(builtIn)
mutableBuiltIn.methods.firstOrNull { it.name == "register" && it.parameterTypes.isEmpty() }?.addInstructions(
0,
"invoke-static {}, $hazeClassType->pikoRegisterMyPatches()V",
)
}
}
    }
