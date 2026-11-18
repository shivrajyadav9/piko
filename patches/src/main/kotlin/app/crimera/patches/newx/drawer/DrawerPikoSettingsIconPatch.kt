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
                sget-object v0, Landroidx/compose/ui/q;->a:Landroidx/compose/ui/q;
                const/4 v2, 0x0
                const/high16 v3, 0x42600000
                const/high16 v4, 0x41800000
                invoke-static {v0, v2, v2, v3, v4}, Landroidx/compose/foundation/layout/c;->K(Landroidx/compose/ui/Modifier;FFFF)Landroidx/compose/ui/Modifier;
                move-result-object v0
                sget-object v2, Landroidx/compose/ui/b;->h:Landroidx/compose/ui/i;
                sget-object v3, Landroidx/compose/foundation/layout/z;->a:Landroidx/compose/foundation/layout/z;
                invoke-virtual {v3, v0, v2}, Landroidx/compose/foundation/layout/z;->a(Landroidx/compose/ui/Modifier;Landroidx/compose/ui/i;)Landroidx/compose/ui/Modifier;
                move-result-object v0
                const/4 v2, 0x0
                invoke-static {v2, v9, v0, v1}, Lcom/x/main/drawer/a;->g(ILandroidx/compose/runtime/Composer;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function0;)V
                :piko_skip_drawer_gear
                nop
            """,
        )
    }
}
