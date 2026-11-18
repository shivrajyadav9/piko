package app.crimera.patches.newx.drawer

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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

internal val navbarIconLambdaFingerprint = Fingerprint(
    definingClass = "Lcom/x/compose/navbars/a;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Ljava/lang/Object;",
        "Ljava/lang/Object;",
    ),
)

@Suppress("unused")
val drawerPikoSettingsIconPatch = bytecodePatch(
    name = "Drawer Piko settings icon",
    description = "Moves Piko Settings from the drawer footer list to a bottom-right Piko gear icon directly below the account switcher button.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
        val drawerClass = drawerIconButtonFingerprint.classDef
        val method = drawerIconButtonFingerprint.method

        val arrangementIndex = method.implementation!!.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.SGET_OBJECT &&
                (insn as ReferenceInstruction).reference.toString()
                    .contains("Landroidx/compose/foundation/layout/n;->a:")
        }
        if (arrangementIndex != -1) {
            val arrReg = (method.getInstruction(arrangementIndex) as OneRegisterInstruction).registerA
            method.addInstructions(
                arrangementIndex + 1,
                """
                    sget-object v$arrReg, Landroidx/compose/foundation/layout/n;->e:Landroidx/compose/foundation/layout/x2;
                """,
            )
        }

        val helperMethod = MutableMethod(
            ImmutableMethod(
                drawerClass.type,
                "pikoDrawerGear",
                listOf(
                    ImmutableMethodParameter("Landroidx/compose/runtime/Composer;", null, null),
                ),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(
                    13,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ),
        )
        helperMethod.addInstructions(
            0,
            """
                invoke-static {}, Lapp/morphe/extension/newx/settings/ComposeSettingsHook;->getSettingsClickHandler()Lkotlin/jvm/functions/Function0;
                move-result-object v1
                if-eqz v1, :piko_skip
                new-instance v0, Lcom/x/compose/navbars/a;
                const/4 v2, 0x4
                const/4 v3, 0x0
                invoke-direct {v0, v2, v3}, Lcom/x/compose/navbars/a;-><init>(BZ)V
                const v2, -0x16a6685e
                invoke-static {v2, v0, p0}, Landroidx/compose/runtime/internal/h;->e(ILkotlin/Function;Landroidx/compose/runtime/Composer;)Landroidx/compose/runtime/internal/g;
                move-result-object v6
                const/4 v2, 0x0
                const/4 v3, 0x1
                const/4 v4, 0x0
                const/4 v5, 0x0
                move-object v7, p0
                const/high16 v8, 0x180000
                const/16 v9, 0x3e
                invoke-static/range {v1 .. v9}, Landroidx/compose/material3/b0;->h(Lkotlin/jvm/functions/Function0;Landroidx/compose/ui/Modifier;ZLandroidx/compose/material3/w5;Landroidx/compose/ui/graphics/i1;Lkotlin/jvm/functions/Function2;Landroidx/compose/runtime/Composer;II)V
                :piko_skip
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
                invoke-static {v8}, Lcom/x/main/drawer/a;->pikoDrawerGear(Landroidx/compose/runtime/Composer;)V
            """,
        )

        val navMethod = navbarIconLambdaFingerprint.method
        val caIndex = navMethod.implementation!!.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.SGET_OBJECT &&
                (insn as ReferenceInstruction).reference.toString().contains("Lcom/x/icons/a;->Ca:")
        }
        if (caIndex != -1) {
            val iconReg = (navMethod.getInstruction(caIndex) as OneRegisterInstruction).registerA
            navMethod.addInstructions(
                caIndex + 1,
                """
                    invoke-static {}, Lapp/morphe/extension/newx/settings/PikoSettingsIcon;->getDrawableId()I
                    move-result v0
                    if-eqz v0, :piko_keep_ca
                    new-instance v$iconReg, Lcom/x/icons/b;
                    invoke-direct {v$iconReg, v0}, Lcom/x/icons/b;-><init>(I)V
                    :piko_keep_ca
                    nop
                """,
            )
        }

        val rowGuardMethod = MutableMethod(
            ImmutableMethod(
                drawerClass.type,
                "pikoShouldSkipDrawerRow",
                listOf(
                    ImmutableMethodParameter("Ljava/lang/String;", null, null),
                ),
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(
                    3,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ),
        )
        rowGuardMethod.addInstructions(
            0,
            """
                invoke-static {}, Lapp/morphe/extension/newx/settings/ComposeSettingsHook;->getSettingsTitle()Ljava/lang/String;
                move-result-object v0
                invoke-static {p0, v0}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                return v0
            """,
        )
        drawerClass.methods.add(rowGuardMethod)

        val tMethod = drawerClass.methods.firstOrNull { it.name == "t" }
        if (tMethod != null) {
            val uCallIndex = tMethod.implementation!!.instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_STATIC_RANGE &&
                    ((insn as ReferenceInstruction).reference as MethodReference).let {
                        it.definingClass == "Lcom/x/main/drawer/a;" && it.name == "u"
                    }
            }
            if (uCallIndex != -1) {
                tMethod.addInstructions(
                    uCallIndex,
                    """
                        invoke-static {v3}, Lcom/x/main/drawer/a;->pikoShouldSkipDrawerRow(Ljava/lang/String;)Z
                        move-result v0
                        if-nez v0, :piko_skip_u_call
                    """,
                )
                tMethod.addInstructions(
                    uCallIndex + 4,
                    """
                        :piko_skip_u_call
                        nop
                    """,
                )
            }
        }
    }
}
