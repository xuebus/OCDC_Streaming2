package com.asiainfo.ocdc.streaming.tool

import java.nio.ByteBuffer
import java.util
import java.util.concurrent.FutureTask

import com.asiainfo.ocdc.streaming.MainFrameConf
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis

import scala.collection.convert.wrapAsJava.mapAsJavaMap
import scala.collection.convert.wrapAsScala._
import scala.collection.mutable.Map

trait CacheManager extends org.apache.spark.Logging {

  def getHashCacheList(key: String): List[String]

  def getHashCacheMap(key: String): Map[String, String]

  def getHashCacheString(key: String): String

  def setHashCacheList(key: String, value: List[String])

  def setHashCacheMap(key: String, value: Map[String, String])

  def setHashCacheString(key: String, value: String)

  def getCommonCacheMap(key: String): Map[String, String]

  def getCommonCacheList(key: String): List[String]

  def getCommonCacheValue(cacheName: String, key: String): String

  def setByteCacheString(key: String, value: Any)

  def getByteCacheString(key: String): Any

  def setMultiCache(keysvalues: Map[String, Any])

  def getMultiCacheByKeys(keys: List[String]): Map[String, Any]

  def setCommonCacheValue(cacheName: String, key: String, value: String)

  def hgetall(keys: List[String]): Map[String, Map[String, String]]

  def hmset(keyValues: Map[String, Map[String, String]])
}


abstract class RedisCacheManager extends CacheManager {

  val logger = LoggerFactory.getLogger(this.getClass)

  private val currentJedis = new ThreadLocal[Jedis] {
    override def initialValue = getResource
  }

  private val currentKryoTool = new ThreadLocal[KryoSerializerStreamAppTool] {
    override def initialValue = new KryoSerializerStreamAppTool
  }

  //  private val currentJedis = getResource
  final def openConnection = currentJedis.set(getResource)

  final def getConnection = {
    val curr_jedis = currentJedis.get()
    curr_jedis
  }

  final def getKryoTool = currentKryoTool.get()

  //final def getConnection = currentJedis

  final def closeConnection = {
    getConnection.close()
    currentJedis.remove()
  }

  def getResource: Jedis

  override def getHashCacheList(key: String): List[String] = {
    getConnection.lrange(key, 0, -1).toList
  }

  override def getHashCacheMap(key: String): Map[String, String] = {
    val t1 = System.currentTimeMillis()
    val value = getConnection.hgetAll(key)
    System.out.println("GET 1 key from userinfo cost " + (System.currentTimeMillis() - t1))
    value
  }

  override def setHashCacheString(key: String, value: String): Unit = {
    getConnection.set(key, value)
  }

  override def getCommonCacheValue(cacheName: String, key: String): String = {
    val t1 = System.currentTimeMillis()
    val value = getConnection.hget(cacheName, key)
    System.out.println("GET 1 key from lacci cost " + (System.currentTimeMillis() - t1))
    value
  }

  override def getHashCacheString(key: String): String = {
    getConnection.get(key)
  }

  override def getCommonCacheMap(key: String): Map[String, String] = {
    getConnection.hgetAll(key)
  }

  override def getCommonCacheList(key: String): List[String] = {
    getConnection.lrange(key, 0, -1).toList
  }

  override def setHashCacheMap(key: String, value: Map[String, String]): Unit = {
    val t1 = System.currentTimeMillis()
    getConnection.hmset(key, mapAsJavaMap(value))
    System.out.println("SET 1 key cost " + (System.currentTimeMillis() - t1))
  }

  override def setHashCacheList(key: String, value: List[String]): Unit = {
    value.map { x => getConnection.rpush(key, x)}
  }

  override def setByteCacheString(key: String, value: Any) {
    val t1 = System.currentTimeMillis()
    val r = getConnection.set(key.getBytes, getKryoTool.serialize(value).array())
    System.out.println("SET 1 key cost " + (System.currentTimeMillis() - t1))
  }

  override def getByteCacheString(key: String): Any = {
    val t1 = System.currentTimeMillis()
    val bytekey = key.getBytes
    val cachedata = getConnection.get(bytekey)

    val t2 = System.currentTimeMillis()
    System.out.println("GET 1 key cost " + (t2 - t1))

    if (cachedata != null) {
      getKryoTool.deserialize[Any](ByteBuffer.wrap(cachedata))
    }
    else null

  }

  //old method
  /*  override def setMultiCache(keysvalues: Map[String, Any]) {
      val t1 = System.currentTimeMillis()
      val seqlist = new ArrayList[Array[Byte]]()
      val it = keysvalues.keySet.iterator
      while (it.hasNext) {
        val elem = it.next()
        seqlist.add(elem.getBytes)
        seqlist.add(getKryoTool.serialize(keysvalues(elem)).array())
      }
      val r = getConnection.mset(seqlist.toIndexedSeq: _*)
      System.out.println("MSET " + keysvalues.size + " key cost " + (System.currentTimeMillis() - t1))
      r
    }*/

  //new method for pipeline
  /*override def setMultiCache(keysvalues: Map[String, Any]) {
    val t1 = System.currentTimeMillis()
    val it = keysvalues.keySet.iterator
    val pl = getConnection.pipelined()
    while (it.hasNext) {
      val elem = it.next()
      pl.set(elem.getBytes, getKryoTool.serialize(keysvalues(elem)).array())
    }
    pl.sync()
    System.out.println("MSET " + keysvalues.size + " key cost " + (System.currentTimeMillis() - t1))
  }*/

  //new set method for pipeline by multiThread
  override def setMultiCache(keysvalues: Map[String, Any]) {
    val t1 = System.currentTimeMillis()
    val miniBatch = MainFrameConf.getInt("pipeLineBatch")
    val taskMap = Map[Int, FutureTask[String]]()
    var index = 0
    var innermap = keysvalues
    while (innermap.size > 0) {
      val settask = new Insert(innermap.take(miniBatch))
      val futuretask = new FutureTask[String](settask)
      CacheQryThreadPool.threadPool.submit(futuretask)
      taskMap.put(index, futuretask)
      innermap = innermap.drop(miniBatch)
      index += 1
    }

    println("start thread : " + index)

    while (taskMap.size > 0) {
      val keys = taskMap.keys
      keys.foreach(key => {
        val task = taskMap.get(key).get
        if (task.isDone) {
          taskMap.remove(key)
        }
      })
    }

    System.out.println("MSET " + keysvalues.size + " key cost " + (System.currentTimeMillis() - t1))
  }

  //  override def setMultiCache(keysvalues: Map[String, Any]) {
  //    val t1 = System.currentTimeMillis()
  //    val it = keysvalues.keySet.iterator
  //    while (it.hasNext) {
  //      val elem = it.next()
  //      new Thread() {
  //        override def run() {
  //          getConnection.set(elem.getBytes,getKryoTool.serialize(keysvalues(elem)).array())
  //        }}.start()
  //    }
  //    System.out.println("MSETTest " + keysvalues.size + " key cost " + (System.currentTimeMillis() - t1))
  //  }


  //old method
  /*override def getMultiCacheByKeys(keys: List[String]): Map[String, Any] = {
    val t1 = System.currentTimeMillis()
    val multimap = Map[String, Any]()
    val bytekeys = keys.map(x => x.getBytes).toSeq
    var i = 0
    val cachedata: util.List[Array[Byte]] = getConnection.mget(bytekeys: _*)

    val t2 = System.currentTimeMillis()
    System.out.println("MGET " + keys.size + " key cost " + (t2 - t1))

    val anyvalues = cachedata.map(x => {
      if (x != null && x.length > 0) {
        val data = getKryoTool.deserialize[Any](ByteBuffer.wrap(x))
        data
      }
      else null
    }).toList

    for (i <- 0 to keys.length - 1) {
      multimap += (keys(i) -> anyvalues(i))
    }

    System.out.println("DESERIALIZED " + keys.size + " key cost " + (System.currentTimeMillis() - t2))

    multimap
  }*/

  //new method for pipeline
  /*override def getMultiCacheByKeys(keys: List[String]): Map[String, Any] = {
    val t1 = System.currentTimeMillis()
    val multimap = Map[String, Any]()
    val bytekeys = keys.map(x => x.getBytes).toSeq

    val pgl = getConnection.pipelined()
    bytekeys.foreach(x => pgl.get(x))
    val cachedata = pgl.syncAndReturnAll().asInstanceOf[util.List[Array[Byte]]]

    val t2 = System.currentTimeMillis()
    System.out.println("MGET " + keys.size + " key cost " + (t2 - t1))

    val anyvalues = cachedata.map(x => {
      if (x != null && x.length > 0) {
        val data = getKryoTool.deserialize[Any](ByteBuffer.wrap(x))
        data
      }
      else null
    }).toList

    for (i <- 0 to keys.length - 1) {
      multimap += (keys(i) -> anyvalues(i))
    }

    System.out.println("DESERIALIZED " + keys.size + " key cost " + (System.currentTimeMillis() - t2))

    multimap
  }*/

  //new get method for pipeline by multiThread
  override def getMultiCacheByKeys(keys: List[String]): Map[String, Any] = {
    val multimap = Map[String, Any]()
    var bytekeys = keys.map(x => x.getBytes).toSeq

    val miniBatch = MainFrameConf.getInt("pipeLineBatch")

    val taskMap = Map[Int, FutureTask[util.List[Array[Byte]]]]()
    var index = 0
    while (bytekeys.size > 0) {
      val qrytask = new Qry(bytekeys.take(miniBatch))
      val futuretask = new FutureTask[util.List[Array[Byte]]](qrytask)
      CacheQryThreadPool.threadPool.submit(futuretask)
      taskMap.put(index, futuretask)

      bytekeys = bytekeys.drop(miniBatch)
      index += 1
    }

    println("start thread : " + index)
    val cachedata = Map[Int, util.List[Array[Byte]]]()

    var errorFlag = false
    while (taskMap.size > 0) {
      val keys = taskMap.keys
      keys.foreach(key => {
        val task = taskMap.get(key).get
        if (task.isDone) {
          try {
            cachedata += (key -> task.get())
          } catch {
            case e: Exception => {
              logger.error("= = " * 15 + "found error in  RedisCacheManager.getMultiCacheByKeys")
              errorFlag = true
              e.printStackTrace()
            }
          } finally {
            // 查询线程失败，如何处理恢复
            cachedata += (key -> null)
            taskMap.remove(key)
          }
        }
      })
    }
    if(errorFlag) logger.error("= = " * 15 +"out of loop with errorFlag = " + errorFlag)

    val flatdata = new util.ArrayList[Array[Byte]]()

    cachedata.toList.sortBy(_._1).map(_._2).foreach(x => {
      x.foreach(flatdata.add(_))
    })

    val anyvalues = flatdata.map(x => {
      if (x != null && x.length > 0) {
        val data = getKryoTool.deserialize[Any](ByteBuffer.wrap(x))
        data
      }
      else null
    }).toList

    for (i <- 0 to keys.length - 1) {
      multimap += (keys(i) -> anyvalues(i))
    }
    multimap
  }

  override def setCommonCacheValue(cacheName: String, key: String, value: String) = {
    getConnection.hset(cacheName, key, value)
  }


  //new get method for pipeline by multiThread
  override def hgetall(keys: List[String]): Map[String, Map[String, String]] = {
    val multimap = Map[String, Map[String, String]]()
    var bytekeys = keys.toSeq

    val miniBatch = MainFrameConf.getInt("pipeLineBatch")

    val taskMap = Map[Int, FutureTask[util.List[util.Map[String, String]]]]()
    var index = 0
    while (bytekeys.size > 0) {
      val qrytask = new QryHashall(bytekeys.take(miniBatch))
      val futuretask = new FutureTask[util.List[util.Map[String, String]]](qrytask)
      CacheQryThreadPool.threadPool.submit(futuretask)
      taskMap.put(index, futuretask)

      bytekeys = bytekeys.drop(miniBatch)
      index += 1
    }

    val cachedata = Map[Int, util.List[util.Map[String, String]]]()

    println("hgetall start thread : " + index)

    var errorFlag = false
    while (taskMap.size > 0) {
      val keys = taskMap.keys
      keys.foreach(key => {
        val task = taskMap.get(key).get
        if (task.isDone) {
          try {
            cachedata += (key -> task.get())
          } catch {
            case e: Exception => {
              logger.error("= = " * 15 + "found error in  RedisCacheManager.hgetall")
              errorFlag = true
              e.printStackTrace()
            }
          } finally {
            // 查询线程失败，如何处理恢复
            val emptyList: util.List[util.Map[String, String]] = new util.ArrayList[util.Map[String, String]]()
            cachedata += (key -> emptyList)
            taskMap.remove(key)
          }
        }
      })
    }

    val flatdata = new util.ArrayList[util.Map[String, String]]()

    cachedata.toList.sortBy(_._1).map(_._2).foreach(x => {
      x.foreach(flatdata.add(_))
    })

    for (i <- 0 to keys.length - 1) {
      multimap += (keys(i) -> flatdata(i))
    }
    multimap
  }


  def hmset(keyValues: Map[String, Map[String, String]]) {
    val t1 = System.currentTimeMillis()

    val miniBatch = MainFrameConf.getInt("pipeLineBatch")
    val taskMap = Map[Int, FutureTask[String]]()
    var index = 0
    var innermap = keyValues
    while (innermap.size > 0) {
      val settask = new InsertHash(innermap.take(miniBatch))
      val futuretask = new FutureTask[String](settask)
      CacheQryThreadPool.threadPool.submit(futuretask)
      taskMap.put(index, futuretask)
      innermap = innermap.drop(miniBatch)
      index += 1
    }

    println("start thread : " + index)

    while (taskMap.size > 0) {
      val keys = taskMap.keys
      keys.foreach(key => {
        val task = taskMap.get(key).get
        if (task.isDone) {
          taskMap.remove(key)
        }
      })
    }

    System.out.println("MSET " + keyValues.size + " key cost " + (System.currentTimeMillis() - t1))
  }
}
