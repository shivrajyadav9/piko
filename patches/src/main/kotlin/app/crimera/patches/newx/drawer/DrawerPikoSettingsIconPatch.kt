package app.crimera.patches.newx.drawer

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

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
        val drawerClass = drawerIconButtonFingerprint.classDef
        val method = drawerIconButtonFingerprint.method

        val helperMethod = MutableMethod(
            ImmutableMethod(
                drawerClass.type,
                "pikoDrawerGear",
                listOf(
                    ImmutableMethodParameter("I", null, null),
                    ImmutableMethodParameter("Landroidx/compose/runtime/Composer;", null, null),
                    ImmutableMethodParameter("Landroidx/compose/ui/Modifier;", null, null),
                ),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(
                    8,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ),
        )
        helperMethod.addInstructions(
            0,
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
        drawerClass.methods.add(helperMethod)

        val b0Index = method.implementation!!.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.INVOKE_STATIC_RANGE &&
                ((insn as ReferenceInstruction).reference as MethodReference).let {
                    it.definingClass == "Landroidx/compose/material3/b0;" && it.name == "h"
                }
        }
        if (b0Index == -1) error("Could not find Material3 IconButton (b0.h) in drawer/a->g")

        method.addInstructions(
            b0Index + 1,
            """
                invoke-static/range {p0 .. p2}, Lcom/x/main/drawer/a;->pikoDrawerGear(ILandroidx/compose/runtime/Composer;Landroidx/compose/ui/Modifier;)V
            """,
        )
    }
}
