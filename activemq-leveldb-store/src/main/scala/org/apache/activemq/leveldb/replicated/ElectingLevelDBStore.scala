/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.leveldb.replicated

import org.fusesource.fabric.groups._
import org.fusesource.fabric.zookeeper.internal.ZKClient
import org.linkedin.util.clock.Timespan
import scala.reflect.BeanProperty
import org.apache.activemq.util.{ServiceStopper, ServiceSupport}
import org.apache.activemq.leveldb.{LevelDBClient, RecordLog, LevelDBStore}
import java.net.{NetworkInterface, InetAddress}
import org.fusesource.hawtdispatch._
import org.apache.activemq.broker.Locker
import org.apache.activemq.store.PersistenceAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.activemq.leveldb.util.Log
import java.io.File

object ElectingLevelDBStore extends Log {

  def machine_hostname: String = {
    import collection.JavaConversions._
    // Get the host name of the first non loop-back interface..
    for (interface <- NetworkInterface.getNetworkInterfaces; if (!interface.isLoopback); inet <- interface.getInetAddresses) {
      var address = inet.getHostAddress
      var name = inet.getCanonicalHostName
      if( address!= name ) {
        return name
      }
    }
    // Or else just go the simple route.
    return InetAddress.getLocalHost.getCanonicalHostName;
  }

}

/**
 *
 */
class ElectingLevelDBStore extends ProxyLevelDBStore {
  import ElectingLevelDBStore._

  def proxy_target = master

  @BeanProperty
  var zkAddress = "tcp://127.0.0.1:2888"
  @BeanProperty
  var zkPassword:String = _
  @BeanProperty
  var zkPath = "/default"
  @BeanProperty
  var zkSessionTmeout = "2s"

  var brokerName: String = _

  @BeanProperty
  var hostname: String = _
  @BeanProperty
  var bind = "tcp://0.0.0.0:61619"
  @BeanProperty
  var minReplica = 1
  @BeanProperty
  var securityToken = ""

  var directory = LevelDBStore.DEFAULT_DIRECTORY;
  override def setDirectory(dir: File) {
    directory = dir
  }
  override def getDirectory: File = {
    return directory
  }

  @BeanProperty
  var logSize: Long = 1024 * 1024 * 100
  @BeanProperty
  var indexFactory: String = "org.fusesource.leveldbjni.JniDBFactory, org.iq80.leveldb.impl.Iq80DBFactory"
  @BeanProperty
  var sync: Boolean = true
  @BeanProperty
  var verifyChecksums: Boolean = false
  @BeanProperty
  var indexMaxOpenFiles: Int = 1000
  @BeanProperty
  var indexBlockRestartInterval: Int = 16
  @BeanProperty
  var paranoidChecks: Boolean = false
  @BeanProperty
  var indexWriteBufferSize: Int = 1024 * 1024 * 6
  @BeanProperty
  var indexBlockSize: Int = 4 * 1024
  @BeanProperty
  var indexCompression: String = "snappy"
  @BeanProperty
  var logCompression: String = "none"
  @BeanProperty
  var indexCacheSize: Long = 1024 * 1024 * 256L
  @BeanProperty
  var flushDelay = 1000 * 5
  @BeanProperty
  var asyncBufferSize = 1024 * 1024 * 4
  @BeanProperty
  var monitorStats = false

  def cluster_size_quorum = minReplica + 1

  def cluster_size_max = (minReplica << 2) + 1

  var master: MasterLevelDBStore = _
  var slave: SlaveLevelDBStore = _

  var zk_client: ZKClient = _
  var zk_group: Group = _
  var master_elector: MasterElector = _

  var position: Long = -1L

  def init() {

    // Figure out our position in the store.
    directory.mkdirs()
    val log = new RecordLog(directory, LevelDBClient.LOG_SUFFIX)
    log.logSize = logSize
    log.open()
    position = try {
      log.current_appender.append_position
    } finally {
      log.close
    }

    zk_client = new ZKClient(zkAddress, Timespan.parse(zkSessionTmeout), null)
    if( zkPassword!=null ) {
      zk_client.setPassword(zkPassword)
    }
    zk_client.start
    zk_client.waitForConnected(Timespan.parse("30s"))

    val zk_group = ZooKeeperGroupFactory.create(zk_client, zkPath)
    val master_elector = new MasterElector(this)
    master_elector.start(zk_group)
    master_elector.join

    this.setUseLock(true)
    this.setLocker(createDefaultLocker())
  }

  def createDefaultLocker(): Locker = new Locker {

    def configure(persistenceAdapter: PersistenceAdapter) {}
    def setFailIfLocked(failIfLocked: Boolean) {}
    def setLockAcquireSleepInterval(lockAcquireSleepInterval: Long) {}
    def setName(name: String) {}

    def start()  = {
      master_started_latch.await()
    }

    def keepAlive(): Boolean = {
      master_started.get()
    }

    def stop() {}
  }


  val master_started_latch = new CountDownLatch(1)
  val master_started = new AtomicBoolean(false)

  def start_master(func: (Int) => Unit) = {
    assert(master==null)
    master = create_master()
    master.blocking_executor.execute(^{
      master_started.set(true)
      master.start();
      master_started_latch.countDown()
      func(master.getPort)
    })
  }

  def isMaster = master_started.get() && !master_stopped.get()

  val stopped_latch = new CountDownLatch(1)
  val master_stopped = new AtomicBoolean(false)

  def stop_master(func: => Unit) = {
    assert(master!=null)
    master.blocking_executor.execute(^{
      master.stop();
      master_stopped.set(true)
      position = master.wal_append_position
      stopped_latch.countDown()
      func
    })
  }

  protected def doStart() = {
    master_started_latch.await()
  }

  protected def doStop(stopper: ServiceStopper) {
    zk_client.close()
    zk_client = null
    if( master_started.get() ) {
      stopped_latch.countDown()
    }
  }

  def start_slave(address: String)(func: => Unit) = {
    assert(master==null)
    slave = create_slave()
    slave.connect = address
    slave.blocking_executor.execute(^{
      slave.start();
      func
    })
  }

  def stop_slave(func: => Unit) = {
    if( slave!=null ) {
      val s = slave
      slave = null
      s.blocking_executor.execute(^{
        s.stop();
        position = s.wal_append_position
        func
      })
    }
  }

  def create_slave() = {
    val slave = new SlaveLevelDBStore();
    configure(slave)
    slave
  }

  def create_master() = {
    val master = new MasterLevelDBStore
    configure(master)
    master.minReplica = minReplica
    master.bind = bind
    master
  }

  override def setBrokerName(brokerName: String): Unit = {
    this.brokerName = brokerName
  }

  def configure(store: ReplicatedLevelDBStoreTrait) {
    store.directory = directory
    store.indexFactory = indexFactory
    store.sync = sync
    store.verifyChecksums = verifyChecksums
    store.indexMaxOpenFiles = indexMaxOpenFiles
    store.indexBlockRestartInterval = indexBlockRestartInterval
    store.paranoidChecks = paranoidChecks
    store.indexWriteBufferSize = indexWriteBufferSize
    store.indexBlockSize = indexBlockSize
    store.indexCompression = indexCompression
    store.logCompression = logCompression
    store.indexCacheSize = indexCacheSize
    store.flushDelay = flushDelay
    store.asyncBufferSize = asyncBufferSize
    store.monitorStats = monitorStats
    store.securityToken = securityToken
    store.setBrokerName(brokerName)
    store.setBrokerService(brokerService)
  }

  def address(port: Int) = {
    if (hostname == null) {
      hostname = machine_hostname
    }
    "tcp://" + hostname + ":" + port
  }

}
