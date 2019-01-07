/*
 * Copyright 2019 Celeral.
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
package com.celeral.netlet.codec;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.celeral.netlet.util.Slice;
import com.celeral.utils.Throwables;

/**
 *
 * @author Chetan Narsude <chetan@celeral.com>
 */
public class CipherStatefulStreamCodec<T> implements StatefulStreamCodec<T>
{
  private final AtomicReference<Cipher> encryption = new AtomicReference<>();
  private final AtomicReference<Cipher> decryption = new AtomicReference<>();
  private final StatefulStreamCodec<T> codec;

  public CipherStatefulStreamCodec(StatefulStreamCodec<T> codec, Key encryption, Key decryption)
  {
    this.codec = codec;
    initCipher(encryption, decryption);
  }

  public final void initCipher(Key encryption, Key decryption)
  {
    try {
      if (encryption != null) {
        Cipher instance = Cipher.getInstance(RSAECBOAEP_WITH_SHA1_AND_MGF1_PADDING);
        instance.init(Cipher.ENCRYPT_MODE, encryption);
        this.encryption.set(instance);
      }

      if (decryption != null) {
        Cipher instance = Cipher.getInstance(RSAECBOAEP_WITH_SHA1_AND_MGF1_PADDING);
        instance.init(Cipher.DECRYPT_MODE, decryption);
        this.decryption.set(instance);
      }
    }
    catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static final String RSAECBOAEP_WITH_SHA1_AND_MGF1_PADDING = "RSA/ECB/OAEPWithSHA1AndMGF1Padding";

  public static Slice doFinal(Cipher cipher, Slice slice) throws IllegalBlockSizeException, BadPaddingException
  {
    if (cipher == null) {
      return slice;
    }

    byte[] newBuffer = new byte[cipher.getOutputSize(slice.length - 1) + 1];
    try {
      slice.length = cipher.doFinal(slice.buffer, slice.offset + 1, slice.length - 1, newBuffer, 1) + 1;
      newBuffer[0] = slice.buffer[slice.offset];
      slice.offset = 0;
      slice.buffer = newBuffer;
      return slice;
    }
    catch (ShortBufferException ex) {
      throw Throwables.throwFormatted(ex, IllegalStateException.class,
                                      "Incorrect implementation caused miscalculation of the buffer size! slice = {}, newbuffer = {}, cipher = {}",
                                      slice.length, newBuffer.length, cipher);
    }
  }

  private static final Logger logger = LoggerFactory.getLogger(CipherStatefulStreamCodec.class);
  @Override
  public DataStatePair toDataStatePair(T o)
  {
    try {
      DataStatePair pair = codec.toDataStatePair(o);
      pair.data = CipherStatefulStreamCodec.doFinal(encryption.get(), pair.data);
      if (pair.state != null) {
        pair.state = CipherStatefulStreamCodec.doFinal(encryption.get(), pair.state);
      }
      return pair;
    }
    catch (IllegalBlockSizeException | BadPaddingException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public Object fromDataStatePair(DataStatePair pair)
  {
    try {
      pair.data = CipherStatefulStreamCodec.doFinal(decryption.get(), pair.data);
      if (pair.state != null) {
        pair.state = CipherStatefulStreamCodec.doFinal(decryption.get(), pair.state);
      }
      return codec.fromDataStatePair(pair);
    }
    catch (IllegalBlockSizeException | BadPaddingException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void resetState()
  {
    codec.resetState();
  }
}