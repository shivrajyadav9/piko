package app.crimera.patches.newx.misc.perf

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
private const val MAIN_ACTIVITY_CLASS = "Lcom/x/android/main/MainActivity;"
private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val BUILT_IN_SETTINGS = "Lapp/morphe/extension/newx/settings/BuiltInSettings;"
private val ENABLE_PERF_SWITCHES = setOf(
    "home_timeline_scroll_framerate_enabled",
    "home_timeline_lazy_cache_window_enabled",
    "home_timeline_autoplay_fast_scroll_suppression_enabled",
    "home_timeline_autoplay_hysteresis_enabled",
    "home_timeline_autoplay_dwell_enabled",
    "x_android_timeline_autoplay_scrolling_flag_on_low_end_soc_enabled",
    "home_timeline_prefetch_urgent_while_scrolling_enabled",
    "x_lite_infra_image_timeline_prefetch_enabled",
    "x_lite_infra_image_trim_memory_handler_enabled",
)

private val DISABLE_TELEMETRY_SWITCHES = setOf(
    "x_lite_infra_perf_diagnostics_enabled",
    "x_lite_infra_session_energy_telemetry_enabled",
    "x_lite_infra_glide_image_telemetry_enabled",
    "x_lite_infra_image_cache_telemetry_enabled",
    "x_lite_infra_timeline_pagination_telemetry_enabled",
    "x_android_infra_process_exit_telemetry_enabled",
    "xlite_performance_logging_enabled",
    "xlite_flow_logger_enabled",
    "xlite_immersive_swipe_metrics_enabled",
    "xlite_explore_scroll_debug_scribes_enabled",
    "xlite_trend_items_debug_scribes_enabled",
    "xlite_scribe_organic_web_page_enabled",
    "ssp_ads_google_native_ad_repository_scribe_enabled",
    "ssp_ads_paid_event_scribe_enabled",
    "ssp_ads_render_rate_tracing_enabled",
    "android_x_lite_ssp_ads_gsig_telemetry_enabled",
    "media_upload_xlite_performance_logging_enabled",
    "av_chat_android_dev_logs_to_analytics_enabled",
)
private val performanceBoostResourcesPatch = resourcePatch {
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
            addStringIfMissing("piko_my_patches_perf_boost_title", "Performance boost")
            addStringIfMissing(
                "piko_my_patches_perf_boost_summary",
                "Optimize scroll framerate, prefetching, caching, max display refresh rate, and disable telemetry overhead",
            )
        }
    }
}

@Suppress("unused")
val performanceBoostPatch = bytecodePatch(
    name = "Performance boost",
    description = "Optimizes X scrolling, startup/image/video prefetching, caching, requests max display refresh rate, and disables background telemetry/diagnostics overhead. Toggleable in Piko Settings > My Patches.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)
    dependsOn(performanceBoostResourcesPatch)

    execute {
        val mainActivityDef = classDefByOrNull(MAIN_ACTIVITY_CLASS) ?: return@execute
        val mutableMain = mutableClassDefBy(mainActivityDef)

        mutableMain.fields.add(
            ImmutableField(
                MAIN_ACTIVITY_CLASS,
                "pikoPerfRegistered",
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
                MAIN_ACTIVITY_CLASS,
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
            mutableMain.methods.add(helper)
        }

        addStaticHelper(
            name = "pikoRegisterPerfSettings",
            params = emptyList(),
            returnType = "V",
            registerCount = 5,
            smali = """
                sget-boolean v0, $MAIN_ACTIVITY_CLASS->pikoPerfRegistered:Z
                if-nez v0, :done
                const/4 v0, 0x1
                sput-boolean v0, $MAIN_ACTIVITY_CLASS->pikoPerfRegistered:Z
                const-string v0, "newx.my_patches"
                const-string v1, "piko_my_patches_category_title"
                const-string v2, "piko_my_patches_category_summary"
                const/4 v3, 0x0
                const/16 v4, 0x32
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerCategory(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches"
                const-string v1, "newx.my_patches.perf_boost"
                const-string v2, "piko_my_patches_perf_boost_title"
                const-string v3, "piko_my_patches_perf_boost_summary"
                const/16 v4, 0x14
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerToggle(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches.perf_boost"
                const/4 v1, 0x1
                const/4 v2, 0x1
                invoke-static {v0, v1, v2}, $SETTINGS_REGISTRY->configureToggle(Ljava/lang/String;ZZ)V
                :done
                return-void
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoIsPerfBoostEnabled",
            params = emptyList(),
            returnType = "Z",
            registerCount = 2,
            smali = """
                const-string v0, "newx.my_patches.perf_boost"
                const/4 v1, 0x1
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                move-result v0
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoBoostTrue",
            params = listOf("Z"),
            returnType = "Z",
            registerCount = 2,
            smali = """
                invoke-static {}, $MAIN_ACTIVITY_CLASS->pikoIsPerfBoostEnabled()Z
                move-result v0
                if-eqz v0, :orig
                const/4 v0, 0x1
                return v0
                :orig
                return p0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoBoostFalse",
            params = listOf("Z"),
            returnType = "Z",
            registerCount = 2,
            smali = """
                invoke-static {}, $MAIN_ACTIVITY_CLASS->pikoIsPerfBoostEnabled()Z
                move-result v0
                if-eqz v0, :orig
                const/4 v0, 0x0
                return v0
                :orig
                return p0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoRequestMaxRefreshRate",
            params = listOf("Landroid/app/Activity;"),
            returnType = "V",
            registerCount = 15,
            smali = """
                invoke-static {}, $MAIN_ACTIVITY_CLASS->pikoIsPerfBoostEnabled()Z
                move-result v9
                if-nez v9, :run
                return-void
                :run
                invoke-virtual/range {p0 .. p0}, Landroid/app/Activity;->getDisplay()Landroid/view/Display;
                move-result-object v0
                if-eqz v0, :done
                invoke-virtual {v0}, Landroid/view/Display;->getMode()Landroid/view/Display${'$'}Mode;
                move-result-object v1
                if-eqz v1, :done
                invoke-virtual {v0}, Landroid/view/Display;->getSupportedModes()[Landroid/view/Display${'$'}Mode;
                move-result-object v2
                if-eqz v2, :done
                invoke-virtual {v1}, Landroid/view/Display${'$'}Mode;->getPhysicalWidth()I
                move-result v3
                invoke-virtual {v1}, Landroid/view/Display${'$'}Mode;->getPhysicalHeight()I
                move-result v4
                move-object v5, v1
                array-length v6, v2
                const/4 v7, 0x0
                :loop
                if-ge v7, v6, :apply
                aget-object v8, v2, v7
                invoke-virtual {v8}, Landroid/view/Display${'$'}Mode;->getPhysicalWidth()I
                move-result v9
                if-ne v9, v3, :next
                invoke-virtual {v8}, Landroid/view/Display${'$'}Mode;->getPhysicalHeight()I
                move-result v9
                if-ne v9, v4, :next
                invoke-virtual {v8}, Landroid/view/Display${'$'}Mode;->getRefreshRate()F
                move-result v10
                invoke-virtual {v5}, Landroid/view/Display${'$'}Mode;->getRefreshRate()F
                move-result v11
                cmpl-float v9, v10, v11
                if-lez v9, :next
                move-object v5, v8
                :next
                add-int/lit8 v7, v7, 0x1
                goto :loop
                :apply
                invoke-virtual/range {p0 .. p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
                move-result-object v12
                if-eqz v12, :done
                invoke-virtual {v12}, Landroid/view/Window;->getAttributes()Landroid/view/WindowManager${'$'}LayoutParams;
                move-result-object v13
                if-eqz v13, :done
                invoke-virtual {v5}, Landroid/view/Display${'$'}Mode;->getModeId()I
                move-result v9
                iput v9, v13, Landroid/view/WindowManager${'$'}LayoutParams;->preferredDisplayModeId:I
                invoke-virtual {v12, v13}, Landroid/view/Window;->setAttributes(Landroid/view/WindowManager${'$'}LayoutParams;)V
                :done
                return-void
            """.trimIndent(),
        )

        // Hook MainActivity.onCreate(Bundle) to request max supported refresh rate
        mutableMain.methods.firstOrNull {
            it.name == "onCreate" && it.parameters.size == 1 && it.parameters[0].type == "Landroid/os/Bundle;"
        }?.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $MAIN_ACTIVITY_CLASS->pikoRequestMaxRefreshRate(Landroid/app/Activity;)V",
         )   

        // Override performance and telemetry feature switches at call sites
        val allSwitches = ENABLE_PERF_SWITCHES + DISABLE_TELEMETRY_SWITCHES
        classDefForEach { cls ->
            if (!cls.type.startsWith("Lcom/x/")) return@classDefForEach
            var hasMatch = false
            for (m in cls.methods) {
                val insns = m.implementation?.instructions ?: continue
                if (insns.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string in allSwitches }) {
                    hasMatch = true
                    break
                }
            }
            if (!hasMatch) return@classDefForEach

            val mutableCls = mutableClassDefBy(cls)
            for (method in mutableCls.methods) {
                val insns = method.implementation?.instructions?.toList() ?: continue
                val patches = mutableListOf<Pair<Int, Pair<Int, Boolean>>>()
                for (i in insns.indices) {
                    val key = ((insns[i] as? ReferenceInstruction)?.reference as? StringReference)?.string ?: continue
                    if (key !in allSwitches) continue
                    val forceTrue = key in ENABLE_PERF_SWITCHES
                    for (j in i + 1 until minOf(i + 12, insns.size - 1)) {
                        val ref = (insns[j] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        if (ref.name == "getBoolean" && ref.returnType == "Z" && insns[j + 1].opcode == Opcode.MOVE_RESULT) {
                            val resReg = (insns[j + 1] as OneRegisterInstruction).registerA
                            patches.add((j + 2) to (resReg to forceTrue))
                            break
                        }
                    }
                }
                for ((insertIdx, regAndFlag) in patches.sortedByDescending { it.first }) {
                    val (resReg, forceTrue) = regAndFlag
                    val helperName = if (forceTrue) "pikoBoostTrue" else "pikoBoostFalse"
                    method.addInstructions(
                        insertIdx,
                        """
                            invoke-static/range {v$resReg .. v$resReg}, $MAIN_ACTIVITY_CLASS->$helperName(Z)Z
                            move-result v$resReg
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    finalize {
        classDefByOrNull(BUILT_IN_SETTINGS)?.let { builtInDef ->
            mutableClassDefBy(builtInDef).methods
                .firstOrNull { it.name == "register" && it.parameters.isEmpty() }
                ?.addInstructions(
                    0,
                    "invoke-static {}, $MAIN_ACTIVITY_CLASS->pikoRegisterPerfSettings()V",
                )
        }
    }
}
