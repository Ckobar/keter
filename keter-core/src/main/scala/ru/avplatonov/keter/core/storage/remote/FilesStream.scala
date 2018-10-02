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

import java.io._
import java.net.ServerSocket
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, LinkedBlockingQueue}

import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.apache.commons.io.IOUtils
import ru.avplatonov.keter.core.discovery.messaging.{Client, Message, MessageType}
import ru.avplatonov.keter.core.discovery.{DiscoveryService, NodeId, RemoteNode}
import ru.avplatonov.keter.core.storage.FileDescriptor
import ru.avplatonov.keter.core.storage.local.{LocalFileDescriptor, LocalFilesStorage}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class DownloadedFile(originalDescriptor: FileDescriptor, file: File)

/**
  * Remote files abstraction.
  * Just wrapper for future.
  */
trait RemoteFiles {
    /**
      * @return true if files was downloaded.
      */
    def isReady: Boolean

    /**
      * Awaits files and return downloaded files list.
      *
      * @return downloaded files list.
      */
    def get(): Try[List[DownloadedFile]]
}

/**
  * Request for sending files to current node.
  *
  * @param files list of remote files.
  * @param listenerPort port of downloader.
  * @param from current node id.
  */
case class DownloadFileMessage(files: List[FileDescriptor], listenerPort: Int, from: NodeId) extends Message {
    override val `type`: MessageType = MessageType.FILE_REQUEST
    override val id: String = UUID.randomUUID().toString
}

/** */
object FilesStream {

    /** */
    case class Settings(
        savingDirectory: Path,
        downloadPortsFrom: Int,
        downloadPortsTo: Int,
        sendingPoolSize: Int
    )

}

/** Future with starting time. */
case class SendingFileFuture(startSendingTs: Long, fut: Future[Unit])

/**  */
case class DownloadTarget(host: String, port: Int)

/**
  * Abstraction for files exchanging.
  */
case class FilesStream(discoveryService: DiscoveryService, settings: FilesStream.Settings) {
    /** Ports for files downloading. */
    private val availablePorts = new LinkedBlockingQueue[Int]((settings.downloadPortsFrom to settings.downloadPortsTo).asJava)

    /** Download files pool. */
    private val downloadPool = Executors.newFixedThreadPool(settings.downloadPortsFrom - settings.downloadPortsTo + 1,
        new ThreadFactoryBuilder()
            .setNameFormat(s"download-files-stream-pool-[${settings.downloadPortsFrom}:${settings.downloadPortsTo}]-%d")
            .build())

    /** Sending files pool. */
    private val sendingPool = Executors.newFixedThreadPool(settings.sendingPoolSize, new ThreadFactoryBuilder()
        .setNameFormat(s"sending-files-stream-pool-[${settings.downloadPortsFrom}:${settings.downloadPortsTo}]-%d")
        .build())

    /** */
    private val downloadExecutionContext = ExecutionContext.fromExecutor(downloadPool)
    /** */
    private val sendingExecutionContext = ExecutionContext.fromExecutor(sendingPool)

    //TODO: create watching to sending queue and add fut to it
    private val sendingQueue = new ConcurrentLinkedQueue[SendingFileFuture]()

    /**
      * Send files from LocalFS to host:port
      *
      * @param files files list.
      * @param to target host port.
      * @return awaitable future.
      */
    def send(files: List[LocalFileDescriptor], to: DownloadTarget): Future[Unit] = {
        Future({
            new Client(Client.Settings(serverHost = to.host, serverPort = to.port)).send(os => {
                os.writeInt(files.size)
                files.foreach(f => os.writeLong(LocalFilesStorage.sizeOf(f)))
                files.foreach(f => sendFile(f, os))
            })
        })(sendingExecutionContext)
    }

    /**
      * Send file to output stream.
      *
      * @param desc local file desc.
      * @param out out.
      */
    private def sendFile(desc: LocalFileDescriptor, out: OutputStream): Unit = {
        resource.managed(new FileInputStream(desc.filepath.toFile)) foreach { from =>
            IOUtils.copyLarge(from, out)
        }
    }

    /**
      * Send request to remote node with list of files and open socket on free port for files awaiting.
      *
      * @param files files list.
      * @param from remote node with files.
      * @return remote files wrapper.
      */
    def download(files: List[FileDescriptor], from: RemoteNode): RemoteFiles = {
        val randomSuffix = UUID.randomUUID().toString
        val fut = Future[List[DownloadedFile]]({
            val port = getFreePort()
            try {
                from.sendMsg(DownloadFileMessage(files, port, discoveryService.getLocalNode().get.id))
                resource.managed(new ServerSocket(port))
                    .flatMap(s => resource.managed(s.accept()))
                    .flatMap(s => resource.managed(s.getInputStream))
                    .foreach(saveFiles(_, files, randomSuffix))
                files.map(d => DownloadedFile(d, downloadedFile(d, randomSuffix)))
            }
            finally {
                releasePort(port)
            }
        })(downloadExecutionContext)

        new RemoteFiles {
            override def isReady: Boolean = fut.isCompleted

            override def get(): Try[List[DownloadedFile]] = {
                while (!isReady) {
                    Thread.sleep((1 second).toMillis)
                }

                fut.value match {
                    case Some(res) => res
                    case None => throw new IllegalStateException("Future was done but value is None")
                }
            }
        }
    }

    /**
      * @return request new port for files awaiting.
      */
    private def getFreePort(): Int = availablePorts.poll()

    /**
      * @param port release listening port.
      */
    private def releasePort(port: Int) = availablePorts.put(port)

    //TODO: we should consider that files may be deleted in node or something other - we should implement error protocol
    /**
      * Save files from input stream.
      *
      * @param in input stream.
      * @param fileNames file names in stream.
      * @param nameSuffix suffix for temp file.
      */
    private def saveFiles(in: InputStream, fileNames: List[FileDescriptor], nameSuffix: String): Unit = {
        resource.managed(new DataInputStream(in)) foreach { dis =>
            val countOfFiles = dis.readInt()
            assert(countOfFiles == fileNames.size, "Count of files from remote node should correspond to request")
            val fileSizes = (0 until countOfFiles).map(x => dis.readLong()).toList
            fileNames zip fileSizes foreach {
                case (name, size) =>
                    saveFile(name, size, dis, nameSuffix)
            }
        }
    }

    /**
      * Saves file from input stream.
      *
      * @param name file name.
      * @param size size of file in stream.
      * @param from input stream.
      * @param suffix suffix for temp file.
      */
    private def saveFile(name: FileDescriptor, size: Long, from: DataInputStream, suffix: String): Unit = {
        resource.managed(new FileOutputStream(downloadedFile(name, suffix))) foreach { to =>
            IOUtils.copyLarge(from, to, 0, size)
        }
    }

    /**
      * @return filename for temp file.
      */
    private def downloadedFile(desc: FileDescriptor, suffix: String): File =
        settings.savingDirectory.resolve(s"${desc.key}-$suffix").toFile
}
