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

package ru.avplatonov.keter.core.storage.remote

import java.util.UUID

import ru.avplatonov.keter.core.discovery.NodeId
import ru.avplatonov.keter.core.discovery.messaging.{Message, MessageType}
import ru.avplatonov.keter.core.storage.FileDescriptor

case class ExchangeFileIndexesMessage(index: FilesIndex, from: NodeId) extends Message {
    override val `type`: MessageType = MessageType.INDEXES_EXCHANGE
    override val id: String = UUID.randomUUID().toString
}

trait FilesIndex {
    def localNodeId: NodeId

    def getNodeId(desc: FileDescriptor): NodeId

    def merge(other: FilesIndex): FilesIndex

    def index(desc: FileDescriptor, from: NodeId): Unit

    def remove(of: NodeId): Unit
}
