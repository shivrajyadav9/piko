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
) {
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
        homeTabbedStateConstructorFingerprint.method.addInstructions(
            0,
            """
                sget-object p3, Lcom/x/home/d;->a:Lcom/x/home/d;
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
        iconMethod.addInstructions(
            e8Index + 1,
            """
                sget-object v$iconReg, Lcom/x/icons/a;->Ca:Lcom/x/icons/b;
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
                sget-object v7, Landroidx/compose/ui/q;->a:Landroidx/compose/ui/q;
                const/high16 v13, 0x41c80000
                invoke-static {v7, v13}, Landroidx/compose/foundation/layout/q3;->m(Landroidx/compose/ui/Modifier;F)Landroidx/compose/ui/Modifier;
                move-result-object v7
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
        clickMethod.addInstructions(
            xIndex + 1,
            """
                sget-object v$clickReg, Lcom/x/home/v;->a:Lcom/x/home/v;
            """,
        )
    }
}

private inline fun <T> List<T>.indexOfFirstIndexed(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (i in startIndex until size) {
        if (predicate(this[i])) return i
    }
    return -1
}
