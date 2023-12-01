/*
 * Agile-Ant: The Executor-cache Manager
 *
 */

package org.apache.spark.agile_ant.executor_side

import scala.collection.mutable.{Map, Set}
import org.apache.spark.storage.BlockManager
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.storage.BlockId
import org.apache.spark.agile_ant.driver_side._
import org.apache.spark.agile_ant.MessageType // Agile-Ant: test

class ExecutorCacheManager{

  private var blockManager: BlockManager = null

  def init(blockManager: BlockManager): Unit = {
      this.blockManager = blockManager
  }


  // ###############################################################################################################################
  // ######################################################## Metrics List #########################################################
  // ###############################################################################################################################

  private var blockSizeMap: Map[BlockId, Long] = Map()
  def ProfileBlockSize(blockId: BlockId, size: Long): Unit = { blockSizeMap += (blockId -> size) }
  
  private var evictedBlockssSet : Set[BlockId] = Set()
  def ProfileEviction(blockId: BlockId): Unit = { evictedBlockssSet += blockId }
  
  private var faildToBeCachedBlockssSet : Set[BlockId] = Set()
  def ProfileFailedToBeCached(blockId: BlockId): Unit = { faildToBeCachedBlockssSet += blockId }

  private val blocksComputationTime: Map[BlockId, (Long, Long)] = Map()
  def ProfileBlockTaskIdAndComputationTime(blockId: BlockId, taskId: Long, computationTime: Long): Unit = { blocksComputationTime += (blockId -> (taskId, computationTime)) }
  def GetBlocksComputationTime() = blocksComputationTime

  private def clearMetricsSets(): Unit = {
    blockSizeMap.clear()
    evictedBlockssSet.clear()
    faildToBeCachedBlockssSet.clear()
    blocksComputationTime.clear()
  }

  def Send_Metrics_List_To_The_Cluster_Cache_Manager(): Unit = {
    
    var cachedBlocksSet: Set[BlockId] = Set()
    for((blockId, size) <- blockManager.GetCachedBlocksWithSizes()){
      cachedBlocksSet += blockId
      ProfileBlockSize(blockId, size)
      evictedBlockssSet -= blockId // This means: cached after beeing evicted
    }

    SendAMessageToTheClusterCacheManager(MessageType.MetricsList_Msg, blockManager.executorId.toInt, (cachedBlocksSet, evictedBlockssSet, faildToBeCachedBlockssSet, blocksComputationTime, blockSizeMap))

  }
  
  // ###############################################################################################################################
  // ####################################################### Cache Eviction ########################################################
  // ###############################################################################################################################

  private var cachedRddsPriorityMap: Map[Int, Int] = Map()
  private var rddChildParentSet : Set[(Int,Int)] = Set()

  def GetEvictionPriorityList(evictingBlockRDDId: Int): List[Int]= {
    var evictionPriorityList: List[Int] = List()

    val evictingBlockDatasetPriority: Int = cachedRddsPriorityMap.getOrElse(evictingBlockRDDId, -3)
    var priorityIterator : Int = -1

    // Firstly add RDDs with less priority than the evicting block RDD starting from the least i.e. -1 then 0 then 1
    while(priorityIterator < evictingBlockDatasetPriority){
      for((rddId, rddPriority) <- cachedRddsPriorityMap){
        if(rddPriority == priorityIterator){
          evictionPriorityList = evictionPriorityList :+ rddId
        }
      }
      priorityIterator = priorityIterator + 1
    }

    // Secondly add RDDs with an equal priority to the evicting block RDD and that are children of the evicting block RDD
    for((rddId, rddPriority) <- cachedRddsPriorityMap){
      if(rddPriority == evictingBlockDatasetPriority && AutoCacher.IsChildParentDependancy(rddId, evictingBlockRDDId, rddChildParentSet)){
        evictionPriorityList = evictionPriorityList :+ rddId
      }
    }

    evictionPriorityList
  }

  // ###############################################################################################################################
  // ###################################################### Blocks Migration #######################################################
  // ###############################################################################################################################


  private var migrationRatio: Double = 0.0
  
  
  def calculateAverageComputationTimeOfOneMBOfCachedBlocks(): Double = {
    var totalComputationTimeOfCachedBlocks: Double = 0
    var totalSizeOfCachedBlocksMB: Double = 0
    val memoryStore = blockManager.GetMemoryStore()
    val cachedBlocksWithSizes = memoryStore.GetCachedBlocksWithSizes()
    var rddComputationTimeOfOneMB: Map[Int, Double] = Map()
    for((cachedBlockId, cachedBlockSize) <- cachedBlocksWithSizes){
      val blockSizeMB: Double = cachedBlockSize.toDouble/(1024*1024)
      var computationTimeOfOneMBOfTheBlock = rddComputationTimeOfOneMB.getOrElse(cachedBlockId.asRDDId.get.rddId, -1.0)
      if(computationTimeOfOneMBOfTheBlock == -1.0){
        computationTimeOfOneMBOfTheBlock = getComputationTimeOfOneMBOfRdd(cachedBlockId.asRDDId.get.rddId) 
        rddComputationTimeOfOneMB += (cachedBlockId.asRDDId.get.rddId -> computationTimeOfOneMBOfTheBlock)
      }
      if(computationTimeOfOneMBOfTheBlock != -1.0){
        totalComputationTimeOfCachedBlocks += (blockSizeMB * computationTimeOfOneMBOfTheBlock)
        totalSizeOfCachedBlocksMB += blockSizeMB
      }
    }
    return totalComputationTimeOfCachedBlocks / totalSizeOfCachedBlocksMB
  }

  private var selectedBlocksForMigrationSet : scala.collection.mutable.Set[BlockId] = scala.collection.mutable.Set() // // since the same executor might migrate blocks to multiple receivers simultaneously using multiple threads, each thread stores its selected blocks for migration in order to block them from being selected by another thread
  def migrateCachedBlocks(peer: BlockManagerId, totalBytesToMigrate: Long): Unit = {
    try{
      var sizeOfMigratedBlocks: Long = 0
      var stillTry: Boolean = true
      val blockInfoManager = blockManager.GetBlockInfoManager()
      val memoryStore = blockManager.GetMemoryStore()
      val averageComputationTimeOfOneMBOfCachedBlocks = calculateAverageComputationTimeOfOneMBOfCachedBlocks()
        
      var counter: Int = 0
      var isFirstBlock: Boolean = true
      var isEfficientMigration: Boolean = true
      while (totalBytesToMigrate > sizeOfMigratedBlocks && stillTry && counter < 100){
        counter += 1
        var selectedBlocksForThisMigrationSet : Set[BlockId] = Set()
        blockInfoManager.synchronized{
          val blocksForMigration = memoryStore.GetBlocksToMigrate(selectedBlocksForMigrationSet /* we send the set to avoid selecting an already selected block for migration*/)
          for(blockId <- blocksForMigration._1){
            selectedBlocksForMigrationSet += blockId
            selectedBlocksForThisMigrationSet += blockId
            
          }
          sizeOfMigratedBlocks += blocksForMigration._2 
          if(blocksForMigration._1.size == 0){
            stillTry = false
          }
        }
        var stillAllBlocksAvailable: Boolean = true
        for(blockId <- selectedBlocksForThisMigrationSet){
          if(blockInfoManager.isLocked(blockId) || !memoryStore.contains(blockId)){
            stillAllBlocksAvailable = false
          }
        }
        if(stillAllBlocksAvailable){
          var isSucceed: Boolean = true
          for(blockId <- selectedBlocksForThisMigrationSet){
            if(isSucceed){
              blockId.synchronized{
                if(!blockInfoManager.isLocked(blockId) && memoryStore.contains(blockId)){
                  if(!isEfficientMigration){
                    removeABlock(blockId) // Evict the block without beeing migrated
                  }
                  else
                  {
                    val startTimeMs = System.currentTimeMillis
                    isSucceed = blockManager.migrateABlockToExecutor(blockId, peer)
                    val endTimeMs = System.currentTimeMillis
                    if(isSucceed){
                      val blockSizeBytes: Long = memoryStore.GetBlockSize(blockId)
                      val blockSizeMB: Double = blockSizeBytes.toDouble / (1024 * 1024)
                      removeABlock(blockId) // Evict the block after beeing migrated
                      if(isFirstBlock && blockSizeBytes != 0){
                        isFirstBlock = false // the migration efficiency is checked only once per migration round on each executor. If a sender sends to two newly added executors in the same migration round, this check is done for each receiver (executor). This is because the receivers might be located on the same host, same rack or different racks. Thus, if migration to one receiver is not efficient, it does not mean that this is the case for all the others
                        val migrationTimeMs = endTimeMs - startTimeMs
                        val migrationTimeMsOfOneMB: Double = migrationTimeMs.toDouble / blockSizeMB
                        if (averageComputationTimeOfOneMBOfCachedBlocks < migrationTimeMsOfOneMB){
                          isEfficientMigration = false // we decide to not migrate blocks anymore because the cost of recomputing them is less than that of migrating them
                        }
                      }
                    }
                  }
                  def removeABlock(blockId: BlockId): Unit = { // before evicting the block, we remove all its references
                    selectedBlocksForMigrationSet -= blockId
                    selectedBlocksForThisMigrationSet -= blockId
                    blockSizeMap -= blockId
                    evictedBlockssSet -= blockId
                    blocksComputationTime -= blockId
                    blockManager.removeBlock(blockId) // evict the block after being migrated
                  }
                }    
              }
            }
          }
        }
      }
    }
    finally{
      SendAMessageToTheClusterCacheManager(MessageType.NotifyMigrationEnded_Msg, blockManager.executorId.toInt, peer.executorId.toInt)
    } 
  }

  private def migrateCachedBlocks(peer: BlockManagerId): Unit = {
    val totalBytesToMigrate: Long = math.floor(blockManager.GetTotalSizeOfCachedBlocks() * migrationRatio).toLong
    if(totalBytesToMigrate > 0){
      migrateCachedBlocks(peer, totalBytesToMigrate)
    }
  }

  private var computationTimeOfOneMBOfAnRdd: scala.collection.immutable.Map[Int, Double] = scala.collection.immutable.Map()
  private def getComputationTimeOfOneMBOfRdd(rddId: Int): Double = {
    
    if(computationTimeOfOneMBOfAnRdd.contains(rddId)){ // Firstly, we use the computation time of one MB (i.e. benefit) obtained from the Cluster-cache Manager
      return computationTimeOfOneMBOfAnRdd(rddId)
    }

    // Secondly, if the Cluster-cache Manager does not have the metrics of this Rdd, it means that the Rdd is a recently cached Rdd that is either non-beneficial or not yet evaluated. In the following, we use the cachedRddsPriorityMap to check
    val rddPriority: Int = cachedRddsPriorityMap.getOrElse(rddId, -3)

    rddPriority match {
      case -3 => return -1.0 // not found in the Priority list. This is a problem 
      case -1 => return -1.0 // recently evaluated as non beneficial RDD
      case 0 => // recent Rdd but not evaluated yet. This means that the cached blocks are computed in the current job and we can collect their metrics from blocksComputationTime and blockSizeMap

      var rddBlocksTotalSizeBytes: Long = 0
      for((blockId, blockSize) <- blockSizeMap){
        if(blockId.asRDDId.get.rddId == rddId){
          rddBlocksTotalSizeBytes += blockSize
        }
      }

      if(rddBlocksTotalSizeBytes == 0){
        return -1.0
      }
      val rddBlocksTotalSizeMB: Double = rddBlocksTotalSizeBytes.toDouble / 1024

      var rddBlocksComputationTimeMs: Long = 0
      for((blockId, blockMetrics) <- blocksComputationTime){
        if(blockId.asRDDId.get.rddId == rddId){
          rddBlocksComputationTimeMs += blockMetrics._2
        }
      }

      return rddBlocksTotalSizeBytes.toDouble / rddBlocksTotalSizeMB
    }
  }

  // ###############################################################################################################################
  // ###################################### Message exchange with the Cluster-cache Manager ########################################
  // ###############################################################################################################################  

  private def handle_Step_1_Msg(messageContent: Any): Unit = {
    this.clearMetricsSets()
    val (beneficialDatasetIds_Map, rddChildParent_Set, recentlyCachedDatasetsWithTargetNumberOfBlocks_Map, expectedExecutorStorageCapacity) = messageContent.asInstanceOf[Tuple4[Map[Int, Double], Set[(Int,Int)], Map[Int, Int], Long]]
      blockManager.updateCachingProfiles(recentlyCachedDatasetsWithTargetNumberOfBlocks_Map, expectedExecutorStorageCapacity)
      for((beneficialDatasetId, computationTimeOfOneMB) <- beneficialDatasetIds_Map){
        cachedRddsPriorityMap += (beneficialDatasetId -> 2)
        cachedRddsPriorityMap(beneficialDatasetId) = 2
        computationTimeOfOneMBOfAnRdd -= beneficialDatasetId
        computationTimeOfOneMBOfAnRdd += (beneficialDatasetId -> computationTimeOfOneMB)
      }
      this.rddChildParentSet = rddChildParent_Set
      for((recentlyCachedDatasetId, targetNumberOfBlocks) <- recentlyCachedDatasetsWithTargetNumberOfBlocks_Map){
        cachedRddsPriorityMap += (recentlyCachedDatasetId -> 0)
      }
  }

  private def handle_Migration_Ratio_And_Early_Evaluation_Results_Msg(messageContent: Any): Unit = {
    val (earlyEvaluatedBeneficialDatasetsIds_Map, earlyEvaluatedNonBeneficialDatasetsIds_Set, migration_Ratio) = messageContent.asInstanceOf[Tuple3[Map[Int, Double], scala.collection.immutable.Set[Int], Double]]
    this.migrationRatio = migration_Ratio 
    for((earlyEvaluatedBeneficialDatasetId, computationTimeOfOneMB) <- earlyEvaluatedBeneficialDatasetsIds_Map){
      cachedRddsPriorityMap += (earlyEvaluatedBeneficialDatasetId -> 1)
      cachedRddsPriorityMap(earlyEvaluatedBeneficialDatasetId) = 1
      computationTimeOfOneMBOfAnRdd -= earlyEvaluatedBeneficialDatasetId
      computationTimeOfOneMBOfAnRdd += (earlyEvaluatedBeneficialDatasetId -> computationTimeOfOneMB)
    }

    for(earlyEvaluatedNonBeneficialDatasetId <- earlyEvaluatedNonBeneficialDatasetsIds_Set){
      cachedRddsPriorityMap += (earlyEvaluatedNonBeneficialDatasetId -> -1)
      cachedRddsPriorityMap(earlyEvaluatedNonBeneficialDatasetId) = -1
    }
  }

  private def handle_NotifyOldExecutorsIdsAboutTheNewExecutor_Msg(messageContent: Any): Unit = {
    val newBlockManagerId = messageContent.asInstanceOf[BlockManagerId]
      val thread = new Thread {
        override def run {
            migrateCachedBlocks(newBlockManagerId)
        }
      }
      thread.start
  }

  private def handle_BorrowRemoteMemory_Msg(messageContent: Any): Unit = {
    val (peer, requiredMemory) = messageContent.asInstanceOf[Tuple2[BlockManagerId, Long]]
      val thread = new Thread {
        override def run {
          migrateCachedBlocks(peer, requiredMemory)
        }
      }
      thread.start
  }

  def ReceiveAMessageFromTheClusterCacheManager(messageType: MessageType.MessageType, messageContent: Any): Unit = {
    messageType match {
      case MessageType.Step_1_Msg => this.handle_Step_1_Msg(messageContent)
      case MessageType.Migration_Ratio_And_Early_Evaluation_Results_Msg => this.handle_Migration_Ratio_And_Early_Evaluation_Results_Msg(messageContent)
      case MessageType.NotifyOldExecutorsIdsAboutTheNewExecutor_Msg => this.handle_NotifyOldExecutorsIdsAboutTheNewExecutor_Msg(messageContent)
      case MessageType.BorrowRemoteMemory_Msg => this.handle_BorrowRemoteMemory_Msg(messageContent)
    }
  }
  
  def SendAMessageToTheClusterCacheManager(messageType: MessageType.MessageType, executorId: Int, messageContent: Any): Unit = {
      this.blockManager.master.SendAMessageToTheClusterCacheManager(messageType, executorId, messageContent)
      
  }
}