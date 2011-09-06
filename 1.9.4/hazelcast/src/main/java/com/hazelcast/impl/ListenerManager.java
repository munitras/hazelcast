/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
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
 *
 */

package com.hazelcast.impl;

import com.hazelcast.cluster.AbstractRemotelyProcessable;
import com.hazelcast.core.*;
import com.hazelcast.impl.base.PacketProcessor;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Data;
import com.hazelcast.nio.DataSerializable;
import com.hazelcast.nio.Packet;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import static com.hazelcast.impl.ClusterOperation.*;
import static com.hazelcast.nio.IOUtil.toData;

public class ListenerManager extends BaseManager {
    final List<ListenerItem> listeners = new CopyOnWriteArrayList<ListenerItem>();

    ListenerManager(Node node) {
        super(node);
        registerPacketProcessor(ClusterOperation.EVENT, new PacketProcessor() {
            public void process(Packet packet) {
                handleEvent(packet);
            }
        });
        registerPacketProcessor(ADD_LISTENER, new AddRemoveListenerOperationHandler());
        registerPacketProcessor(REMOVE_LISTENER, new AddRemoveListenerOperationHandler());
        registerPacketProcessor(ADD_LISTENER_NO_RESPONSE, new PacketProcessor() {
            public void process(Packet packet) {
                handleAddRemoveListener(true, packet);
            }
        });
    }

    private void handleEvent(Packet packet) {
        int eventType = (int) packet.longValue;
        Data key = packet.getKeyData();
        Data value = packet.getValueData();
        String name = packet.name;
        Address from = packet.lockAddress;
        releasePacket(packet);
        enqueueEvent(eventType, name, key, value, from, false);
    }

    private void handleAddRemoveListener(boolean add, Packet packet) {
        Data key = packet.getKeyData();
        boolean returnValue = (packet.longValue == 1);
        String name = packet.name;
        Address address = packet.conn.getEndPoint();
        releasePacket(packet);
        handleListenerRegistrations(add, name, key, address, returnValue);
    }

    public void syncForDead(Address deadAddress) {
        syncForAdd();
    }

    public void syncForAdd() {
        for (ListenerItem listenerItem : listeners) {
            if (!listenerItem.localListener) {
                registerListenerWithNoResponse(listenerItem.name, listenerItem.key, listenerItem.includeValue);
            }
        }
    }

    public void syncForAdd(Address newAddress) {
        for (ListenerItem listenerItem : listeners) {
            if (!listenerItem.localListener) {
                Data dataKey = null;
                if (listenerItem.key != null) {
                    dataKey = ThreadContext.get().toData(listenerItem.key);
                }
                sendAddListener(newAddress, listenerItem.name, dataKey, listenerItem.includeValue);
            }
        }
    }

    class AddRemoveListenerOperationHandler extends TargetAwareOperationHandler {
        boolean isRightRemoteTarget(Request request) {
            return (null == request.key) || thisAddress.equals(node.concurrentMapManager.getKeyOwner(request));
        }

        void doOperation(Request request) {
            Address from = request.caller;
            logger.log(Level.FINEST, "AddListenerOperation from " + from + ", local=" + request.local + "  key:" + request.key + " op:" + request.operation);
            if (from == null) throw new RuntimeException("Listener origin is not known!");
            boolean add = (request.operation == ADD_LISTENER);
            boolean includeValue = (request.longValue == 1);
            handleListenerRegistrations(add, request.name, request.key, request.caller, includeValue);
            request.response = Boolean.TRUE;
        }
    }

    public class AddRemoveListener extends MultiCall<Boolean> {
        final String name;
        final boolean add;
        final boolean includeValue;

        public AddRemoveListener(String name, boolean add, boolean includeValue) {
            this.name = name;
            this.add = add;
            this.includeValue = includeValue;
        }

        SubCall createNewTargetAwareOp(Address target) {
            return new AddListenerAtTarget(target);
        }

        boolean onResponse(Object response) {
            return true;
        }

        Object returnResult() {
            return Boolean.TRUE;
        }

        private final class AddListenerAtTarget extends SubCall {
            public AddListenerAtTarget(Address target) {
                super(target);
                ClusterOperation operation = (add) ? ADD_LISTENER : REMOVE_LISTENER;
                setLocal(operation, name, null, null, -1, -1);
                request.setBooleanRequest();
                request.longValue = (includeValue) ? 1 : 0;
            }
        }
    }

    private void registerListener(String name, Object key, boolean add, boolean includeValue) {
        if (key == null) {
            AddRemoveListener addRemoveListener = new AddRemoveListener(name, add, includeValue);
            addRemoveListener.call();
        } else {
            node.concurrentMapManager.new MAddKeyListener().addListener(name, add, key, includeValue);
        }
    }

    private void registerListenerWithNoResponse(String name, Object key, boolean includeValue) {
        Data dataKey = null;
        if (key != null) {
            dataKey = ThreadContext.get().toData(key);
        }
        enqueueAndReturn(new ListenerRegistrationProcess(name, dataKey, includeValue));
    }

    final class ListenerRegistrationProcess implements Processable {
        final String name;
        final Data key;
        final boolean includeValue;

        public ListenerRegistrationProcess(String name, Data key, boolean includeValue) {
            super();
            this.key = key;
            this.name = name;
            this.includeValue = includeValue;
        }

        public void process() {
            if (key != null) {
                processWithKey();
            } else {
                processWithoutKey();
            }
        }

        private void processWithKey() {
            Address owner = node.concurrentMapManager.getKeyOwner(key);
            if (owner.equals(thisAddress)) {
                handleListenerRegistrations(true, name, key, thisAddress, includeValue);
            } else {
                Packet packet = obtainPacket();
                packet.set(name, ADD_LISTENER_NO_RESPONSE, key, null);
                packet.longValue = (includeValue) ? 1 : 0;
                boolean sent = send(packet, owner);
                if (!sent) {
                    releasePacket(packet);
                }
            }
        }

        private void processWithoutKey() {
            for (MemberImpl member : lsMembers) {
                if (member.localMember()) {
                    handleListenerRegistrations(true, name, null, thisAddress, includeValue);
                } else {
                    sendAddListener(member.getAddress(), name, null, includeValue);
                }
            }
        }
    }

    void sendAddListener(Address toAddress, String name, Data key,
                         boolean includeValue) {
        Packet packet = obtainPacket();
        packet.set(name, ClusterOperation.ADD_LISTENER_NO_RESPONSE, key, null);
        packet.longValue = (includeValue) ? 1 : 0;
        boolean sent = send(packet, toAddress);
        if (!sent) {
            releasePacket(packet);
        }
    }

    public void addLocalListener(final String name, Object listener, Instance.InstanceType instanceType) {
        ListenerItem listenerItem = new ListenerItem(name, null, listener, true, instanceType, true);
        listeners.add(listenerItem);
        node.concurrentMapManager.enqueueAndWait(new Processable() {
            public void process() {
                node.concurrentMapManager.getOrCreateMap(name).addListener(null, node.getThisAddress(), true);
            }
        }, 10);
    }

    public void addListener(String name, Object listener, Object key, boolean includeValue,
                            Instance.InstanceType instanceType) {
        boolean remotelyRegister = true;
        for (ListenerItem listenerItem : listeners) {
            if (!remotelyRegister) {
                break;
            }
            // If existing listener is local then continue 
            // and don't take into account for remote registration check. (issue:584)
            if (!listenerItem.localListener && listenerItem.name.equals(name)) {
                if (key == null) {
                    if (listenerItem.key == null &&
                            (!includeValue || listenerItem.includeValue == includeValue)) {
                        remotelyRegister = false;
                    }
                } else if (listenerItem.key != null) {
                    if (listenerItem.key.equals(key) &&
                            (!includeValue || listenerItem.includeValue == includeValue)) {
                        remotelyRegister = false;
                    }
                }
            }
        }
        if (remotelyRegister) {
            registerListener(name, key, true, includeValue);
        }
        ListenerItem listenerItem = new ListenerItem(name, key, listener, includeValue, instanceType);
        listeners.add(listenerItem);
    }

    public List<ListenerItem> getListeners() {
        return listeners;
    }

    public synchronized void removeListener(String name, Object listener, Object key) {
        for (ListenerItem listenerItem : listeners) {
            if (listener == listenerItem.listener && listenerItem.name.equals(name)) {
                if (key == null && listenerItem.key == null) {
                    listeners.remove(listenerItem);
                } else if (key != null && key.equals(listenerItem.key)) {
                    listeners.remove(listenerItem);
                }
            }
        }
        boolean left = false;
        for (ListenerItem listenerItem : listeners) {
            if (key == null && listenerItem.key == null) {
                left = true;
            } else if (key != null && key.equals(listenerItem.key)) {
                left = true;
            }
        }
        if (!left) {
            registerListener(name, key, false, false);
        }
    }

    void callListeners(DataAwareEntryEvent dataAwareEntryEvent) {
        for (ListenerItem listenerItem : listeners) {
            if (listenerItem.listens(dataAwareEntryEvent)) {
                callListener(listenerItem, dataAwareEntryEvent);
            }
        }
    }

    private void callListener(final ListenerItem listenerItem, final EntryEvent event) {
    	if(listenerItem.localListener && !((DataAwareEntryEvent) event).firedLocally) {
	    	return;
    	}
    	
        final Object listener = listenerItem.listener;
        final EntryEventType entryEventType = event.getEventType();
        if (listenerItem.instanceType == Instance.InstanceType.MAP) {
            if (!listenerItem.name.startsWith(Prefix.MAP_HAZELCAST)) {
                Object proxy = node.factory.getOrCreateProxyByName(listenerItem.name);
                if (proxy instanceof MProxy) {
                    MProxy mProxy = (MProxy) proxy;
                    mProxy.getMapOperationCounter().incrementReceivedEvents();
                }
            }
        } else if (listenerItem.instanceType == Instance.InstanceType.QUEUE) {
            if (!listenerItem.name.startsWith(Prefix.QUEUE_HAZELCAST)) {
                Object proxy = node.factory.getOrCreateProxyByName(listenerItem.name);
                if (proxy instanceof QProxy) {
                    QProxy qProxy = (QProxy) proxy;
                    qProxy.getQueueOperationCounter().incrementReceivedEvents();
                }
            }
        } else if (listenerItem.instanceType == Instance.InstanceType.TOPIC) {
            if (!listenerItem.name.startsWith(Prefix.TOPIC_HAZELCAST)) {
                Object proxy = node.factory.getOrCreateProxyByName(listenerItem.name);
                if (proxy instanceof TopicProxy) {
                    TopicProxy tProxy = (TopicProxy) proxy;
                    tProxy.getTopicOperationCounter().incrementReceivedMessages();
                }
            }
        }
        final EntryEvent event2 = listenerItem.includeValue ?
                event :
                (event.getValue() != null ?
                        new EntryEvent(event.getSource(),
                                event.getMember(),
                                event.getEventType().getType(),
                                event.getKey(),
                                null,
                                null) :
                        event);
        switch (listenerItem.instanceType) {
            case MAP:
            case MULTIMAP:
                EntryListener entryListener = (EntryListener) listener;
                switch (entryEventType) {
                    case ADDED:
                        entryListener.entryAdded(event2);
                        break;
                    case REMOVED:
                        entryListener.entryRemoved(event2);
                        break;
                    case UPDATED:
                        entryListener.entryUpdated(event2);
                        break;
                    case EVICTED:
                        entryListener.entryEvicted(event2);
                        break;
                }
                break;
            case SET:
            case LIST:
                ItemListener itemListener = (ItemListener) listener;
                switch (entryEventType) {
                    case ADDED:
                        itemListener.itemAdded(event2.getKey());
                        break;
                    case REMOVED:
                        itemListener.itemRemoved(event2.getKey());
                        break;
                }
                break;
            case TOPIC:
                MessageListener messageListener = (MessageListener) listener;
                messageListener.onMessage(event2.getValue());
                break;
            case QUEUE:
                ItemListener queueItemListener = (ItemListener) listener;
                switch (entryEventType) {
                    case ADDED:
                        queueItemListener.itemAdded(event2.getValue());
                        break;
                    case REMOVED:
                        queueItemListener.itemRemoved(event2.getValue());
                        break;
                }
                break;
        }
    }

    public static class ListenerItem extends AbstractRemotelyProcessable implements DataSerializable {
        public String name;
        public Object key;
        public Object listener;
        public boolean includeValue;
        public Instance.InstanceType instanceType;
        public boolean localListener = false;

        public ListenerItem() {
        }

        public ListenerItem(String name, Object key, Object listener, boolean includeValue,
                            Instance.InstanceType instanceType) {
            this(name, key, listener, includeValue, instanceType, false);
        }

        public ListenerItem(String name, Object key, Object listener, boolean includeValue,
                            Instance.InstanceType instanceType, boolean localListener) {
            super();
            this.key = key;
            this.listener = listener;
            this.name = name;
            this.includeValue = includeValue;
            this.instanceType = instanceType;
            this.localListener = localListener;
        }

        public boolean listens(DataAwareEntryEvent dataAwareEntryEvent) {
            String name = dataAwareEntryEvent.getName();
            return this.name.equals(name) && (this.key == null || dataAwareEntryEvent.getKey().equals(this.key));
        }

        public void writeData(DataOutput out) throws IOException {
            out.writeUTF(name);
            writeObject(out, key);
            out.writeBoolean(includeValue);
        }

        public void readData(DataInput in) throws IOException {
            name = in.readUTF();
            key = readObject(in);
            includeValue = in.readBoolean();
        }

        public void process() {
            getNode().listenerManager.handleListenerRegistrations(true, name, toData(key), getConnection().getEndPoint(), includeValue);
        }
    }
}