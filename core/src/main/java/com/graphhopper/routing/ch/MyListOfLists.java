/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.routing.ch;

import java.util.Arrays;

public class MyListOfLists {
    private static final int GROW_FACTOR = 2;
    private final int initialCapacityPerNode;
    private final CHPreparationGraph.PrepareEdge[][] data;
    private final int[] sizes;

    public MyListOfLists(int size, int initialCapacityPerNode) {
        data = new CHPreparationGraph.PrepareEdge[size][];
        sizes = new int[size];
        this.initialCapacityPerNode = initialCapacityPerNode;
    }

    public int getEdges(int node) {
        return sizes[node];
    }

    public void add(int node, CHPreparationGraph.PrepareEdge edge) {
        if (data[node] == null) {
            data[node] = new CHPreparationGraph.PrepareEdge[initialCapacityPerNode];
            data[node][0] = edge;
            sizes[node] = 1;
        } else {
            assert data[node].length != 0;
            if (sizes[node] == data[node].length)
                data[node] = Arrays.copyOf(data[node], data[node].length * GROW_FACTOR);
            data[node][sizes[node]] = edge;
            sizes[node]++;
        }
    }

    public CHPreparationGraph.PrepareEdge get(int node, int index) {
        return data[node][index];
    }

    public void remove(int node, CHPreparationGraph.PrepareEdge edge) {
        for (int i = 0; i < sizes[node]; i++) {
            if (data[node][i] == edge) {
                data[node][i] = data[node][sizes[node] - 1];
                data[node][sizes[node] - 1] = null;
                sizes[node]--;
            }
        }
    }

    public void clear(int node) {
        data[node] = null;
        sizes[node] = 0;
    }

}
