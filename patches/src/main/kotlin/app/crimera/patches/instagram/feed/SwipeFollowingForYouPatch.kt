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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
private const val USER_SESSION_CLASS = "Lcom/instagram/common/session/UserSession;"

@Suppress("unused")
val swipeFollowingForYouPatch = bytecodePatch(
    name = "Swipe between Following & For You",
    description = "Adds a toggle in Piko Settings (Distraction Free) to start Home on Following and swipe horizontally between Following and For You.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    var resolvedHelperType: String? = null

    execute {
        // 1. Locate MergedFeedHostFragment (contains "MergedFeedHostFragment.onViewCreated:restoreStartingTab")
        var hostDef: ClassDef? = null
        classDefForEach { cls ->
            if (hostDef == null) {
                val matches = cls.methods.any { m ->
                    m.name == "onViewCreated" && m.implementation?.instructions?.any { ins ->
                        (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                            ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                            "MergedFeedHostFragment.onViewCreated:restoreStartingTab"
                    } == true
                }
                if (matches) hostDef = cls
            }
        }
        val hostMutable = mutableClassDefBy(
            hostDef ?: throw PatchException("SwipeFollowingForYouPatch: could not find MergedFeedHostFragment"),
        )
        val hostType = hostMutable.type
        resolvedHelperType = hostType

        // 2. Locate FeedSwitcher config class (contains "feed_switcher_dropdown" & "feed_switcher_hscroll")
        var switcherCfgDef: ClassDef? = null
        classDefForEach { cls ->
            if (switcherCfgDef == null) {
                val hasStrings = cls.methods.any { m ->
                    m.name == "<clinit>" && m.implementation?.instructions?.any { ins ->
                        (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                            ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                            "feed_switcher_dropdown"
                    } == true
                }
                if (hasStrings) switcherCfgDef = cls
            }
        }
        val switcherCfgMutable = mutableClassDefBy(
            switcherCfgDef ?: throw PatchException("SwipeFollowingForYouPatch: could not find feed switcher config class"),
        )

        // 3. Locate Homecoming gating class (contains "homecoming_has_ever_opted_out")
        var homecomingDef: ClassDef? = null
        classDefForEach { cls ->
            if (homecomingDef == null) {
                val matches = cls.methods.any { m ->
                    m.implementation?.instructions?.any { ins ->
                        (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                            ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                            "homecoming_has_ever_opted_out"
                    } == true
                }
                if (matches) homecomingDef = cls
            }
        }
        val homecomingMutable = mutableClassDefBy(
            homecomingDef ?: throw PatchException("SwipeFollowingForYouPatch: could not find Homecoming gating class"),
        )

        // 4. Find Following tab singleton (LX/0SHj;.A00) from MergedFeedHostFragment.<clinit>
        val clinitInsns = hostMutable.methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions?.toList()
            ?: throw PatchException("SwipeFollowingForYouPatch: could not find MergedFeedHostFragment.<clinit>")
        val sgetFields = clinitInsns.mapNotNull { ins ->
            if (ins.opcode == Opcode.SGET_OBJECT) {
                val fRef = (ins as? ReferenceInstruction)?.reference as? FieldReference
                if (fRef != null && fRef.name == "A00" && fRef.type == fRef.definingClass) fRef else null
            } else {
                null
            }
        }
        if (sgetFields.size < 2) {
            throw PatchException("SwipeFollowingForYouPatch: could not find Following tab singleton in <clinit>")
        }
        // A1V = [0SHa (feed), 0SHj (following), 0PSS (friends), 0PST (latest)] -> index 1 is Following!
        val followingField = sgetFields[1]
        val followingClass = followingField.definingClass

        // 5. Find For You singleton (LX/0SHs;.A00) in onViewCreated before List.indexOf(Object)
        val onViewCreated = hostMutable.methods.firstOrNull { it.name == "onViewCreated" }
            ?: throw PatchException("SwipeFollowingForYouPatch: could not find onViewCreated")
        val ovcInsns = onViewCreated.implementation?.instructions?.toList()
            ?: throw PatchException("SwipeFollowingForYouPatch: onViewCreated has no implementation")
        var hookIdx = -1
        var tabReg = -1
        for (i in 0 until ovcInsns.size - 1) {
            val ins = ovcInsns[i]
            val next = ovcInsns[i + 1]
            if (ins.opcode == Opcode.SGET_OBJECT &&
                (next.opcode == Opcode.INVOKE_INTERFACE || next.opcode == Opcode.INVOKE_VIRTUAL)
            ) {
                val fRef = (ins as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                val mRef = (next as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (mRef.name == "indexOf" && fRef.name == "A00" && fRef.type == fRef.definingClass) {
                    hookIdx = i + 1
                    tabReg = (ins as OneRegisterInstruction).registerA
                    break
                }
            }
        }
        if (hookIdx < 0) {
            throw PatchException("SwipeFollowingForYouPatch: could not find initial For You tab indexOf in onViewCreated")
        }

        // 6. Add BooleanSetting + helpers on hostType
        if (hostMutable.staticFields.none { it.name == "pikoSwipeFollowingSetting" }) {
            hostMutable.staticFields.add(
                ImmutableField(
                    hostType,
                    "pikoSwipeFollowingSetting",
                    BOOLEAN_SETTING_CLASS,
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
            if (hostMutable.methods.any { it.name == name }) return
            val impl = MutableMethodImplementation(registerCount)
            impl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            val m = ImmutableMethod(
                hostType,
                name,
                params.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                impl,
            ).toMutable()
            m.addInstructions(0, smali)
            m.removeInstruction(m.implementation!!.instructions.size - 1)
            hostMutable.methods.add(m)
        }

        addStaticHelper(
            name = "pikoGetSwipeFollowingSetting",
            params = emptyList(),
            returnType = BOOLEAN_SETTING_CLASS,
            registerCount = 4,
            smali = """
                sget-object v0, $hostType->pikoSwipeFollowingSetting:$BOOLEAN_SETTING_CLASS
                if-nez v0, :have
                new-instance v0, $BOOLEAN_SETTING_CLASS
                const-string v1, "piko_swipe_following_for_you"
                const/4 v2, 0x1
                sget-object v3, $RESTART_POLICY_CLASS->REQUIRED:$RESTART_POLICY_CLASS
                invoke-direct {v0, v1, v2, v3}, $BOOLEAN_SETTING_CLASS-><init>(Ljava/lang/String;Z$RESTART_POLICY_CLASS)V
                sput-object v0, $hostType->pikoSwipeFollowingSetting:$BOOLEAN_SETTING_CLASS
                :have
                return-object v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoIsSwipeFollowingEnabled",
            params = emptyList(),
            returnType = "Z",
            registerCount = 1,
            smali = """
                invoke-static {}, $hostType->pikoGetSwipeFollowingSetting()$BOOLEAN_SETTING_CLASS
                move-result-object v0
                invoke-static {v0}, $SHARED_PREF_CLASS->getBooleanPref($BASE_BOOLEAN_SETTING_CLASS)Z
                move-result v0
                return v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoPickInitialMergedTab",
            params = listOf("Ljava/lang/Object;"),
            returnType = "Ljava/lang/Object;",
            registerCount = 2,
            smali = """
                invoke-static {}, $hostType->pikoIsSwipeFollowingEnabled()Z
                move-result v0
                if-eqz v0, :keep
                sget-object v0, $followingClass->${followingField.name}:$followingClass
                return-object v0
                :keep
                return-object p0
            """.trimIndent(),
        )

        // 7. Hook onViewCreated to start on Following (0SHj) instead of For You (0SHs)
        onViewCreated.addInstructions(
            hookIdx,
            """
                invoke-static/range {v$tabReg .. v$tabReg}, $hostType->pikoPickInitialMergedTab(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$tabReg
            """.trimIndent(),
        )

        // 8. Force Homecoming gating (02wx.A04) -> true when enabled
        homecomingMutable.methods.firstOrNull { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) &&
                m.returnType == "Z" &&
                m.parameters.size == 1 &&
                m.parameters[0].type == USER_SESSION_CLASS &&
                m.implementation?.instructions?.any { ins ->
                    (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                        ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string == "test"
                } == true
        }?.addInstructions(
            0,
            """
                invoke-static {}, $hostType->pikoIsSwipeFollowingEnabled()Z
                move-result v0
                if-eqz v0, :piko_pass_hc
                const/4 v0, 0x1
                return v0
                :piko_pass_hc
            """.trimIndent(),
        ) ?: throw PatchException("SwipeFollowingForYouPatch: could not find Homecoming A04 method")

        // 9. Force FeedSwitcher enabled (03ce.A07 -> true) and disable dropdown (03ce.A09 -> false = hscroll swipe)
        switcherCfgMutable.methods.firstOrNull { m ->
            !AccessFlags.STATIC.isSet(m.accessFlags) &&
                m.returnType == "Z" &&
                m.parameters.size == 1 &&
                m.parameters[0].type == USER_SESSION_CLASS &&
                m.implementation?.instructions?.any { ins ->
                    (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                        ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string == "feed_switcher_dropdown"
                } == true
        }?.addInstructions(
            0,
            """
                invoke-static {}, $hostType->pikoIsSwipeFollowingEnabled()Z
                move-result v0
                if-eqz v0, :piko_pass_dd
                const/4 v0, 0x0
                return v0
                :piko_pass_dd
            """.trimIndent(),
        ) ?: throw PatchException("SwipeFollowingForYouPatch: could not find dropdown check (A09)")

        switcherCfgMutable.methods.firstOrNull { m ->
            !AccessFlags.STATIC.isSet(m.accessFlags) &&
                m.returnType == "Z" &&
                m.parameters.size == 1 &&
                m.parameters[0].type == USER_SESSION_CLASS &&
                m.implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.name == "ordinal"
                } == true
        }?.addInstructions(
            0,
            """
                invoke-static {}, $hostType->pikoIsSwipeFollowingEnabled()Z
                move-result v0
                if-eqz v0, :piko_pass_fs
                const/4 v0, 0x1
                return v0
                :piko_pass_fs
            """.trimIndent(),
        ) ?: throw PatchException("SwipeFollowingForYouPatch: could not find feed switcher enabled check (A07)")
    }

    finalize {
        val helperType = resolvedHelperType ?: return@finalize

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
                "pikoAddSwipeFollowingToggle",
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
                    const-string v0, "Swipe between Following & For You"
                    const-string v1, "Start Home on Following and swipe horizontally between Following and For You"
                    invoke-static {}, $helperType->pikoGetSwipeFollowingSetting()$BOOLEAN_SETTING_CLASS
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
                            "invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoAddSwipeFollowingToggle()V",
                        )
                    }
                }
        }
    }
}
