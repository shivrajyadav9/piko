package app.crimera.patches.instagram.feed

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
private const val MEDIA_CLASS = "Lcom/instagram/feed/media/Media;"
private const val CLIPS_NETEGO_CLASS = "Lcom/instagram/api/schemas/ClipsNetego;"
private const val BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/instagram/settings/BooleanSetting;"
private const val BASE_BOOLEAN_SETTING_CLASS = "Lapp/morphe/extension/crimera/settings/BooleanSetting;"
private const val RESTART_POLICY_CLASS = "Lapp/morphe/extension/instagram/settings/RestartPolicy;"
private const val SHARED_PREF_CLASS = "Lapp/morphe/extension/crimera/sharedPreference/SharedPref;"
private const val SCREEN_BUILDER_CLASS = "Lapp/morphe/extension/instagram/settings/preference/ScreenBuilder;"
private const val SETTINGS_STATUS_CLASS = "Lapp/morphe/extension/instagram/settings/SettingsStatus;"

private const val HASH_MEDIA_TYPE = 1939875509L
private const val HASH_ORIGINAL_HEIGHT = -636516523L
private const val HASH_ORIGINAL_WIDTH = 1933097432L

private val feedItemParserFingerprint = fingerprint {
    returns("Ljava/lang/Object;")
    parameters("L")
    strings("media_or_ad", "explore_story", "clips_netego")
}

private val homeFeedResponseFingerprint = fingerprint {
    returns("Ljava/lang/Object;")
    parameters("L")
    strings("pull_to_refresh_window_ms", "feed_items")
}

private val savedFeedPrefFingerprint = fingerprint {
    strings("last_selected_feed_type")
}
@Suppress("unused")
val hideForYouVerticalReelsPatch = bytecodePatch(
    name = "Hide vertical reels in For You",
    description = "Removes tall vertical reels (overlay header) from the For You feed while keeping photos, carousels, horizontal videos, and Following reels. Adds a toggle in Distraction free settings.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    var resolvedFeedItemType: String? = null

    execute {
        // 1. Resolve FeedItem class from FeedItem parser ("media_or_ad", "explore_story", "clips_netego")
        val parserMethod = feedItemParserFingerprint.method
        val feedItemType = parserMethod.implementation!!.instructions
            .asSequence()
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type }
            .firstOrNull { candidateType ->
                val cls = classDefByOrNull(candidateType) ?: return@firstOrNull false
                cls.fields.any { it.type == CLIPS_NETEGO_CLASS } && cls.fields.any { it.type == MEDIA_CLASS }
            } ?: return@execute

        resolvedFeedItemType = feedItemType
        val feedItemClassDef = classDefBy(feedItemType)
        val mutableFeedItem = mutableClassDefBy(feedItemClassDef)

        val mediaOrAdField = feedItemClassDef.fields.firstOrNull { it.type == MEDIA_CLASS } ?: return@execute
        val clipsNetegoField = feedItemClassDef.fields.firstOrNull { it.type == CLIPS_NETEGO_CLASS }

        // Resolve explore_story wrapper class and its Media fields
        val exploreStoryField = feedItemClassDef.fields.firstOrNull { field ->
            val cls = classDefByOrNull(field.type) ?: return@firstOrNull false
            cls.fields.count { it.type == MEDIA_CLASS } >= 1 &&
                cls.methods.any { m ->
                    m.name == "<init>" && m.implementation?.instructions?.any { insn ->
                        (insn as? ReferenceInstruction)?.reference?.toString()?.contains("XDTExploreStory") == true
                    } == true
                }
        }
        val exploreStoryMediaFields = exploreStoryField?.let { f ->
            classDefByOrNull(f.type)?.fields?.filter { it.type == MEDIA_CLASS } ?: emptyList()
        } ?: emptyList()

        // 2. Resolve Media getters for media_type, original_height, original_width via stable hash codes
        val mediaClassDef = classDefBy(MEDIA_CLASS)
        fun resolveMediaIntGetter(targetHash: Long): String? {
            for (method in mediaClassDef.methods) {
                val insns = method.implementation?.instructions?.toList() ?: continue
                for (i in insns.indices) {
                    val lit = (insns[i] as? WideLiteralInstruction)?.wideLiteral ?: continue
                    if (lit == targetHash) {
                        for (j in i + 1 until minOf(i + 8, insns.size)) {
                            if (insns[j].opcode == Opcode.IPUT_OBJECT) {
                                val ref = (insns[j] as? ReferenceInstruction)?.reference as? FieldReference
                                if (ref != null) {
                                    val getter = mediaClassDef.methods.firstOrNull {
                                        it.name == ref.name && it.parameters.isEmpty() && it.returnType == "Ljava/lang/Integer;"
                                    }
                                    if (getter != null) return getter.name
                                }
                            }
                        }
                    }
                }
            }
            return null
        }

        val mediaTypeGetter = resolveMediaIntGetter(HASH_MEDIA_TYPE) ?: return@execute
        val originalHeightGetter = resolveMediaIntGetter(HASH_ORIGINAL_HEIGHT) ?: return@execute
        val originalWidthGetter = resolveMediaIntGetter(HASH_ORIGINAL_WIDTH) ?: return@execute

        // 3. Add static fields on FeedItem for active feed mode and Piko BooleanSetting
        mutableFeedItem.fields.add(
            ImmutableField(
                feedItemType,
                "pikoActiveFeed",
                "Ljava/lang/String;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                null,
            ).toMutable(),
        )
        mutableFeedItem.fields.add(
            ImmutableField(
                feedItemType,
                "pikoHideVerticalReelsSetting",
                BOOLEAN_SETTING_CLASS,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                null,
            ).toMutable(),
        )

        fun addStaticHelper(
            name: String,
            params: List<String>,
            returnType: String,
            registerCount: Int,
            smali: String,
        ) {
            val impl = MutableMethodImplementation(registerCount)
            impl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            val helper = ImmutableMethod(
                feedItemType,
                name,
                params.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                impl,
            ).toMutable()
            helper.addInstructions(0, smali)
            helper.removeInstruction(helper.implementation!!.instructions.size - 1)
            mutableFeedItem.methods.add(helper)
        }

        // Lazy BooleanSetting getter
        addStaticHelper(
            name = "pikoGetVerticalReelsSetting",
            params = emptyList(),
            returnType = BOOLEAN_SETTING_CLASS,
            registerCount = 4,
            smali = """
                sget-object v0, $feedItemType->pikoHideVerticalReelsSetting:$BOOLEAN_SETTING_CLASS
                if-nez v0, :ret
                new-instance v0, $BOOLEAN_SETTING_CLASS
                const-string v1, "piko_hide_for_you_vertical_reels"
                sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                sget-object v3, $RESTART_POLICY_CLASS->NOT_REQUIRED:$RESTART_POLICY_CLASS
                invoke-direct {v0, v1, v2, v3}, $BOOLEAN_SETTING_CLASS-><init>(Ljava/lang/String;Ljava/lang/Boolean;$RESTART_POLICY_CLASS)V
                sput-object v0, $feedItemType->pikoHideVerticalReelsSetting:$BOOLEAN_SETTING_CLASS
                :ret
                return-object v0
            """.trimIndent(),
        )

        // Check toggle enabled
        addStaticHelper(
            name = "pikoIsVerticalReelsFilterEnabled",
            params = emptyList(),
            returnType = "Z",
            registerCount = 2,
            smali = """
                invoke-static {}, $feedItemType->pikoGetVerticalReelsSetting()$BOOLEAN_SETTING_CLASS
                move-result-object v0
                invoke-static {v0}, $SHARED_PREF_CLASS->getBooleanPref($BASE_BOOLEAN_SETTING_CLASS)Ljava/lang/Boolean;
                move-result-object v0
                if-eqz v0, :default_on
                invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
                move-result v0
                return v0
                :default_on
                const/4 v0, 0x1
                return v0
            """.trimIndent(),
        )

        val clipsDropSmali = if (clipsNetegoField != null) {
            """
                iget-object v0, p0, $feedItemType->${clipsNetegoField.name}:$CLIPS_NETEGO_CLASS
                if-eqz v0, :check_media
                const/4 v0, 0x0
                return-object v0
                :check_media
            """.trimIndent()
        } else {
            ""
        }

        val exploreExtractSmali = if (exploreStoryField != null && exploreStoryMediaFields.isNotEmpty()) {
            val f1 = exploreStoryMediaFields[0]
            val f2Check = if (exploreStoryMediaFields.size >= 2) {
                val f2 = exploreStoryMediaFields[1]
                """
                    if-nez v0, :have_media
                    iget-object v0, v1, ${exploreStoryField.type}->${f2.name}:$MEDIA_CLASS
                """.trimIndent()
            } else {
                ""
            }
            """
                if-nez v0, :have_media
                iget-object v1, p0, $feedItemType->${exploreStoryField.name}:${exploreStoryField.type}
                if-eqz v1, :keep
                iget-object v0, v1, ${exploreStoryField.type}->${f1.name}:$MEDIA_CLASS
                $f2Check
            """.trimIndent()
        } else {
            ""
        }

        // Main feed item filter: drops tall vertical reels (h*4 > w*5) in For You while keeping photos, carousels, horizontal/4:5 videos, and all Following posts
        addStaticHelper(
            name = "pikoFilterFeedItem",
            params = listOf(feedItemType),
            returnType = feedItemType,
            registerCount = 4,
            smali = """
                if-nez p0, :non_null
                const/4 v0, 0x0
                return-object v0
                :non_null
                invoke-static {}, $feedItemType->pikoIsVerticalReelsFilterEnabled()Z
                move-result v0
                if-nez v0, :check_feed
                return-object p0
                :check_feed
                sget-object v0, $feedItemType->pikoActiveFeed:Ljava/lang/String;
                if-eqz v0, :do_filter
                const-string v1, "FOLLOWING"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :check_fav
                return-object p0
                :check_fav
                const-string v1, "FAVORITES"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :do_filter
                return-object p0
                :do_filter
                $clipsDropSmali
                iget-object v0, p0, $feedItemType->${mediaOrAdField.name}:$MEDIA_CLASS
                $exploreExtractSmali
                :have_media
                if-nez v0, :check_type
                return-object p0
                :check_type
                invoke-virtual {v0}, $MEDIA_CLASS->$mediaTypeGetter()Ljava/lang/Integer;
                move-result-object v1
                if-nez v1, :unbox_type
                return-object p0
                :unbox_type
                invoke-virtual {v1}, Ljava/lang/Integer;->intValue()I
                move-result v1
                const/4 v2, 0x2
                if-eq v1, v2, :check_dims
                return-object p0
                :check_dims
                invoke-virtual {v0}, $MEDIA_CLASS->$originalWidthGetter()Ljava/lang/Integer;
                move-result-object v1
                invoke-virtual {v0}, $MEDIA_CLASS->$originalHeightGetter()Ljava/lang/Integer;
                move-result-object v2
                if-eqz v1, :keep
                if-eqz v2, :keep
                invoke-virtual {v1}, Ljava/lang/Integer;->intValue()I
                move-result v0
                invoke-virtual {v2}, Ljava/lang/Integer;->intValue()I
                move-result v1
                if-lez v0, :keep
                if-lez v1, :keep
                mul-int/lit8 v1, v1, 0x4
                mul-int/lit8 v0, v0, 0x5
                if-le v1, v0, :keep
                const/4 v0, 0x0
                return-object v0
                :keep
                return-object p0
            """.trimIndent(),
        )

        // 4. Track active feed mode ("FOLLOWING" / "FOR_YOU" / "FAVORITES") via saved feed preference class
        val prefClass = mutableClassDefBy(savedFeedPrefFingerprint.classDef)
        prefClass.methods.firstOrNull {
            it.parameters.size == 1 && it.parameters[0].type == "Ljava/lang/String;" && it.returnType == "V"
        }?.addInstructions(
            0,
            "sput-object p1, $feedItemType->pikoActiveFeed:Ljava/lang/String;",
        )
        prefClass.methods.firstOrNull {
            it.parameters.isEmpty() && it.returnType == "Ljava/lang/String;"
        }?.let { getMethod ->
            val insns = getMethod.implementation?.instructions ?: return@let
            val retIndices = insns.mapIndexedNotNull { idx, insn ->
                if (insn.opcode == Opcode.RETURN_OBJECT) idx else null
            }.reversed()
            for (idx in retIndices) {
                val reg = (insns[idx] as OneRegisterInstruction).registerA
                getMethod.addInstructions(
                    idx,
                    "sput-object v$reg, $feedItemType->pikoActiveFeed:Ljava/lang/String;",
                )
            }
        }

        // 5. Hook Home feed response parser ("pull_to_refresh_window_ms", "feed_items") after FeedItem parse calls
        val homeResponseMethod = homeFeedResponseFingerprint.method
        val responseInsns = homeResponseMethod.implementation!!.instructions
        val parseCallSites = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until responseInsns.size - 1) {
            val insn = responseInsns[i]
            val next = responseInsns[i + 1]
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (ref.definingClass == feedItemType && ref.returnType == feedItemType && next.opcode == Opcode.MOVE_RESULT_OBJECT) {
                val reg = (next as OneRegisterInstruction).registerA
                parseCallSites.add((i + 2) to reg)
            }
        }
        for ((insertIdx, reg) in parseCallSites.asReversed()) {
            homeResponseMethod.addInstructions(
                insertIdx,
                """
                    invoke-static/range {v$reg .. v$reg}, $feedItemType->pikoFilterFeedItem($feedItemType)$feedItemType
                    move-result-object v$reg
                """.trimIndent(),
            )
        }
    }

    finalize {
        val feedItemType = resolvedFeedItemType ?: return@finalize

        // Ensure Distraction free section is visible in Instagram Piko settings
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

        // Add toggle switch inside ScreenBuilder.distractionFreeSection()
        classDefByOrNull(SCREEN_BUILDER_CLASS)?.let { sbDef ->
            val mutableSb = mutableClassDefBy(sbDef)
            val helperImpl = MutableMethodImplementation(4)
            helperImpl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
            val addToggleMethod = ImmutableMethod(
                SCREEN_BUILDER_CLASS,
                "pikoAddHideVerticalReelsToggle",
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
                    const-string v0, "Hide vertical reels in For You"
                    const-string v1, "Remove tall vertical reels from For You while keeping photos, carousels, horizontal videos, and Following reels"
                    invoke-static {}, $feedItemType->pikoGetVerticalReelsSetting()$BOOLEAN_SETTING_CLASS
                    move-result-object v2
                    invoke-direct {p0, v0, v1, v2}, $SCREEN_BUILDER_CLASS->addLockableSwitch(Ljava/lang/String;Ljava/lang/String;$BOOLEAN_SETTING_CLASS)V
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
                            "invoke-direct {p0}, $SCREEN_BUILDER_CLASS->pikoAddHideVerticalReelsToggle()V",
                        )
                    }
                }
        }
    }
}
