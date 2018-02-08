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

import com.carrotsearch.hppc.ObjectByteMap;
import com.carrotsearch.hppc.ObjectByteOpenHashMap;
import org.teavm.interop.Structure;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;

public class Characteristics {
    private ClassReaderSource classSource;
    private ObjectByteMap<String> isStructure = new ObjectByteOpenHashMap<>();

    public Characteristics(ClassReaderSource classSource) {
        this.classSource = classSource;
    }

    public boolean isStructure(String className) {
        byte result = isStructure.getOrDefault(className, (byte) -1);
        if (result < 0) {
            if (className.equals(Structure.class.getName())) {
                result = 1;
            } else {
                ClassReader cls = classSource.get(className);
                if (cls.getParent() != null) {
                    result = isStructure(cls.getParent()) ? (byte) 1 : 0;
                } else {
                    result = 0;
                }
            }
            isStructure.put(className, result);
        }
        return result != 0;
    }
}
