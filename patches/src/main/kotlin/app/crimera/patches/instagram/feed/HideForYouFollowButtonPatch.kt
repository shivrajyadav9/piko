/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.feed

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.addInstagramBooleanSettingHelpers
import app.crimera.patches.instagram.utils.registerMyPatchesToggle
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

private const val FOLLOW_BUTTON_BASE_CLASS = "Lcom/instagram/user/follow/FollowButtonBase;"

@Suppress("unused")
val hideForYouFollowButtonPatch =
    bytecodePatch(
        name = "Hide Follow button on For You feed",
        description = "Hides the Follow button on posts and Reels in the Home / For You feed while keeping Follow buttons on profiles and other screens.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val followClass = mutableClassDefBy(FOLLOW_BUTTON_BASE_CLASS)
            val superClass = followClass.superclass ?: "Landroid/widget/TextView;"

        addInstagramBooleanSettingHelpers(followClass, "pikoHideFollowSetting", "pikoGetHideFollowSetting", "pikoIsHideFollowEnabled", "piko_hide_for_you_follow_button", defaultValue = true, restartRequired = false)

            followClass.staticFields.add(
                ImmutableField(
                    FOLLOW_BUTTON_BASE_CLASS,
                    "pikoFeedHeaderId",
                    "I",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    null,
                ).toMutable(),
            )
listOf("pikoInlineFollowId", "pikoHeaderFollowId").forEach { followClass.staticFields.add(ImmutableField(FOLLOW_BUTTON_BASE_CLASS, it, "I", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null).toMutable()) }

            // Helper: returns true if this FollowButtonBase sits inside row_feed_profile_header.
            val isInFeedHeaderHelper =
                ImmutableMethod(
                    FOLLOW_BUTTON_BASE_CLASS,
                    "pikoIsInFeedHeader",
                    listOf(ImmutableMethodParameter("Landroid/view/View;", null, "view")),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        6,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            isInFeedHeaderHelper.addInstructions(
                0,
                """
                sget v0, $FOLLOW_BUTTON_BASE_CLASS->pikoFeedHeaderId:I
                if-nez v0, :have_id
                invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v1
                if-eqz v1, :ret_false
                invoke-virtual {v1}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
                move-result-object v2
                invoke-virtual {v1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object v3
                const-string v4, "row_feed_profile_header"
                const-string v1, "id"
                invoke-virtual {v2, v4, v1, v3}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                move-result v0
                if-nez v0, :store_id
                const/4 v0, -0x1
                :store_id
                sput v0, $FOLLOW_BUTTON_BASE_CLASS->pikoFeedHeaderId:I
                const-string v4, "inline_follow_button"
invoke-virtual {v2, v4, v1, v3}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
move-result v4
if-nez v4, :store_inline
const/4 v4, -0x1
:store_inline
sput v4, $FOLLOW_BUTTON_BASE_CLASS->pikoInlineFollowId:I
const-string v4, "follow_in_header"
invoke-virtual {v2, v4, v1, v3}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
move-result v4
if-nez v4, :store_header
const/4 v4, -0x1
:store_header
sput v4, $FOLLOW_BUTTON_BASE_CLASS->pikoHeaderFollowId:I
:have_id
invoke-virtual {p0}, Landroid/view/View;->getId()I
move-result v1
if-lez v1, :walk_check
sget v2, $FOLLOW_BUTTON_BASE_CLASS->pikoInlineFollowId:I
if-ne v1, v2, :check_header_follow
const/4 v0, 0x1
return v0
:check_header_follow
sget v2, $FOLLOW_BUTTON_BASE_CLASS->pikoHeaderFollowId:I
if-ne v1, v2, :walk_check
const/4 v0, 0x1
return v0
:walk_check
                if-gtz v0, :walk

                :ret_false
                const/4 v0, 0x0
                return v0
                :walk
                invoke-virtual {p0}, Landroid/view/View;->getParent()Landroid/view/ViewParent;
                move-result-object v1
                const/4 v2, 0x0
                :loop
                if-eqz v1, :ret_false
                const/16 v3, 0xa
                if-ge v2, v3, :ret_false
                instance-of v3, v1, Landroid/view/View;
                if-eqz v3, :next
                move-object v3, v1
                check-cast v3, Landroid/view/View;
                invoke-virtual {v3}, Landroid/view/View;->getId()I
                move-result v3
                if-ne v3, v0, :next
                const/4 v0, 0x1
                return v0
                :next
                invoke-interface {v1}, Landroid/view/ViewParent;->getParent()Landroid/view/ViewParent;
                move-result-object v1
                add-int/lit8 v2, v2, 0x1
                goto :loop
                """.trimIndent(),
            )
            isInFeedHeaderHelper.removeInstruction(isInFeedHeaderHelper.implementation!!.instructions.size - 1)
            followClass.methods.add(isInFeedHeaderHelper)

            // Helper: maps VISIBLE (0) -> GONE (8) when inside a feed header.
            val filterVisHelper =
                ImmutableMethod(
                    FOLLOW_BUTTON_BASE_CLASS,
                    "pikoFilterVisibility",
                    listOf(
                        ImmutableMethodParameter("Landroid/view/View;", null, "view"),
                        ImmutableMethodParameter("I", null, "visibility"),
                    ),
                    "I",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        3,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            filterVisHelper.addInstructions(
                0,
                """
            invoke-static {}, Lcom/instagram/user/follow/FollowButtonBase;->pikoIsHideFollowEnabled()Z
            move-result v0
            if-eqz v0, :keep
                if-nez p1, :keep
                invoke-static {p0}, $FOLLOW_BUTTON_BASE_CLASS->pikoIsInFeedHeader(Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :keep
                const/16 p1, 0x8
                :keep
                return p1
                """.trimIndent(),
            )
            filterVisHelper.removeInstruction(filterVisHelper.implementation!!.instructions.size - 1)
            followClass.methods.add(filterVisHelper)

            // Hook or override setVisibility(I)V
            val existingSetVis = followClass.methods.firstOrNull {
                !AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.name == "setVisibility" &&
                    it.returnType == "V" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("I")
            }
            if (existingSetVis != null) {
                existingSetVis.addInstructions(
                    0,
                    """
                    invoke-static/range {p0 .. p1}, $FOLLOW_BUTTON_BASE_CLASS->pikoFilterVisibility(Landroid/view/View;I)I
                    move-result p1
                    """.trimIndent(),
                )
            } else {
                val setVisOverride =
                    ImmutableMethod(
                        FOLLOW_BUTTON_BASE_CLASS,
                        "setVisibility",
                        listOf(ImmutableMethodParameter("I", null, "visibility")),
                        "V",
                        AccessFlags.PUBLIC.value,
                        null,
                        null,
                        ImmutableMethodImplementation(
                            2,
                            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                            null,
                            null,
                        ),
                    ).toMutable()

                setVisOverride.addInstructions(
                    0,
                    """
                    invoke-static {p0, p1}, $FOLLOW_BUTTON_BASE_CLASS->pikoFilterVisibility(Landroid/view/View;I)I
                    move-result p1
                    invoke-super {p0, p1}, $superClass->setVisibility(I)V
                    return-void
                    """.trimIndent(),
                )
                setVisOverride.removeInstruction(setVisOverride.implementation!!.instructions.size - 1)
                followClass.methods.add(setVisOverride)
            }

            // Also hide on attach if visibility was set before attaching to the feed header.
            val hideOnAttachHelper =
                ImmutableMethod(
                    FOLLOW_BUTTON_BASE_CLASS,
                    "pikoHideOnAttach",
                    listOf(ImmutableMethodParameter("Landroid/view/View;", null, "view")),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        3,
                        listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        null,
                        null,
                    ),
                ).toMutable()

            hideOnAttachHelper.addInstructions(
                0,
                """
invoke-static {}, $FOLLOW_BUTTON_BASE_CLASS->pikoIsHideFollowEnabled()Z
move-result v0
if-eqz v0, :disabled
                invoke-static {p0}, $FOLLOW_BUTTON_BASE_CLASS->pikoIsInFeedHeader(Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :done
const v0, 0x70696b6e
sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
invoke-virtual {p0, v0, v1}, Landroid/view/View;->setTag(ILjava/lang/Object;)V
                const/16 v0, 0x8
                invoke-virtual {p0, v0}, Landroid/view/View;->setVisibility(I)V
invoke-virtual {p0}, Landroid/view/View;->requestLayout()V
return-void
:disabled
const v0, 0x70696b6e
invoke-virtual {p0, v0}, Landroid/view/View;->getTag(I)Ljava/lang/Object;
move-result-object v0
if-eqz v0, :done
const v0, 0x70696b6e
const/4 v1, 0x0
invoke-virtual {p0, v0, v1}, Landroid/view/View;->setTag(ILjava/lang/Object;)V
const/4 v0, 0x0
invoke-virtual {p0, v0}, Landroid/view/View;->setVisibility(I)V
invoke-virtual {p0}, Landroid/view/View;->requestLayout()V
                :done
                return-void
                """.trimIndent(),
            )
            hideOnAttachHelper.removeInstruction(hideOnAttachHelper.implementation!!.instructions.size - 1)
            followClass.methods.add(hideOnAttachHelper)

            val existingAttach = followClass.methods.firstOrNull {
                !AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.name == "onAttachedToWindow" &&
                    it.returnType == "V" &&
                    it.parameterTypes.isEmpty()
            }
            if (existingAttach != null) {
                existingAttach.addInstructions(
                    0,
                    "invoke-static/range {p0 .. p0}, $FOLLOW_BUTTON_BASE_CLASS->pikoHideOnAttach(Landroid/view/View;)V",
                )
            } else {
                val attachOverride =
                    ImmutableMethod(
                        FOLLOW_BUTTON_BASE_CLASS,
                        "onAttachedToWindow",
                        emptyList(),
                        "V",
                        AccessFlags.PROTECTED.value,
                        null,
                        null,
                        ImmutableMethodImplementation(
                            1,
                            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                            null,
                            null,
                        ),
                    ).toMutable()

                attachOverride.addInstructions(
                    0,
                    """
                    invoke-super {p0}, $superClass->onAttachedToWindow()V
                    invoke-static {p0}, $FOLLOW_BUTTON_BASE_CLASS->pikoHideOnAttach(Landroid/view/View;)V
                    return-void
                    """.trimIndent(),
                )
                attachOverride.removeInstruction(attachOverride.implementation!!.instructions.size - 1)
                followClass.methods.add(attachOverride)
            }
val existingMeasure = followClass.methods.firstOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.name == "onMeasure" &&
        it.returnType == "V" &&
        it.parameterTypes.size == 2 &&
        it.parameterTypes[0] == "I" &&
        it.parameterTypes[1] == "I"
}
if (existingMeasure != null) {
    existingMeasure.addInstructions(
        0,
        """
        invoke-static {}, $FOLLOW_BUTTON_BASE_CLASS->pikoIsHideFollowEnabled()Z
        move-result v0
        if-eqz v0, :piko_measure_orig
        invoke-static {p0}, $FOLLOW_BUTTON_BASE_CLASS->pikoIsInFeedHeader(Landroid/view/View;)Z
        move-result v0
        if-eqz v0, :piko_measure_orig
        const/4 v0, 0x0
        invoke-virtual {p0, v0, v0}, $FOLLOW_BUTTON_BASE_CLASS->setMeasuredDimension(II)V
        return-void
        :piko_measure_orig
        nop
        """.trimIndent(),
    )
} else {
    val measureOverride =
        ImmutableMethod(
            FOLLOW_BUTTON_BASE_CLASS,
            "onMeasure",
            listOf(
                ImmutableMethodParameter("I", null, "widthMeasureSpec"),
                ImmutableMethodParameter("I", null, "heightMeasureSpec"),
            ),
            "V",
            AccessFlags.PROTECTED.value,
            null,
            null,
            ImmutableMethodImplementation(
                4,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ).toMutable()
    measureOverride.addInstructions(
        0,
        """
        invoke-static {}, $FOLLOW_BUTTON_BASE_CLASS->pikoIsHideFollowEnabled()Z
        move-result v0
        if-eqz v0, :piko_measure_orig
        invoke-static {p0}, $FOLLOW_BUTTON_BASE_CLASS->pikoIsInFeedHeader(Landroid/view/View;)Z
        move-result v0
        if-eqz v0, :piko_measure_orig
        const/4 v0, 0x0
        invoke-virtual {p0, v0, v0}, $FOLLOW_BUTTON_BASE_CLASS->setMeasuredDimension(II)V
        return-void
        :piko_measure_orig
        invoke-super {p0, p1, p2}, $superClass->onMeasure(II)V
        return-void
        """.trimIndent(),
    )
    measureOverride.removeInstruction(measureOverride.implementation!!.instructions.size - 1)
    followClass.methods.add(measureOverride)
}
        }
        finalize {
            registerMyPatchesToggle("pikoAddHideFollowToggle", "Hide Follow button on For You feed", "Hide Follow buttons on posts and Reels in the Home feed", "Lcom/instagram/user/follow/FollowButtonBase;", "pikoGetHideFollowSetting")
        }
    }
