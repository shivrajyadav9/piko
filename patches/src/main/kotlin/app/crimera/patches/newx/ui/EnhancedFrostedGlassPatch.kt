package app.crimera.patches.newx.ui

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val BUILT_IN_SETTINGS = "Lapp/morphe/extension/newx/settings/BuiltInSettings;"
private const val FEATURE_SWITCHES_IMPL = "Lcom/x/featureswitches/FeatureSwitchesRepositoryImpl;"

private val enhancedFrostedGlassResourcesPatch = resourcePatch {
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
            addStringIfMissing("piko_my_patches_enhanced_glass_title", "Enhanced frosted glass")
            addStringIfMissing(
                "piko_my_patches_enhanced_glass_summary",
                "Clearer frosted glass bars, menus, and nav pill with bright specular borders and XChat Liquid Glass header",
            )
        }
    }
}

@Suppress("unused")
val enhancedFrostedGlassPatch = bytecodePatch(
    name = "Enhanced frosted glass",
    description = "Upgrades NewX frosted glass translucency, bright specular borders on glass pills, and enables XChat Liquid Glass headers.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)
    dependsOn(enhancedFrostedGlassResourcesPatch)

    var resolvedHelperClass: String? = null

    execute {
        val glassDef = classDefByOrNull("Lcom/x/ui/common/glass/z;")
            ?: throw PatchException("EnhancedFrostedGlassPatch: could not find com/x/ui/common/glass/z")
        val glassMutable = mutableClassDefBy(glassDef)
        val glassType = glassMutable.type
        resolvedHelperClass = glassType

        if (glassMutable.staticFields.none { it.name == "pikoEnhancedGlassRegistered" }) {
            glassMutable.staticFields.add(
                ImmutableField(
                    glassType,
                    "pikoEnhancedGlassRegistered",
                    "Z",
                    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    null,
                ).toMutable(),
            )
        }

        fun addStaticHelper(
            name: String,
            params: List<String>,
            returnType: String,
            registerCount: Int,
            smali: String,
        ) {
            if (glassMutable.methods.any { it.name == name }) return
            val impl = MutableMethodImplementation(registerCount)
            impl.addInstruction(BuilderInstruction10x(Opcode.NOP))
            val m = ImmutableMethod(
                glassType,
                name,
                params.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                impl,
            ).toMutable()
            m.addInstructions(0, smali)
            glassMutable.methods.add(m)
        }

        addStaticHelper(
            name = "pikoRegisterEnhancedGlassSettings",
            params = emptyList(),
            returnType = "V",
            registerCount = 5,
            smali = """
                sget-boolean v0, $glassType->pikoEnhancedGlassRegistered:Z
                if-nez v0, :done
                const/4 v0, 0x1
                sput-boolean v0, $glassType->pikoEnhancedGlassRegistered:Z
                const-string v0, "newx.my_patches"
                const-string v1, "piko_my_patches_category_title"
                const-string v2, "piko_my_patches_category_summary"
                const-string v3, "ic_vector_wrench"
                const/16 v4, 0x32
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerCategory(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches"
                const-string v1, "newx.my_patches.enhanced_frosted_glass"
                const-string v2, "piko_my_patches_enhanced_glass_title"
                const-string v3, "piko_my_patches_enhanced_glass_summary"
                const/16 v4, 0x3c
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerToggle(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches.enhanced_frosted_glass"
                const/4 v1, 0x1
                const/4 v2, 0x1
                invoke-static {v0, v1, v2}, $SETTINGS_REGISTRY->configureToggle(Ljava/lang/String;ZZ)V
                :done
                return-void
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoIsEnhancedGlassEnabled",
            params = emptyList(),
            returnType = "Z",
            registerCount = 2,
            smali = """
                const-string v0, "newx.my_patches.enhanced_frosted_glass"
                const/4 v1, 0x1
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                move-result v0
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoGlassBarAlpha",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x3ee66666
                return v0
                :stock
                const/high16 v0, 0x3f400000
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoGlassSheetAlpha",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x3f0ccccd
                return v0
                :stock
                const v0, 0x3f666666
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoGlassPillAlpha",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x3ec28f5c
                return v0
                :stock
                const v0, 0x3f266666
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoGlassRimWidth",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const/high16 v0, 0x3fa00000
                return v0
                :stock
                const/high16 v0, 0x3f800000
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoGlassRimAlpha",
            params = listOf("F"),
            returnType = "F",
            registerCount = 2,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const/high16 v0, 0x40000000
                mul-float/2addr p0, v0
                :stock
                return p0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoGlassFeatureSwitch",
            params = listOf("Ljava/lang/String;"),
            returnType = "I",
            registerCount = 3,
            smali = """
                if-eqz p0, :pass
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :pass
                const-string v0, "xchat_liquid_glass_convo_header_enabled"
                invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :check_pill
                const/4 v0, 0x1
                return v0
                :check_pill
                const-string v0, "x_android_home_timeline_new_posts_pill_blur_enabled"
                invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :check_video
                const/4 v0, 0x1
                return v0
                :check_video
                const-string v0, "enable_native_video_blur_effect"
                invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :check_adaptive
                const/4 v0, 0x1
                return v0
                :check_adaptive
                const-string v0, "x_lite_infra_haze_adaptive_performance_enabled"
                invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :pass
                const/4 v0, 0x0
                return v0
                :pass
                const/4 v0, -0x1
                return v0
            """.trimIndent(),
        )

        val glassBMethod = glassMutable.methods.firstOrNull { m ->
            m.name == "b" && m.returnType == "Landroidx/compose/ui/Modifier;"
        } ?: throw PatchException("EnhancedFrostedGlassPatch: could not find glass/z.b")
        val glassBInsns = glassBMethod.implementation?.instructions?.toList()
            ?: throw PatchException("EnhancedFrostedGlassPatch: glass/z.b has no implementation")
        val pillAlphaIdx = glassBInsns.indexOfFirst { ins ->
            (ins.opcode == Opcode.CONST || ins.opcode == Opcode.CONST_HIGH16) &&
                (ins as? NarrowLiteralInstruction)?.narrowLiteral == 0x3f266666
        }
        if (pillAlphaIdx < 0) {
            throw PatchException("EnhancedFrostedGlassPatch: could not find 0.65f alpha in glass/z.b")
        }
        val pillAlphaReg = (glassBInsns[pillAlphaIdx] as OneRegisterInstruction).registerA
        glassBMethod.addInstructions(
            pillAlphaIdx + 1,
            """
                invoke-static {}, $glassType->pikoGlassPillAlpha()F
                move-result v$pillAlphaReg
            """.trimIndent(),
        )

        val glassClinit = glassMutable.methods.firstOrNull { it.name == "<clinit>" }
            ?: throw PatchException("EnhancedFrostedGlassPatch: could not find glass/z.<clinit>")
        val clinitInsns = glassClinit.implementation?.instructions?.toList()
            ?: throw PatchException("EnhancedFrostedGlassPatch: glass/z.<clinit> has no implementation")
        val rimAlphas = setOf(
            0x3e23d70a,
            0x3e3851ec,
            0x3e0f5c29,
            0x3d23d70a,
            0x3e051eb8,
            0x3e2e147b,
            0x3e570a3d,
            0x3d8f5c29,
        )
        val rimMatches = clinitInsns.mapIndexedNotNull { idx, ins ->
            if (ins.opcode == Opcode.CONST && (ins as? NarrowLiteralInstruction)?.narrowLiteral in rimAlphas) {
                idx to (ins as OneRegisterInstruction).registerA
            } else {
                null
            }
        }
        for ((idx, reg) in rimMatches.reversed()) {
            glassClinit.addInstructions(
                idx + 1,
                """
                    invoke-static {v$reg}, $glassType->pikoGlassRimAlpha(F)F
                    move-result v$reg
                """.trimIndent(),
            )
        }
        val sputBorderIdx = glassClinit.implementation!!.instructions.toList().indexOfFirst { ins ->
            ins.opcode == Opcode.SPUT &&
                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let { f ->
                    f.definingClass == glassType && f.type == "F"
                } == true
        }
        if (sputBorderIdx >= 0) {
            val borderField = ((glassClinit.implementation!!.instructions.toList()[sputBorderIdx] as ReferenceInstruction).reference as FieldReference).name
            glassClinit.addInstructions(
                sputBorderIdx + 1,
                """
                    invoke-static {}, $glassType->pikoGlassRimWidth()F
                    move-result v1
                    sput v1, $glassType->$borderField:F
                """.trimIndent(),
            )
        }

        val s0Def = classDefByOrNull("Lcom/x/compose/core/s0;")
            ?: throw PatchException("EnhancedFrostedGlassPatch: could not find com/x/compose/core/s0")
        val s0Method = mutableClassDefBy(s0Def).methods.firstOrNull { m ->
            m.name == "c" && m.returnType == "Ldev/chrisbanes/haze/blur/g0;"
        } ?: throw PatchException("EnhancedFrostedGlassPatch: could not find s0.c")
        val s0Insns = s0Method.implementation?.instructions?.toList()
            ?: throw PatchException("EnhancedFrostedGlassPatch: s0.c has no implementation")
        val barAlphaIdx = s0Insns.indexOfFirst { ins ->
            (ins.opcode == Opcode.CONST_HIGH16 || ins.opcode == Opcode.CONST) &&
                (ins as? NarrowLiteralInstruction)?.narrowLiteral == 0x3f400000
        }
        if (barAlphaIdx < 0) {
            throw PatchException("EnhancedFrostedGlassPatch: could not find 0.75f alpha in s0.c")
        }
        val barAlphaReg = (s0Insns[barAlphaIdx] as OneRegisterInstruction).registerA
        s0Method.addInstructions(
            barAlphaIdx + 1,
            """
                invoke-static {}, $glassType->pikoGlassBarAlpha()F
                move-result v$barAlphaReg
            """.trimIndent(),
        )

        val u0Def = classDefByOrNull("Lcom/x/compose/core/u0;")
            ?: throw PatchException("EnhancedFrostedGlassPatch: could not find com/x/compose/core/u0")
        val u0FMethod = mutableClassDefBy(u0Def).methods.firstOrNull { m ->
            m.name == "f" && m.returnType == "Landroidx/compose/ui/Modifier;"
        } ?: throw PatchException("EnhancedFrostedGlassPatch: could not find u0.f")
        val u0FInsns = u0FMethod.implementation?.instructions?.toList()
            ?: throw PatchException("EnhancedFrostedGlassPatch: u0.f has no implementation")
        val sheetAlphaIdx = u0FInsns.indexOfFirst { ins ->
            (ins.opcode == Opcode.CONST || ins.opcode == Opcode.CONST_HIGH16) &&
                (ins as? NarrowLiteralInstruction)?.narrowLiteral == 0x3f666666
        }
        if (sheetAlphaIdx < 0) {
            throw PatchException("EnhancedFrostedGlassPatch: could not find 0.90f alpha in u0.f")
        }
        val sheetAlphaReg = (u0FInsns[sheetAlphaIdx] as OneRegisterInstruction).registerA
        u0FMethod.addInstructions(
            sheetAlphaIdx + 1,
            """
                invoke-static {}, $glassType->pikoGlassSheetAlpha()F
                move-result v$sheetAlphaReg
            """.trimIndent(),
        )

        classDefByOrNull(FEATURE_SWITCHES_IMPL)?.let { fsDef ->
            mutableClassDefBy(fsDef).methods.firstOrNull { m ->
                m.name == "getBoolean" &&
                    m.parameters.size == 2 &&
                    m.parameters[0].type == "Ljava/lang/String;" &&
                    m.parameters[1].type == "Z" &&
                    m.returnType == "Z"
            }?.addInstructions(
                0,
                """
                    invoke-static {p1}, $glassType->pikoGlassFeatureSwitch(Ljava/lang/String;)I
                    move-result v0
                    if-ltz v0, :piko_glass_pass
                    return v0
                    :piko_glass_pass
                """.trimIndent(),
            )
        }
    }

    finalize {
        val helperClassType = resolvedHelperClass ?: return@finalize
        classDefByOrNull(BUILT_IN_SETTINGS)?.let { builtInDef ->
            mutableClassDefBy(builtInDef).methods
                .firstOrNull { it.name == "register" && it.parameters.isEmpty() }
                ?.addInstructions(
                    0,
                    "invoke-static {}, $helperClassType->pikoRegisterEnhancedGlassSettings()V",
                )
        }
    }
}
