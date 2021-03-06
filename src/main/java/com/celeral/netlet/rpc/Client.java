/*
 * Copyright 2017 Celeral.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celeral.netlet.rpc;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import com.esotericsoftware.kryo.serializers.FieldSerializer.Bind;
import com.esotericsoftware.kryo.serializers.JavaSerializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.celeral.netlet.AbstractLengthPrependerClient;
import com.celeral.netlet.codec.DefaultStatefulStreamCodec;
import com.celeral.netlet.codec.StatefulStreamCodec;
import com.celeral.netlet.codec.StatefulStreamCodec.DataStatePair;
import com.celeral.netlet.util.Slice;

/**
 *
 * @author Chetan Narsude {@literal <chetan@apache.org>}
 *
 * @param <T> - Type of the object that's received by the client.
 */
public abstract class Client<T> extends AbstractLengthPrependerClient
{
  Slice state;
  private StatefulStreamCodec<Object> serdes;
  private transient Executor executors;

  private Client()
  {
    this(Runnable::run);
  }

  public Client(Executor executors)
  {
    this.executors = executors;

    DefaultStatefulStreamCodec<Object> codec = new DefaultStatefulStreamCodec<>();
    /* setup the classes that we know about before hand */
    codec.register(Ack.class);
    codec.register(RPC.class);
    codec.register(ExtendedRPC.class);
    codec.register(RR.class);

    this.serdes = codec;
  }

  public abstract void onMessage(T message);

  class Sender implements Runnable
  {
    Object object;

    Sender(Object object)
    {
      this.object = object;
    }

    @Override
    public void run()
    {
      writeObject(serdes.toDataStatePair(object));
    }
  }

  protected void send(final Object object)
  {
    executors.execute(new Sender(object));
  }

  private synchronized void writeObject(DataStatePair pair)
  {
    try {
      if (pair.state != null) {
        write(pair.state.buffer, pair.state.offset, pair.state.length);
      }

      write(pair.data.buffer, pair.data.offset, pair.data.length);
    } catch (Exception ex) {
      handleException(ex, null);
    }
  }

  public void execute(Runnable runnable)
  {
    executors.execute(runnable);            
  }
  
  class Receiver implements Runnable
  {
    DataStatePair pair;

    Receiver(DataStatePair pair)
    {
      this.pair = pair;
    }

    @Override
    public void run()
    {
      @SuppressWarnings("unchecked")
      T object = (T)serdes.fromDataStatePair(pair);
      onMessage(object);
    }
  }

  /**
   * @return the serdes
   */
  public StatefulStreamCodec<Object> getSerdes()
  {
    return serdes;
  }

  /**
   * @param serdes the serdes to set
   */
  public void setSerdes(StatefulStreamCodec<Object> serdes)
  {
    this.serdes = serdes;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onMessage(byte[] buffer, int offset, int size)
  {
    if (size > 0) {
      if (buffer[offset] == StatefulStreamCodec.MessageType.STATE.getByte()) {
        state = new Slice(buffer, offset, size);
      }
      else {
        final DataStatePair pair = new DataStatePair();
        pair.state = state;
        state = null;
        pair.data = new Slice(buffer, offset, size);
        executors.execute(new Receiver(pair));
      }
    }
  }

  public static class Ack
  {
    protected static final AtomicInteger counter = new AtomicInteger();
    /**
     * Unique Identifier for the calls and responses.
     * Receiving object with this type is sufficient to establish successful delivery
     * of corresponding call or response from the other end.
     */
    protected int id;

    public Ack(int id)
    {
      this.id = id;
    }

    public Ack()
    {
      /* for serialization */
    }

    public int getId()
    {
      return id;
    }

    @Override
    public String toString()
    {
      return "Ack{" + "id=" + id + '}';
    }

    @Override
    public int hashCode()
    {
      return id;
    }

    @Override
    public boolean equals(Object obj)
    {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      final Ack other = (Ack)obj;
      return this.id == other.id;
    }

  }

  /**
   * Compact method to communicate which method to be called with the arguments.
   * Before this method is understood by the other party, it needs to receive
   * ExtendedRPC which communicates the mapping of methodId with the method.
   */
  public static class RPC extends Ack
  {
    final int methodId;
    final Object identifier;
    final Object[] args;

    /**
     * This extra payload allows us to reflect the garbage collection on the server side.
     */
    Object[] deletedIdentifiers;

    protected RPC()
    {
      /* for serialization */
      this(0, 0, null, null);
    }

    public RPC(int methodId, Object identifier, Object[] args)
    {
      this(counter.incrementAndGet(), methodId, identifier, args);
    }

    public RPC(int id, int methodId, Object identifier, Object[] args)
    {
      super(id);
      this.methodId = methodId;
      this.identifier = identifier;
      this.args = args;
    }

    public void setDeletedIdentifiers(Object[] identifiers) {
      this.deletedIdentifiers = identifiers;
    }

    @Override
    public String toString()
    {
      return "RPC{" + "methodId=" + methodId + ", identifier=" + (identifier == null? "null" :
                     identifier.toString()) + ", args=" + Arrays.toString(args) + '}' + super.toString();
    }
  }

  /**
   * The first time a method is invoked by the client, this structure will be
   * sent to the remote end.
   */
  public static class ExtendedRPC extends RPC
  {
    public Object serializableMethod;

    protected ExtendedRPC()
    {
      /* for serialization */
    }

    public ExtendedRPC(int id, Object method, int methodId, Object identifier, Object[] args)
    {
      super(id, methodId, identifier, args);
      serializableMethod = method;
    }

    public ExtendedRPC(Object method, int methodId, Object identifier, Object[] args)
    {
      this(counter.incrementAndGet(), method, methodId, identifier, args);
    }

    @Override
    public String toString()
    {
      return "ExtendedRPC@" + System.identityHashCode(this) + '{' + "methodGenericstring=" + serializableMethod + '}' + super.toString();
    }

  }

  public static class RR extends Ack
  {
    Object response;
    Object[] removedIdentifiers;

    @Bind(JavaSerializer.class)
    Throwable exception;

    protected RR()
    {
      /* for serialization */
    }

    public RR(int id, Object response, Throwable exception)
    {
      super(id);
      this.response = response;
      this.exception = exception;
    }

    public RR(int id, Object response)
    {
      this(id, response, null);
    }

    @Override
    public String toString()
    {
      return "RR{" + "exception=" + exception + ", response=" + response + '}' + super.toString();
    }
  }

  private static final Logger logger = LogManager.getLogger(Client.class);
}
