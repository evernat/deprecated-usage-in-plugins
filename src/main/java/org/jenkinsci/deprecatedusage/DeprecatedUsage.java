package org.jenkinsci.deprecatedusage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class DeprecatedUsage {
    // python-wrapper has wrappers for all extension points and descriptors,
    // they are just wrappers and not real usage
    public static final Set<String> IGNORED_PLUGINS = new HashSet<>(
            Arrays.asList("python-wrapper.hpi"));

    private final String pluginName;
    private final String pluginKey;
    private final DeprecatedApi deprecatedApi;

    private final Set<String> classes = new LinkedHashSet<>();
    private final Set<String> methods = new LinkedHashSet<>();
    private final Set<String> fields = new LinkedHashSet<>();
    private final ClassVisitor indexerClassVisitor = new IndexerClassVisitor();
    private final ClassVisitor classVisitor = new CallersClassVisitor();
    private final Map<String, List<String>> superClassAndInterfacesByClass = new HashMap<>();

    public DeprecatedUsage(String pluginName, String pluginVersion, DeprecatedApi deprecatedApi) {
        super();
        this.pluginName = pluginName;
        this.pluginKey = pluginName + '-' + pluginVersion;
        this.deprecatedApi = deprecatedApi;
    }

    public void analyze(File pluginFile) throws IOException {
        if (IGNORED_PLUGINS.contains(pluginFile.getName())) {
            return;
        }
        analyzeWithClassVisitor(pluginFile, indexerClassVisitor);
        analyzeWithClassVisitor(pluginFile, classVisitor);
    }

    public void analyzeWithClassVisitor(File pluginFile, ClassVisitor aClassVisitor)
            throws IOException {
        // do not analyse WEB-INF/lib/*jar in plugins,
        // because it would cause too many false positives in dependent libraries
        // final WarReader warReader = new WarReader(pluginFile);
        // try {
        // String fileName = warReader.nextClass();
        // while (fileName != null) {
        // try {
        // analyze(warReader.getInputStream(), aClassVisitor);
        // } catch (final Exception e) {
        // // ignore ArrayIndexOutOfBoundsException: 48188 for
        // // com/ibm/icu/impl/data/LocaleElements_zh__PINYIN.class
        // continue;
        // }
        // fileName = warReader.nextClass();
        // }
        // } finally {
        // warReader.close();
        // }

        final InputStream input = new FileInputStream(pluginFile);
        final JarReader jarReader = new JarReader(input);
        try {
            String fileName = jarReader.nextClass();
            while (fileName != null) {
                analyze(jarReader.getInputStream(), aClassVisitor);
                fileName = jarReader.nextClass();
            }
        } finally {
            jarReader.close();
            input.close();
        }
    }

    private void analyze(InputStream input, ClassVisitor aClassVisitor) throws IOException {
        final ClassReader classReader = new ClassReader(input);
        classReader.accept(aClassVisitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    public String getPluginName() {
        return pluginName;
    }

    public String getPluginKey() {
        return pluginKey;
    }

    public Set<String> getClasses() {
        return classes;
    }

    public Set<String> getMethods() {
        return methods;
    }

    public Set<String> getFields() {
        return fields;
    }

    public boolean hasDeprecatedUsage() {
        return !classes.isEmpty() || !methods.isEmpty() || !fields.isEmpty();
    }

    void methodCalled(String className, String name, String desc) {
        // Calls to java and javax are ignored first
        if (!isJavaClass(className)) {
            if (deprecatedApi.getClasses().contains(className)) {
                classes.add(className);
            } else {
                final String method = DeprecatedApi.getMethodKey(className, name, desc);
                if (deprecatedApi.getMethods().contains(method)) {
                    methods.add(method);
                }
                final List<String> superClassAndInterfaces = superClassAndInterfacesByClass
                        .get(className);
                if (superClassAndInterfaces != null) {
                    for (final String superClassOrInterface : superClassAndInterfaces) {
                        methodCalled(superClassOrInterface, name, desc);
                    }
                }
            }
        }
    }

    void fieldCalled(String className, String name, String desc) {
        // Calls to java and javax are ignored first
        if (!isJavaClass(className)) {
            if (deprecatedApi.getClasses().contains(className)) {
                classes.add(className);
            } else {
                final String field = DeprecatedApi.getFieldKey(className, name, desc);
                if (deprecatedApi.getFields().contains(field)) {
                    fields.add(field);
                }
                final List<String> superClassAndInterfaces = superClassAndInterfacesByClass
                        .get(className);
                if (superClassAndInterfaces != null) {
                    for (final String superClassOrInterface : superClassAndInterfaces) {
                        fieldCalled(superClassOrInterface, name, desc);
                    }
                }
            }
        }
    }

    private static boolean isJavaClass(String asmClassName) {
        // if starts with java/ or javax/, then it's a class of core java
        return asmClassName.startsWith("java/") || asmClassName.startsWith("javax/");
    }

    /**
     * Implements ASM ClassVisitor.
     */
    private class IndexerClassVisitor extends ClassVisitor {
        IndexerClassVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            // log(name + " extends " + superName + " {");
            final List<String> superClassAndInterfaces = new ArrayList<>();
            if (!isJavaClass(superName)) {
                superClassAndInterfaces.add(superName);
            }
            if (interfaces != null) {
                for (final String anInterface : interfaces) {
                    if (!isJavaClass(anInterface)) {
                        superClassAndInterfaces.add(anInterface);
                    }
                }
            }
            if (!superClassAndInterfaces.isEmpty()) {
                superClassAndInterfacesByClass.put(name, superClassAndInterfaces);
            }
        }
    }

    /**
     * Implements ASM ClassVisitor.
     */
    private class CallersClassVisitor extends ClassVisitor {
        CallersClassVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                String[] exceptions) {
            // asm javadoc says to return a new instance each time
            return new CallersMethodVisitor();
        }
    }

    /**
     * Implements ASM MethodVisitor.
     */
    private class CallersMethodVisitor extends MethodVisitor {
        CallersMethodVisitor() {
            super(Opcodes.ASM5);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc) {
            // log("\t" + owner + " " + name + " " + desc);
            methodCalled(owner, name, desc);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            // log("\t" + owner + " " + name + " " + desc);
            methodCalled(owner, name, desc);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            // log("\t" + owner + " " + name + " " + desc);
            fieldCalled(owner, name, desc);
        }
    }
}
