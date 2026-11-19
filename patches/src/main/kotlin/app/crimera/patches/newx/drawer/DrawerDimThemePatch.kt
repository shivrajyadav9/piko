package app.crimera.patches.newx.drawer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

private const val THEME_UI_CLASS = "Lcom/x/android/theme/impl/ui/a;"
private const val DARK_MODE_ENUM = "Lcom/x/android/theme/api/f;"
private const val RADIO_ROW_CLASS = "Lcom/x/ui/common/ports/preference/b;"
private const val CLICK_LAMBDA_CLASS = "Lcom/x/aitrend/o;"
private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val SETTING_CLASS = "Lapp/morphe/extension/shared/settings/Setting;"
private const val UTILS_CLASS = "Lapp/morphe/extension/shared/Utils;"

@Suppress("unused")
val drawerDimThemePatch = bytecodePatch(
    name = "Drawer Dim theme",
    description = "Adds Crimera's Dim theme option to the Dark mode sheet opened from the side drawer moon button.",
) {
    compatibleWith("com.twitter.android")

    execute {
        val themeUiClassDef = classDefByOrNull(THEME_UI_CLASS) ?: return@execute
        val mutableThemeUi = mutableClassDefBy(themeUiClassDef)

        fun addStaticHelper(
            name: String,
            params: List<String>,
            returnType: String,
            registerCount: Int,
            smali: String,
        ) {
            val impl = MutableMethodImplementation(registerCount)
            impl.addInstruction(ImmutableInstruction10x(Opcode.RETURN_VOID))
            val helper = ImmutableMethod(
                THEME_UI_CLASS,
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
            mutableThemeUi.methods.add(helper)
        }

        addStaticHelper(
            name = "pikoIsDimTheme",
            params = emptyList(),
            returnType = "Z",
            registerCount = 2,
            smali = """
                const-string v0, "newx.theme.dark_style"
                const-string v1, "contrast"
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getStringOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                const-string v1, "dim"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoIsLightsOutSelected",
            params = listOf("Z"),
            returnType = "Z",
            registerCount = 2,
            smali = """
                if-eqz p0, :ret_false
                invoke-static {}, $THEME_UI_CLASS->pikoIsDimTheme()Z
                move-result v0
                if-nez v0, :ret_false
                const/4 v0, 0x1
                return v0
                :ret_false
                const/4 v0, 0x0
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoRestartApp",
            params = emptyList(),
            returnType = "V",
            registerCount = 3,
            smali = """
                const-wide/16 v0, 0xc8
                invoke-static {v0, v1}, Ljava/lang/Thread;->sleep(J)V
                invoke-static {}, $UTILS_CLASS->getContext()Landroid/content/Context;
                move-result-object v0
                if-eqz v0, :exit
                invoke-virtual {v0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;
                move-result-object v1
                invoke-virtual {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object v2
                invoke-virtual {v1, v2}, Landroid/content/pm/PackageManager;->getLaunchIntentForPackage(Ljava/lang/String;)Landroid/content/Intent;
                move-result-object v1
                if-eqz v1, :exit
                const v2, 0x10008000
                invoke-virtual {v1, v2}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;
                invoke-virtual {v0, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                invoke-static {}, Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, Ljava/lang/Runtime;->exit(I)V
                :exit
                return-void
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoRenderDimRow",
            params = listOf(DARK_MODE_ENUM, "Lkotlin/jvm/functions/Function1;", "Landroidx/compose/runtime/Composer;"),
            returnType = "V",
            registerCount = 13,
            smali = """
                const-string v0, "Dim"
                const/4 v1, 0x0
                const/4 v2, 0x0
                const/4 v3, 0x0
                sget-object v5, $DARK_MODE_ENUM->ON:$DARK_MODE_ENUM
                if-ne p0, v5, :not_sel
                invoke-static {}, $THEME_UI_CLASS->pikoIsDimTheme()Z
                move-result v4
                goto :build_row
                :not_sel
                const/4 v4, 0x0
                :build_row
                const/4 v5, 0x0
                new-instance v6, $CLICK_LAMBDA_CLASS
                const/16 v7, 0x63
                invoke-direct {v6, p1, v7}, $CLICK_LAMBDA_CLASS-><init>(Lkotlin/jvm/functions/Function1;B)V
                move-object/from16 v7, p2
                const/4 v8, 0x0
                const/16 v9, 0x2e
                invoke-static/range {v0 .. v9}, $RADIO_ROW_CLASS->l(Ljava/lang/String;Landroidx/compose/ui/Modifier;Ljava/lang/String;Landroidx/compose/runtime/internal/g;ZZLkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;II)V
                return-void
            """.trimIndent(),
        )

        // Hook method e(...) in com/x/android/theme/impl/ui/a
        val sheetMethod = mutableThemeUi.methods.firstOrNull {
            it.name == "e" && it.parameters.size == 5 && it.parameters[0].type == DARK_MODE_ENUM
        }
        if (sheetMethod != null) {
            val insns = sheetMethod.implementation!!.instructions
            val radioCallIndices = insns.mapIndexedNotNull { idx, insn ->
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                if (ref != null && ref.definingClass == RADIO_ROW_CLASS && ref.name == "l") idx else null
            }
            if (radioCallIndices.size >= 3) {
                // Insert Dim row right after the 3rd radio row ("Use device settings")
                sheetMethod.addInstructions(
                    radioCallIndices[2] + 1,
                    "invoke-static {v1, v2, v10}, $THEME_UI_CLASS->pikoRenderDimRow($DARK_MODE_ENUM;Lkotlin/jvm/functions/Function1;Landroidx/compose/runtime/Composer;)V",
                )
                // Adjust "On" row (2nd radio row) selected state so it is unchecked when Dim is active
                sheetMethod.addInstructions(
                    radioCallIndices[1],
                    """
                        invoke-static {v7}, $THEME_UI_CLASS->pikoIsLightsOutSelected(Z)Z
                        move-result v7
                    """.trimIndent(),
                )
            }
        }

        // Hook com/x/aitrend/o;->invoke()Ljava/lang/Object; to handle Dim (case 99) and Lights Out (case 24)
        classDefByOrNull(CLICK_LAMBDA_CLASS)?.let { clickDef ->
            val mutableClick = mutableClassDefBy(clickDef)
            mutableClick.methods.firstOrNull { it.name == "invoke" && it.parameters.isEmpty() }?.addInstructions(
                0,
                """
                    iget-byte v0, p0, $CLICK_LAMBDA_CLASS->a:B
                    const/16 v1, 0x63
                    if-ne v0, v1, :piko_check_on
                    invoke-static {}, $THEME_UI_CLASS->pikoIsDimTheme()Z
                    move-result v0
                    const-string v1, "newx.theme.dark_style"
                    const-string v2, "dim"
                    invoke-static {v1, v2}, $SETTINGS_REGISTRY->pikoSetString(Ljava/lang/String;Ljava/lang/String;)V
                    iget-object v1, p0, $CLICK_LAMBDA_CLASS->b:Lkotlin/jvm/functions/Function1;
                    sget-object v2, $DARK_MODE_ENUM->ON:$DARK_MODE_ENUM
                    invoke-interface {v1, v2}, Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;
                    if-nez v0, :piko_dim_done
                    invoke-static {}, $THEME_UI_CLASS->pikoRestartApp()V
                    :piko_dim_done
                    sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v0
                    :piko_check_on
                    const/16 v1, 0x18
                    if-ne v0, v1, :piko_orig
                    invoke-static {}, $THEME_UI_CLASS->pikoIsDimTheme()Z
                    move-result v0
                    if-eqz v0, :piko_orig
                    const-string v1, "newx.theme.dark_style"
                    const-string v2, "contrast"
                    invoke-static {v1, v2}, $SETTINGS_REGISTRY->pikoSetString(Ljava/lang/String;Ljava/lang/String;)V
                    iget-object v1, p0, $CLICK_LAMBDA_CLASS->b:Lkotlin/jvm/functions/Function1;
                    sget-object v2, $DARK_MODE_ENUM->ON:$DARK_MODE_ENUM
                    invoke-interface {v1, v2}, Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;
                    invoke-static {}, $THEME_UI_CLASS->pikoRestartApp()V
                    sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v0
                    :piko_orig
                """.trimIndent(),
            )
        }
    }

    finalize {
        classDefByOrNull(SETTINGS_REGISTRY)?.let { regDef ->
            val mutableReg = mutableClassDefBy(regDef)
            if (mutableReg.methods.none { it.name == "pikoSetString" }) {
                val impl = MutableMethodImplementation(3)
                impl.addInstruction(ImmutableInstruction10x(Opcode.RETURN_VOID))
                val setter = ImmutableMethod(
                    SETTINGS_REGISTRY,
                    "pikoSetString",
                    listOf(
                        ImmutableMethodParameter("Ljava/lang/String;", null, null),
                        ImmutableMethodParameter("Ljava/lang/String;", null, null),
                    ),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    impl,
                ).toMutable()
                setter.addInstructions(
                    0,
                    """
                        invoke-static {p0}, $SETTINGS_REGISTRY->findSetting(Ljava/lang/String;)$SETTING_CLASS
                        move-result-object v0
                        if-eqz v0, :done
                        invoke-virtual {v0, p1}, $SETTING_CLASS->save(Ljava/lang/Object;)V
                        :done
                        return-void
                    """.trimIndent(),
                )
                setter.removeInstruction(setter.implementation!!.instructions.size - 1)
                mutableReg.methods.add(setter)
            }
        }
    }
}
