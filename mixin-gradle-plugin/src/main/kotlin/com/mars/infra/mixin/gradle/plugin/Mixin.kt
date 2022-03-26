package com.mars.infra.mixin.gradle.plugin

import com.android.build.api.transform.JarInput
import com.android.build.api.transform.TransformInvocation
import com.mars.infra.mixin.gradle.plugin.visitor.MixinCollectClassVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.io.InputStream
import java.lang.reflect.TypeVariable
import java.util.zip.ZipFile

/**
 * Created by Mars on 2022/3/15
 */
object Mixin {

    const val OPCODE_PROCEED = -0x001

    @Deprecated("存在问题，无法判断是否hook相同的方法")
    val mixinDataList by lazy {
        arrayListOf<MixinData>()
    }

    val mixinDataMap by lazy {
        hashMapOf<String, MixinData>()
    }

    val mixinHookClasses by lazy {
        mutableSetOf<String>()
    }

    fun clear() {
        mixinDataList.clear()
        mixinDataMap.clear()
    }


    /**
     * 在进行transform之前，需要先获取到Proxy注解标识的内容
     * TODO 将Transform拆分，拆成三个步骤，beforeTransform、transform以及afterTransform
     */
    fun collectHookInfo(transformInvocation: TransformInvocation) {
        clear()
        transformInvocation.inputs.forEach {
            it.directoryInputs.forEach { directoryInput ->
                forEachDir(directoryInput.file)
            }
            it.jarInputs.forEach { jarInput ->
                forEachJar(jarInput)
            }
        }
    }

    private fun forEachJar(jarInput: JarInput) {
        ZipFile(jarInput.file).use { originJar ->
            originJar.entries().iterator().forEach { zipEntry ->
                if (!zipEntry.isDirectory && zipEntry.name.endsWith(".class")) {
                    collectInternalV2(originJar.getInputStream(zipEntry))
                }
            }
        }
    }

    private fun forEachDir(input: File) {
        input.listFiles()?.forEach {
            if (it.isDirectory) {
                forEachDir(it)
            } else if (it.isFile) {
                if (it.absolutePath.endsWith(".class")) {
                    collectInternalV2(it.inputStream())
                }
            }
        }
    }

    private fun collectInternalV2(inputStream: InputStream) {
        inputStream.use {
            val classReader = ClassReader(it.readBytes())
            val classNode = ClassNode()
            classReader.accept(classNode, ClassReader.EXPAND_FRAMES)

            // Mixin注解是AnnotationRetention.BINARY，因此使用invisibleAnnotations
            var mixinClass = false
            classNode.invisibleAnnotations?.forEach { node ->
                if (node.desc == ANNOTATION_MIXIN) {
                    mixinClass = true
                    mixinHookClasses.add(classNode.name)
                }
            }
            mixinClass.yes {
                classNode.handleNode()
            }
        }
    }

    private fun collectInternal(inputStream: InputStream) {
        inputStream.use {
            val cr = ClassReader(it.readBytes())
            val cv = MixinCollectClassVisitor()
            cr.accept(cv, ClassReader.EXPAND_FRAMES)
        }
    }

    private fun collectInternal(file: File) {
        file.inputStream().use {
            val cr = ClassReader(it.readBytes())
            val cv = MixinCollectClassVisitor()
            cr.accept(cv, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    }
}

private fun ClassNode.handleNode() {
    methods.asIterable().filter {
        it.access and Opcodes.ACC_ABSTRACT == 0
                && it.access and Opcodes.ACC_NATIVE == 0
                && it.name != "<init>"
    }.forEach { methodNode ->
        val mixinData = MixinData(name, methodNode.name, methodNode.desc, methodNode = methodNode)

        methodNode.invisibleAnnotations?.forEach { annotationNode ->
            if (annotationNode.desc == ANNOTATION_PROXY) {
                // TODO 粗糙实现，默认是严格按照顺序的
                var index = 0
                val owner = annotationNode.values[++index] as String
                index++
                val name = annotationNode.values[++index] as String
                index++
                val isStatic = annotationNode.values[++index] as Boolean
                val argumentTypes = Type.getArgumentTypes(methodNode.desc)
                val returnType = Type.getReturnType(methodNode.desc)

                var realDescriptor = "("
                for (i in argumentTypes.indices) {
                    if (i == 0 && !isStatic) {
                        continue
                    }
                    realDescriptor += argumentTypes[i]
                }
                realDescriptor += ")"
                realDescriptor += returnType.descriptor

                mixinData.proxyData = ProxyData(owner, name, realDescriptor)

                Mixin.mixinDataList.add(mixinData)

                checkHookMethodExist(owner, name, success = {
                    Mixin.mixinDataMap[it] = mixinData
                }, error = {
                    throw Exception("${owner}.${name} 方法已经被hook了，不能重复hook")
                })
            }
        }

        methodNode.instructions.iterator().forEach {
            if (it is MethodInsnNode
                && it.owner == PROXY_INSN_CHAIN_NAME
                && it.name == "proceed"
            ) {
                val returnType = Type.getReturnType(methodNode.desc)
                /**
                 * ProxyInsnChain.proceed是有返回值的，
                 * 如果目标方法是不带返回值的，则需要移除hook方法中的POP指令
                 */
                if (returnType == Type.VOID_TYPE) {
                    if (it.next.opcode == Opcodes.POP) {
                        methodNode.instructions.remove(it.next)
                    }
                }
            }
        }
    }
}