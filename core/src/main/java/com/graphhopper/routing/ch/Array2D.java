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

/**
 * This is a more memory-efficient version of `ArrayList<T>[]`, i.e. this is a fixed size array with variable
 * sized sub-arrays. It is more memory efficient than an array of `ArrayList`s, because it saves the object-overhead
 * of using an ArrayList object for each sub-array.
 * todonow: update
 */
class Array2D<T> {
    private static final int GROW_FACTOR = 2;
    private final int initialSubArrayCapacity;
    private final Object[][] data;
    private final int[] sizes;
    private final int[] sizesIn;

    Array2D(int size, int initialSubArrayCapacity) {
        data = new Object[size][];
        sizes = new int[size];
        sizesIn = new int[size];
        this.initialSubArrayCapacity = initialSubArrayCapacity;
    }

    int size(int n) {
        return sizes[n];
    }

    public int sizeIn(int subArray) {
        return sizesIn[subArray];
    }

    T get(int n, int index) {
        return (T) data[n][index];
    }

    public void addIn(int n, T element) {
        if (data[n] == null) {
            data[n] = new Object[initialSubArrayCapacity];
            data[n][0] = element;
            sizesIn[n] = 1;
            sizes[n] = 1;
        } else {
            assert data[n].length != 0;
            if (sizes[n] == data[n].length)
                data[n] = Arrays.copyOf(data[n], data[n].length * GROW_FACTOR);
            data[n][sizes[n]] = data[n][sizesIn[n]];
            data[n][sizesIn[n]] = element;
            sizesIn[n]++;
            sizes[n]++;
        }
    }

    public void addOut(int n, T element) {
        if (data[n] == null) {
            data[n] = new Object[initialSubArrayCapacity];
            data[n][0] = element;
            sizes[n] = 1;
        } else {
            assert data[n].length != 0;
            if (sizes[n] == data[n].length)
                data[n] = Arrays.copyOf(data[n], data[n].length * GROW_FACTOR);
            data[n][sizes[n]] = element;
            sizes[n]++;
        }
    }

    /**
     * Removes the given element from the given sub-array. Using this method changes the order of the existing elements
     * in the sub-array unless we remove the very last element!
     */
    void remove(int n, T element) {
        for (int i = 0; i < sizesIn[n]; ++i) {
            while (sizesIn[n] > 0 && i < sizesIn[n] && data[n][i] == element) {
                data[n][i] = data[n][sizesIn[n] - 1];
                data[n][sizesIn[n] - 1] = data[n][sizes[n] - 1];
                data[n][sizes[n] - 1] = null;
                sizesIn[n]--;
                sizes[n]--;
            }
        }
        for (int i = sizesIn[n]; i < sizes[n]; ++i) {
            while (sizes[n] > sizesIn[n] && i < sizes[n] && data[n][i] == element) {
                data[n][i] = data[n][sizes[n] - 1];
                data[n][sizes[n] - 1] = null;
                sizes[n]--;
            }
        }
    }

    void clear(int n) {
        data[n] = null;
        sizes[n] = 0;
        sizesIn[n] = 0;
    }

}
