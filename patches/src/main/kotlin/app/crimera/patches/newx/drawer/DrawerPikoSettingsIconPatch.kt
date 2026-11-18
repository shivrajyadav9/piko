package app.crimera.patches.newx.drawer

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

internal val drawerIconButtonFingerprint = Fingerprint(
    definingClass = "Lcom/x/main/drawer/a;",
    name = "g",
    returnType = "V",
    parameters = listOf(
        "I",
        "Landroidx/compose/runtime/Composer;",
        "Landroidx/compose/ui/Modifier;",
        "Lkotlin/jvm/functions/Function0;",
    ),
)

@Suppress("unused")
val drawerPikoSettingsIconPatch = bytecodePatch(
    name = "Drawer Piko settings icon",
    description = "Adds a Piko Settings gear icon to the bottom-right corner of the side navigation drawer.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
        val method = drawerIconButtonFingerprint.method
        val returnIndex = method.implementation!!.instructions.indexOfLast {
            it.opcode == Opcode.RETURN_VOID
        }
        if (returnIndex == -1) error("Could not find return-void in drawer/a->g")

        method.addInstructions(
            returnIndex,
            """
                if-nez p0, :piko_render_only
                invoke-static {}, Lapp/morphe/extension/newx/settings/ComposeSettingsHook;->getSettingsClickHandler()Lkotlin/jvm/functions/Function0;
                move-result-object v0
                if-eqz v0, :piko_render_only
                const/high16 v1, 0x42600000
                const/4 v2, 0x0
                invoke-static {p2, v1, v2}, Landroidx/compose/foundation/layout/c;->H(Landroidx/compose/ui/Modifier;FF)Landroidx/compose/ui/Modifier;
                move-result-object v1
                const/4 v2, 0x1
                invoke-static {v2, p1, v1, v0}, Lcom/x/main/drawer/a;->g(ILandroidx/compose/runtime/Composer;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function0;)V
                :piko_render_only
                nop
            """,
        )
    }
}
