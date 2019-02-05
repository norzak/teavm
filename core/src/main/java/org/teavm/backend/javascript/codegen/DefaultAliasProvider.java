/*
 *  Copyright 2016 Alexey Andreev.
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
package org.teavm.backend.javascript.codegen;

import com.carrotsearch.hppc.ObjectIntHashMap;
import com.carrotsearch.hppc.ObjectIntMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.teavm.model.FieldReference;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;

public class DefaultAliasProvider implements AliasProvider {
    private final Map<String, String> classAliases = new HashMap<>();
    private final Set<String> knownAliases = new HashSet<>(200, 0.5f);
    private final ObjectIntMap<String> knowAliasesCounter = new ObjectIntHashMap<>();
    private final Set<String> knownVirtualAliases = new HashSet<>(200, 0.5f);
    private final ObjectIntMap<String> knowVirtualAliasesCounter = new ObjectIntHashMap<>();

    @Override
    public String getClassAlias(String cls) {
        return classAliases.computeIfAbsent(cls, key -> makeUnique(knownAliases, knowAliasesCounter,
                suggestAliasForClass(key)));
    }

    private static String suggestAliasForClass(String cls) {
        StringBuilder alias = new StringBuilder();
        int lastIndex = 0;
        while (true) {
            int index = cls.indexOf('.', lastIndex);
            if (index == -1) {
                if (lastIndex > 0) {
                    alias.append("_");
                }
                alias.append(cls.substring(lastIndex));
                break;
            } else {
                if (index > lastIndex) {
                    alias.append(cls.charAt(lastIndex));
                }
                lastIndex = index + 1;
            }
        }

        for (int i = 1; i < alias.length(); ++i) {
            char c = alias.charAt(i);
            if (!Character.isJavaIdentifierPart(c)) {
                alias.setCharAt(i, '_');
            }
        }

        if (!Character.isJavaIdentifierStart(alias.charAt(0))) {
            alias.setCharAt(0, '_');
        }

        return alias.toString();
    }

    @Override
    public String getMethodAlias(MethodDescriptor method) {
        String alias = method.getName();
        switch (alias) {
            case "<init>":
                alias = "$_init_";
                break;
            case "<clinit>":
                alias = "$_clinit_";
                break;
            default:
                alias = "$" + alias;
                break;
        }
        return makeUnique(knownVirtualAliases, knowVirtualAliasesCounter, alias);
    }

    @Override
    public String getStaticMethodAlias(MethodReference method) {
        String alias = method.getDescriptor().getName();
        switch (alias) {
            case "<init>":
                alias = "_init_";
                break;
            case "<clinit>":
                alias = "_clinit_";
                break;
        }

        return makeUnique(knownAliases, knowAliasesCounter, getClassAlias(method.getClassName()) + "_" + alias);
    }

    @Override
    public String getFieldAlias(FieldReference field) {
        return makeUnique(knownVirtualAliases, knowVirtualAliasesCounter, "$" + field.getFieldName());
    }

    @Override
    public String getStaticFieldAlias(FieldReference field) {
        return makeUnique(knownAliases, knowAliasesCounter,
                getClassAlias(field.getClassName()) + "_" + field.getFieldName());
    }

    @Override
    public String getFunctionAlias(String name) {
        return name;
    }

    @Override
    public String getClassInitAlias(String className) {
        return makeUnique(knownAliases, knowAliasesCounter, suggestAliasForClass(className) + "_$callClinit");
    }

    private String makeUnique(Set<String> knowAliases, ObjectIntMap<String> indexMap, String alias) {
        String uniqueAlias = alias;
        int index = indexMap.get(alias);
        if (index > 0) {
            uniqueAlias = alias + index++;
        }
        while (!knowAliases.add(uniqueAlias)) {
            uniqueAlias = alias + index++;
        }
        indexMap.put(alias, index);
        return uniqueAlias;
    }
}
