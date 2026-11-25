package app.crimera.patches.instagram.feed

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val BOOLEAN_SETTING_CLASS =
    "Lapp/morphe/extension/instagram/settings/BooleanSetting;"
private const val BASE_BOOLEAN_SETTING_CLASS =
    "Lapp/morphe/extension/crimera/settings/BooleanSetting;"
private const val RESTART_POLICY_CLASS =
    "Lapp/morphe/extension/instagram/settings/RestartPolicy;"
private const val SHARED_PREF_CLASS =
    "Lapp/morphe/extension/crimera/sharedPreference/SharedPref;"
private const val SCREEN_BUILDER_CLASS =
    "Lapp/morphe/extension/instagram/settings/preference/ScreenBuilder;"
private const val SETTINGS_STATUS_CLASS =
    "Lapp/morphe/extension/instagram/settings/SettingsStatus;"
private const val USER_SESSION_CLASS =
    "Lcom/instagram/common/session/UserSession;"

@Suppress("unused")
val homecomingUiPatch = bytecodePatch(
    name = "Homecoming UI",
    description = "Adds a toggle in Piko Settings to switch between the current Instagram UI and the Homecoming UI.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        val screenBuilder = classDefByOrNull(SCREEN_BUILDER_CLASS)?.let { mutableClassDefBy(it) } ?: return@execute
        val helperType = screenBuilder.type

        // 1. Static setting cache field
        if (screenBuilder.fields.none { it.name == "pikoHomecomingUiSetting" }) {
            screenBuilder.staticFields.add(
                ImmutableField(
                    helperType,
                    "pikoHomecomingUiSetting",
                    BOOLEAN_SETTING_CLASS,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    null,
                ).toMutable(),
            )
        }

        // 2. pikoGetHomecomingUiSetting(): BooleanSetting (default FALSE, RestartPolicy.REQUIRED)
        if (screenBuilder.methods.none { it.name == "pikoGetHomecomingUiSetting" }) {
            val getter = ImmutableMethod(
                helperType,
                "pikoGetHomecomingUiSetting",
                emptyList(),
                BOOLEAN_SETTING_CLASS,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        sget-object v0, $helperType->pikoHomecomingUiSetting:$BOOLEAN_SETTING_CLASS
                        if-nez v0, :have
                        new-instance v0, $BOOLEAN_SETTING_CLASS
                        const-string v1, "piko_homecoming_ui"
                        sget-object v2, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                        sget-object v3, $RESTART_POLICY_CLASS->REQUIRED:$RESTART_POLICY_CLASS
                        invoke-direct {v0, v1, v2, v3}, $BOOLEAN_SETTING_CLASS-><init>(Ljava/lang/String;Ljava/lang/Boolean;$RESTART_POLICY_CLASS)V
                        sput-object v0, $helperType->pikoHomecomingUiSetting:$BOOLEAN_SETTING_CLASS
                        :have
                        return-object v0
                    """.trimIndent(),
                )
            }
            screenBuilder.methods.add(getter)
        }

        // 3. pikoIsHomecomingUiEnabled(): boolean
        if (screenBuilder.methods.none { it.name == "pikoIsHomecomingUiEnabled" }) {
            val isEnabled = ImmutableMethod(
                helperType,
                "pikoIsHomecomingUiEnabled",
                emptyList(),
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-static {}, Lapp/morphe/extension/shared/Utils;->getContext()Landroid/content/Context;
                        move-result-object v0
                        if-eqz v0, :default_off
                        invoke-static {}, $helperType->pikoGetHomecomingUiSetting()$BOOLEAN_SETTING_CLASS
                        move-result-object v0
                        invoke-static {v0}, $SHARED_PREF_CLASS->getBooleanPref($BASE_BOOLEAN_SETTING_CLASS)Ljava/lang/Boolean;
                        move-result-object v0
                        if-eqz v0, :default_off
                        invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
                        move-result v0
                        return v0
                        :default_off
                        const/4 v0, 0x0
                        return v0
                    """.trimIndent(),
                )
            }
            screenBuilder.methods.add(isEnabled)
        }

        // 4. pikoAddHomecomingUiToggle(): void on ScreenBuilder
        if (screenBuilder.methods.none { it.name == "pikoAddHomecomingUiToggle" }) {
            val addToggle = ImmutableMethod(
                helperType,
                "pikoAddHomecomingUiToggle",
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value,
                null,
                null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const-string v0, "Homecoming UI"
                        const-string v1, "Switch between current Instagram UI and Homecoming UI"
                        invoke-static {}, $helperType->pikoGetHomecomingUiSetting()$BOOLEAN_SETTING_CLASS
                        move-result-object v2
                        invoke-direct {p0, v0, v1, v2}, $helperType->addLockableSwitch(Ljava/lang/String;Ljava/lang/String;$BOOLEAN_SETTING_CLASS)V
                        return-void
                    """.trimIndent(),
                )
            }
            screenBuilder.methods.add(addToggle)
        }

        // 5. Hook X.02wx.A04
        classDefByOrNull("LX/02wx;")?.let { mutableClassDefBy(it) }?.methods?.firstOrNull {
            it.name == "A04" && it.returnType == "Z" && it.implementation != null
        }?.addInstructions(
            0,
            """
                invoke-static {}, $helperType->pikoIsHomecomingUiEnabled()Z
                move-result v0
                if-eqz v0, :piko_orig_02wx
                const/4 v0, 0x1
                return v0
                :piko_orig_02wx
                nop
            """.trimIndent(),
        )

        // 6. Hook X.03ce.A07 and X.03ce.A09
        val x03ce = classDefByOrNull("LX/03ce;")?.let { mutableClassDefBy(it) }
        listOf("A07", "A09").forEach { methodName ->
            x03ce?.methods?.firstOrNull {
                it.name == methodName && it.returnType == "Z" && it.implementation != null
            }?.addInstructions(
                0,
                """
                    invoke-static {}, $helperType->pikoIsHomecomingUiEnabled()Z
                    move-result v0
                    if-eqz v0, :piko_orig_$methodName
                    const/4 v0, 0x1
                    return v0
                    :piko_orig_$methodName
                    nop
                """.trimIndent(),
            )
        }

        // 7. Find MergedFeedHostFragment.onViewCreated:restoreStartingTab -> X.07AW and hook
        var targetOwnerType: String? = null
        classDefForEach { classDef ->
            if (targetOwnerType != null) return@classDefForEach
            for (method in classDef.methods) {
                if (method.name != "onViewCreated") continue
                val insns = method.implementation?.instructions ?: continue
                val hasMarker = insns.any { insn ->
                    (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
                        ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "MergedFeedHostFragment.onViewCreated:restoreStartingTab"
                }
                if (!hasMarker) continue
                for (insn in insns) {
                    if (insn.opcode != Opcode.INVOKE_STATIC) continue
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    val defClass = ref.definingClass
                    if (defClass.startsWith("Landroid/") ||
                        defClass.startsWith("Landroidx/") ||
                        defClass.startsWith("Ljava/") ||
                        defClass.startsWith("Lkotlin/") ||
                        defClass.startsWith("Lcom/instagram/")
                    ) continue
                    val params = ref.parameterTypes.map { it.toString() }
                    if (USER_SESSION_CLASS in params && "Z" in params) {
                        targetOwnerType = defClass
                        return@classDefForEach
                    }
                }
            }
        }

        val resolvedType = targetOwnerType ?: "LX/07AW;"
        classDefByOrNull(resolvedType)?.let { mutableClassDefBy(it) }?.methods?.filter { m ->
            m.returnType == "Z" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0].toString() == USER_SESSION_CLASS &&
                m.parameterTypes[1].toString() == "Z" &&
                m.parameterTypes[2].toString() == "Z" &&
                m.implementation != null
        }?.forEach { method ->
            method.addInstructions(
                0,
                """
                    invoke-static {}, $helperType->pikoIsHomecomingUiEnabled()Z
                    move-result v0
                    if-eqz v0, :piko_orig_07aw
                    const/4 v0, 0x1
                    return v0
                    :piko_orig_07aw
                    nop
                """.trimIndent(),
            )
        }
    }

    finalize {
        classDefByOrNull(SETTINGS_STATUS_CLASS)?.let { mutableClassDefBy(it) }
            ?.methods
            ?.firstOrNull { it.name == "distractionFreeSection" && it.parameters.isEmpty() }
            ?.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """.trimIndent(),
            )

        val screenBuilder = classDefByOrNull(SCREEN_BUILDER_CLASS)?.let { mutableClassDefBy(it) } ?: return@finalize
        var sectionMethod = screenBuilder.methods.firstOrNull {
            it.name == "distractionFreeSection" && it.parameters.isEmpty()
        } ?: return@finalize
        val lastReturnIdx = sectionMethod.implementation?.instructions?.indexOfLast {
            it.opcode == Opcode.RETURN_VOID
        } ?: -1
        if (lastReturnIdx < 0) return@finalize
        if (sectionMethod.implementation == null) {
            sectionMethod.implementation = MutableMethodImplementation(4)
        }
        sectionMethod.removeInstruction(lastReturnIdx)
        sectionMethod.addInstructions(
            lastReturnIdx,
            """
                invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoAddHomecomingUiToggle()V
            """.trimIndent(),
        )
        sectionMethod.implementation!!.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
    }
}
