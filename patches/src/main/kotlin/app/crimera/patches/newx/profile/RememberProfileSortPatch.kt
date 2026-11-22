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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val SETTINGS_REGISTRY = "Lapp/morphe/extension/crimera/settings/SettingsRegistry;"
private const val BUILT_IN_SETTINGS = "Lapp/morphe/extension/newx/settings/BuiltInSettings;"
private const val UTILS_CLASS = "Lapp/morphe/extension/shared/Utils;"

private val rememberProfileSortResourcesPatch = resourcePatch {
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
            addStringIfMissing("piko_my_patches_remember_profile_sort_title", "Remember last profile sorting")
            addStringIfMissing(
                "piko_my_patches_remember_profile_sort_summary",
                "Remember whether you last sorted profile posts by Popular or Latest",
            )
        }
    }
}

@Suppress("unused")
val rememberProfileSortPatch = bytecodePatch(
    name = "Remember last profile sorting",
    description = "Remembers your last selected profile post sort order (Popular or Latest) across profiles.",
) {
    compatibleWith(COMPATIBILITY_NEW_X)
    dependsOn(rememberProfileSortResourcesPatch)

    var resolvedHelperClass: String? = null

    execute {
       var controllerDefFound: com.android.tools.smali.dexlib2.iface.ClassDef? = null
        classDefForEach { cls ->
        if (controllerDefFound == null) {    
            val matches = cls.methods.any { m ->
                m.name == "<init>" && m.implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.CONST_STRING &&
                        ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "Combined timeline requires at least two sub-tabs"
                } == true
            }
            if (matches) controllerDefFound = cls
        }
        }
    val controllerDef = controllerDefFound
    ?: throw PatchException("RememberProfileSortPatch: could not find combined profile timeline controller")

        val controllerMutable = mutableClassDefBy(controllerDef)
        val controllerType = controllerMutable.type
        resolvedHelperClass = controllerType

        val sortingEnabledMethod = controllerMutable.methods.firstOrNull { m ->
            m.returnType == "Z" &&
                m.parameters.isEmpty() &&
                m.implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.CONST_STRING &&
                        ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "x_lite_profile_timeline_sorting_enabled"
                } == true
        } ?: throw PatchException("RememberProfileSortPatch: could not find sortingEnabled method")

        val setSortMethod = controllerMutable.methods.firstOrNull { m ->
            m.returnType == "V" &&
                m.parameters.size == 1 &&
                m.parameters[0].type == "Z" &&
                m.implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((ins as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                            ref.definingClass == controllerType && ref.name == sortingEnabledMethod.name
                        } == true
                } == true
        } ?: throw PatchException("RememberProfileSortPatch: could not find setSort(Z) method")

        val initMethod = controllerMutable.methods.firstOrNull { it.name == "<init>" }
            ?: throw PatchException("RememberProfileSortPatch: could not find controller <init>")
        val initInsns = initMethod.implementation?.instructions?.toList()
            ?: throw PatchException("RememberProfileSortPatch: controller <init> has no implementation")

        var hookIdx = -1
        var boolReg = -1
        var thisReg = -1
        for (i in 0 until initInsns.size - 3) {
            val ins = initInsns[i]
            if (ins.opcode != Opcode.SGET_OBJECT) continue
            val fRef = (ins as? ReferenceInstruction)?.reference as? FieldReference ?: continue
            if (fRef.definingClass != "Ljava/lang/Boolean;" || fRef.name != "FALSE") continue
            val next = initInsns[i + 1]
            if (next.opcode != Opcode.INVOKE_STATIC) continue
            val mRef = (next as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (mRef.parameterTypes.size != 1 || mRef.parameterTypes[0].toString() != "Ljava/lang/Object;") continue
            val iput = initInsns[i + 3]
            if (iput.opcode != Opcode.IPUT_OBJECT) continue
            hookIdx = i + 1
            boolReg = (ins as OneRegisterInstruction).registerA
            thisReg = (iput as TwoRegisterInstruction).registerB
            break
        }
        if (hookIdx < 0) {
            throw PatchException("RememberProfileSortPatch: could not find initial sort StateFlow creation in <init>")
        }

        if (controllerMutable.staticFields.none { it.name == "pikoRememberProfileSortRegistered" }) {
            controllerMutable.staticFields.add(
                ImmutableField(
                    controllerType,
                    "pikoRememberProfileSortRegistered",
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
            name = "pikoRegisterRememberProfileSortSettings",
            isStatic = true,
            params = emptyList(),
            returnType = "V",
            registerCount = 5,
            smali = """
                sget-boolean v0, $controllerType->pikoRememberProfileSortRegistered:Z
                if-nez v0, :done
                const/4 v0, 0x1
                sput-boolean v0, $controllerType->pikoRememberProfileSortRegistered:Z
                const-string v0, "newx.my_patches"
                const-string v1, "piko_my_patches_category_title"
                const-string v2, "piko_my_patches_category_summary"
                const-string v3, "ic_vector_wrench"
                const/16 v4, 0x32
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerCategory(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches"
                const-string v1, "newx.my_patches.remember_profile_sort"
                const-string v2, "piko_my_patches_remember_profile_sort_title"
                const-string v3, "piko_my_patches_remember_profile_sort_summary"
                const/16 v4, 0x32
                invoke-static {v0, v1, v2, v3, v4}, $SETTINGS_REGISTRY->registerToggle(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
                const-string v0, "newx.my_patches.remember_profile_sort"
                const/4 v1, 0x1
                const/4 v2, 0x0
                invoke-static {v0, v1, v2}, $SETTINGS_REGISTRY->configureToggle(Ljava/lang/String;ZZ)V
                :done
                return-void
            """.trimIndent(),
        )

        addHelper(
            name = "pikoSaveProfileSort",
            isStatic = true,
            params = listOf("Z"),
            returnType = "V",
            registerCount = 4,
            smali = """
                const-string v0, "newx.my_patches.remember_profile_sort"
                const/4 v1, 0x1
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                move-result v0
                if-eqz v0, :done
                invoke-static {}, $UTILS_CLASS->getContext()Landroid/content/Context;
                move-result-object v0
                if-eqz v0, :done
                const-string v1, "piko_profile_prefs"
                const/4 v2, 0x0
                invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                move-result-object v0
                invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v0
                const-string v1, "last_profile_sort_popular"
                invoke-interface {v0, v1, p0}, Landroid/content/SharedPreferences${'$'}Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences${'$'}Editor;
                move-result-object v0
                invoke-interface {v0}, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
                :done
                return-void
            """.trimIndent(),
        )

        addHelper(
            name = "pikoInitialProfileSort",
            isStatic = false,
            params = emptyList(),
            returnType = "Ljava/lang/Boolean;",
            registerCount = 4,
            smali = """
                invoke-virtual {p0}, $controllerType->${sortingEnabledMethod.name}()Z
                move-result v0
                if-eqz v0, :use_false
                const-string v0, "newx.my_patches.remember_profile_sort"
                const/4 v1, 0x1
                invoke-static {v0, v1}, $SETTINGS_REGISTRY->getBooleanOrDefault(Ljava/lang/String;Z)Z
                move-result v0
                if-eqz v0, :use_false
                invoke-static {}, $UTILS_CLASS->getContext()Landroid/content/Context;
                move-result-object v0
                if-eqz v0, :use_false
                const-string v1, "piko_profile_prefs"
                const/4 v2, 0x0
                invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                move-result-object v0
                const-string v1, "last_profile_sort_popular"
                const/4 v2, 0x0
                invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z
                move-result v0
                invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object v0
                return-object v0
                :use_false
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object v0
            """.trimIndent(),
        )

        initMethod.addInstructions(
            hookIdx,
            """
                invoke-direct {v$thisReg}, $controllerType->pikoInitialProfileSort()Ljava/lang/Boolean;
                move-result-object v$boolReg
            """.trimIndent(),
        )

        setSortMethod.addInstructions(
            0,
            "invoke-static {p1}, $controllerType->pikoSaveProfileSort(Z)V",
        )
    }

    finalize {
        val helperClassType = resolvedHelperClass ?: return@finalize
        classDefByOrNull(BUILT_IN_SETTINGS)?.let { builtInDef ->
            mutableClassDefBy(builtInDef).methods
                .firstOrNull { it.name == "register" && it.parameters.isEmpty() }
                ?.addInstructions(
                    0,
                    "invoke-static {}, $helperClassType->pikoRegisterRememberProfileSortSettings()V",
                )
        }
    }
}
