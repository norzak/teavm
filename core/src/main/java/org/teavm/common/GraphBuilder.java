/*
 *  Copyright 2013 Alexey Andreev.
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
package org.teavm.common;

import com.carrotsearch.hppc.IntOpenHashSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GraphBuilder {
    private GraphImpl builtGraph;
    private List<IntSet> addedEdges = new ArrayList<>();
    private int sz;

    public GraphBuilder() {
    }

    public GraphBuilder(int sz) {
        addedEdges.addAll(Collections.nCopies(sz, null));
        this.sz = sz;
    }

    public void clear() {
        addedEdges.clear();
        sz = 0;
    }

    public void addEdge(int from, int to) {
        if (to < 0 || from < 0) {
            throw new IllegalArgumentException();
        }
        sz = Math.max(sz, Math.max(from, to) + 1);
        builtGraph = null;
        if (addedEdges.size() == from) {
            addedEdges.add(IntOpenHashSet.from(to));
        } else if (addedEdges.size() <= from) {
            addedEdges.addAll(Collections.nCopies(from - addedEdges.size(), null));
            addedEdges.add(IntOpenHashSet.from(to));
        } else {
            IntSet set = addedEdges.get(from);
            if (set == null) {
                addedEdges.set(from, IntOpenHashSet.from(to));
            } else {
                set.add(to);
            }
        }
    }

    public void removeEdge(int from, int to) {
        if (to < 0 || from < 0) {
            throw new IllegalArgumentException();
        }
        if (from >= addedEdges.size() || to >= addedEdges.size()) {
            return;
        }
        addedEdges.get(from).removeAllOccurrences(to);
    }

    public Graph build() {
        if (builtGraph == null) {
            IntegerArray data = new IntegerArray(0);
            data.addAll(new int[sz * 2 + 1]);

            int index = 0;

            IntSet[] incomingEdges = new IntSet[sz];
            for (int i = 0; i < sz; ++i) {
                incomingEdges[i] = new IntOpenHashSet();
            }

            for (int i = 0; i < addedEdges.size(); ++i) {
                if (addedEdges.get(i) != null) {
                    for (IntCursor cursor : addedEdges.get(i)) {
                        incomingEdges[cursor.value].add(i);
                    }
                }
            }

            for (int i = 0; i < sz; ++i) {
                IntSet outgoing = i < addedEdges.size() ? addedEdges.get(i) : null;
                data.set(index++, data.size());
                int[] outgoingArray = outgoing != null ? outgoing.toArray() : new int[0];
                Arrays.sort(outgoingArray);
                data.addAll(outgoingArray);

                data.set(index++, data.size());
                int[] incomingArray = incomingEdges[i].toArray();
                Arrays.sort(incomingArray);
                data.addAll(incomingArray);
            }
            data.set(index, data.size());

            builtGraph = new GraphImpl(sz, data.getAll());
        }
        return builtGraph;
    }

    static class GraphImpl implements Graph {
        private final int size;
        private final int[] data;

        GraphImpl(int size, int[] data) {
            this.size = size;
            this.data = data;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public int[] incomingEdges(int node) {
            checkRange(node);
            int start = data[node * 2 + 1];
            int end = data[node * 2 + 2];
            return Arrays.copyOfRange(data, start, end);
        }

        @Override
        public int copyIncomingEdges(int node, int[] target) {
            checkRange(node);
            int start = data[node * 2 + 1];
            int end = data[node * 2 + 2];
            int size = Math.min(end - start, target.length);
            System.arraycopy(data, start, target, 0, size);
            return size;
        }

        @Override
        public int[] outgoingEdges(int node) {
            checkRange(node);
            int start = data[node * 2];
            int end = data[node * 2 + 1];
            return Arrays.copyOfRange(data, start, end);
        }

        @Override
        public int copyOutgoingEdges(int node, int[] target) {
            checkRange(node);
            int start = data[node * 2];
            int end = data[node * 2 + 1];
            int size = Math.min(end - start, target.length);
            System.arraycopy(data, start, target, 0, size);
            return size;
        }

        @Override
        public int incomingEdgesCount(int node) {
            checkRange(node);
            return data[node * 2 + 2] - data[node * 2 + 1];
        }

        @Override
        public int outgoingEdgesCount(int node) {
            checkRange(node);
            return data[node * 2 + 1] - data[node * 2];
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("digraph {\n");

            for (int i = 0; i < size; ++i) {
                if (outgoingEdgesCount(i) > 0) {
                    sb.append("  ").append(i).append(" -> { ");
                    int[] outgoingEdges = outgoingEdges(i);
                    sb.append(outgoingEdges[0]);
                    for (int j = 1; j < outgoingEdges.length; ++j) {
                        sb.append(", ").append(outgoingEdges[j]);
                    }
                    sb.append(" }\n");
                }
            }

            sb.append("}");

            return sb.toString();
        }

        private void checkRange(int node) {
            if (node < 0 || node >= size) {
                throw new IndexOutOfBoundsException(node + " it out of range [0; " + size + ")");
            }
        }
    }
}
