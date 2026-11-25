package app.crimera.patches.instagram.feed

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/instagram/settings/BooleanSetting;"
private const val BASE_BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/crimera/settings/BooleanSetting;"
private const val RESTART_POLICY_CLASS = "Lapp/morphe/extension/instagram/settings/RestartPolicy;"
private const val SHARED_PREF_CLASS = "Lapp/morphe/extension/crimera/sharedPreference/SharedPref;"
private const val SCREEN_BUILDER_CLASS = "Lapp/morphe/extension/instagram/settings/preference/ScreenBuilder;"
private const val SETTINGS_STATUS_CLASS = "Lapp/morphe/extension/instagram/settings/SettingsStatus;"
private const val UTILS_CLASS = "Lapp/morphe/extension/shared/Utils;"
private const val SWIPE_NAV_CONTAINER_CLASS = "Lcom/instagram/ui/swipenavigation/container/SwipeNavigationContainer;"

private fun methodReferencesString(method: Method, target: String): Boolean {
    val insns = method.implementation?.instructions ?: return false
    return insns.any { insn ->
        insn is ReferenceInstruction &&
            (insn.reference as? StringReference)?.string == target
    }
}

@Suppress("unused")
val swipeFollowingForYouPatch = bytecodePatch(
    name = "Swipe between Following & For You",
    description = "Swipe horizontally on Home to switch between Following and For You feeds.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        var prefClass: ClassDef? = null
        var prefGetter: Method? = null
        var prefSetter: Method? = null

        var enumClass: ClassDef? = null
        var forYouFieldName: String? = null
        var followingFieldName: String? = null

        var swipeClass: ClassDef? = null

        classDefForEach { classDef ->
            if (classDef.type == SWIPE_NAV_CONTAINER_CLASS) {
                swipeClass = classDef
            }

            if (prefClass == null && classDef.methods.any { methodReferencesString(it, "last_selected_feed_type") }) {
                           prefClass = classDef
                val getters = classDef.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
prefGetter = getters.firstOrNull { methodReferencesString(it, "last_selected_feed_type") } ?: getters.firstOrNull()
val setters = classDef.methods.filter { it.parameterTypes.size == 1 && it.parameterTypes[0].toString() == "Ljava/lang/String;" && it.returnType == "V" }
prefSetter = setters.firstOrNull { methodReferencesString(it, "last_selected_feed_type") } ?: setters.firstOrNull()
            }

            if (enumClass == null && classDef.superclass == "Ljava/lang/Enum;") {
                val clinit = classDef.methods.firstOrNull { it.name == "<clinit>" }
                val insns = clinit?.implementation?.instructions?.toList()
                if (insns != null) {
                    var idxForYou = -1
                    var idxFollowing = -1
                    for (i in insns.indices) {
                        val ref = (insns[i] as? ReferenceInstruction)?.reference as? StringReference ?: continue
                        when (ref.string) {
                            "BLENDED_FOR_YOU" -> if (idxForYou == -1) idxForYou = i
                            "FOLLOWING" -> if (idxFollowing == -1) idxFollowing = i
                        }
                    }
                    if (idxForYou != -1 && idxFollowing != -1) {
                        var fyField: String? = null
                        for (i in (idxForYou + 1) until insns.size) {
                            val insn = insns[i]
                            if (insn.opcode == Opcode.SPUT_OBJECT && insn is ReferenceInstruction) {
                                val fRef = insn.reference as? FieldReference
                                if (fRef != null && fRef.type == classDef.type) {
                                    fyField = fRef.name
                                    break
                                }
                            }
                        }
                        var flField: String? = null
                        for (i in (idxFollowing + 1) until insns.size) {
                            val insn = insns[i]
                            if (insn.opcode == Opcode.SPUT_OBJECT && insn is ReferenceInstruction) {
                                val fRef = insn.reference as? FieldReference
                                if (fRef != null && fRef.type == classDef.type) {
                                    flField = fRef.name
                                    break
                                }
                            }
                        }
                        if (fyField != null && flField != null) {
                            enumClass = classDef
                            forYouFieldName = fyField
                            followingFieldName = flField
                        }
                    }
                }
            }
        }

        val resolvedPrefClass = prefClass ?: throw PatchException("Could not find feed pref class")
        val resolvedPrefGetter = prefGetter
        val resolvedPrefSetter = prefSetter
        val resolvedEnumClass = enumClass ?: throw PatchException("Could not find feed enum class")
        val resolvedForYouField = forYouFieldName ?: throw PatchException("Could not find BLENDED_FOR_YOU field")
        val resolvedFollowingField = followingFieldName ?: throw PatchException("Could not find FOLLOWING field")
        val resolvedSwipeClass = swipeClass ?: throw PatchException("Could not find SwipeNavigationContainer")

        var switcherClass: ClassDef? = null
        var switchMethod: Method? = null
        classDefForEach { classDef ->
           if (switcherClass != null) return@classDefForEach
            val candidate = classDef.methods.firstOrNull { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].toString() == resolvedEnumClass.type &&
                    method.returnType == "V" &&
                    method.implementation?.instructions?.any { insn ->
                        insn is ReferenceInstruction &&
                            (insn.reference as? MethodReference)?.let { ref ->
                                ref.definingClass == resolvedPrefClass.type &&
                                    (resolvedPrefSetter == null || ref.name == resolvedPrefSetter.name) &&
                                    ref.returnType == "V" &&
                                    ref.parameterTypes.size == 1 &&
                                    ref.parameterTypes[0].toString() == "Ljava/lang/String;"
                            } == true
                    } == true
            }
            if (candidate != null) {
                switcherClass = classDef
                switchMethod = candidate
                return@classDefForEach
            }
        }

        val resolvedSwitcherClass = switcherClass ?: throw PatchException("Could not find feed switcher class")
        val resolvedSwitchMethod = switchMethod ?: throw PatchException("Could not find feed switch method")
        val prefFieldName = resolvedSwitcherClass.fields.firstOrNull {
            (it.accessFlags and AccessFlags.STATIC.value) == 0 && it.type == resolvedPrefClass.type
        }?.name

        val swipeMutable = mutableClassDefBy(resolvedSwipeClass.type)
        val switcherMutable = mutableClassDefBy(resolvedSwitcherClass.type)

        val publicStaticFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val privateStaticFlags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value

        swipeMutable.fields.add(
            ImmutableField(
                SWIPE_NAV_CONTAINER_CLASS,
                "pikoSwipeFollowingSetting",
                BOOLEAN_SETTING_CLASS,
                privateStaticFlags,
                null, null, null,
            ).toMutable(),
        )
        swipeMutable.fields.add(
            ImmutableField(
                SWIPE_NAV_CONTAINER_CLASS,
                "pikoSwitcherRef",
                "Ljava/lang/ref/WeakReference;",
                publicStaticFlags,
                null, null, null,
            ).toMutable(),
        )
        swipeMutable.fields.add(
            ImmutableField(
                SWIPE_NAV_CONTAINER_CLASS,
                "pikoCurrentFeed",
                "Ljava/lang/Object;",
                publicStaticFlags,
                null, null, null,
            ).toMutable(),
        )
        swipeMutable.fields.add(
            ImmutableField(
                SWIPE_NAV_CONTAINER_CLASS,
                "pikoDownX",
                "F",
                publicStaticFlags,
                null, null, null,
            ).toMutable(),
        )
        swipeMutable.fields.add(
            ImmutableField(
                SWIPE_NAV_CONTAINER_CLASS,
                "pikoHandledSwipe",
                "Z",
                publicStaticFlags,
                null, null, null,
            ).toMutable(),
        )

        fun addSwipeStaticMethod(
            name: String,
            params: List<String>,
            returnType: String,
            regCount: Int,
            instructions: String,
        ) {
            val method = ImmutableMethod(
                SWIPE_NAV_CONTAINER_CLASS,
                name,
                params.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                publicStaticFlags,
                null,
                null,
                ImmutableMethodImplementation(regCount, emptyList(), null, null),
            ).toMutable()
            method.addInstructions(0, instructions)
            swipeMutable.methods.add(method)
        }

        addSwipeStaticMethod(
            "pikoGetSwipeFollowingSetting",
            emptyList(),
            BOOLEAN_SETTING_CLASS,
            5,
            """
                sget-object v0, $SWIPE_NAV_CONTAINER_CLASS->pikoSwipeFollowingSetting:$BOOLEAN_SETTING_CLASS
                if-nez v0, :piko_cached
                new-instance v0, $BOOLEAN_SETTING_CLASS
                const-string v1, "piko_swipe_following_for_you"
                const-string v2, "Swipe between Following & For You"
                sget-object v3, $RESTART_POLICY_CLASS->NOT_REQUIRED:$RESTART_POLICY_CLASS
                const/4 v4, 0x1
                invoke-direct {v0, v1, v2, v3, v4}, $BOOLEAN_SETTING_CLASS-><init>(Ljava/lang/String;Ljava/lang/String;${RESTART_POLICY_CLASS}Z)V
                sput-object v0, $SWIPE_NAV_CONTAINER_CLASS->pikoSwipeFollowingSetting:$BOOLEAN_SETTING_CLASS
                :piko_cached
                return-object v0
            """.trimIndent(),
        )

        addSwipeStaticMethod(
            "pikoRegisterSwitcher",
            listOf("Ljava/lang/Object;"),
            "V",
            2,
            """
                new-instance v0, Ljava/lang/ref/WeakReference;
                invoke-direct {v0, p0}, Ljava/lang/ref/WeakReference;-><init>(Ljava/lang/Object;)V
                sput-object v0, $SWIPE_NAV_CONTAINER_CLASS->pikoSwitcherRef:Ljava/lang/ref/WeakReference;
                return-void
            """.trimIndent(),
        )

        addSwipeStaticMethod(
            "pikoOnFeedSelected",
            listOf("Ljava/lang/Object;"),
            "V",
            1,
            """
                sput-object p0, $SWIPE_NAV_CONTAINER_CLASS->pikoCurrentFeed:Ljava/lang/Object;
                return-void
            """.trimIndent(),
        )

        addSwipeStaticMethod(
            "pikoShouldBlockIntercept",
            listOf("Landroid/view/MotionEvent;"),
            "Z",
            3,
            """
                invoke-virtual {p0}, Landroid/view/MotionEvent;->getActionMasked()I
                move-result v0
                if-nez v0, :piko_check_up
                invoke-virtual {p0}, Landroid/view/MotionEvent;->getX()F
                move-result v0
                sput v0, $SWIPE_NAV_CONTAINER_CLASS->pikoDownX:F
                const/4 v0, 0x0
                sput-boolean v0, $SWIPE_NAV_CONTAINER_CLASS->pikoHandledSwipe:Z
                return v0
                :piko_check_up
                const/4 v1, 0x1
                if-eq v0, v1, :piko_reset
                const/4 v1, 0x3
                if-eq v0, v1, :piko_reset
                sget-boolean v0, $SWIPE_NAV_CONTAINER_CLASS->pikoHandledSwipe:Z
                return v0
                :piko_reset
                const/4 v0, 0x0
                sput-boolean v0, $SWIPE_NAV_CONTAINER_CLASS->pikoHandledSwipe:Z
                return v0
            """.trimIndent(),
        )

        val prefFieldResolutionBlock = if (prefFieldName != null && resolvedPrefGetter != null) {
            """
                iget-object v2, v0, ${resolvedSwitcherClass.type}->$prefFieldName:${resolvedPrefClass.type}
                if-eqz v2, :piko_not_following
                invoke-virtual {v2}, ${resolvedPrefClass.type}->${resolvedPrefGetter.name}()Ljava/lang/String;
                move-result-object v2
                if-eqz v2, :piko_not_following
                const-string v3, "FOLLOWING"
                invoke-virtual {v3, v2}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z
                move-result v2
                goto :piko_got_following
            """.trimIndent()
        } else {
            "const/4 v2, 0x0"
        }

        addSwipeStaticMethod(
            "pikoFilterIntercept",
            listOf(SWIPE_NAV_CONTAINER_CLASS, "Landroid/view/MotionEvent;", "Z"),
            "Z",
            9,
            """
                if-nez p2, :piko_check_pref
                return p2
                :piko_check_pref
                invoke-static {}, $UTILS_CLASS->getContext()Landroid/content/Context;
                move-result-object v0
                if-eqz v0, :piko_return_orig
                invoke-static {}, $SWIPE_NAV_CONTAINER_CLASS->pikoGetSwipeFollowingSetting()$BOOLEAN_SETTING_CLASS
                move-result-object v0
                invoke-static {v0}, $SHARED_PREF_CLASS->getBooleanPref($BASE_BOOLEAN_SETTING_CLASS)Ljava/lang/Boolean;
                move-result-object v0
                if-eqz v0, :piko_pref_ok
                invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
                move-result v0
                if-eqz v0, :piko_return_orig
                :piko_pref_ok
                invoke-virtual {p0}, $SWIPE_NAV_CONTAINER_CLASS->getPosition()F
                move-result v0
                invoke-static {v0}, Ljava/lang/Math;->abs(F)F
                move-result v0
                const v1, 0x3d4ccccd
                cmpl-float v0, v0, v1
                if-gez v0, :piko_return_orig
                sget-object v0, $SWIPE_NAV_CONTAINER_CLASS->pikoSwitcherRef:Ljava/lang/ref/WeakReference;
                if-eqz v0, :piko_return_orig
                invoke-virtual {v0}, Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :piko_return_orig
                check-cast v0, ${resolvedSwitcherClass.type}
                invoke-virtual {p1}, Landroid/view/MotionEvent;->getX()F
                move-result v1
                sget v2, $SWIPE_NAV_CONTAINER_CLASS->pikoDownX:F
                sub-float v1, v1, v2
                sget-object v2, $SWIPE_NAV_CONTAINER_CLASS->pikoCurrentFeed:Ljava/lang/Object;
                if-eqz v2, :piko_check_pref_field
                sget-object v3, ${resolvedEnumClass.type}->$resolvedFollowingField:${resolvedEnumClass.type}
                if-ne v2, v3, :piko_not_following
                const/4 v2, 0x1
                goto :piko_got_following
                :piko_not_following
                const/4 v2, 0x0
                goto :piko_got_following
                :piko_check_pref_field
                $prefFieldResolutionBlock
                :piko_got_following
                const/4 v3, 0x0
                if-eqz v2, :piko_check_for_you
                cmpg-float v4, v1, v3
                if-lez v4, :piko_return_orig
                sget-object v4, ${resolvedEnumClass.type}->$resolvedForYouField:${resolvedEnumClass.type}
                invoke-virtual {v0, v4}, ${resolvedSwitcherClass.type}->${resolvedSwitchMethod.name}(${resolvedEnumClass.type})V
                const/4 v4, 0x1
                sput-boolean v4, $SWIPE_NAV_CONTAINER_CLASS->pikoHandledSwipe:Z
                const/4 v4, 0x0
                return v4
                :piko_check_for_you
                cmpl-float v4, v1, v3
                if-gez v4, :piko_return_orig
                sget-object v4, ${resolvedEnumClass.type}->$resolvedFollowingField:${resolvedEnumClass.type}
                invoke-virtual {v0, v4}, ${resolvedSwitcherClass.type}->${resolvedSwitchMethod.name}(${resolvedEnumClass.type})V
                const/4 v4, 0x1
                sput-boolean v4, $SWIPE_NAV_CONTAINER_CLASS->pikoHandledSwipe:Z
                const/4 v4, 0x0
                return v4
                :piko_return_orig
                return p2
            """.trimIndent(),
        )

        switcherMutable.methods.filter { it.name == "<init>" }.forEach { ctor ->
            val returnIndices = ctor.implementation?.instructions
                ?.mapIndexedNotNull { index, insn -> if (insn.opcode == Opcode.RETURN_VOID) index else null }
                ?.asReversed()
                ?: emptyList()
            for (idx in returnIndices) {
                ctor.addInstructions(
                    idx,
                    "invoke-static/range {p0 .. p0}, $SWIPE_NAV_CONTAINER_CLASS->pikoRegisterSwitcher(Ljava/lang/Object;)V",
                )
            }
        }

        val mutableSwitchMethod = switcherMutable.methods.first {
            it.name == resolvedSwitchMethod.name &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString() == resolvedEnumClass.type &&
                it.returnType == "V"
        }
        mutableSwitchMethod.addInstructions(
            0,
            "invoke-static/range {p1 .. p1}, $SWIPE_NAV_CONTAINER_CLASS->pikoOnFeedSelected(Ljava/lang/Object;)V",
        )

        val onInterceptMethod = swipeMutable.methods.firstOrNull {
            it.name == "onInterceptTouchEvent" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString() == "Landroid/view/MotionEvent;" &&
                it.returnType == "Z"
        } ?: throw PatchException("Could not find onInterceptTouchEvent on SwipeNavigationContainer")

        val returnIndices = onInterceptMethod.implementation?.instructions
            ?.mapIndexedNotNull { index, insn ->
                if (insn.opcode == Opcode.RETURN) index to (insn as OneRegisterInstruction).registerA else null
            }
            ?.asReversed()
            ?: emptyList()

        for ((idx, reg) in returnIndices) {
            onInterceptMethod.addInstructions(
                idx,
                """
                    move/from16 v2, v$reg
                    move-object/from16 v0, p0
                    move-object/from16 v1, p1
                    invoke-static {v0, v1, v2}, $SWIPE_NAV_CONTAINER_CLASS->pikoFilterIntercept(${SWIPE_NAV_CONTAINER_CLASS}Landroid/view/MotionEvent;Z)Z
                    move-result v0
                    return v0
                """.trimIndent(),
            )
        }

        onInterceptMethod.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $SWIPE_NAV_CONTAINER_CLASS->pikoShouldBlockIntercept(Landroid/view/MotionEvent;)Z
                move-result v0
                if-eqz v0, :piko_continue
                const/4 v0, 0x0
                return v0
                :piko_continue
            """.trimIndent(),
        )
    }

    finalize {
        classDefByOrNull(SETTINGS_STATUS_CLASS)?.let { statusDef ->
            mutableClassDefBy(statusDef).methods
                .firstOrNull { it.name == "distractionFreeSection" && it.parameters.isEmpty() }
                ?.addInstructions(
                    0,
                    """
                        const/4 v0, 0x1
                        return v0
                    """.trimIndent(),
                )
        }

        classDefByOrNull(SCREEN_BUILDER_CLASS)?.let { sbDef ->
            val mutableSb = mutableClassDefBy(sbDef)

            val helperImpl = MutableMethodImplementation(4)
            helperImpl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            val addToggleMethod = ImmutableMethod(
                SCREEN_BUILDER_CLASS,
                "pikoAddSwipeFollowingToggle",
                emptyList(),
                "V",
                AccessFlags.PRIVATE.value,
                null,
                null,
                helperImpl,
            ).toMutable()
            addToggleMethod.addInstructions(
                0,
                """
                    const-string v0, "Swipe between Following & For You"
                    const-string v1, "Start Home on Following and swipe horizontally between Following and For You"
                    invoke-static {}, $SWIPE_NAV_CONTAINER_CLASS->pikoGetSwipeFollowingSetting()${BOOLEAN_SETTING_CLASS}
                    move-result-object v2
                    invoke-direct {p0, v0, v1, v2}, $SCREEN_BUILDER_CLASS->addLockableSwitch(Ljava/lang/String;Ljava/lang/String;${BOOLEAN_SETTING_CLASS})V
                    return-void
                """.trimIndent(),
            )
            addToggleMethod.removeInstruction(addToggleMethod.implementation!!.instructions.size - 1)
            mutableSb.methods.add(addToggleMethod)

            mutableSb.methods
                .firstOrNull { it.name == "distractionFreeSection" && it.parameters.isEmpty() }
                ?.let { sectionMethod ->
                    val insns = sectionMethod.implementation!!.instructions
                    val lastReturnIdx = insns.indexOfLast { it.opcode == Opcode.RETURN_VOID }
                    if (lastReturnIdx >= 0) {
                        sectionMethod.addInstructions(
                            lastReturnIdx,
                            "invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoAddSwipeFollowingToggle()V",
                        )
                    }
                }
        }
    }
}
