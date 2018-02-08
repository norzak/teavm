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
package org.teavm.backend.c.generate;

import java.util.ArrayList;
import java.util.List;
import org.teavm.ast.RegularMethodNode;
import org.teavm.ast.decompilation.Decompiler;
import org.teavm.interop.Address;
import org.teavm.interop.Structure;
import org.teavm.model.ClassHolder;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldHolder;
import org.teavm.model.FieldReference;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.model.classes.TagRegistry;
import org.teavm.model.classes.VirtualTable;
import org.teavm.model.classes.VirtualTableEntry;
import org.teavm.runtime.RuntimeClass;

public class ClassGenerator {
    private GenerationContext context;
    private Decompiler decompiler;
    private TagRegistry tagRegistry;
    private CodeWriter writer;
    private CodeGenerator codeGenerator;

    public ClassGenerator(GenerationContext context, TagRegistry tagRegistry, Decompiler decompiler,
            CodeWriter writer) {
        this.context = context;
        this.tagRegistry = tagRegistry;
        this.decompiler = decompiler;
        this.writer = writer;
        codeGenerator = new CodeGenerator(context, writer);
    }

    public void generateForwardDeclarations(ClassHolder cls) {
        generateForwardClassStructure(cls);

        for (MethodHolder method : cls.getMethods()) {
            if (method.hasModifier(ElementModifier.NATIVE) || method.hasModifier(ElementModifier.ABSTRACT)) {
                continue;
            }

            codeGenerator.generateMethodSignature(method.getReference(), method.hasModifier(ElementModifier.STATIC));
            writer.println(";");
        }
    }

    private void generateForwardClassStructure(ClassHolder cls) {
        if (!needsData(cls) || isSystemClass(cls)) {
            return;
        }

        writer.print("struct ").print(context.getNames().forClass(cls.getName())).println(";");
    }

    public void generateStructures(ClassHolder cls) {
        generateClassStructure(cls);
        generateVirtualTableStructure(cls);
        generateVirtualTable(cls);
    }

    private void generateClassStructure(ClassHolder cls) {
        if (!needsData(cls) || isSystemClass(cls)) {
            return;
        }

        String name = context.getNames().forClass(cls.getName());

        writer.print("typedef struct ").print(name).println(" {").indent();

        if (cls.getParent() != null) {
            writer.print("struct ").print(context.getNames().forClass(cls.getParent())).println(" parent;");
        }

        for (FieldHolder field : cls.getFields()) {
            if (field.hasModifier(ElementModifier.STATIC)) {
                continue;
            }
            String fieldName = context.getNames().forMemberField(field.getReference());
            writer.printType(field.getType()).print(" ").print(fieldName).println(";");
        }

        writer.outdent().print("} ").print(name).println(";");
    }

    private void generateVirtualTableStructure(ClassHolder cls) {
        if (!needsVirtualTable(cls)) {
            return;
        }

        String name = context.getNames().forClassClass(cls.getName());

        writer.print("typedef struct ").print(name).println(" {").indent();
        writer.println("JavaClass parent;");

        VirtualTable virtualTable = context.getVirtualTableProvider().lookup(cls.getName());
        for (VirtualTableEntry entry : virtualTable.getEntries().values()) {
            String methodName = context.getNames().forVirtualMethod(
                    new MethodReference(cls.getName(), entry.getMethod()));
            writer.print("void *").print(methodName).println(";");
        }

        writer.outdent().print("} ").print(name).println(";");
    }

    private void generateVirtualTable(ClassHolder cls) {
        if (!needsVirtualTable(cls)) {
            return;
        }

        String structName = context.getNames().forClassClass(cls.getName());
        String name = context.getNames().forClassInstance(ValueType.object(cls.getName()));

        writer.print(structName).print(" ").print(name).println(" = {").indent();

        writer.println(".parent = {").indent();
        generateRuntimeClassInitializer(cls);
        writer.outdent().println("},");

        VirtualTable virtualTable = context.getVirtualTableProvider().lookup(cls.getName());
        List<VirtualTableEntry> entries = new ArrayList<>(virtualTable.getEntries().values());
        for (int i = 0; i < entries.size(); ++i) {
            VirtualTableEntry entry = entries.get(i);
            String methodName = context.getNames().forVirtualMethod(
                    new MethodReference(cls.getName(), entry.getMethod()));
            String implName = entry.getImplementor() != null
                    ? context.getNames().forMethod(entry.getImplementor())
                    : "NULL";
            writer.print(".").print(methodName).print(" = ").print(implName);
            if (i < entries.size() - 1) {
                writer.print(",");
            }
            writer.println();
        }

        writer.outdent().println("}");
    }

    private void generateRuntimeClassInitializer(ClassHolder cls) {
        String structName = context.getNames().forClass(cls.getName());
        int tag = tagRegistry.getRanges(cls.getName()).get(0).lower;
        int nameRef = context.getStringPool().getStringIndex(cls.getName());
        String parent = cls.getParent() != null
                ? context.getNames().forClassInstance(ValueType.object(cls.getParent()))
                : "NULL";

        writer.println(".parent = {},");
        writer.print(".").print(classFieldName("size")).print(" = sizeof(").print(structName).println("),");
        writer.print(".").print(classFieldName("flags")).println(" = 0,");
        writer.print(".").print(classFieldName("tag")).print(" = ").print(String.valueOf(tag)).println(",");
        writer.print(".").print(classFieldName("canary")).println(" = 0,");
        writer.print(".").print(classFieldName("name")).println(" = stringPool[" + nameRef + ",");
        writer.print(".").print(classFieldName("arrayType")).println(" = NULL,");
        writer.print(".").print(classFieldName("isSupertypeOf")).println(" = NULL,");
        writer.print(".").print(classFieldName("parent")).println(" = " + parent + ",");
        writer.print(".").print(classFieldName("enumValues")).println(" = NULL,");
        writer.print(".").print(classFieldName("layout")).println(" = NULL");
    }

    private String classFieldName(String field) {
        return context.getNames().forMemberField(new FieldReference(RuntimeClass.class.getName(), field));
    }

    private boolean needsData(ClassHolder cls) {
        if (cls.hasModifier(ElementModifier.INTERFACE)) {
            return false;
        }
        return !cls.getName().equals(Structure.class.getName())
                && !cls.getName().equals(Address.class.getName());
    }

    private boolean isSystemClass(ClassHolder cls) {
        switch (cls.getName()) {
            case "java.lang.Object":
            case "java.lang.Class":
            case "java.lang.String":
                return true;
            default:
                return false;
        }
    }

    private boolean needsVirtualTable(ClassHolder cls) {
        if (cls.hasModifier(ElementModifier.ABSTRACT)) {
            return false;
        }
        if (!needsData(cls) || context.getVirtualTableProvider().lookup(cls.getName()) == null) {
            return false;
        }
        return !context.getCharacteristics().isStructure(cls.getName());
    }

    public void generateClass(ClassHolder cls) {
        for (MethodHolder method : cls.getMethods()) {
            if (method.hasModifier(ElementModifier.NATIVE) || method.hasModifier(ElementModifier.ABSTRACT)) {
                continue;
            }

            RegularMethodNode methodNode = decompiler.decompileRegular(method);
            codeGenerator.generateMethod(methodNode);
        }
    }
}
