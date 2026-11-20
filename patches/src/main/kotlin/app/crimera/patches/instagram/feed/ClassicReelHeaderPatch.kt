package app.crimera.patches.instagram.feed

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/instagram/settings/BooleanSetting;"
private const val BASE_BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/crimera/settings/BooleanSetting;"
private const val RESTART_POLICY_CLASS = "Lapp/morphe/extension/instagram/settings/RestartPolicy;"
private const val SHARED_PREF_CLASS = "Lapp/morphe/extension/crimera/sharedPreference/SharedPref;"
private const val SCREEN_BUILDER_CLASS = "Lapp/morphe/extension/instagram/settings/preference/ScreenBuilder;"
private const val SETTINGS_STATUS_CLASS = "Lapp/morphe/extension/instagram/settings/SettingsStatus;"

@Suppress("unused")
val classicReelHeaderPatch = bytecodePatch(
    name = "Classic reel header above video",
    description = "Moves vertical reel username and menu button above outside the video with equal top/bottom center-crop across all Home feeds (For You, Following, Favorites). Adds a toggle in Distraction free settings.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    var resolvedBinderType: String? = null

    execute {
        var rowTypeEnumClass: String? = null
        var fullHeightFieldName: String? = null

        classDefForEach { cls ->
            if (fullHeightFieldName != null) return@classDefForEach
            val clinit = cls.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
            val insns = clinit.implementation?.instructions?.toList() ?: return@classDefForEach
            for (i in insns.indices) {
                val str = ((insns[i] as? ReferenceInstruction)?.reference as? StringReference)?.string ?: continue
                if (str == "LITHO_FULL_HEIGHT_SINGLE_MEDIA") {
                    for (j in i + 1 until minOf(i + 8, insns.size)) {
                        if (insns[j].opcode == Opcode.SPUT_OBJECT) {
                            val fRef = (insns[j] as? ReferenceInstruction)?.reference as? FieldReference
                            if (fRef != null && fRef.definingClass == cls.type) {
                                rowTypeEnumClass = cls.type
                                fullHeightFieldName = fRef.name
                                break
                            }
                        }
                    }
                }
            }
        }

        val enumType = rowTypeEnumClass
            ?: throw PatchException("ClassicReelHeaderPatch: could not find row type enum for LITHO_FULL_HEIGHT_SINGLE_MEDIA")
        val fhField = fullHeightFieldName
            ?: throw PatchException("ClassicReelHeaderPatch: could not find field for LITHO_FULL_HEIGHT_SINGLE_MEDIA")

        var targetBinderType: String? = null
        var targetGateMethodName: String? = null

        classDefForEach { cls ->
            if (targetBinderType != null) return@classDefForEach
            val buildMethod = cls.methods.firstOrNull { it.name == "buildRowViewTypes" } ?: return@classDefForEach
            val usesFullHeight = buildMethod.implementation?.instructions?.any { insn ->
                val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
                fRef != null && fRef.definingClass == enumType && fRef.name == fhField
            } == true
            if (!usesFullHeight) return@classDefForEach

            val gateMethod = cls.methods.firstOrNull { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) &&
                    m.returnType == "Z" &&
                    m.parameters.size == 3 &&
                    m.parameters[1].type == cls.type &&
                    m.parameters[2].type == "Ljava/lang/String;"
            }
            if (gateMethod != null) {
                targetBinderType = cls.type
                targetGateMethodName = gateMethod.name
            }
        }

        val binderType = targetBinderType
            ?: throw PatchException("ClassicReelHeaderPatch: could not find FeedFullHeightMediaBinderGroup")
        val gateMethodName = targetGateMethodName
            ?: throw PatchException("ClassicReelHeaderPatch: could not find full-height gate method")

        resolvedBinderType = binderType
        val mutableBinder = mutableClassDefBy(classDefBy(binderType))

        mutableBinder.fields.add(
            ImmutableField(
                binderType,
                "pikoClassicReelHeaderSetting",
                BOOLEAN_SETTING_CLASS,
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
                binderType,
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
            mutableBinder.methods.add(helper)
        }

        addStaticHelper(
            name = "pikoGetClassicReelHeaderSetting",
            params = emptyList(),
            returnType = BOOLEAN_SETTING_CLASS,
            registerCount = 4,
            smali = """
                sget-object v0, $binderType->pikoClassicReelHeaderSetting:$BOOLEAN_SETTING_CLASS
                if-nez v0, :ret
                new-instance v0, $BOOLEAN_SETTING_CLASS
                const-string v1, "piko_classic_reel_header"
                sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                sget-object v3, $RESTART_POLICY_CLASS->NOT_REQUIRED:$RESTART_POLICY_CLASS
                invoke-direct {v0, v1, v2, v3}, $BOOLEAN_SETTING_CLASS-><init>(Ljava/lang/String;Ljava/lang/Boolean;$RESTART_POLICY_CLASS)V
                sput-object v0, $binderType->pikoClassicReelHeaderSetting:$BOOLEAN_SETTING_CLASS
                :ret
                return-object v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoIsClassicReelHeaderEnabled",
            params = emptyList(),
            returnType = "Z",
            registerCount = 2,
            smali = """
                invoke-static {}, $binderType->pikoGetClassicReelHeaderSetting()$BOOLEAN_SETTING_CLASS
                move-result-object v0
                invoke-static {v0}, $SHARED_PREF_CLASS->getBooleanPref($BASE_BOOLEAN_SETTING_CLASS)Ljava/lang/Boolean;
                move-result-object v0
                if-eqz v0, :default_on
                invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
                move-result v0
                return v0
                :default_on
                const/4 v0, 0x1
                return v0
            """.trimIndent(),
        )

        val gateMethod = mutableBinder.methods.first {
            it.name == gateMethodName &&
                AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.returnType == "Z" &&
                it.parameters.size == 3
        }
        gateMethod.addInstructions(
            0,
            """
                invoke-static {}, $binderType->pikoIsClassicReelHeaderEnabled()Z
                move-result v0
                if-eqz v0, :piko_orig
                const/4 v0, 0x0
                return v0
                :piko_orig
            """.trimIndent(),
        )
    }

    finalize {
        val binderType = resolvedBinderType ?: return@finalize

        classDefByOrNull(SETTINGS_STATUS_CLASS)?.let { statusDef ->
            mutableClassDefBy(statusDef).methods
                .firstOrNull { it.name == "distractionFreeSection" && it.parameters.isEmpty() }
                ?.addInstructions(
                    0,
                    """
                        const/4 v0, 0x1
                        return v0
                    """.trimIndent(),
                )
        }

        classDefByOrNull(SCREEN_BUILDER_CLASS)?.let { sbDef ->
            val mutableSb = mutableClassDefBy(sbDef)
            val helperImpl = MutableMethodImplementation(4)
            helperImpl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            val addToggleMethod = ImmutableMethod(
                SCREEN_BUILDER_CLASS,
                "pikoAddClassicReelHeaderToggle",
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value,
                null,
                null,
                helperImpl,
            ).toMutable()
            addToggleMethod.addInstructions(
                0,
                """
                    const-string v0, "Classic reel header above video"
                    const-string v1, "Show username and menu button above vertical reels (center-cropped equally from top and bottom) in all Home feeds"
                    invoke-static {}, $binderType->pikoGetClassicReelHeaderSetting()$BOOLEAN_SETTING_CLASS
                    move-result-object v2
                    invoke-direct {p0, v0, v1, v2}, $SCREEN_BUILDER_CLASS->addLockableSwitch(Ljava/lang/String;Ljava/lang/String;$BOOLEAN_SETTING_CLASS)V
                    return-void
                """.trimIndent(),
            )
            addToggleMethod.removeInstruction(addToggleMethod.implementation!!.instructions.size - 1)
            mutableSb.methods.add(addToggleMethod)

            mutableSb.methods
                .firstOrNull { it.name == "distractionFreeSection" && it.parameters.isEmpty() }
                ?.let { sectionMethod ->
                    val insns = sectionMethod.implementation!!.instructions
                    val lastReturnIdx = insns.indexOfLast { it.opcode == Opcode.RETURN_VOID }
                    if (lastReturnIdx >= 0) {
                        sectionMethod.addInstructions(
                            lastReturnIdx,
                            "invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoAddClassicReelHeaderToggle()V",
                        )
                    }
                }
        }
    }
}
