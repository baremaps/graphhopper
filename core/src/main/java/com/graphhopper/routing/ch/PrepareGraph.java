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

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;

public class PrepareGraph {
    private final int nodes;
    private int edges;
    private final List<List<PrepareEdge>> outArcs;
    private final List<List<PrepareEdge>> inArcs;
    private final List<List<Edge>> outEdges;
    private final List<List<Edge>> inEdges;

    public PrepareGraph(int nodes) {
        this.nodes = nodes;
        outArcs = IntStream.range(0, nodes).<List<PrepareEdge>>mapToObj(i -> new ArrayList<>(3)).collect(toList());
        inArcs = IntStream.range(0, nodes).<List<PrepareEdge>>mapToObj(i -> new ArrayList<>(3)).collect(toList());
        outEdges = IntStream.range(0, nodes).<List<Edge>>mapToObj(i -> new ArrayList<>(3)).collect(toList());
        inEdges = IntStream.range(0, nodes).<List<Edge>>mapToObj(i -> new ArrayList<>(3)).collect(toList());
    }

    public void initFromGraph(Graph graph, Weighting weighting) {
        if (graph.getNodes() != nodes)
            throw new IllegalArgumentException("Cannot initialize from given graph. The number of nodes does not match: " +
                    graph.getNodes() + " vs. " + nodes);
        edges = graph.getEdges();
        BooleanEncodedValue accessEnc = weighting.getFlagEncoder().getAccessEnc();
        AllEdgesIterator iter = graph.getAllEdges();
        while (iter.next()) {
            if (iter.get(accessEnc)) {
                double weight = weighting.calcEdgeWeight(iter, false);
                if (Double.isFinite(weight)) {
                    addEdge(iter.getBaseNode(), iter.getAdjNode(), iter.getEdge(), weight);
                }
            }
            if (iter.getReverse(accessEnc)) {
                double weight = weighting.calcEdgeWeight(iter, true);
                if (Double.isFinite(weight)) {
                    addEdge(iter.getAdjNode(), iter.getBaseNode(), iter.getEdge(), weight);
                }
            }
        }
        // todonow:
//        outEdges.forEach(Collections::reverse);
//        inEdges.forEach(Collections::reverse);
    }

    public int getNodes() {
        return nodes;
    }

    public int getOriginalEdges() {
        return edges;
    }

    public int getDegree(int node) {
        return outArcs.get(node).size() + inArcs.get(node).size();
    }

    public void addEdge(int from, int to, int prepareEdge, double weight) {
        PrepareEdge prepareEdgeObj = PrepareEdge.edge(prepareEdge, from, to, weight);
        outArcs.get(from).add(prepareEdgeObj);
        inArcs.get(to).add(prepareEdgeObj);

        Edge edgeObj = new Edge(prepareEdge, from, to);
        outEdges.get(from).add(edgeObj);
        inEdges.get(to).add(edgeObj);
    }

    public int addShortcut(int from, int to, int origEdgeKeyFirst, int origEdgeKeyLast, int skipped1, int skipped2, double weight, int origEdgeCount) {
        PrepareEdge prepareEdge = PrepareEdge.shortcut(edges, from, to, origEdgeKeyFirst, origEdgeKeyLast, skipped1, skipped2, weight, origEdgeCount);
        outArcs.get(from).add(prepareEdge);
        inArcs.get(to).add(prepareEdge);
        return edges++;
    }

    public PrepareGraphExplorer createOutEdgeExplorer() {
        return new PrepareGraphExplorerImpl(outArcs, false);
    }

    public PrepareGraphExplorer createInEdgeExplorer() {
        return new PrepareGraphExplorerImpl(inArcs, true);
    }

    public BaseGraphExplorer createBaseOutEdgeExplorer() {
        return new BaseGraphExplorerImpl(outEdges, false);
    }

    public BaseGraphExplorer createBaseInEdgeExplorer() {
        return new BaseGraphExplorerImpl(inEdges, true);
    }

    public IntSet disconnect(int node) {
        IntSet neighbors = new IntHashSet(getDegree(node));
        for (PrepareEdge prepareEdge : outArcs.get(node)) {
            if (prepareEdge.to == node)
                continue;
            inArcs.get(prepareEdge.to).removeIf(a -> a == prepareEdge);
            neighbors.add(prepareEdge.to);
        }
        for (PrepareEdge prepareEdge : inArcs.get(node)) {
            if (prepareEdge.from == node)
                continue;
            outArcs.get(prepareEdge.from).removeIf(a -> a == prepareEdge);
            neighbors.add(prepareEdge.from);
        }
        outArcs.get(node).clear();
        inArcs.get(node).clear();
        return neighbors;
    }

    public interface PrepareGraphExplorer {
        PrepareGraphIterator setBaseNode(int node);
    }

    public interface PrepareGraphIterator {
        boolean next();

        int getBaseNode();

        int getAdjNode();

        int getPrepareEdge();

        boolean isShortcut();

        int getOrigEdgeKeyFirst();

        int getOrigEdgeKeyLast();

        int getSkipped1();

        int getSkipped2();

        double getWeight();

        int getOrigEdgeCount();

        void setSkippedEdges(int skipped1, int skipped2);

        void setWeight(double weight);

        void setOrigEdgeCount(int origEdgeCount);
    }

    public static class PrepareGraphExplorerImpl implements PrepareGraphExplorer, PrepareGraphIterator {
        private final List<List<PrepareEdge>> prepareEdges;
        private final boolean reverse;
        private List<PrepareEdge> prepareEdgesAtNode;
        private int index;

        PrepareGraphExplorerImpl(List<List<PrepareEdge>> prepareEdges, boolean reverse) {
            this.prepareEdges = prepareEdges;
            this.reverse = reverse;
        }

        @Override
        public PrepareGraphIterator setBaseNode(int node) {
            this.prepareEdgesAtNode = prepareEdges.get(node);
            this.index = -1;
            return this;
        }

        @Override
        public boolean next() {
            index++;
            return index < prepareEdgesAtNode.size();
        }

        @Override
        public int getBaseNode() {
            return reverse ? prepareEdgesAtNode.get(index).to : prepareEdgesAtNode.get(index).from;
        }

        @Override
        public int getAdjNode() {
            return reverse ? prepareEdgesAtNode.get(index).from : prepareEdgesAtNode.get(index).to;
        }

        @Override
        public int getPrepareEdge() {
            return prepareEdgesAtNode.get(index).prepareEdge;
        }

        @Override
        public boolean isShortcut() {
            return prepareEdgesAtNode.get(index).isShortcut();
        }

        @Override
        public int getOrigEdgeKeyFirst() {
            return prepareEdgesAtNode.get(index).origEdgeKeyFirst;
        }

        @Override
        public int getOrigEdgeKeyLast() {
            return prepareEdgesAtNode.get(index).origEdgeKeyLast;
        }

        @Override
        public int getSkipped1() {
            return prepareEdgesAtNode.get(index).skipped1;
        }

        @Override
        public int getSkipped2() {
            return prepareEdgesAtNode.get(index).skipped2;
        }

        @Override
        public double getWeight() {
            return prepareEdgesAtNode.get(index).weight;
        }

        @Override
        public int getOrigEdgeCount() {
            return prepareEdgesAtNode.get(index).origEdgeCount;
        }

        @Override
        public void setSkippedEdges(int skipped1, int skipped2) {
            prepareEdgesAtNode.get(index).skipped1 = skipped1;
            prepareEdgesAtNode.get(index).skipped2 = skipped2;
        }

        @Override
        public void setWeight(double weight) {
            assert Double.isFinite(weight);
            prepareEdgesAtNode.get(index).weight = weight;
        }

        @Override
        public void setOrigEdgeCount(int origEdgeCount) {
            prepareEdgesAtNode.get(index).origEdgeCount = origEdgeCount;
        }

        @Override
        public String toString() {
            return index < 0 ? "not_started" : getBaseNode() + "-" + getAdjNode();
        }
    }


    public interface BaseGraphExplorer {
        BaseGraphIterator setBaseNode(int node);
    }

    public interface BaseGraphIterator {
        boolean next();

        int getBaseNode();

        int getAdjNode();

        int getEdge();

        int getOrigEdgeKeyFirst();

        int getOrigEdgeKeyLast();
    }

    public static class BaseGraphExplorerImpl implements BaseGraphExplorer, BaseGraphIterator {
        private final List<List<Edge>> edges;
        private final boolean reverse;
        private List<Edge> edgesAtNode;
        private int index;

        BaseGraphExplorerImpl(List<List<Edge>> edges, boolean reverse) {
            this.edges = edges;
            this.reverse = reverse;
        }

        @Override
        public BaseGraphIterator setBaseNode(int node) {
            this.edgesAtNode = edges.get(node);
            this.index = -1;
            return this;
        }

        @Override
        public boolean next() {
            index++;
            return index < edgesAtNode.size();
        }

        @Override
        public int getBaseNode() {
            return reverse ? edgesAtNode.get(index).adjNode : edgesAtNode.get(index).baseNode;
        }

        @Override
        public int getAdjNode() {
            return reverse ? edgesAtNode.get(index).baseNode : edgesAtNode.get(index).adjNode;
        }

        @Override
        public int getEdge() {
            return edgesAtNode.get(index).edge;
        }

        @Override
        public int getOrigEdgeKeyFirst() {
            int e = getEdge() << 1;
            if (getBaseNode() > getAdjNode())
                e += 1;
            return e;
        }

        @Override
        public int getOrigEdgeKeyLast() {
            return getOrigEdgeKeyFirst();
        }

        @Override
        public String toString() {
            return getEdge() + ": " + getBaseNode() + "-" + getAdjNode();
        }
    }


    public static class Edge {
        private final int edge;
        private final int baseNode;
        private final int adjNode;

        public Edge(int edge, int baseNode, int adjNode) {
            this.edge = edge;
            this.baseNode = baseNode;
            this.adjNode = adjNode;
        }
    }

    public static class PrepareEdge {
        private final int prepareEdge;
        private final int from;
        private final int to;
        private double weight;
        private final int origEdgeKeyFirst;
        private final int origEdgeKeyLast;
        private int skipped1;
        private int skipped2;
        private int origEdgeCount;

        private static PrepareEdge edge(int prepareEdge, int from, int to, double weight) {
            int key = prepareEdge << 1;
            if (from > to)
                key += 1;
            return new PrepareEdge(prepareEdge, from, to, weight, key, key, -1, -1, 1);
        }

        private static PrepareEdge shortcut(int prepareEdge, int from, int to, int origEdgeKeyFirst, int origEdgeKeyLast, int skipped1, int skipped2, double weight, int origEdgeCount) {
            return new PrepareEdge(prepareEdge, from, to, weight, origEdgeKeyFirst, origEdgeKeyLast, skipped1, skipped2, origEdgeCount);
        }

        private PrepareEdge(int prepareEdge, int from, int to, double weight, int origEdgeKeyFirst, int origEdgeKeyLast, int skipped1, int skipped2, int origEdgeCount) {
            this.prepareEdge = prepareEdge;
            this.from = from;
            this.to = to;
            assert Double.isFinite(weight);
            this.weight = weight;
            this.origEdgeKeyFirst = origEdgeKeyFirst;
            this.origEdgeKeyLast = origEdgeKeyLast;
            this.skipped1 = skipped1;
            this.skipped2 = skipped2;
            this.origEdgeCount = origEdgeCount;
        }

        public boolean isShortcut() {
            return skipped1 != -1;
        }

        @Override
        public String toString() {
            return from + "-" + to + " (" + origEdgeKeyFirst + ", " + origEdgeKeyLast + ") " + weight;
        }
    }
}
