/*
 * Agile-Ant: Agile-Ant Utilities
 *
 */

package org.apache.spark.agile_ant

import org.apache.spark.rdd.RDD
import scala.collection.mutable.{HashMap, Map}
import org.apache.spark.storage.BlockId

object AgileAntUtilities {
  
  def IncrementMapValue(map: HashMap[Int, Int], key: Int): Unit = if (map.contains(key)) map(key) += 1 else map += (key -> 1)
  
  def AddOrUpdateMapValueIfLarger(map: HashMap[String, Int], key: String, value: Int): Unit = {
    if(!map.contains(key)) 
      map += ( key -> value )
    else if(map.contains(key) && map.get(key).get < value) 
      map += ( key -> value )
  }
  
  def GetRddCallsite(rdd: RDD[_]): String = "%s at %s".format(Option(rdd.name).map(_ + " ").getOrElse(""), rdd.getCreationSite)

  def IsCached(rdd: RDD[_]): Boolean = rdd.getStorageLevel.useMemory

  def GetRddIdOfABlock(blockId: BlockId): Int = blockId.asRDDId.get.rddId

  def AddOrInsertMapValue(map: Map[Int, Long], key: Int, value: Long): Unit = if (map.contains(key)) map(key) += value else map += (key -> value)

  def IncrementMapValue(map: Map[Int, Int], key: Int): Unit = if (map.contains(key)) map(key) += 1 else map += (key -> 1)
}