package app.crimera.patches.newx.drawer

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
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
    description = "Moves Piko Settings from the drawer footer list to a bottom-right Piko gear icon with balanced left/right margins, keeping Explore's normal gear icon untouched.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)

    execute {
        val drawerClass = drawerIconButtonFingerprint.classDef
        val method = drawerIconButtonFingerprint.method

        // 0. Increase left/right padding on the drawer bottom Row from 4.dp to 16.dp
        val paddingIndex = method.implementation!!.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.INVOKE_STATIC_RANGE &&
                ((insn as ReferenceInstruction).reference as? MethodReference)?.let {
                    it.definingClass == "Landroidx/compose/foundation/layout/c;" && it.name == "K"
                } == true
        }
        if (paddingIndex != -1) {
            method.addInstructions(
                paddingIndex,
                """
                    const/high16 v13, 0x41800000
                    const/high16 v15, 0x41800000
                """,
            )
        }

        // 1. In drawer/a->g, replace Arrangement.Start (n->a) with Arrangement.SpaceBetween (n->g)
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
                    sget-object v$arrReg, Landroidx/compose/foundation/layout/n;->g:Landroidx/compose/foundation/layout/x2;
                """,
            )
        }

        // 1b. Tag field on navbars/a so ONLY our drawer instance uses the Piko P-gear icon (Explore keeps normal o-gear)
        val navClass = navbarIconLambdaFingerprint.classDef
        navClass.fields.add(
            ImmutableField(
                navClass.type,
                "pikoUsePikoIcon",
                "Z",
                AccessFlags.PUBLIC.value,
                null,
                null,
                null,
            ).toMutable(),
        )

        // 2. Helper method to render the second IconButton inside the same Row using tagged navbars/a(4, false)
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
                const/4 v2, 0x1
                iput-boolean v2, v0, Lcom/x/compose/navbars/a;->pikoUsePikoIcon:Z
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

        // 3. Call pikoDrawerGear(v8) right after the theme toggle IconButton (b0.h) inside the Row (branchless)
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

        // 4. Helper method to resolve Piko Settings icon ONLY when navbars/a.pikoUsePikoIcon == true
        val iconHelperMethod = MutableMethod(
            ImmutableMethod(
                drawerClass.type,
                "pikoGetDrawerIcon",
                listOf(
                    ImmutableMethodParameter("Lcom/x/compose/navbars/a;", null, null),
                ),
                "Lcom/x/icons/b;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(
                    4,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                ),
            ),
        )
        iconHelperMethod.addInstructions(
            0,
            """
                iget-boolean v0, p0, Lcom/x/compose/navbars/a;->pikoUsePikoIcon:Z
                if-eqz v0, :piko_keep_ca
                invoke-static {}, Lapp/morphe/extension/newx/settings/PikoSettingsIcon;->getDrawableId()I
                move-result v0
                if-eqz v0, :piko_keep_ca
                new-instance v1, Lcom/x/icons/b;
                invoke-direct {v1, v0}, Lcom/x/icons/b;-><init>(I)V
                return-object v1
                :piko_keep_ca
               sget-object v0, Lcom/x/icons/a;->Ca:Lcom/x/icons/b;
               return-object v0
            """,
        )
        iconHelperMethod.removeInstruction(iconHelperMethod.implementation!!.instructions.size - 1)
        drawerClass.methods.add(iconHelperMethod)

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
                    invoke-static/range {p0 .. p0}, Lcom/x/main/drawer/a;->pikoGetDrawerIcon(Lcom/x/compose/navbars/a;)Lcom/x/icons/b;
                    move-result-object v$iconReg
                """,
            )
        }

        // 5. Hide the "Piko Settings" text row by hooking index 0 of drawer/a->u
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
        rowGuardMethod.removeInstruction(rowGuardMethod.implementation!!.instructions.size - 1)
        drawerClass.methods.add(rowGuardMethod)

        val uMethod = drawerClass.methods.firstOrNull { it.name == "u" }
        uMethod?.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                invoke-static {v0}, Lcom/x/main/drawer/a;->pikoShouldSkipDrawerRow(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :piko_draw_row
                return-void
                :piko_draw_row
                nop
            """,
        )
    }
}
