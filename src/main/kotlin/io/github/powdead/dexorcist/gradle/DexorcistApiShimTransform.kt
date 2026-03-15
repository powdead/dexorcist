package io.github.powdead.dexorcist.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

// Rewrites missing framework calls → static shim calls. Subclass-aware.
abstract class DexorcistApiShimTransform :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(classContext: ClassContext, next: ClassVisitor): ClassVisitor =
        ShimClassVisitor(classContext, next)

    override fun isInstrumentable(classData: ClassData): Boolean =
        !classData.className.startsWith("io.github.powdead.dexorcist.shim.")
}

private class ShimClassVisitor(
    private val ctx: ClassContext, cv: ClassVisitor
) : ClassVisitor(Opcodes.ASM9, cv) {
    private val cache = mutableMapOf<Pair<String, String>, Boolean>()
    override fun visitMethod(access: Int, name: String?, desc: String?, sig: String?, exc: Array<out String>?): MethodVisitor {
        val mv = super.visitMethod(access, name, desc, sig, exc)
        return ShimMethodVisitor(mv, ctx, cache)
    }
}

private class ShimMethodVisitor(
    mv: MethodVisitor,
    private val ctx: ClassContext,
    private val cache: MutableMap<Pair<String, String>, Boolean>
) : MethodVisitor(Opcodes.ASM9, mv) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        if (opcode == Opcodes.INVOKEVIRTUAL) {
            SHIM_MAP[MK(name, desc)]?.forEach { e ->
                if (isSubclass(owner, e.base)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, e.shim, e.name, "(L${e.base};${desc.substring(1)}", false)
                    return
                }
            }
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    private fun isSubclass(cls: String, parent: String): Boolean {
        if (cls == parent) return true
        return cache.getOrPut(cls to parent) {
            val d = ctx.loadClassData(cls.replace('/', '.'))
            d != null && parent.replace('/', '.') in d.superClasses
        }
    }

    companion object {
        private data class MK(val name: String, val desc: String)
        private data class SE(val base: String, val shim: String, val name: String)

        private val SHIM_MAP: Map<MK, List<SE>> = buildMap {
            fun add(base: String, m: String, d: String, shim: String) {
                val k = MK(m, d); put(k, (get(k) ?: emptyList()) + SE(base, shim, m))
            }
            val S = "io/github/powdead/dexorcist/shim"

            // Bundle (API 12)
            add("android/os/Bundle", "getString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", "$S/BundleCompat")
            add("android/os/Bundle", "getCharSequence", "(Ljava/lang/String;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;", "$S/BundleCompat")
            // Display (API 13)
            add("android/view/Display", "getSize", "(Landroid/graphics/Point;)V", "$S/DisplayCompat")
            // View (API 11)
            for ((m, d) in listOf(
                "setAlpha" to "(F)V", "getAlpha" to "()F",
                "setTranslationX" to "(F)V", "getTranslationX" to "()F",
                "setTranslationY" to "(F)V", "getTranslationY" to "()F",
                "setScaleX" to "(F)V", "getScaleX" to "()F",
                "setScaleY" to "(F)V", "getScaleY" to "()F",
                "setRotation" to "(F)V", "getRotation" to "()F",
                "setLayerType" to "(ILandroid/graphics/Paint;)V",
                "getLayerType" to "()I", "isHardwareAccelerated" to "()Z",
            )) add("android/view/View", m, d, "$S/ViewCompat")
            // View (API 15-16)
            add("android/view/View", "callOnClick", "()Z", "$S/ViewCompat")
            add("android/view/View", "setBackground", "(Landroid/graphics/drawable/Drawable;)V", "$S/ViewCompat")
            add("android/view/View", "getMinimumHeight", "()I", "$S/ViewCompat")
            add("android/view/View", "getMinimumWidth", "()I", "$S/ViewCompat")
            // Activity (API 11)
            add("android/app/Activity", "getActionBar", "()Landroid/app/ActionBar;", "$S/ActivityCompat")
            add("android/app/Activity", "recreate", "()V", "$S/ActivityCompat")
        }
    }
}
