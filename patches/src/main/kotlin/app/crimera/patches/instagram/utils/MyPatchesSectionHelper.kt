package app.crimera.patches.instagram.utils

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClassDef
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

internal const val SCREEN_BUILDER_CLASS = "Lapp/morphe/extension/instagram/settings/preference/ScreenBuilder;"
internal const val BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/instagram/settings/BooleanSetting;"
internal const val BASE_BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/crimera/settings/BooleanSetting;"
internal const val RESTART_POLICY_CLASS = "Lapp/morphe/extension/instagram/settings/RestartPolicy;"
internal const val SHARED_PREF_CLASS = "Lapp/morphe/extension/crimera/sharedPreference/SharedPref;"
internal const val UTILS_CLASS = "Lapp/morphe/extension/shared/Utils;"
private const val PIKO_FRAGMENT_MY_PATCHES = "piko_my_patches"

internal fun addInstagramBooleanSettingHelpers(
    targetClass: MutableClassDef,
    fieldName: String,
    getterName: String,
    enabledName: String,
    prefKey: String,
    defaultValue: Boolean = true,
    restartRequired: Boolean = false,
) {
    val ownerType = targetClass.type
    val publicStatic = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
    val defaultBoolField = if (defaultValue) "TRUE" else "FALSE"
    val defaultInt = if (defaultValue) "0x1" else "0x0"
    val restartEnum = if (restartRequired) "REQUIRED" else "NOT_REQUIRED"

    targetClass.staticFields.add(
        ImmutableField(
            ownerType,
            fieldName,
            BOOLEAN_SETTING_CLASS,
            publicStatic,
            null,
            null,
            null,
        ).toMutable(),
    )

    val getterMethod =
        ImmutableMethod(
            ownerType,
            getterName,
            emptyList(),
            BOOLEAN_SETTING_CLASS,
            publicStatic,
            null,
            null,
            ImmutableMethodImplementation(
                4,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ).toMutable()

    getterMethod.addInstructions(
        0,
        """
        sget-object v0, $ownerType->$fieldName:$BOOLEAN_SETTING_CLASS
        if-nez v0, :piko_cached
        new-instance v0, $BOOLEAN_SETTING_CLASS
        const-string v1, "$prefKey"
        sget-object v2, Ljava/lang/Boolean;->$defaultBoolField:Ljava/lang/Boolean;
        sget-object v3, $RESTART_POLICY_CLASS->$restartEnum:$RESTART_POLICY_CLASS
        invoke-direct {v0, v1, v2, v3}, $BOOLEAN_SETTING_CLASS-><init>(Ljava/lang/String;Ljava/lang/Boolean;$RESTART_POLICY_CLASS)V
        sput-object v0, $ownerType->$fieldName:$BOOLEAN_SETTING_CLASS
        :piko_cached
        return-object v0
        """.trimIndent(),
    )
    getterMethod.removeInstruction(getterMethod.implementation!!.instructions.size - 1)
    targetClass.methods.add(getterMethod)

    val enabledMethod =
        ImmutableMethod(
            ownerType,
            enabledName,
            emptyList(),
            "Z",
            publicStatic,
            null,
            null,
            ImmutableMethodImplementation(
                1,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ).toMutable()

    enabledMethod.addInstructions(
        0,
        """
        invoke-static {}, $UTILS_CLASS->getContext()Landroid/content/Context;
        move-result-object v0
        if-nez v0, :piko_ctx_ok
        const/4 v0, $defaultInt
        return v0
        :piko_ctx_ok
        invoke-static {}, $ownerType->$getterName()$BOOLEAN_SETTING_CLASS
        move-result-object v0
        invoke-static {v0}, $SHARED_PREF_CLASS->getBooleanPref($BASE_BOOLEAN_SETTING_CLASS)Ljava/lang/Boolean;
        move-result-object v0
        if-nez v0, :piko_val_ok
        const/4 v0, $defaultInt
        return v0
        :piko_val_ok
        invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
        move-result v0
        return v0
        """.trimIndent(),
    )
    enabledMethod.removeInstruction(enabledMethod.implementation!!.instructions.size - 1)
    targetClass.methods.add(enabledMethod)
}

internal fun BytecodePatchContext.registerMyPatchesToggle(
    helperMethodName: String,
    title: String,
    description: String,
    settingOwnerType: String,
    settingGetterName: String,
) {
    val screenBuilderDef = classDefByOrNull(SCREEN_BUILDER_CLASS) ?: return
    val screenBuilder = mutableClassDefBy(screenBuilderDef)

    if (screenBuilder.methods.none { it.name == "pikoBuildMyPatchesSection" }) {
        val helperField = screenBuilder.fields.firstOrNull { it.name == "helper" } ?: return
        val helperType = helperField.type
        val addPrefMethod =
            screenBuilder.methods.firstOrNull {
                it.name == "addPreference" && it.parameters.size == 1
            } ?: return
        val prefType = addPrefMethod.parameterTypes[0].toString()

        val addCategoryMethod =
            ImmutableMethod(
                SCREEN_BUILDER_CLASS,
                "pikoAddMyPatchesCategory",
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value,
                null,
                null,
                ImmutableMethodImplementation(
                    5,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ).toMutable()

        addCategoryMethod.addInstructions(
            0,
            """
            iget-object v0, p0, $SCREEN_BUILDER_CLASS->helper:$helperType
            const-string v1, "My Patches"
            const-string v2, ""
            const-string v3, "$PIKO_FRAGMENT_MY_PATCHES"
            invoke-virtual {v0, v1, v2, v3}, $helperType->categoryPreference(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)$prefType
            move-result-object v0
            invoke-direct {p0, v0}, $SCREEN_BUILDER_CLASS->addPreference($prefType)V
            return-void
            """.trimIndent(),
        )
        addCategoryMethod.removeInstruction(addCategoryMethod.implementation!!.instructions.size - 1)
        screenBuilder.methods.add(addCategoryMethod)

        val sectionMethod =
            ImmutableMethod(
                SCREEN_BUILDER_CLASS,
                "pikoBuildMyPatchesSection",
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value,
                null,
                null,
                ImmutableMethodImplementation(
                    1,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ).toMutable()
        screenBuilder.methods.add(sectionMethod)

        screenBuilder.methods.firstOrNull {
            it.name == "buildSettingsPage" && it.parameters.isEmpty()
        }?.addInstructions(
            0,
            """
            invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoAddMyPatchesCategory()V
            """.trimIndent(),
        )

        screenBuilder.methods.firstOrNull {
            it.name == "buildFragment" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
        }?.addInstructions(
            0,
            """
            const-string v0, "$PIKO_FRAGMENT_MY_PATCHES"
            invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :piko_not_my_patches
            invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoBuildMyPatchesSection()V
            return-void
            :piko_not_my_patches
            """.trimIndent(),
        )
    }

    if (screenBuilder.methods.none { it.name == helperMethodName }) {
        val addToggleMethod =
            ImmutableMethod(
                SCREEN_BUILDER_CLASS,
                helperMethodName,
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value,
                null,
                null,
                ImmutableMethodImplementation(
                    4,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ).toMutable()

        addToggleMethod.addInstructions(
            0,
            """
            const-string v0, "$title"
            const-string v1, "$description"
            invoke-static {}, $settingOwnerType->$settingGetterName()$BOOLEAN_SETTING_CLASS
            move-result-object v2
            invoke-direct {p0, v0, v1, v2}, $SCREEN_BUILDER_CLASS->addLockableSwitch(Ljava/lang/String;Ljava/lang/String;$BOOLEAN_SETTING_CLASS)V
            return-void
            """.trimIndent(),
        )
        addToggleMethod.removeInstruction(addToggleMethod.implementation!!.instructions.size - 1)
        screenBuilder.methods.add(addToggleMethod)

        val sectionMethod = screenBuilder.methods.first { it.name == "pikoBuildMyPatchesSection" }
        val insns = sectionMethod.implementation!!.instructions.toList()
        val lastReturnIdx = insns.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        if (lastReturnIdx >= 0) {
            sectionMethod.addInstructions(
                lastReturnIdx,
                """
                invoke-direct {p0}, $SCREEN_BUILDER_CLASS->$helperMethodName()V
                """.trimIndent(),
            )
        }
    }
}
