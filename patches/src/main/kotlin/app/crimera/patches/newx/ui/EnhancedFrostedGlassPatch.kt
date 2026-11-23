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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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
                "Luminous frosted glass sheen, vivid 16dp blur, bright specular borders, translucent drawer, and XChat Liquid Glass",
            )
        }
    }
}

@Suppress("unused")
val enhancedFrostedGlassPatch = bytecodePatch(
    name = "Enhanced frosted glass",
    description = "Strong frosted glass look: icy-white glass sheen, vivid 16dp blur, frosted grain, bright 1.5dp specular borders, translucent drawer, and XChat Liquid Glass headers.",
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

        // Top bars / standard haze tint alpha: 0.75f -> 0.28f
        addStaticHelper(
            name = "pikoGlassBarAlpha",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x3e8f5c29
                return v0
                :stock
                const/high16 v0, 0x3f400000
                return v0
            """.trimIndent(),
        )

        // Elevated sheets / menus haze tint alpha: 0.90f -> 0.35f
        addStaticHelper(
            name = "pikoGlassSheetAlpha",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x3eb33333
                return v0
                :stock
                const v0, 0x3f666666
                return v0
            """.trimIndent(),
        )

        // Floating glass bottom nav pill tint alpha: 0.65f -> 0.22f
        addStaticHelper(
            name = "pikoGlassPillAlpha",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x3e6147ae
                return v0
                :stock
                const v0, 0x3f266666
                return v0
            """.trimIndent(),
        )

        // Glass pill border width: 1.0dp -> 1.5dp
        addStaticHelper(
            name = "pikoGlassRimWidth",
            params = emptyList(),
            returnType = "F",
            registerCount = 1,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const/high16 v0, 0x3fc00000
                return v0
                :stock
                const/high16 v0, 0x3f800000
                return v0
            """.trimIndent(),
        )

        // Specular highlight rim stops: 3.2x brightness when enabled
        addStaticHelper(
            name = "pikoGlassRimAlpha",
            params = listOf("F"),
            returnType = "F",
            registerCount = 2,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x404ccccd
                mul-float/2addr p0, v0
                :stock
                return p0
            """.trimIndent(),
        )

        // Global Haze blur radius: 32-40dp -> 16dp (vivid colors glow through instead of mud)
        addStaticHelper(
            name = "pikoGlassBlurRadius",
            params = listOf("F"),
            returnType = "F",
            registerCount = 2,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const/high16 p0, 0x41800000
                :stock
                return p0
            """.trimIndent(),
        )

        // Global Haze frosted grain: 0.05f -> 0.18f
        addStaticHelper(
            name = "pikoGlassNoiseFactor",
            params = listOf("F"),
            returnType = "F",
            registerCount = 2,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const p0, 0x3e3851ec
                :stock
                return p0
            """.trimIndent(),
        )

        // Global Haze 2-layer frosted tint: append 13% icy-white sheen over base tint
        addStaticHelper(
            name = "pikoEnhanceHazeTints",
            params = listOf("Ljava/util/List;"),
            returnType = "Ljava/util/List;",
            registerCount = 6,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0, p0}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
                sget-wide v1, Lcom/x/compose/core/z2;->E1:J
                const v3, 0x3e051eb8
                invoke-static {v3, v1, v2}, Landroidx/compose/ui/graphics/z;->c(FJ)J
                move-result-wide v1
                new-instance v3, Ldev/chrisbanes/haze/blur/g0;
                invoke-direct {v3, v1, v2}, Ldev/chrisbanes/haze/blur/g0;-><init>(J)V
                invoke-virtual {v0, v3}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                return-object v0
                :stock
                return-object p0
            """.trimIndent(),
        )

        // Translucent frosted Side Drawer container color (0.72f alpha)
        addStaticHelper(
            name = "pikoGlassDrawerColor",
            params = listOf("J"),
            returnType = "J",
            registerCount = 5,
            smali = """
                invoke-static {}, $glassType->pikoIsEnhancedGlassEnabled()Z
                move-result v0
                if-eqz v0, :stock
                const v0, 0x3f3851ec
                invoke-static {v0, p0, p1}, Landroidx/compose/ui/graphics/z;->c(FJ)J
                move-result-wide v0
                return-wide v0
                :stock
                return-wide p0
            """.trimIndent(),
        )

        // Translucent Side Drawer bottom bar alpha: 0.95f -> 0.45f
        addStaticHelper(
            name = "pikoGlassDrawerBarAlpha",
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
                const v0, 0x3f733333
                return v0
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

        // 1. Hook global Haze effect builder com/x/compose/core/t0.invoke (blurRadius, noiseFactor, 2-layer frosted sheen tints)
        val t0Def = classDefByOrNull("Lcom/x/compose/core/t0;")
            ?: throw PatchException("EnhancedFrostedGlassPatch: could not find com/x/compose/core/t0")
        val t0Invoke = mutableClassDefBy(t0Def).methods.firstOrNull { it.name == "invoke" }
            ?: throw PatchException("EnhancedFrostedGlassPatch: could not find t0.invoke")
        val t0Insns = t0Invoke.implementation?.instructions?.toList()
            ?: throw PatchException("EnhancedFrostedGlassPatch: t0.invoke has no implementation")
        val t0Hooks = mutableListOf<Pair<Int, String>>()
        for (i in t0Insns.indices) {
            val ins = t0Insns[i]
            val fRef = (ins as? ReferenceInstruction)?.reference as? FieldReference ?: continue
            if (fRef.definingClass != "Lcom/x/compose/core/t0;") continue
            val reg = (ins as TwoRegisterInstruction).registerA
            when {
                ins.opcode == Opcode.IGET && fRef.name == "b" && fRef.type == "F" ->
                    t0Hooks.add(i + 1 to "invoke-static {v$reg}, $glassType->pikoGlassBlurRadius(F)F\nmove-result v$reg")
                ins.opcode == Opcode.IGET && fRef.name == "c" && fRef.type == "F" ->
                    t0Hooks.add(i + 1 to "invoke-static {v$reg}, $glassType->pikoGlassNoiseFactor(F)F\nmove-result v$reg")
                ins.opcode == Opcode.IGET_OBJECT && fRef.name == "e" && fRef.type == "Ljava/util/List;" ->
                    t0Hooks.add(i + 1 to "invoke-static {v$reg}, $glassType->pikoEnhanceHazeTints(Ljava/util/List;)Ljava/util/List;\nmove-result-object v$reg")
            }
        }
        for ((idx, smali) in t0Hooks.sortedByDescending { it.first }) {
            t0Invoke.addInstructions(idx, smali)
        }

        // 2. Hook glass/z.b (0.65f pill tint alpha -> 0.22f)
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

        // 3. Hook glass/z.<clinit> (3.2x specular rim stops + 1.5dp border width)
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

        // 4. Hook com/x/compose/core/s0.c (0.75f default bar haze alpha -> 0.28f)
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

        // 5. Hook com/x/compose/core/u0.f (0.90f elevated sheet/menu haze alpha -> 0.35f)
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

        // 6. Hook Side Drawer (com/x/main/drawer/a.o container color -> 0.72f alpha, a.g bottom bar -> 0.45f alpha)
        classDefByOrNull("Lcom/x/main/drawer/a;")?.let { drawerDef ->
            val drawerMutable = mutableClassDefBy(drawerDef)
            drawerMutable.methods.firstOrNull { it.name == "o" }?.let { drawerO ->
                val insns = drawerO.implementation?.instructions?.toList() ?: return@let
                val colorIdx = insns.indexOfFirst { ins ->
                    ins.opcode == Opcode.IGET_WIDE &&
                        ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let { f ->
                            f.definingClass == "Lcom/x/compose/theme/e;" && f.name == "q"
                        } == true
                }
                if (colorIdx >= 0) {
                    val regLo = (insns[colorIdx] as TwoRegisterInstruction).registerA
                    val regHi = regLo + 1
                    drawerO.addInstructions(
                        colorIdx + 1,
                        """
                            invoke-static {v$regLo, v$regHi}, $glassType->pikoGlassDrawerColor(J)J
                            move-result-wide v$regLo
                        """.trimIndent(),
                    )
                }
            }
            drawerMutable.methods.firstOrNull { it.name == "g" }?.let { drawerG ->
                val insns = drawerG.implementation?.instructions?.toList() ?: return@let
                val alphaIdx = insns.indexOfFirst { ins ->
                    (ins.opcode == Opcode.CONST || ins.opcode == Opcode.CONST_HIGH16) &&
                        (ins as? NarrowLiteralInstruction)?.narrowLiteral == 0x3f733333
                }
                if (alphaIdx >= 0) {
                    val reg = (insns[alphaIdx] as OneRegisterInstruction).registerA
                    drawerG.addInstructions(
                        alphaIdx + 1,
                        """
                            invoke-static {}, $glassType->pikoGlassDrawerBarAlpha()F
                            move-result v$reg
                        """.trimIndent(),
                    )
                }
            }
        }

        // 7. Hook FeatureSwitchesRepositoryImpl.getBoolean(String, Z)Z
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
