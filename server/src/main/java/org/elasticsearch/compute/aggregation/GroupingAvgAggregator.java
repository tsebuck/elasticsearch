/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.compute.aggregation;

import org.elasticsearch.compute.Experimental;
import org.elasticsearch.compute.data.AggregatorStateBlock;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleArrayBlock;
import org.elasticsearch.compute.data.Page;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

@Experimental
final class GroupingAvgAggregator implements GroupingAggregatorFunction {

    private final GroupingAvgState state;
    private final int channel;

    static GroupingAvgAggregator create(int inputChannel) {
        if (inputChannel < 0) {
            throw new IllegalArgumentException();
        }
        return new GroupingAvgAggregator(inputChannel, new GroupingAvgState());
    }

    static GroupingAvgAggregator createIntermediate() {
        return new GroupingAvgAggregator(-1, new GroupingAvgState());
    }

    private GroupingAvgAggregator(int channel, GroupingAvgState state) {
        this.channel = channel;
        this.state = state;
    }

    @Override
    public void addRawInput(Block groupIdBlock, Page page) {
        assert channel >= 0;
        Block valuesBlock = page.getBlock(channel);
        GroupingAvgState state = this.state;
        for (int i = 0; i < valuesBlock.getPositionCount(); i++) {
            int groupId = (int) groupIdBlock.getLong(i);
            state.add(valuesBlock.getDouble(i), groupId);
        }
    }

    @Override
    public void addIntermediateInput(Block groupIdBlock, Block block) {
        assert channel == -1;
        if (block instanceof AggregatorStateBlock) {
            @SuppressWarnings("unchecked")
            AggregatorStateBlock<GroupingAvgState> blobBlock = (AggregatorStateBlock<GroupingAvgState>) block;
            GroupingAvgState tmpState = new GroupingAvgState();
            blobBlock.get(0, tmpState);
            this.state.addIntermediate(groupIdBlock, tmpState);
        } else {
            throw new RuntimeException("expected AggregatorStateBlock, got:" + block);
        }
    }

    @Override
    public Block evaluateIntermediate() {
        AggregatorStateBlock.Builder<AggregatorStateBlock<GroupingAvgState>, GroupingAvgState> builder = AggregatorStateBlock
            .builderOfAggregatorState(GroupingAvgState.class);
        builder.add(state);
        return builder.build();
    }

    @Override
    public Block evaluateFinal() {  // assume block positions == groupIds
        GroupingAvgState s = state;
        int positions = s.largestGroupId + 1;
        double[] result = new double[positions];
        for (int i = 0; i < positions; i++) {
            result[i] = s.values[i] / s.counts[i];
        }
        return new DoubleArrayBlock(result, positions);
    }

    static class GroupingAvgState implements AggregatorState<GroupingAvgState> {

        double[] values;
        double[] deltas;
        long[] counts;

        // total number of groups; <= values.length
        int largestGroupId;

        // TODO prototype:
        // 1. BigDoubleArray BigDoubleArray, BigLongArray
        // 2. big byte array

        private final AvgStateSerializer serializer;

        GroupingAvgState() {
            this(new double[1], new double[1], new long[1]);
        }

        GroupingAvgState(double[] value, double[] delta, long[] count) {
            this.values = value;
            this.deltas = delta;
            this.counts = count;
            this.serializer = new AvgStateSerializer();
        }

        void addIntermediate(Block groupIdBlock, GroupingAvgState state) {
            final double[] valuesToAdd = state.values;
            final double[] deltasToAdd = state.deltas;
            final long[] countsToAdd = state.counts;
            final int positions = groupIdBlock.getPositionCount();
            for (int i = 0; i < positions; i++) {
                int groupId = (int) groupIdBlock.getLong(i);
                add(valuesToAdd[i], deltasToAdd[i], groupId, countsToAdd[i]);
            }
        }

        void add(double valueToAdd, int groupId) {
            add(valueToAdd, 0d, groupId, 1);
        }

        void add(double valueToAdd, double deltaToAdd, int groupId, long increment) {
            ensureCapacity(groupId);
            if (groupId > largestGroupId) {
                largestGroupId = groupId;
            }
            add(valueToAdd, deltaToAdd, groupId);
            counts[groupId] += increment;
        }

        private void ensureCapacity(int position) {
            if (position >= values.length) {
                int newSize = values.length << 1;  // trivial
                values = Arrays.copyOf(values, newSize);
                deltas = Arrays.copyOf(deltas, newSize);
                counts = Arrays.copyOf(counts, newSize);
            }
        }

        void add(double valueToAdd, double deltaToAdd, int position) {
            // If the value is Inf or NaN, just add it to the running tally to "convert" to
            // Inf/NaN. This keeps the behavior bwc from before kahan summing
            if (Double.isFinite(valueToAdd) == false) {
                values[position] = valueToAdd + values[position];
            }

            if (Double.isFinite(values[position])) {
                double correctedSum = valueToAdd + (deltas[position] + deltaToAdd);
                double updatedValue = values[position] + correctedSum;
                deltas[position] = correctedSum - (updatedValue - values[position]);
                values[position] = updatedValue;
            }
        }

        @Override
        public AggregatorStateSerializer<GroupingAvgState> serializer() {
            return serializer;
        }
    }

    // @SerializedSize(value = Double.BYTES + Double.BYTES + Long.BYTES)
    static class AvgStateSerializer implements AggregatorStateSerializer<GroupingAvgState> {

        // record Shape (double value, double delta, long count) {}

        static final int BYTES_SIZE = Double.BYTES + Double.BYTES + Long.BYTES;

        @Override
        public int size() {
            return BYTES_SIZE;
        }

        private static final VarHandle doubleHandle = MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.BIG_ENDIAN);
        private static final VarHandle longHandle = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

        @Override
        public int serialize(GroupingAvgState state, byte[] ba, int offset) {
            int positions = state.largestGroupId + 1;
            longHandle.set(ba, offset, positions);
            offset += 8;
            for (int i = 0; i < positions; i++) {
                doubleHandle.set(ba, offset, state.values[i]);
                doubleHandle.set(ba, offset + 8, state.deltas[i]);
                longHandle.set(ba, offset + 16, state.counts[i]);
                offset += BYTES_SIZE;
            }
            return 8 + (BYTES_SIZE * positions); // number of bytes written
        }

        // sets the state in value
        @Override
        public void deserialize(GroupingAvgState state, byte[] ba, int offset) {
            Objects.requireNonNull(state);
            int positions = (int) (long) longHandle.get(ba, offset);
            offset += 8;
            double[] values = new double[positions];
            double[] deltas = new double[positions];
            long[] counts = new long[positions];
            for (int i = 0; i < positions; i++) {
                values[i] = (double) doubleHandle.get(ba, offset);
                deltas[i] = (double) doubleHandle.get(ba, offset + 8);
                counts[i] = (long) longHandle.get(ba, offset + 16);
                offset += BYTES_SIZE;
            }
            state.values = values;
            state.deltas = deltas;
            state.counts = counts;
            state.largestGroupId = positions - 1;
        }
    }
}
