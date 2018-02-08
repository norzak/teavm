/*
 *  Copyright 2018 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.c;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.teavm.ast.decompilation.Decompiler;
import org.teavm.backend.c.generate.Characteristics;
import org.teavm.backend.c.generate.ClassGenerator;
import org.teavm.backend.c.generate.CodeWriter;
import org.teavm.backend.c.generate.GenerationContext;
import org.teavm.backend.c.generate.NameProvider;
import org.teavm.backend.c.generate.StringPool;
import org.teavm.backend.c.intrinsic.Intrinsic;
import org.teavm.backend.c.intrinsic.ShadowStackIntrinsic;
import org.teavm.dependency.ClassDependency;
import org.teavm.dependency.DependencyAnalyzer;
import org.teavm.dependency.DependencyListener;
import org.teavm.interop.Address;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.FieldReader;
import org.teavm.model.Instruction;
import org.teavm.model.ListableClassHolderSource;
import org.teavm.model.ListableClassReaderSource;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.classes.TagRegistry;
import org.teavm.model.classes.VirtualTableProvider;
import org.teavm.model.instructions.CloneArrayInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.lowlevel.ClassInitializerEliminator;
import org.teavm.model.lowlevel.ClassInitializerTransformer;
import org.teavm.model.lowlevel.ShadowStackTransformer;
import org.teavm.model.transformation.ClassInitializerInsertionTransformer;
import org.teavm.model.transformation.ClassPatch;
import org.teavm.runtime.Allocator;
import org.teavm.runtime.ExceptionHandling;
import org.teavm.runtime.RuntimeArray;
import org.teavm.runtime.RuntimeClass;
import org.teavm.runtime.RuntimeJavaObject;
import org.teavm.runtime.RuntimeObject;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.TeaVMTarget;
import org.teavm.vm.TeaVMTargetController;
import org.teavm.vm.spi.TeaVMHostExtension;

public class CTarget implements TeaVMTarget {
    private TeaVMTargetController controller;
    private ClassInitializerInsertionTransformer clinitInsertionTransformer;
    private ClassInitializerEliminator classInitializerEliminator;
    private ClassInitializerTransformer classInitializerTransformer;
    private ShadowStackTransformer shadowStackTransformer;

    @Override
    public List<ClassHolderTransformer> getTransformers() {
        List<ClassHolderTransformer> transformers = new ArrayList<>();
        transformers.add(new ClassPatch());
        return transformers;
    }

    @Override
    public List<DependencyListener> getDependencyListeners() {
        return Collections.emptyList();
    }

    @Override
    public void setController(TeaVMTargetController controller) {
        this.controller = controller;
        classInitializerEliminator = new ClassInitializerEliminator(controller.getUnprocessedClassSource());
        classInitializerTransformer = new ClassInitializerTransformer();
        shadowStackTransformer = new ShadowStackTransformer(controller.getUnprocessedClassSource());
        clinitInsertionTransformer = new ClassInitializerInsertionTransformer(controller.getUnprocessedClassSource());
    }

    @Override
    public List<TeaVMHostExtension> getHostExtensions() {
        return Collections.emptyList();
    }

    @Override
    public boolean requiresRegisterAllocation() {
        return true;
    }

    @Override
    public void contributeDependencies(DependencyAnalyzer dependencyAnalyzer) {
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocate",
                RuntimeClass.class, Address.class), null).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocateArray",
                RuntimeClass.class, int.class, Address.class), null).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocateMultiArray",
                RuntimeClass.class, Address.class, int.class, RuntimeArray.class), null).use();

        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "<clinit>", void.class), null).use();

        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "throwException",
                Throwable.class, void.class), null).use();

        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "catchException",
                Throwable.class), null).use();

        ClassDependency runtimeClassDep = dependencyAnalyzer.linkClass(RuntimeClass.class.getName(), null);
        ClassDependency runtimeObjectDep = dependencyAnalyzer.linkClass(RuntimeObject.class.getName(), null);
        ClassDependency runtimeJavaObjectDep = dependencyAnalyzer.linkClass(RuntimeJavaObject.class.getName(), null);
        ClassDependency runtimeArrayDep = dependencyAnalyzer.linkClass(RuntimeArray.class.getName(), null);
        for (ClassDependency classDep : Arrays.asList(runtimeClassDep, runtimeObjectDep, runtimeJavaObjectDep,
                runtimeArrayDep)) {
            for (FieldReader field : classDep.getClassReader().getFields()) {
                dependencyAnalyzer.linkField(field.getReference(), null);
            }
        }
    }

    @Override
    public void afterOptimizations(Program program, MethodReader method, ListableClassReaderSource classSource) {
        clinitInsertionTransformer.apply(method, program);
        classInitializerEliminator.apply(program);
        classInitializerTransformer.transform(program);
        shadowStackTransformer.apply(program, method);
    }

    @Override
    public void emit(ListableClassHolderSource classes, BuildTarget buildTarget, String outputName) throws IOException {
        VirtualTableProvider vtableProvider = createVirtualTableProvider(classes);
        TagRegistry tagRegistry = new TagRegistry(classes);
        StringPool stringPool = new StringPool();

        Decompiler decompiler = new Decompiler(classes, controller.getClassLoader(), new HashSet<>(),
                new HashSet<>(), false);
        Characteristics characteristics = new Characteristics(classes);

        NameProvider nameProvider = new NameProvider();

        List<Intrinsic> intrinsics = new ArrayList<>();
        intrinsics.add(new ShadowStackIntrinsic());
        GenerationContext context = new GenerationContext(vtableProvider, characteristics, stringPool, nameProvider,
                intrinsics);

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                buildTarget.createResource(outputName), "UTF-8"))) {
            CodeWriter codeWriter = new CodeWriter(writer);
            ClassGenerator classGenerator = new ClassGenerator(context, tagRegistry, decompiler, codeWriter);

            generateClasses(classes, classGenerator);
        }
    }

    private void generateClasses(ListableClassHolderSource classes, ClassGenerator classGenerator) {
        for (String className : classes.getClassNames()) {
            ClassHolder cls = classes.get(className);
            classGenerator.generateForwardDeclarations(cls);
        }

        for (String className : classes.getClassNames()) {
            ClassHolder cls = classes.get(className);
            classGenerator.generateStructures(cls);
        }

        for (String className : classes.getClassNames()) {
            ClassHolder cls = classes.get(className);
            classGenerator.generateClass(cls);
        }
    }

    private VirtualTableProvider createVirtualTableProvider(ListableClassHolderSource classes) {
        Set<MethodReference> virtualMethods = new HashSet<>();

        for (String className : classes.getClassNames()) {
            ClassHolder cls = classes.get(className);
            for (MethodHolder method : cls.getMethods()) {
                Program program = method.getProgram();
                if (program == null) {
                    continue;
                }
                for (int i = 0; i < program.basicBlockCount(); ++i) {
                    BasicBlock block = program.basicBlockAt(i);
                    for (Instruction insn : block) {
                        if (insn instanceof InvokeInstruction) {
                            InvokeInstruction invoke = (InvokeInstruction) insn;
                            if (invoke.getType() == InvocationType.VIRTUAL) {
                                virtualMethods.add(invoke.getMethod());
                            }
                        } else if (insn instanceof CloneArrayInstruction) {
                            virtualMethods.add(new MethodReference(Object.class, "clone", Object.class));
                        }
                    }
                }
            }
        }

        return new VirtualTableProvider(classes, virtualMethods);
    }
}
