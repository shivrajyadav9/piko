package app.crimera.patches.newx.profile

import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val BUILT_IN_SETTINGS = "Lapp/morphe/extension/newx/settings/BuiltInSettings;"
private const val UTILS_CLASS = "Lapp/morphe/extension/shared/Utils;"

private val rememberProfileTabResourcesPatch = resourcePatch {
    execute {
        document("res/values/strings.xml").use { doc ->
            val resources = doc.getElementsByTagName("resources").item(0)
            val existingNames = mutableSetOf<String>()
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val attr = nodes.item(i).attributes?.getNamedItem("name")?.nodeValue
                if (attr != null) existingNames.add(attr)
            }
            fun addStringIfMissing(name: String, value: String) {
                if (name in existingNames) return
                val el = doc.createElement("string")
                el.setAttribute("name", name)
                el.textContent = value
                resources.appendChild(el)
                existingNames.add(name)
            }
            addStringIfMissing("piko_my_patches_category_title", "My Patches")
            addStringIfMissing("piko_my_patches_category_summary", "Custom patches")
            addStringIfMissing("piko_my_patches_remember_profile_tab_title", "Remember last profile tab")
            addStringIfMissing(
                "piko_my_patches_remember_profile_tab_summary",
                "Remember whether you last chose All, Tweets, or Highlights on profiles",
            )
        }
    }
}

@Suppress("unused")
val rememberProfileTabPatch = bytecodePatch(
    name = "Remember last profile tab",
    description = "Remembers your last selected profile tab (All, Tweets, or Highlights) across profiles.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)
    dependsOn(rememberProfileTabResourcesPatch)

    var resolvedHelperClass: String? = null

    execute {
        var tabEnumDefFound : com.android.tools.smali.dexlib2.iface.ClassDef?= classDefByOrNull("Lcom/x/profile/l0;")
            if (tabEnumDefFound == null) { 
                classDefForEach { cls ->
                    if (tabEnumDefFound == null) {
                val names = cls.staticFields.filter { it.type == cls.type }.map { it.name }.toSet()
                if ("All" in names && "Posts" in names && "Highlights" in names) { tabEnumDefFound = cls } }
            }
            }
            val tabEnumDef = tabEnumDefFound ?: throw PatchException("RememberProfileTabPatch: could not find profile tab enum")
        val tabEnum = tabEnumDef.type

        var controllerDefFound: com.android.tools.smali.dexlib2.iface.ClassDef? = null; classDefForEach { cls -> if (controllerDefFound == null &&
            cls.methods.any { m ->
                m.name == "<init>" && m.implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.CONST_STRING &&
                        ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "Combined timeline requires at least two sub-tabs"
                } == true
            }
        ) { controllerDefFound = cls } }; val controllerDef = controllerDefFound ?: throw PatchException("RememberProfileTabPatch: could not find combined profile timeline controller")

        val controllerMutable = mutableClassDefBy(controllerDef)
        val controllerType = controllerMutable.type
        resolvedHelperClass = controllerType

        val initMethod = controllerMutable.methods.firstOrNull { it.name == "<init>" }
            ?: throw PatchException("RememberProfileTabPatch: could not find controller <init>")
        val initInsns = initMethod.implementation?.instructions?.toList()
            ?: throw PatchException("RememberProfileTabPatch: controller <init> has no implementation")

        var hookIdx = -1
        var tabReg = -1
        var thisReg = -1
        for (i in 0 until initInsns.size - 3) {
            val ins = initInsns[i]
            if (ins.opcode != Opcode.CHECK_CAST) continue
            val typeRef = (ins as? ReferenceInstruction)?.reference as? TypeReference ?: continue
            if (typeRef.type != tabEnum) continue
            val next = initInsns[i + 1]
            if (next.opcode != Opcode.INVOKE_STATIC) continue
            val mRef = (next as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (mRef.parameterTypes.size != 1 || mRef.parameterTypes[0].toString() != "Ljava/lang/Object;") continue
            val iput = initInsns[i + 3]
            if (iput.opcode != Opcode.IPUT_OBJECT) continue
            hookIdx = i + 1
            tabReg = (next as FiveRegisterInstruction).registerC
            thisReg = (iput as TwoRegisterInstruction).registerB
            break
        }
        if (hookIdx < 0) {
            throw PatchException("RememberProfileTabPatch: could not find initial tab StateFlow creation in <init>")
        }

        var sFieldName: String? = null
        var sFieldType: String? = null
        for (i in hookIdx - 1 downTo 0) {
            val ins = initInsns[i]
            if (ins.opcode == Opcode.IPUT_OBJECT) {
                val fRef = (ins as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                if (fRef.definingClass == controllerType && fRef.type.startsWith("Lkotlinx/coroutines/flow/")) {
                    sFieldName = fRef.name
                    sFieldType = fRef.type
                    break
                }
            }
        }
        if (sFieldName == null || sFieldType == null) {
            throw PatchException("RememberProfileTabPatch: could not find availableTabs StateFlow field")
        }

        val selectTabMethod = controllerMutable.methods.firstOrNull { m ->
            m.returnType == "V" &&
                m.parameters.size == 1 &&
                m.parameters[0].type == tabEnum &&
                m.implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                            ref.definingClass == controllerType &&
                                ref.returnType == "V" &&
                                ref.parameterTypes.size == 1 &&
                                ref.parameterTypes[0].toString() == tabEnum &&
                                ref.name != m.name
                        } == true
                } == true
        } ?: throw PatchException("RememberProfileTabPatch: could not find user tab selection method")

        if (controllerMutable.staticFields.none { it.name == "pikoRememberProfileTabRegistered" }) {
            controllerMutable.staticFields.add(
                ImmutableField(
                    controllerType,
                    "pikoRememberProfileTabRegistered",
                    "Z",
                    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    null,
                ).toMutable(),
            )
        }

        fun addHelper(
            name: String,
            isStatic: Boolean,
            params: List<String>,
            returnType: String,
            registerCount: Int,
            smali: String,
        ) {
            if (controllerMutable.methods.any { it.name == name }) return
            val flags = (if (isStatic) AccessFlags.PUBLIC.value or AccessFlags.STATIC.value else AccessFlags.PRIVATE.value)
            val impl = MutableMethodImplementation(registerCount)
            impl.addInstruction(BuilderInstruction10x(Opcode.NOP))
            val m = ImmutableMethod(
                controllerType,
                name,
                params.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                flags,
                null,
                null,
                impl,
            ).toMutable()
            m.addInstructions(0, smali)
            controllerMutable.methods.add(m)
        }

        addHelper(
            name = "pikoRegisterRememberProfileTabSettings",
            isStatic = true,
            params = emptyList(),
            returnType = "V",
            registerCount = 5,
            smali = """
                sget-boolean v0, $controllerType->pikoRememberProfileTabRegistered:Z
                if-nez v0, :done
                const/4 v0, 0x1
                sput-boolean v0, $controllerType->pikoRememberProfileTabRegistered:Z
                const-string v0, "newx.my_patches"
                const-string v1, "piko_my_patches_category_title"
                const-string v2, "piko_my_patches_category_summary"
                const-string v3, "ic_vector_wrench"
                const/16 v4, 0x32
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerCategory(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches"
                const-string v1, "newx.my_patches.remember_profile_tab"
                const-string v2, "piko_my_patches_remember_profile_tab_title"
                const-string v3, "piko_my_patches_remember_profile_tab_summary"
                const/16 v4, 0x28
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerToggle(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches.remember_profile_tab"
                const/4 v1, 0x1
                const/4 v2, 0x0
                invoke-static {v0, v1, v2}, $SETTINGS_REGISTRY->configureToggle(Ljava/lang/String;ZZ)V
                :done
                return-void
            """.trimIndent(),
        )

        addHelper(
            name = "pikoSaveProfileTab",
            isStatic = true,
            params = listOf(tabEnum),
            returnType = "V",
            registerCount = 5,
            smali = """
                if-eqz p0, :done
                const-string v0, "newx.my_patches.remember_profile_tab"
                const/4 v1, 0x1
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                move-result v0
                if-eqz v0, :done
                invoke-virtual {p0}, Ljava/lang/Enum;->name()Ljava/lang/String;
                move-result-object v0
                const-string v1, "All"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :save
                const-string v1, "Posts"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-nez v1, :save
                const-string v1, "Highlights"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :done
                :save
                invoke-static {}, $UTILS_CLASS->getContext()Landroid/content/Context;
                move-result-object v1
                if-eqz v1, :done
                const-string v2, "piko_profile_prefs"
                const/4 v3, 0x0
                invoke-virtual {v1, v2, v3}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                move-result-object v1
                invoke-interface {v1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v1
                const-string v2, "last_profile_tab"
                invoke-interface {v1, v2, v0}, Landroid/content/SharedPreferences${'$'}Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v1
                invoke-interface {v1}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
                :done
                return-void
            """.trimIndent(),
        )

        addHelper(
            name = "pikoRestoreProfileTab",
            isStatic = false,
            params = listOf(tabEnum),
            returnType = tabEnum,
            registerCount = 6,
            smali = """
                const-string v0, "newx.my_patches.remember_profile_tab"
                const/4 v1, 0x1
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                move-result v0
                if-eqz v0, :fallback
                invoke-static {}, $UTILS_CLASS->getContext()Landroid/content/Context;
                move-result-object v0
                if-eqz v0, :fallback
                const-string v1, "piko_profile_prefs"
                const/4 v2, 0x0
                invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                move-result-object v0
                const-string v1, "last_profile_tab"
                const/4 v2, 0x0
                invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :fallback
                iget-object v1, p0, $controllerType->$sFieldName:$sFieldType
                invoke-virtual {v1}, $sFieldType->getValue()Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Ljava/util/List;
                sget-object v2, $tabEnum->Posts:$tabEnum
                invoke-interface {v1, v2}, Ljava/util/List;->contains(Ljava/lang/Object;)Z
                move-result v2
                if-nez v2, :find_tab
                sget-object v2, $tabEnum->All:$tabEnum
                invoke-interface {v1, v2}, Ljava/util/List;->contains(Ljava/lang/Object;)Z
                move-result v2
                if-eqz v2, :fallback
                :find_tab
                invoke-interface {v1}, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v1
                :loop
                invoke-interface {v1}, Ljava/util/Iterator;->hasNext()Z
                move-result v2
                if-eqz v2, :fallback
                invoke-interface {v1}, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v2
                check-cast v2, $tabEnum
                invoke-virtual {v2}, Ljava/lang/Enum;->name()Ljava/lang/String;
                move-result-object v3
                invoke-virtual {v0, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v3
                if-eqz v3, :loop
                return-object v2
                :fallback
                return-object p1
            """.trimIndent(),
        )

        initMethod.addInstructions(
            hookIdx,
            """
                invoke-direct {v$thisReg, v$tabReg}, $controllerType->pikoRestoreProfileTab($tabEnum)$tabEnum
                move-result-object v$tabReg
            """.trimIndent(),
        )

        selectTabMethod.addInstructions(
            0,
            "invoke-static {p1}, $controllerType->pikoSaveProfileTab($tabEnum)V",
        )
    }

    finalize {
        val helperClassType = resolvedHelperClass ?: return@finalize
        classDefByOrNull(BUILT_IN_SETTINGS)?.let { builtInDef ->
            mutableClassDefBy(builtInDef).methods
                .firstOrNull { it.name == "register" && it.parameters.isEmpty() }
                ?.addInstructions(
                    0,
                    "invoke-static {}, $helperClassType->pikoRegisterRememberProfileTabSettings()V",
                )
        }
    }
}
