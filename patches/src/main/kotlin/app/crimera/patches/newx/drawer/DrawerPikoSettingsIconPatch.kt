package app.crimera.patches.newx.drawer

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val drawerContentLambdaFingerprint = Fingerprint(
    definingClass = "Lcom/x/main/drawer/n;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
)

@Suppress("unused")
val drawerPikoSettingsIconPatch = bytecodePatch(
    name = "Drawer Piko settings icon",
    description = "Adds a Piko Settings gear icon to the bottom-right corner of the side navigation drawer.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
        val method = drawerContentLambdaFingerprint.method
        val insns = method.implementation!!.instructions

        val gCallIndex = insns.indexOfFirst { insn ->
            insn.opcode == Opcode.INVOKE_STATIC &&
                ((insn as ReferenceInstruction).reference as MethodReference).let {
                    it.definingClass == "Lcom/x/main/drawer/a;" && it.name == "g"
                }
        }
        if (gCallIndex == -1) error("Could not find drawer/a->g() call in drawer/n.invoke")

        method.addInstructions(
            gCallIndex + 1,
            """
                invoke-static {}, Lapp/morphe/extension/newx/settings/ComposeSettingsHook;->getSettingsClickHandler()Lkotlin/jvm/functions/Function0;
                move-result-object v1
                if-eqz v1, :piko_skip_drawer_gear
                const/high16 v0, 0x42600000
                const/4 v6, 0x0
                invoke-static {v7, v0, v6}, Landroidx/compose/foundation/layout/c;->H(Landroidx/compose/ui/Modifier;FF)Landroidx/compose/ui/Modifier;
                move-result-object v7
                const/4 v0, 0x0
                invoke-static {v0, v9, v7, v1}, Lcom/x/main/drawer/a;->g(ILandroidx/compose/runtime/Composer;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function0;)V
                :piko_skip_drawer_gear
                nop
            """,
        )
    }
}
