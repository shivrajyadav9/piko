package app.crimera.patches.newx.home.gear

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

internal val homeTabbedRootFingerprint = Fingerprint(
    definingClass = "Lcom/x/home/tabbed/a;",
    name = "d",
    returnType = "V",
    parameters = listOf(
        "Lcom/x/compose/core/l2;",
        "Lcom/x/home/t;",
        "Z",
        "Landroidx/compose/ui/Modifier;",
        "Lkotlin/jvm/functions/Function1;",
        "Landroidx/compose/runtime/Composer;",
        "I",
        "I",
    ),
)

internal val homeTopBarFingerprint = Fingerprint(
    definingClass = "Lcom/x/home/tabbed/a;",
    name = "i",
    returnType = "V",
    strings = listOf("home_navigation_bar_title"),
)

internal val addTabIconButtonFingerprint = Fingerprint(
    definingClass = "Lcom/x/home/tabbed/a;",
    name = "a",
    returnType = "V",
    parameters = listOf(
        "Lkotlin/jvm/functions/Function0;",
        "Landroidx/compose/runtime/Composer;",
        "I",
    ),
)

@Suppress("unused")
val homeTimelineGearPatch = bytecodePatch(
    name = "Home timeline gear button",
    description = "Adds a gear icon to the top-right of the Home bar that opens the custom timelines picker (same as the + button).",
) {
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
        val rootClass = homeTabbedRootFingerprint.classDef
        val rootMethod = homeTabbedRootFingerprint.method
        val topBarMethod = homeTopBarFingerprint.method
        val addButtonMethod = addTabIconButtonFingerprint.method

        rootClass.staticFields.add(
            MutableField(
                ImmutableField(
                    rootClass.type,
                    "pikoHomeComponent",
                    "Lcom/x/home/t;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    null,
                ),
            ),
        )

        rootMethod.addInstructions(
            0,
            """
                sput-object p1, Lcom/x/home/tabbed/a;->pikoHomeComponent:Lcom/x/home/t;
            """,
        )

        val cCallIndex = topBarMethod.implementation!!.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.INVOKE_STATIC &&
                ((insn as ReferenceInstruction).reference as MethodReference).let {
                    it.definingClass == "Lcom/x/home/tabbed/a;" && it.name == "c"
                }
        }
        if (cCallIndex == -1) error("Could not find a->c() call in Home top bar")

        topBarMethod.addInstructions(
            cCallIndex + 1,
            """
                sget-object v0, Lcom/x/home/tabbed/a;->pikoHomeComponent:Lcom/x/home/t;
                if-eqz v0, :piko_skip_gear
                new-instance v1, Lcom/x/home/tabbed/c0;
                const/4 v2, 0x0
                invoke-direct {v1, v0, v2}, Lcom/x/home/tabbed/c0;-><init>(Lcom/x/home/t;B)V
                invoke-static {v1, p7, v2}, Lcom/x/home/tabbed/a;->a(Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;I)V
                :piko_skip_gear
                nop
            """,
        )

        val n9Index = addButtonMethod.implementation!!.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.SGET_OBJECT &&
                (insn as ReferenceInstruction).reference.toString().contains("Lcom/x/icons/a;->n9:")
        }
        if (n9Index != -1) {
            val reg = (addButtonMethod.getInstruction(n9Index) as OneRegisterInstruction).registerA
            addButtonMethod.addInstructions(
                n9Index + 1,
                """
                    sget-object v$reg, Lcom/x/icons/a;->Ca:Lcom/x/icons/b;
                """,
            )
        }
    }
}
