package com.powdead.dexorcist.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

// Strips System.loadLibrary("c++_shared") calls.
abstract class DexorcistNativeShimTransform :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(classContext: ClassContext, next: ClassVisitor): ClassVisitor =
        StripLoadLibraryVisitor(next)

    override fun isInstrumentable(classData: ClassData): Boolean = true
}

private class StripLoadLibraryVisitor(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
    override fun visitMethod(access: Int, name: String?, desc: String?, sig: String?, exc: Array<out String>?): MethodVisitor =
        StripLoadLibraryMethodVisitor(super.visitMethod(access, name, desc, sig, exc))
}

private class StripLoadLibraryMethodVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {
    private var pending = false

    private fun flush() {
        if (pending) { super.visitLdcInsn("c++_shared"); pending = false }
    }

    override fun visitLdcInsn(value: Any?) {
        flush()
        if (value == "c++_shared") { pending = true; return }
        super.visitLdcInsn(value)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        if (pending && opcode == Opcodes.INVOKESTATIC &&
            owner == "java/lang/System" && name == "loadLibrary") {
            pending = false; return
        }
        flush(); super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    override fun visitInsn(opcode: Int) { flush(); super.visitInsn(opcode) }
    override fun visitIntInsn(opcode: Int, operand: Int) { flush(); super.visitIntInsn(opcode, operand) }
    override fun visitVarInsn(opcode: Int, v: Int) { flush(); super.visitVarInsn(opcode, v) }
    override fun visitTypeInsn(opcode: Int, type: String) { flush(); super.visitTypeInsn(opcode, type) }
    override fun visitFieldInsn(opcode: Int, o: String, n: String, d: String) { flush(); super.visitFieldInsn(opcode, o, n, d) }
    override fun visitJumpInsn(opcode: Int, label: Label) { flush(); super.visitJumpInsn(opcode, label) }
    override fun visitEnd() { flush(); super.visitEnd() }
}
