package app.crimera.patches.newx.home.gear

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.w3c.dom.Element

private const val HOST_CLASS = "Lcom/x/home/v0;"
private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val BUILT_IN_SETTINGS = "Lapp/morphe/extension/newx/settings/BuiltInSettings;"

private val homeTimelineGearResourcesPatch = resourcePatch {
execute {
document("res/values/strings.xml").use { doc ->
val resources = doc.documentElement
fun addStringIfMissing(name: String, value: String) {
val existing = resources.getElementsByTagName("string")
for (i in 0 until existing.length) {
if ((existing.item(i) as? Element)?.getAttribute("name") == name) return
}
val el = doc.createElement("string")
el.setAttribute("name", name)
el.textContent = value
resources.appendChild(el)
}
addStringIfMissing("piko_my_patches_category_title", "My Patches")
addStringIfMissing("piko_my_patches_category_summary", "Custom patches")
addStringIfMissing("piko_my_patches_home_timeline_gear_title", "Home timeline gear button")
addStringIfMissing(
"piko_my_patches_home_timeline_gear_summary",
"Show the custom timelines gear icon in the top-right of the Home bar",
)
}
}
}

internal val homeTabbedStateConstructorFingerprint = Fingerprint(
    definingClass = "Lcom/x/home/v0;",
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Lcom/x/home/o0;",
        "Lcom/x/home/u0;",
        "Lcom/x/home/f;",
        "Z",
        "Z",
    ),
)

internal val homeNavClickLambdaFingerprint = Fingerprint(
    definingClass = "Lcom/x/home/tabbed/c0;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
)

internal val homeNavIconComposableFingerprint = Fingerprint(
    definingClass = "Lcom/x/compose/core/v2;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Ljava/lang/Object;",
        "Ljava/lang/Object;",
    ),
)

@Suppress("unused")
val homeTimelineGearPatch = bytecodePatch(
    name = "Home timeline gear button",
    description = "Places a 25dp gear icon in the top-right corner of the Home bar (in place of the Premium upsell) that opens the custom timelines picker.",
)  {
    dependsOn(homeTimelineGearResourcesPatch)
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
val hostClass = homeTabbedStateConstructorFingerprint.classDef
hostClass.staticFields.add(
    ImmutableField(
        HOST_CLASS,
        "pikoHomeGearRegistered",
        "Z",
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null, null, null,
    ).toMutable(),
)

val enabledMethod = ImmutableMethod(
    HOST_CLASS,
    "pikoIsHomeGearEnabled",
    emptyList(),
    "Z",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(2),
).toMutable().apply {
    addInstructions(
        0,
        """
            const-string v0, "newx.my_patches.home_timeline_gear"
            const/4 v1, 0x1
            invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
            move-result v0
            return v0
        """.trimIndent(),
    )
}
hostClass.methods.add(enabledMethod)

val registerMethod = ImmutableMethod(
    HOST_CLASS,
    "pikoRegisterHomeGearSettings",
    emptyList(),
    "V",
    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    null,
    null,
    MutableMethodImplementation(7),
).toMutable().apply {
    addInstructions(
        0,
        """
            sget-boolean v0, $HOST_CLASS->pikoHomeGearRegistered:Z
            if-eqz v0, :do_register
            return-void
            :do_register
            const/4 v0, 0x1
            sput-boolean v0, $HOST_CLASS->pikoHomeGearRegistered:Z
            const-string v0, "newx.my_patches"
            const-string v1, "piko_my_patches_category_title"
            const-string v2, "piko_my_patches_category_summary"
            const/16 v3, 0x3c
            invoke-static {v0, v1, v2, v3}, $SETTINGS_REGISTRY->registerCategory(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
            const-string v0, "newx.my_patches"
            const-string v1, "newx.my_patches.home_timeline_gear"
            const-string v2, "piko_my_patches_home_timeline_gear_title"
            const-string v3, "piko_my_patches_home_timeline_gear_summary"
            const/4 v4, 0x1
            const/16 v5, 0x50
            const/4 v6, 0x1
            invoke-static/range {v0 .. v6}, $SETTINGS_REGISTRY->registerToggle(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZIZ)V
            return-void
        """.trimIndent(),
    )
}
hostClass.methods.add(registerMethod)
        homeTabbedStateConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $HOST_CLASS->pikoIsHomeGearEnabled()Z
                move-result v0
                if-eqz v0, :piko_skip_ctor_gear
                sget-object p3, Lcom/x/home/d;->a:Lcom/x/home/d;
                :piko_skip_ctor_gear
                nop
            """,
        )

        val iconMethod = homeNavIconComposableFingerprint.method
        val insns = iconMethod.implementation!!.instructions
        val e8Index = insns.indexOfFirst { insn ->
            insn.opcode == Opcode.SGET_OBJECT &&
                (insn as ReferenceInstruction).reference.toString().contains("Lcom/x/icons/a;->E8:")
        }
        if (e8Index == -1) error("Could not find E8 icon in v2.invoke")
        val iconReg = (iconMethod.getInstruction(e8Index) as OneRegisterInstruction).registerA
        val e8Ref = (iconMethod.getInstruction(e8Index) as ReferenceInstruction).reference
        iconMethod.addInstructions(
            e8Index + 1,
            """
                invoke-static {}, $HOST_CLASS->pikoIsHomeGearEnabled()Z
                move-result v$iconReg
                if-eqz v$iconReg, :piko_keep_e8
                sget-object v$iconReg, Lcom/x/icons/a;->Ca:Lcom/x/icons/b;
                goto :piko_icon_done
                :piko_keep_e8
                sget-object v$iconReg, $e8Ref
                :piko_icon_done
                nop
            """,
        )

        val jCallIndex = insns.indexOfFirstIndexed(e8Index) { insn ->
            insn.opcode == Opcode.INVOKE_STATIC_RANGE &&
                ((insn as ReferenceInstruction).reference as MethodReference).let {
                    it.definingClass == "Lcom/x/compose/core/j;" && it.name == "f"
                }
        }
        if (jCallIndex == -1) error("Could not find j.f() call in v2.invoke")
        iconMethod.addInstructions(
            jCallIndex,
            """
                invoke-static {}, $HOST_CLASS->pikoIsHomeGearEnabled()Z
                move-result v13
                if-eqz v13, :piko_restore_v13
                sget-object v7, Landroidx/compose/ui/q;->a:Landroidx/compose/ui/q;
                const/high16 v13, 0x41c80000
                invoke-static {v7, v13}, Landroidx/compose/foundation/layout/q3;->m(Landroidx/compose/ui/Modifier;F)Landroidx/compose/ui/Modifier;
                move-result-object v7
                :piko_restore_v13
                const/16 v13, 0x18
            """,
        )

        val clickMethod = homeNavClickLambdaFingerprint.method
        val xIndex = clickMethod.implementation!!.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.SGET_OBJECT &&
                (insn as ReferenceInstruction).reference.toString().contains("Lcom/x/home/x;->a:")
        }
        if (xIndex == -1) error("Could not find OnHomeNavButtonClicked in c0.invoke")
        val clickReg = (clickMethod.getInstruction(xIndex) as OneRegisterInstruction).registerA
        val xRef = (clickMethod.getInstruction(xIndex) as ReferenceInstruction).reference
        clickMethod.addInstructions(
            xIndex + 1,
            """
                invoke-static {}, $HOST_CLASS->pikoIsHomeGearEnabled()Z
                move-result v$clickReg
                if-eqz v$clickReg, :piko_keep_click
                sget-object v$clickReg, Lcom/x/home/v;->a:Lcom/x/home/v;
                goto :piko_click_done
                :piko_keep_click
                sget-object v$clickReg, $xRef
                :piko_click_done
                nop
            """,
        )
    }

    finalize {
        val registerAll = mutableClassDefBy(BUILT_IN_SETTINGS)
            .methods
            .first { it.name == "register" }
        registerAll.addInstructions(
            0,
            """
                invoke-static {}, $HOST_CLASS->pikoRegisterHomeGearSettings()V
            """.trimIndent(),
        )
    }
}

private inline fun <T> List<T>.indexOfFirstIndexed(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (i in startIndex until size) {
        if (predicate(this[i])) return i
    }
    return -1
}
