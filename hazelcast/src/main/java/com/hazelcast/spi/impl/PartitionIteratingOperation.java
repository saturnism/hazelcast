/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl;

import com.hazelcast.nio.Data;
import com.hazelcast.nio.DataSerializable;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.spi.AbstractOperation;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.ResponseHandler;
import com.hazelcast.util.ResponseQueueFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

class PartitionIteratingOperation extends AbstractOperation {
    private List<Integer> partitions;
    private Data operationData;

    private transient Map<Integer, Object> results;

    public PartitionIteratingOperation(List<Integer> partitions, Data operationData) {
        this.partitions = partitions;
        this.operationData = operationData;
    }

    public PartitionIteratingOperation() {
    }

    @Override
    public void beforeRun() throws Exception {
        results = new HashMap<Integer, Object>(partitions != null? partitions.size() : 0);
    }

    public void run() {
        final NodeEngine nodeEngine = getNodeEngine();
        try {
            Map<Integer, ResponseQueue> responses = new HashMap<Integer, ResponseQueue>(partitions.size());
            for (final int partitionId : partitions) {
                final Operation op = (Operation) nodeEngine.toObject(operationData);
                ResponseQueue responseQueue = new ResponseQueue();
                op.setNodeEngine(getNodeEngine())
                        .setCaller(getCaller())
                        .setPartitionId(partitionId)
                        .setReplicaIndex(getReplicaIndex())
                        .setResponseHandler(responseQueue)
                        .setService(getService());
                responses.put(partitionId, responseQueue);

                // TODO: !too many threads may start!
                nodeEngine.getExecutionService().execute(new Runnable() {
                    public void run() {
                        nodeEngine.getOperationService().runOperation(op);
                    }
                });
            }
            for (Map.Entry<Integer, ResponseQueue> responseQueueEntry : responses.entrySet()) {
                final ResponseQueue queue = responseQueueEntry.getValue();
                final Object result = queue.get();
                results.put(responseQueueEntry.getKey(), result);
            }
        } catch (Exception e) {
            nodeEngine.getLogger(PartitionIteratingOperation.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }
    }

    @Override
    public void afterRun() throws Exception {
    }

    @Override
    public Object getResponse() {
        return new PartitionResponse(results);
    }

    @Override
    public boolean returnsResponse() {
        return true;
    }

    private class ResponseQueue implements ResponseHandler {
        final BlockingQueue b = ResponseQueueFactory.newResponseQueue();

        public void sendResponse(Object obj) {
            b.offer(obj);
        }

        public Object get() throws InterruptedException {
            return b.take();
        }
    }

    // To make serialization of HashMap faster.
    public static class PartitionResponse implements DataSerializable {

        private Map<Integer, Object> results;

        public PartitionResponse() {
        }

        public PartitionResponse(Map<Integer, Object> results) {
            this.results = results != null ? results : Collections.<Integer, Object>emptyMap();
        }

        public void writeData(DataOutput out) throws IOException {
            int len = results != null ? results.size() : 0;
            out.writeInt(len);
            if (len > 0) {
                for (Map.Entry<Integer, Object> entry : results.entrySet()) {
                    out.writeInt(entry.getKey());
                    IOUtil.writeObject(out, entry.getValue());
                }
            }
        }

        public void readData(DataInput in) throws IOException {
            int len = in.readInt();
            if (len > 0) {
                results = new HashMap<Integer, Object>(len);
                for (int i = 0; i < len; i++) {
                    int pid = in.readInt();
                    Object value = IOUtil.readObject(in);
                    results.put(pid, value);
                }
            } else {
                results = Collections.emptyMap();
            }
        }

        public Map<? extends Integer, ?> asMap() {
            return results;
        }
    }

    @Override
    public void writeInternal(DataOutput out) throws IOException {
        super.writeInternal(out);
        int pCount = partitions.size();
        out.writeInt(pCount);
        for (int i = 0; i < pCount; i++) {
            out.writeInt(partitions.get(i));
        }
        operationData.writeData(out);
    }

    @Override
    public void readInternal(DataInput in) throws IOException {
        super.readInternal(in);
        int pCount = in.readInt();
        partitions = new ArrayList<Integer>(pCount);
        for (int i = 0; i < pCount; i++) {
            partitions.add(in.readInt());
        }
        operationData = new Data();
        operationData.readData(in);
    }
}
