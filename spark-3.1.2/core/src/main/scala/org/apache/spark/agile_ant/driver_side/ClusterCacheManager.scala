/*
 * Agile-Ant: The Cluster-cache Manager
 *
 */

package org.apache.spark.agile_ant.driver_side

import scala.collection.mutable.{Map, ListBuffer}
import scala.collection.immutable.ListMap
import org.apache.spark.storage._
import org.apache.spark.SparkContext
import org.apache.spark.agile_ant.AgileAntConf
import org.apache.spark.agile_ant.MessageType
import org.apache.spark.agile_ant.AgileAntUtilities

class RddCacheBenefit (var id: Int, var from: Int, var benefit: Double, var computeTime: Double, var numberOfPartitions: Int, var size: Long)
  
object ClusterCacheManagerFactHub {
  val RddsMetricsMap: Map[Int, RddMetrics] = Map()
}

object ClusterCacheManager {

  private var jobId = 0
  private val executorCachedBlocksMap: Map[Int, Map[BlockId, Long]] = Map()

  private var sc: SparkContext = null
  private var VOTING_THRESHOLD: Double = 0.33
  def init(sc: SparkContext): Unit = {
    this.sc = sc
    VOTING_THRESHOLD = AgileAntConf.GetAgileAntVotingThreshold
  }

// ###############################################################################################################################
// ########################### Asynchrnous events receiving and filling Metrics List and filling raw maps ########################
// ###############################################################################################################################

  private var blocksComputationMap:Map[BlockId, (Long, Long)] = Map()
  private val executorFailedCachingBlocksMap: Map[Int, Map[BlockId, Long]] = Map()
  private val executorEvictedBlocksMap: Map[Int, Map[BlockId, Long]] = Map()
  private def receiveMetricsList(executorId: Int, cachedBlocksSet: scala.collection.mutable.Set[BlockId], evictedBlocksSet: scala.collection.mutable.Set[BlockId], faildToBeCachedBlocksSet: scala.collection.mutable.Set[BlockId], blocksComputationTimesMap: Map[BlockId, (Long, Long)], blockSizeMap: Map[BlockId, Long]){ // at the end of the job execution, the Cluster-cache Manager receives the Metrics List of each executor (Sect. 7.2 of the paper) and store these metrics in its raw maps. The raw maps are executorCachedBlocksMap, executorEvictedBlocksMap, failedCachingBlocksWithSize and blocksComputationMap
    executorCachedBlocksMap -= executorId
    var cachedBlocksWithSize: Map[BlockId, Long] = Map()
    for(blockId <- cachedBlocksSet){
      cachedBlocksWithSize += (blockId -> blockSizeMap(blockId))
    }
    if(cachedBlocksWithSize.size > 0){
      executorCachedBlocksMap += (executorId -> cachedBlocksWithSize)
    }

    executorEvictedBlocksMap -= executorId
    var evictedBlocksWithSize: Map[BlockId, Long] = Map()
    for(blockId <- evictedBlocksSet){
      evictedBlocksWithSize += (blockId -> blockSizeMap(blockId))
    }
    if(evictedBlocksWithSize.size > 0){
      executorEvictedBlocksMap += (executorId -> evictedBlocksWithSize)
    }

    executorFailedCachingBlocksMap -= executorId
    var failedCachingBlocksWithSize: Map[BlockId, Long] = Map()
    for(blockId <- faildToBeCachedBlocksSet){
      failedCachingBlocksWithSize += (blockId -> blockSizeMap(blockId))
    }
    if(failedCachingBlocksWithSize.size > 0){
      executorFailedCachingBlocksMap += (executorId -> failedCachingBlocksWithSize)
    }

    blocksComputationMap = blocksComputationMap.++(blocksComputationTimesMap)
  }

  private val taskInfoMap: Map[Long, Long] = Map() // in this map, we store the execution time of each task. This metrics help us in calculating the benefitRatio of each RDD (Eq. 2 of the paper)
  def SetTaskInfo(attemptId: Long, duration: Long): Unit = { 
    taskInfoMap += (attemptId -> duration)
  }

// ###############################################################################################################################
// ########################## Analyze raw maps and extract information for auto scaling and auto caching components ##############
// ###############################################################################################################################

  private var rddBlocksNumberMap: Map[Int, Int] = Map()
  
  private var rddEstimatedSizeMBMap:Map[Int, Long] = Map()

  private var doesEvictionOrFailureToCacheOccurInTheCurrentJob: Boolean = false // we consider it that there are overloaded executors when this becomes true

  private var executorBlocksSizeMap:Map[Int, Long] = Map() // in this map, we store the total size of cached blocks in each executor. We use this map for data migration among executors (i.e. borrow and balance) and to determine the actual executor cache size
  
  private var totalSizeOfCachedPartitionsAtJobEnd: Long = 0
  private var totalSizeOfNonCachedPartitionsAtJobEnd: Long = 0

  private var executorBlocksSizeForMigrationMap:Map[Int, Long] = Map()

  private def calculateStatisticsMapsAndTakeAction(): Unit = { 
    
    this.votesCounter = 0
    this.totalRequiredMemoryForVotingExecutors = 0
    this.predictedRddFullTargetSizeMap.clear()
    
    executorBlocksSizeMap.clear    
    rddBlocksNumberMap.clear
    rddEstimatedSizeMBMap.clear

    doesEvictionOrFailureToCacheOccurInTheCurrentJob = executorEvictedBlocksMap.size > 0 || executorFailedCachingBlocksMap.size > 0

    // ****************************************************************************************************************
    // Calculate the size of each RDD and the total size of cached blocks per executor, based on the Metrics List
    // ****************************************************************************************************************
    for ((k,v) <- executorCachedBlocksMap){
      for ((blockId, blockSize) <- v){
        var rddId: Int = AgileAntUtilities.GetRddIdOfABlock(blockId)
        if(AutoCacher.IsRddCached(rddId)){
          AgileAntUtilities.AddOrInsertMapValue(executorBlocksSizeMap, k, blockSize)
          AgileAntUtilities.IncrementMapValue(rddBlocksNumberMap, rddId)
          AgileAntUtilities.AddOrInsertMapValue(rddEstimatedSizeMBMap, rddId, (blockSize/(1024*1024)))
        }
      }
    }
    
    for ((k,v) <- executorFailedCachingBlocksMap){
      for ((blockId, blockSize) <- v){
        var rddId: Int = AgileAntUtilities.GetRddIdOfABlock(blockId)
        if(AutoCacher.IsRddCached(rddId)){
          AgileAntUtilities.IncrementMapValue(rddBlocksNumberMap, rddId)
          AgileAntUtilities.AddOrInsertMapValue(rddEstimatedSizeMBMap, rddId, (blockSize/(1024*1024)))
        }
      }
    }

    for ((k,v) <- executorEvictedBlocksMap){
      for ((blockId, blockSize) <- v){
        var rddId: Int = AgileAntUtilities.GetRddIdOfABlock(blockId)
        if(AutoCacher.IsRddCached(rddId)){
          var cachedAfterBeeingEvictedFlag: Boolean = false
          for ((executorId,cachedBlocksMap) <- executorCachedBlocksMap){
            if(cachedBlocksMap.keySet.contains(blockId)) { // cached after being evicted
              cachedAfterBeeingEvictedFlag = true
            }
          }
          if(!cachedAfterBeeingEvictedFlag)
          {
            AgileAntUtilities.IncrementMapValue(rddBlocksNumberMap, rddId)
            AgileAntUtilities.AddOrInsertMapValue(rddEstimatedSizeMBMap, rddId, (blockSize/(1024*1024)))
          }
        }
      }
    }
    
    removeExecutorsWithEmptyCache()

    // ****************************************************************************************************************
    // Knowing the total size of cached blocks in each executor, conduct borrow and balance procedures.
    // ****************************************************************************************************************
    
    if(this.currentMigrationSendingNumber == 0){ // if there is no blocks migration round, borrow remote cache and balance executor's cache is possible
      var overLoadedExecutors: Map[Int, Long] = Map() 
      var lessLoadedExecutors: Map[Int, Long] = Map() 

      for((executorId, sizeOfCachedBlocks) <- executorBlocksSizeMap){
        if(executorEvictedBlocksMap.contains(executorId) || executorFailedCachingBlocksMap.contains(executorId)){ // we consider an executor as overloaded in a job if it evicts a block or more, or fails to cache a block or more
          overLoadedExecutors += (executorId -> sizeOfCachedBlocks)
        }
        else{
          lessLoadedExecutors += (executorId -> sizeOfCachedBlocks)
        }
      }

      if(overLoadedExecutors.size > 0 && lessLoadedExecutors.size > 0){
        borrowRemoteMemoryForOverloadedExecutors(overLoadedExecutors, lessLoadedExecutors)
      }
      else{
        balanceStorageIfNeeded()
      }
    }
    
    executorCachedBlocksMap.clear
    executorFailedCachingBlocksMap.clear
    executorEvictedBlocksMap.clear
  }

  private def removeExecutorsWithEmptyCache(): Unit = {
    
    var idsOfExecutorsForRemoval : Set[Int] = Set()

    for((executorId, sizeOfCachedBlocks) <- executorBlocksSizeMap){
      if (sizeOfCachedBlocks == 0){
        idsOfExecutorsForRemoval += executorId
      }
    }

    if(idsOfExecutorsForRemoval.size != 0){
      ScaleOutManager.RemoveExecutors(idsOfExecutorsForRemoval)
    }
  }

  private def borrowRemoteMemoryForOverloadedExecutors(overLoadedExecutors: Map[Int, Long], lessLoadedExecutors: Map[Int, Long]): Unit = { // (Sect. 7.2.1 of the paper)
    
    // we sort the executors based on their total number of cached blocks. The overleaded executors are sorted in descending order and the less-loaded ones are sorted in ascending order. Thus, at the head of each sorted list is the most overloaded executor to borrow cache from the least loaded one
    var overLoadedExecutorsSortedDesc = ListMap(overLoadedExecutors.toSeq.sortWith(_._2 > _._2):_*)
    var lessLoadedExecutorsSortedAsce = ListMap(lessLoadedExecutors.toSeq.sortWith(_._2 < _._2):_*)
    
    while(overLoadedExecutorsSortedDesc.size > 0 && lessLoadedExecutorsSortedAsce.size > 0){
      val overloadedExecutorId = overLoadedExecutorsSortedDesc.head._1 
      val overloadedExecutorSizeOfCachedBlocks = overLoadedExecutorsSortedDesc.head._2
      val lessloadedExecutorId = lessLoadedExecutorsSortedAsce.head._1
      val lessloadedExecutorSizeOfCachedBlocks = lessLoadedExecutorsSortedAsce.head._2

      if(overloadedExecutorSizeOfCachedBlocks < lessloadedExecutorSizeOfCachedBlocks){ // rarely does an overloaded executor store less block (in size) than an executor that does not have evictions
        return
      }

      val requiredMemory = scala.math.ceil((overloadedExecutorSizeOfCachedBlocks - lessloadedExecutorSizeOfCachedBlocks) /2).toLong // requiredMemory is the amount of memory that the overloaded executor needs to borrow from the less loaded one
      overLoadedExecutorsSortedDesc -= overloadedExecutorId
      lessLoadedExecutorsSortedAsce -= lessloadedExecutorId
      SendAMessageToExecutorCacheManagers(MessageType.BorrowRemoteMemory_Msg, (getBlockManagerByExecutorId(lessloadedExecutorId), requiredMemory), Set(overloadedExecutorId)) // notify the overloaded executor (i.e. overloadedExecutorId) to send some of its cached blocks whose total size do not exceed requiredMemory to the selected less loaded executor (i.e. lessloadedExecutorId)
      synchronized{
        this.currentMigrationSendingNumber += 1
      }
    }
  }

  private def balanceStorageIfNeeded(): Unit = { // (Sect. 7.2.2 of the paper)
    executorBlocksSizeForMigrationMap = executorBlocksSizeMap.clone()
    var avgStorage: Double = 0
    for((k,v) <- executorBlocksSizeForMigrationMap) { avgStorage += v.toDouble }
    avgStorage = avgStorage/executorBlocksSizeForMigrationMap.size.toDouble

    while(executorBlocksSizeForMigrationMap.size > 1){
      var minExec = executorBlocksSizeForMigrationMap.minBy(_._2)
      val minExecId: Int = minExec._1
      val minExecSpace: Long = minExec._2

      var maxExec = executorBlocksSizeForMigrationMap.maxBy(_._2)
      val maxExecId: Int = maxExec._1
      val maxExecSpace: Long = maxExec._2

      if(maxExecSpace.toDouble > (1.1 * avgStorage.toDouble) && minExecSpace.toDouble < (0.9 * avgStorage.toDouble)){
        SendAMessageToExecutorCacheManagers(MessageType.BorrowRemoteMemory_Msg, (getBlockManagerByExecutorId(minExecId), scala.math.ceil((maxExecSpace.toDouble - minExecSpace.toDouble)/2).toLong), Set(maxExecId))
        synchronized{
          this.currentMigrationSendingNumber += 1
        }
      }
      executorBlocksSizeForMigrationMap -= minExecId
      executorBlocksSizeForMigrationMap -= maxExecId
    }
  }

  private def calculateComputationTimesForCachedRdds(): Unit = { // to calculate the computation time of recently cached datasets before the final evaluation and also before the early evaluation
    
    // ****************************************************************************************************************
    // Firstly, store the execution time for each task executed in this job (Eq. 2 of the paper)
    // ****************************************************************************************************************
    val tasksMap: Map[Long, Array[(Int, Long)]] = Map()
    for( (blockId, blockInfo) <- blocksComputationMap){
      val taskId = blockInfo._1
      if(taskInfoMap.contains(taskId)){
        val blockCompuationTime = blockInfo._2.toLong
        if(!tasksMap.contains(taskId)){
          tasksMap += (taskId -> Array()) 
        }
        tasksMap(taskId) +:= Tuple2(AgileAntUtilities.GetRddIdOfABlock(blockId), blockCompuationTime)
      }
    }

    // *******************************************************************************************************************************************************************
    // Secondly, for each blockId, store the taskId that computes it (just as in the metrics list)
    // *******************************************************************************************************************************************************************
    val rddBenefitFromDirectParentMap: Map[Int, ListBuffer[(Long, Long)]] = Map()
    for( (taskId, blocksArray) <- tasksMap){
      if(blocksArray.size > 0){
        blocksArray.sortBy(-_._1)
        for(blockItemChild <- blocksArray){
          val childRddId = blockItemChild._1
          var blockComputationTime: Long = blockItemChild._2
          var parentBlocksTotalComputationTime: Long = 0
          for(blockItemParent <- blocksArray){
            val parentRddId = blockItemParent._1
            var parentComputationTime = blockItemParent._2
            if(parentRddId < childRddId && AutoCacher.IsDirectChildParentDependancy(childRddId, parentRddId)){
              parentBlocksTotalComputationTime += parentComputationTime
            }
          }
          blockComputationTime -= parentBlocksTotalComputationTime
          val taskComputationTimeFromDirectParent = taskInfoMap(taskId) - parentBlocksTotalComputationTime // Here we substract the computation time of block D_5_0 from the computation time of block D_41_0
          if(!rddBenefitFromDirectParentMap.contains(childRddId)){
            rddBenefitFromDirectParentMap += (childRddId -> ListBuffer())
          }
          rddBenefitFromDirectParentMap(childRddId) += ((blockComputationTime, taskComputationTimeFromDirectParent))
        }
      }
    }

    // *******************************************************************************************************************************************************************
    // Thirdly, for each dataset, compute the average block size, the average computation time, the average execution time of the tasks that compute the RDD block, etc
    // *******************************************************************************************************************************************************************
    for((rddId, computationTimesList) <- rddBenefitFromDirectParentMap){
      val numberOfRddPartitions: Int = AutoCacher.GetCachedRddNumberOfPartitions(rddId)
      val numberOfEvaluatedPartitions: Int = computationTimesList.size
      if (numberOfEvaluatedPartitions == 1 && numberOfRddPartitions > 1){ 
      }
      else{
        if(cachedRddsMetricsMap.contains(rddId) && cachedRddsMetricsMap(rddId).numberOfUsedPartitionsToCalculateMetrics > numberOfEvaluatedPartitions){ 
          if(rddEstimatedSizeMBMap.contains(rddId)){
            cachedRddsMetricsMap(rddId).avgBlockSize = rddEstimatedSizeMBMap(rddId).toDouble/rddBlocksNumberMap(rddId).toDouble
          }
        }
        else
        {
          val computationTimesListSortedByBlockComputationTime = computationTimesList.sortBy(-_._1)
          val medianBlockComputationTime = computationTimesListSortedByBlockComputationTime(numberOfEvaluatedPartitions/2)._1
          val computationTimesListSortedByTaskExecutionTime = computationTimesList.sortBy(-_._2)
          val medianTaskExecutionTime = computationTimesListSortedByTaskExecutionTime(numberOfEvaluatedPartitions/2)._2
          val avgBlockComputationTime = computationTimesList.map(_._1).sum/numberOfEvaluatedPartitions
          val avgTaskExecutionTime = computationTimesList.map(_._2).sum/numberOfEvaluatedPartitions
          cachedRddsMetricsMap -= rddId
          cachedRddsMetricsMap += (rddId -> new CachedRddMetrics(rddId, if(rddEstimatedSizeMBMap.contains(rddId) && rddBlocksNumberMap.contains(rddId)) rddEstimatedSizeMBMap(rddId).toDouble/rddBlocksNumberMap(rddId).toDouble else 0.0, medianBlockComputationTime, medianTaskExecutionTime, avgBlockComputationTime, avgTaskExecutionTime, AutoCacher.GetCachedRddNumberOfPartitions(rddId) , numberOfEvaluatedPartitions))
          }
        }
    }

    // ****************************************************************************************************************
    // Lastly, for each dataset, compute the computation time, the size, the benefit and benefitRatio
    // ****************************************************************************************************************
    for((rddId, cachedRddsMetrics) <- cachedRddsMetricsMap){
      val cachedRddMetrics = cachedRddsMetricsMap(rddId)
      val rddEstimatedSize = cachedRddMetrics.avgBlockSize * cachedRddMetrics.totalNumberOfPartitions
      val rddComputationTime = cachedRddMetrics.avgComputationTimeInATask * cachedRddMetrics.totalNumberOfPartitions
      val rddBenefit = rddComputationTime.toDouble / rddEstimatedSize.toDouble
      val rddBenefitRatio = cachedRddMetrics.avgComputationTimeInATask.toDouble / cachedRddMetrics.avgTaskExecutionTime.toDouble
      ClusterCacheManagerFactHub.RddsMetricsMap -= rddId
      ClusterCacheManagerFactHub.RddsMetricsMap += (rddId -> new RddMetrics(rddId, rddEstimatedSize, rddComputationTime, rddBenefit, rddBenefitRatio))
    }
  }

  def BroadcastCachingProfilesToAllExecutors(beneficialDatasetIds_Map: Map[Int, Double], rddChildParentSet : scala.collection.mutable.Set[(Int,Int)], cachedRddsNumberOfTargetPartitionsMap : Map[Int, Int], expectedExecutorStorageCapacity: Long, executorId: Int): Unit = {
    SendAMessageToExecutorCacheManagers(MessageType.Step_1_Msg, (beneficialDatasetIds_Map, rddChildParentSet, cachedRddsNumberOfTargetPartitionsMap, expectedExecutorStorageCapacity), if(executorId == -1) Set() else Set(executorId) )
  }
  
  private val cachedRddsMetricsMap: Map[Int, CachedRddMetrics] = Map()
  def CalculateStatisticsAtJobEnd(jobId: Int): Unit = { // this method is called at the end of the job to calculate the computation time and size of the recently cached datasets and trigger data migrations

    this.jobId = jobId
    
    calculateStatisticsMapsAndTakeAction() // action here is borrow remote cache and balance executors' cache
    
    calculateComputationTimesForCachedRdds() //the computation time of each recently cached dataset is computed. The Auto-Cacher uses these metrics during the final evaluation of each dataset

    blocksComputationMap.clear()

    var maxStorageCapacityPerExecutor: Long = 0
    for((k,v) <- executorBlocksSizeMap){
      if(v > maxStorageCapacityPerExecutor) {
        maxStorageCapacityPerExecutor = v
      }
    }

    if(doesEvictionOrFailureToCacheOccurInTheCurrentJob){
      ScaleOutManager.SetRegisteredActualStorageCapacityPerExecutor(maxStorageCapacityPerExecutor/(1024*1024))
    }

    this.earlyEvaluatedBeneficialDatasetIds_Map = Map()
    this.earlyEvaluatedNonBeneficialDatasetIds_Set = Set()
  }

  def MigrationTriggeredOnFullDataScale(currentNumberOfExecutors: Int, numberOfAddedExecutors: Int): Unit = { // to calculate the migration ratio and send it to all executors
    val migrationSendingNumber: Int = currentNumberOfExecutors * numberOfAddedExecutors
    val migrationRatio: Double = 1/(currentNumberOfExecutors.toDouble + numberOfAddedExecutors.toDouble)
    synchronized{
      this.currentMigrationSendingNumber += migrationSendingNumber
    }
    SendAMessageToExecutorCacheManagers(MessageType.Migration_Ratio_And_Early_Evaluation_Results_Msg, (Map(), Set(), migrationRatio))
  }

  private var blockManagerStorageSet: Set[BlockManagerId] = Set()
  def RegisterNewBlockManagerId(blockManagerId: BlockManagerId): Unit ={
    if(!blockManagerId.isDriver){
      blockManagerStorageSet += blockManagerId
    }
  }

  private def getBlockManagerByExecutorId(executorId: Int): BlockManagerId ={
    for(blockManagerId <- blockManagerStorageSet){
      if(blockManagerId.executorId.toInt == executorId){
        return blockManagerId
      }
    }
    return null
  }

  // ###############################################################################################################################
  // ############################################## Early evaluation  (Sect. 7.1.1 of the paper) ###################################
  // ###############################################################################################################################

  var votesCounter: Int = 0
  var totalRequiredMemoryForVotingExecutors: Double = 0
  var predictedRddFullTargetSizeMap: Map[Int, Long] = Map()
  private def receiveMemoryStatusAndBlocksComputationTimeAndTakeAQuickAction(executorId: Int, requiredMemory: Long /* each Executor-cache Manager sends the predicted required amount of memory to cache the target number of blocks (with the beneficial RDDs' blocks) */, predictedRddFullTargetSizeMap: Map[Int, Long], blocksComputation: Map[BlockId, (Long, Long)]): Unit = { // the Cluster-cache Manager receives the votes from the Executor-cache Managers (Sect. 7.1.1 of the paper)
    if(this.currentMigrationSendingNumber != 0){ 
      return 
    }
    
    if(this.votesCounter == -1){ // votesCounter = -1 means that for this job, the number of votes already exceeded the threshold. In other words, an early evaluation has already been conducted in this job. At the end of each job execution, votesCounter is reset to 0
      return 
    }

    this.votesCounter += 1
    this.totalRequiredMemoryForVotingExecutors += ScaleOutManager.GetExecutorStorageCapacity() + (requiredMemory.toDouble/(1024*1024)).toDouble
    
    blocksComputationMap = blocksComputationMap.++(blocksComputation)
    for((rddId, predictedSize) <- predictedRddFullTargetSizeMap){
      if(!this.predictedRddFullTargetSizeMap.contains(rddId)){
        this.predictedRddFullTargetSizeMap += (rddId -> 0)
      }
      this.predictedRddFullTargetSizeMap += (rddId -> (this.predictedRddFullTargetSizeMap(rddId) + predictedSize))
    }
    if(this.votesCounter >= scala.math.floor(ScaleOutManager.GetCurrentNumberOfExecutors().toDouble * VOTING_THRESHOLD).toInt) // check if the number of votes exceeds the threshold
    {
      for((rddId, predictedSize) <- this.predictedRddFullTargetSizeMap){
        this.predictedRddFullTargetSizeMap(rddId) = scala.math.ceil(predictedSize.toDouble * ScaleOutManager.GetCurrentNumberOfExecutors().toDouble / this.votesCounter.toDouble).toLong // to predict the total size of each RDD, linearly scale the number of the voters to the total number of executors
      }
      // at this stage, we know the size of RDDs cached in previous jobs and predict the size of recently cached RDDs of the current job. But still, we did not evaluate yet whether the recently cached RDDs are beneficial or not
      evaluateRecentlyCachedRddsQuicklyAndScaleOutIfNeeded() // the Auto-Cacher evaluates
      this.votesCounter = -1
    }
  }

  def GetCurrentMigrationSendingNumber(): Int = currentMigrationSendingNumber

  private var currentMigrationSendingNumber: Int = 0
  var earlyEvaluatedBeneficialDatasetIds_Map : Map[Int, Double] = Map()
  var earlyEvaluatedNonBeneficialDatasetIds_Set : Set[Int] = Set()
  
  private def evaluateRecentlyCachedRddsQuicklyAndScaleOutIfNeeded(): Unit = {
    
    calculateComputationTimesForCachedRdds() // before evaluating whether a dataset is beneficail for caching or not, calculate the computation time of each dataset
    
    var totalRequiredClusterMemory: Double = this.totalRequiredMemoryForVotingExecutors * ScaleOutManager.GetCurrentNumberOfExecutors().toDouble / this.votesCounter.toDouble // For example if 2 Executor-cache Manager voted out of 6 Executor-cache Manager and in each vote an Executor-cache Manager requires 3 GB, then the Cluster-cache Manager predicts that the required memory is (3+3) * 3 GB
    var atLeastOneRddIsEvaluated: Boolean = false
    for(recntlyCachedRddId <- AutoCacher.GetRecentlyCachedRdds()){
      if(ClusterCacheManagerFactHub.RddsMetricsMap.contains(recntlyCachedRddId)){ // If there are multiple recently cached datasets in sequential stages, we have the metrics of some recently cached datasets, but not all (NWeight is an example of this case).
        atLeastOneRddIsEvaluated = true 
        val rddMetrics = ClusterCacheManagerFactHub.RddsMetricsMap(recntlyCachedRddId)
        rddMetrics.totalSizeMB = this.predictedRddFullTargetSizeMap(recntlyCachedRddId)/(1024*1024)
        rddMetrics.benefit = rddMetrics.computationTime / rddMetrics.totalSizeMB
        if(AutoCacher.IsRddBeneficial(rddMetrics)){
          earlyEvaluatedBeneficialDatasetIds_Map += (recntlyCachedRddId -> rddMetrics.benefit)
        }
        else{
          earlyEvaluatedNonBeneficialDatasetIds_Set += recntlyCachedRddId
          totalRequiredClusterMemory -= (this.predictedRddFullTargetSizeMap(recntlyCachedRddId)/(1024*1024))
        }
      }
    }

    var recommendedNumberOfExecutors = scala.math.ceil(totalRequiredClusterMemory.toDouble / (ScaleOutManager.GetExecutorStorageCapacity().toDouble)).toInt
    if(recommendedNumberOfExecutors > 1 && recommendedNumberOfExecutors < ScaleOutManager.GetMaxNumberOfAllowedExecutors() ){
      recommendedNumberOfExecutors += 1
    }
    recommendedNumberOfExecutors = ScaleOutManager.AddExecutors(recommendedNumberOfExecutors)

    val migrationRatio: Double = if(recommendedNumberOfExecutors == ScaleOutManager.GetCurrentNumberOfExecutors()) 0.0 else 1/recommendedNumberOfExecutors.toDouble
    if(migrationRatio != 0.0 && this.currentMigrationSendingNumber == 0){
      synchronized{
        this.currentMigrationSendingNumber = (recommendedNumberOfExecutors - ScaleOutManager.GetCurrentNumberOfExecutors()) * ScaleOutManager.GetCurrentNumberOfExecutors()
      }
    }
    SendAMessageToExecutorCacheManagers(MessageType.Migration_Ratio_And_Early_Evaluation_Results_Msg, (earlyEvaluatedBeneficialDatasetIds_Map, earlyEvaluatedNonBeneficialDatasetIds_Set, if(this.currentMigrationSendingNumber == 0) 0.0 else migrationRatio))
  }

  // ###############################################################################################################################
  // ####################################### Newly added executors by the Scale-out Manager ########################################
  // ###############################################################################################################################


  def NotifyOldExecutorsIdsAboutTheNewExecutor(oldExecutorsIdsSet: scala.collection.mutable.Set[Int], blockManagerId: BlockManagerId): Unit = {
    val oldExecutorsIdsSetImmutable = Set.empty ++ oldExecutorsIdsSet // convert the oldExecutorsIdsSet to an immutable set.
    SendAMessageToExecutorCacheManagers(MessageType.NotifyOldExecutorsIdsAboutTheNewExecutor_Msg, blockManagerId, oldExecutorsIdsSetImmutable)
  }
  
  def Send_Migration_Ratio_And_Early_Evaluation_Results_Msg_To_A_Newly_Added_Executor(executorId: Int): Unit = { // notify newly added executors about the beneficial RDDs, non evaluated RDDs etc to prepare its Priority List that it uses during blocks eviction.
    SendAMessageToExecutorCacheManagers(MessageType.Migration_Ratio_And_Early_Evaluation_Results_Msg, (earlyEvaluatedBeneficialDatasetIds_Map, earlyEvaluatedNonBeneficialDatasetIds_Set, 0.0), Set(executorId))
  }

  private def SenderMigrationEnded(senderExecutorId: Int, receiverExecutorId: Int): Unit = { // At the end of each peer to peer migration, the Cluster-cache Manager is notified to track whether the current blocks migration round has ended or not
    synchronized{
      this.currentMigrationSendingNumber -= 1 
    }  
  }
  
  // ###############################################################################################################################
  // ##################### Cluster-cache Manager as a proxy between the Auto-cacher and the Scale-out Manager ######################
  // ###############################################################################################################################

  def GetTotalSizeOfEvaluatedCachedRdds() = AutoCacher.GetTotalSizeOfEvaluatedCachedRdds()

  def DoesFitInClusterMemory(totalSizeOfCachedRdds: Double) = ScaleOutManager.DoesFitInClusterMemory(totalSizeOfCachedRdds)

  def IsMmaxNumberOfExecutorsReached() = ScaleOutManager.IsMmaxNumberOfExecutorsReached()

  def GetCurrentNumberOfExecutors() = ScaleOutManager.GetCurrentNumberOfExecutors()

  def GetExecutorStorageCapacity() = ScaleOutManager.GetExecutorStorageCapacity()

  // ###############################################################################################################################
  // ##################################### Message exchange with the Executor-cache Managers #######################################
  // ###############################################################################################################################

  private def SendAMessageToExecutorCacheManagers(messageType: MessageType.MessageType, messageContent: Any, executorIds: Set[Int] = Set()): Unit = {
    sc.env.blockManager.master.SendAMessageToExecutorCacheManager_s(messageType, messageContent, executorIds)
  }

  def ReceiveAMessageFromAnExecutorCacheManager(messageType: MessageType.MessageType, executorId: Int, messageContent: Any): Unit = {
    messageType match {
      case MessageType.MetricsList_Msg =>
        val thread = new Thread {
          override def run {
            val messageContentAsATuple = messageContent.asInstanceOf[Tuple5[scala.collection.mutable.Set[BlockId], scala.collection.mutable.Set[BlockId], scala.collection.mutable.Set[BlockId], Map[BlockId, (Long, Long)], Map[BlockId, Long]]]
            receiveMetricsList(executorId, messageContentAsATuple._1, messageContentAsATuple._2, messageContentAsATuple._3, messageContentAsATuple._4, messageContentAsATuple._5)
          }
        }
        thread.start
      case MessageType.SendMemoryStatusAndBlocksComputationTime_Msg => 
        val messageContentAsATuple = messageContent.asInstanceOf[Tuple3[Long, Map[Int, Long], Map[BlockId, (Long, Long)]]]
        receiveMemoryStatusAndBlocksComputationTimeAndTakeAQuickAction(executorId, messageContentAsATuple._1, messageContentAsATuple._2, messageContentAsATuple._3)
      case MessageType.NotifyMigrationEnded_Msg => 
        val messageContentAsInt: Int = messageContent.asInstanceOf[Int]
        SenderMigrationEnded(executorId, messageContentAsInt)
    }
  }
}

class CachedRddMetrics( var id: Int, 
                  var avgBlockSize: Double,
                  var medianComputationTimeInATask: Long,
                  var medianTaskExecutionTime: Long,
                  var avgComputationTimeInATask: Long,
                  var avgTaskExecutionTime: Long,
                  var totalNumberOfPartitions: Int,
                  var numberOfUsedPartitionsToCalculateMetrics: Int)

class RddMetrics( var id: Int, 
                  var totalSizeMB: Double,
                  var computationTime: Double,
                  var benefit: Double,
                  var benefitRatio: Double,
                  )