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

import org.teavm.ast.RegularMethodNode;
import org.teavm.ast.VariableNode;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodReference;

public class CodeGenerator {
    private GenerationContext context;
    private CodeWriter writer;
    private NameProvider names;

    public CodeGenerator(GenerationContext context, CodeWriter writer) {
        this.context = context;
        this.writer = writer;
        this.names = context.getNames();
    }

    public void generateMethod(RegularMethodNode methodNode) {
        generateMethodSignature(methodNode.getReference(),
                methodNode.getModifiers().contains(ElementModifier.STATIC));

        writer.print(" {").indent().println();

        generateLocals(methodNode);

        CodeGenerationVisitor visitor = new CodeGenerationVisitor(context, writer);
        methodNode.getBody().acceptVisitor(visitor);

        writer.outdent().println("}");
    }

    public void generateMethodSignature(MethodReference methodRef, boolean isStatic) {
        writer.printType(methodRef.getReturnType()).print(" ").print(names.forMethod(methodRef)).print("(");

        if (methodRef.parameterCount() > 0 || !isStatic) {
            int firstParam = 1;
            if (!isStatic) {
                writer.print("JavaObject *_this_");
            } else {
                writer.printType(methodRef.parameterType(1)).print(" local_1");
                firstParam++;
            }
            for (int i = firstParam; i <= methodRef.parameterCount(); ++i) {
                writer.print(", ").printType(methodRef.parameterType(i)).print(" ")
                        .print("local_").print(String.valueOf(i));
            }
        }

        writer.print(")");
    }

    private void generateLocals(RegularMethodNode methodNode) {
        for (int i = methodNode.getReference().parameterCount() + 1; i < methodNode.getVariables().size(); ++i) {
            VariableNode variableNode = methodNode.getVariables().get(i);
            writer.printType(variableNode.getType()).print(" local_").print(String.valueOf(i)).println(";");
        }

        TemporaryVariableEstimator temporaryEstimator = new TemporaryVariableEstimator();
        methodNode.getBody().acceptVisitor(temporaryEstimator);
        for (int i = 0; i < temporaryEstimator.getMaxReceiverIndex(); ++i) {
            writer.print("JavaObject *recv_").print(String.valueOf(i)).println(";");
        }
    }
}
