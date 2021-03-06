/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.avplatonov.keter.core.storage.local

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
  * Holder for temporary object.
  *
  * @param obj   stored object.
  * @param cache cache.
  * @tparam K type of object's key.
  * @tparam V type of stored object.
  */
case class Holder[K, V](private val key: K, private val obj: V, private val cache: TempObjsCache[K, V]) {
    /**
      * Process object in holder and release it.
      *
      * @param f processor.
      * @tparam R type of result.
      * @return result of processor.
      */
    def foreach[R](f: V => R): R = {
        try {
            f(obj)
        }
        finally {
            cache.release(key, obj)
        }
    }
}

/**
  * Interface for temporary objects that can be removed after all of holders will be released.
  *
  * @tparam K type of object key.
  * @tparam V type of stored object.
  */
trait TempObjsCache[K, V] {
    /**
      * Put object to cache.
      *
      * @param key key.
      * @param obj value.
      * @return value holder.
      */
    def put(key: K, obj: V): Holder[K, V]

    /**
      * Get oject from cache.
      *
      * @param key key.
      * @return value holder.
      */
    def get(key: K): Option[Holder[K, V]]

    /**
      * Release object by holder.
      *
      * @param key key.
      * @param obj obj.
      */
    private[local] def release(key: K, obj: V): Unit
}

//todo: synchronized blocks
/**
  * Cache using counter for detect deletion.
  *
  * @tparam K type of object key.
  * @tparam V type of stored object.
  */
trait OnCountersTempObjsCache[K, V] extends TempObjsCache[K, V] {
    private val objs: ConcurrentHashMap[K, (V, AtomicInteger)] = new ConcurrentHashMap[K, (V, AtomicInteger)]()

    /**
      * Will be fired when object removed from cache.
      *
      * @param key key.
      * @param value value.
      */
    def onRemove(key: K, value: V): Unit

    /** */
    override def put(key: K, obj: V): Holder[K, V] = synchronized {
        if (objs.contains(key)) {
            val res = objs.get(key)
            res._2.incrementAndGet()
            return Holder(key, res._1, this)
        } else {
            objs.put(key, (obj, new AtomicInteger(1)))
            return Holder(key, obj, this)
        }
    }

    /** */
    override def get(key: K): Option[Holder[K, V]] = {
        if(!objs.contains(key)) None
        else {
            val res = objs.get(key)
            res._2.incrementAndGet()

            Some(Holder(key, res._1, this))
        }
    }

    /** */
    override def release(key: K, obj: V): Unit = {
        preRelease(key, obj)
        var isDeleted = false
        synchronized {
            val (obj, counter) = objs.get(key)
            if(counter.decrementAndGet() == 0) {
                objs.remove(key)
                isDeleted = true
            }
        }

        if(isDeleted) {
            onRemove(key, obj)
        }

        postRelease(key, obj)
    }

    /**
      * Pre-Release hook.
      *
      * @param key key.
      * @param value obj.
      */
    def preRelease(key: K, value: V): Unit = { }

    /**
      * Post-Release hook.
      *
      * @param key key.
      * @param value obj.
      */
    def postRelease(key: K, value: V): Unit = { }
}
