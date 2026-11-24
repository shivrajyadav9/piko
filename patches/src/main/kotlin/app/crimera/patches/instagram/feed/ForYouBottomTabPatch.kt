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
val forYouBottomTabPatch = bytecodePatch(
    name = "Add For You to bottom tab bar",
    description = "Adds a toggle in Piko Settings (Distraction Free) to add the For You (Feed Switcher) tab next to Home on Instagram's bottom navigation bar.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    var resolvedHelperType: String? = null

    execute {
        // 1. Locate bottom navigation tab enum (contains "fragment_feed", "fragment_feed_switcher", "fragment_profile")
        var tabEnumDef: ClassDef? = null
        classDefForEach { cls ->
            if (tabEnumDef == null && cls.superclass == "Ljava/lang/Enum;") {
                val clinit = cls.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
                val strs = clinit.implementation?.instructions
                    ?.filter { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
                    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                    ?.toSet()
                    ?: return@classDefForEach
                if ("fragment_feed" in strs && "fragment_feed_switcher" in strs && "fragment_profile" in strs) {
                    tabEnumDef = cls
                }
            }
        }
        val tabEnum = tabEnumDef?.type
            ?: throw PatchException("ForYouBottomTabPatch: could not find bottom navigation tab enum")

        // Find FEED ("fragment_feed") and FEED_SWITCHER ("fragment_feed_switcher") static fields in <clinit>
        val clinitInsns = tabEnumDef!!.methods.first { it.name == "<clinit>" }.implementation!!.instructions.toList()
        fun findEnumFieldForString(marker: String): String {
            val sIdx = clinitInsns.indexOfFirst { ins ->
                (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                    ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string == marker
            }
            if (sIdx < 0) throw PatchException("ForYouBottomTabPatch: could not find $marker in $tabEnum.<clinit>")
            for (j in (sIdx + 1) until minOf(clinitInsns.size, sIdx + 15)) {
                val ins = clinitInsns[j]
                if (ins.opcode == Opcode.SPUT_OBJECT) {
                    val fRef = (ins as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                    if (fRef.definingClass == tabEnum && fRef.type == tabEnum) return fRef.name
                }
            }
            throw PatchException("ForYouBottomTabPatch: could not find enum field for $marker in $tabEnum")
        }
        val feedField = findEnumFieldForString("fragment_feed")
        val feedSwitcherField = findEnumFieldForString("fragment_feed_switcher")

        // 2. Locate bottom tab list builder class (has ()Ljava/util/List; referencing tabEnum->feedField AND (UserSession;Z)Ljava/util/List;)
        var builderDef: ClassDef? = null
        classDefForEach { cls ->
            if (builderDef == null) {
                val hasDefaultList = cls.methods.any { m ->
                    m.parameters.isEmpty() &&
                        m.returnType == "Ljava/util/List;" &&
                        m.implementation?.instructions?.any { ins ->
                            ins.opcode == Opcode.SGET_OBJECT &&
                                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let { f ->
                                    f.definingClass == tabEnum && f.name == feedField
                                } == true
                        } == true
                }
                val hasBuildTabs = cls.methods.any { m ->
                    m.returnType == "Ljava/util/List;" &&
                        m.parameters.size == 2 &&
                        m.parameters[0].type == USER_SESSION_CLASS &&
                        m.parameters[1].type == "Z"
                }
                if (hasDefaultList && hasBuildTabs) builderDef = cls
            }
        }
        val builderMutable = mutableClassDefBy(
            builderDef ?: throw PatchException("ForYouBottomTabPatch: could not find bottom tab list builder"),
        )
        val builderType = builderMutable.type
        resolvedHelperType = builderType

        val buildTabsMethod = builderMutable.methods.first { m ->
            m.returnType == "Ljava/util/List;" &&
                m.parameters.size == 2 &&
                m.parameters[0].type == USER_SESSION_CLASS &&
                m.parameters[1].type == "Z"
        }

        // 3. Add BooleanSetting static field + helpers on builderType
        if (builderMutable.staticFields.none { it.name == "pikoForYouBottomTabSetting" }) {
            builderMutable.staticFields.add(
                ImmutableField(
                    builderType,
                    "pikoForYouBottomTabSetting",
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
            if (builderMutable.methods.any { it.name == name }) return
            val impl = MutableMethodImplementation(registerCount)
            impl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            val m = ImmutableMethod(
                builderType,
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
            builderMutable.methods.add(m)
        }

        addStaticHelper(
            name = "pikoGetForYouBottomTabSetting",
            params = emptyList(),
            returnType = BOOLEAN_SETTING_CLASS,
            registerCount = 4,
            smali = """
                sget-object v0, $builderType->pikoForYouBottomTabSetting:$BOOLEAN_SETTING_CLASS
                if-nez v0, :have
                new-instance v0, $BOOLEAN_SETTING_CLASS
                const-string v1, "piko_for_you_bottom_tab"
                sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                sget-object v3, $RESTART_POLICY_CLASS->REQUIRED:$RESTART_POLICY_CLASS
                invoke-direct {v0, v1, v2, v3}, $BOOLEAN_SETTING_CLASS-><init>(Ljava/lang/String;Ljava/lang/Boolean;$RESTART_POLICY_CLASS)V
                sput-object v0, $builderType->pikoForYouBottomTabSetting:$BOOLEAN_SETTING_CLASS
                :have
                return-object v0
            """.trimIndent(),
        )

        addStaticHelper(
            name = "pikoIsForYouBottomTabEnabled",
            params = emptyList(),
            returnType = "Z",
            registerCount = 1,
            smali = """
               invoke-static {}, Lapp/morphe/extension/shared/Utils;->getContext()Landroid/content/Context;
               move-result-object v0
               if-eqz v0, :default_on
               invoke-static {}, $builderType->pikoGetForYouBottomTabSetting()$BOOLEAN_SETTING_CLASS
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

        addStaticHelper(
            name = "pikoInjectForYouTab",
            params = listOf("Ljava/util/List;"),
            returnType = "Ljava/util/List;",
            registerCount = 5,
            smali = """
                if-eqz p0, :keep
                invoke-static {}, $builderType->pikoIsForYouBottomTabEnabled()Z
                move-result v0
                if-eqz v0, :keep
                sget-object v1, $tabEnum->$feedSwitcherField:$tabEnum
                invoke-interface {p0, v1}, Ljava/util/List;->contains(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :keep
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0, p0}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
                sget-object v2, $tabEnum->$feedField:$tabEnum
                invoke-virtual {v0, v2}, Ljava/util/ArrayList;->indexOf(Ljava/lang/Object;)I
                move-result v2
                if-ltz v2, :at_start
                add-int/lit8 v2, v2, 0x1
                goto :insert
                :at_start
                const/4 v2, 0x0
                :insert
                invoke-virtual {v0, v2, v1}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
                return-object v0
                :keep
                return-object p0
            """.trimIndent(),
        )

        // 4. Hook all RETURN_OBJECT instructions in buildTabsMethod (reverse order)
        val insns = buildTabsMethod.implementation?.instructions?.toList()
            ?: throw PatchException("ForYouBottomTabPatch: buildTabsMethod has no implementation")
        val retIndices = insns.mapIndexedNotNull { idx, ins ->
            if (ins.opcode == Opcode.RETURN_OBJECT) idx to (ins as OneRegisterInstruction).registerA else null
        }
        for ((idx, reg) in retIndices.reversed()) {
            buildTabsMethod.addInstructions(
                idx,
                """
                    invoke-static/range {v$reg .. v$reg}, $builderType->pikoInjectForYouTab(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$reg
                """.trimIndent(),
            )
        }
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
                "pikoAddForYouBottomTabToggle",
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
                    const-string v0, "Add For You to bottom tab bar"
                    const-string v1, "Adds the For You (Feed Switcher) tab next to Home on the bottom navigation bar"
                    invoke-static {}, $helperType->pikoGetForYouBottomTabSetting()$BOOLEAN_SETTING_CLASS
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
                            "invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoAddForYouBottomTabToggle()V",
                        )
                    }
                }
        }
    }
}
