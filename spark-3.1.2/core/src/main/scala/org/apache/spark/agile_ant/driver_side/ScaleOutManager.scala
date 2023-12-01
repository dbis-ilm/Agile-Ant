/*
 * Agile-Ant: The Scale-out Manager
 *
 */

package org.apache.spark.agile_ant.driver_side

import org.apache.spark.SparkContext
import org.apache.spark.storage.BlockManagerId
import org.apache.spark.agile_ant.AgileAntConf

// Sizes in MB
object ScaleOutManager {
  
  private val RESERVER_SYSTEM_MEMORY = 300 // MB Static
  private var MIN_STORAGE_MEMORY_PER_EXECUTOR: Int = 0 // MB
  private var MAX_STORAGE_MEMORY_PER_EXECUTOR: Int = 0 // MB

  private var maxNumberOfAllowedExecutors: Int = 1
  def GetMaxNumberOfAllowedExecutors() = maxNumberOfAllowedExecutors

  private var currentClusterStorageCapacity: Long = 0

  private var sc: SparkContext = null

  private var targetNumberOfExecutors: Int = 1


  def init(sc: SparkContext): Unit = {
    this.sc = sc    
    MIN_STORAGE_MEMORY_PER_EXECUTOR = ((AgileAntConf.GetExecutorMemory() - RESERVER_SYSTEM_MEMORY) * AgileAntConf.GetMemoryFraction() * AgileAntConf.GetMemoryStorageFraction()).toInt
    MAX_STORAGE_MEMORY_PER_EXECUTOR = ((AgileAntConf.GetExecutorMemory() - RESERVER_SYSTEM_MEMORY) * AgileAntConf.GetMemoryFraction()).toInt
    this.maxNumberOfAllowedExecutors = AgileAntConf.GetMaximumNumberOfExecutors() // determined by the administrator, using spark-submit --conf spark.dynamicAllocation.maxExecutors=SelectedValue, when submitting the application run 
  }

  private var registeredActualStorageCapacityPerExecutor: Long = 0 // the executor cache size. The Cluster-cache Manager updates this value at the end of each job by checking the total size of cached blocks in each executor and selecting the maximum as the capacity that each executor has to cache blocks (i.e. executor-cache capacity). If the tasks require more execution memory and thus evict some blocks, Agile-Ant detects the execution memory pressure using this variable and reacts accordingly by adding more executors in the AnalyzeAndTakeAction function. Thus, Agile-Ant is adaptive with regards to execution memory utilization changes during application runs. Agile-Ant uses this value in calculating the cluster cache size while comparing it with the total size of cached datasets and adding new executors
  def SetRegisteredActualStorageCapacityPerExecutor(registeredActualStorageCapacityPerExecutor: Long): Unit = { // the Cluster-cache Manager sets this value at the end of the job execution
    if(registeredActualStorageCapacityPerExecutor > this.registeredActualStorageCapacityPerExecutor){
      this.registeredActualStorageCapacityPerExecutor = registeredActualStorageCapacityPerExecutor
    }
  }
  
  private var isMaxNumberOfExecutorsReached: Boolean = false
  def IsMmaxNumberOfExecutorsReached(): Boolean = { // true when Agile-Ant is in the resource-constrained mode and false when in the scale-out mode
  	return this.isMaxNumberOfExecutorsReached // we could compare this.maxNumberOfAllowedExecutors with the current number of executors, i.e. sc.getExecutorIds.length. However, sc.getExecutorIds.length becomes misleading when the Scale-out Manager requests for new executors but not all arrives due to resource allocation delays
  }

  def GetExecutorStorageCapacity(): Long ={
    if(this.registeredActualStorageCapacityPerExecutor == 0)
      return (MAX_STORAGE_MEMORY_PER_EXECUTOR.toDouble * 0.9).toLong // at the begining, we consider 90% of the unified memory as the executor cache size
    return scala.math.max(this.registeredActualStorageCapacityPerExecutor, MIN_STORAGE_MEMORY_PER_EXECUTOR) // registeredActualStorageCapacityPerExecutor is the actual executor cache size w.r.t execution memory utilization
  }

  def DoesFitInClusterMemory(totalSizeOfCachedRdds: Double): Boolean = { // to check if the cluster cache of the maximum number of executors fits the total size of cached RDDs. This check determines whether scale out would solve cache eviction problems. If not, less beneficial RDDs are unpersisted
    val requiredNumberOfExecutors: Int = scala.math.ceil(totalSizeOfCachedRdds / GetExecutorStorageCapacity()).toInt
    return requiredNumberOfExecutors <= maxNumberOfAllowedExecutors
  }

  private def IsThereAnIncompleteScaleOutRound(): Boolean = this.targetNumberOfExecutors > GetCurrentNumberOfExecutors()

  def AddExecutors(recommendedNumberOfExecutors: Int): Int = { // recommendedNumberOfExecutors is the sum of the number of the current and requested executors
    if(IsThereAnIncompleteScaleOutRound()) { // if a previous scale out round did not finish, a new scale out round cannot be conducted
      return 0 
    }

    if(recommendedNumberOfExecutors > this.maxNumberOfAllowedExecutors){ 
      this.isMaxNumberOfExecutorsReached = true // switch to the resource-constrained mode
    }

    val newNumberOfExecutors: Int = math.min(this.maxNumberOfAllowedExecutors, math.max(this.targetNumberOfExecutors, recommendedNumberOfExecutors))
    
    if(this.targetNumberOfExecutors >= newNumberOfExecutors){ // do nothing if the current number of executors is equal to recommendedNumberOfExecutors (new target)
      return this.targetNumberOfExecutors
    }

    this.targetNumberOfExecutors = newNumberOfExecutors
    sc.executorAllocationManager.foreach(_.SetNewTargetNumberOfExecutors(this.targetNumberOfExecutors)) // the Dynamic Resource Allocation API we use to set the target number of executors. E.g. if two executors are running and three are requested, the target number passed to the API function is five
    newNumberOfExecutors
  }

  def RemoveExecutors(executorIds: Set[Int]): Unit = {
    this.targetNumberOfExecutors = GetCurrentNumberOfExecutors() - executorIds.size
    this.isMaxNumberOfExecutorsReached = false // switch to the scale-out mode
    sc.executorAllocationManager.foreach(_.RemoveExecutors(executorIds)) 
  }

  def GetCurrentNumberOfExecutors(): Int = sc.getExecutorIds.length 

  private def isScaleOutPossible(): Boolean = !IsThereAnIncompleteScaleOutRound() && ClusterCacheManager.GetCurrentMigrationSendingNumber() == 0 && !IsMmaxNumberOfExecutorsReached()
  // Scale out is possible if: 
  // 1- there is no incomplete scale out round
  // 2- there is no incomplete data migration round. We avoid scale out while there is an incomplete data migration round because after the data migration ends, the Cluster-cache Manager obtains the actual executor cache size of each executor (which cannot be accurately obtained during the data migration) to make robust scale out decisions
  // 3- the maximun number of executors is not reached
  
  private var partitionsMigrationMap : scala.collection.mutable.Map[Int, scala.collection.mutable.Map[Int, Double]] = scala.collection.mutable.Map()
  private var oldExecutorsIdsSet : scala.collection.mutable.Set[Int] = scala.collection.mutable.Set()
  
  def AnalyzeAndTakeAction(): Unit = { // at the end of job execution. The potential action here is scale out
    oldExecutorsIdsSet.clear()
    for(executorId <- sc.getExecutorIds){
      oldExecutorsIdsSet += executorId.toInt
    }

    if(IsThereAnIncompleteScaleOutRound()){ // if true, there is an incomplete scale out round
      sc.executorAllocationManager.foreach(_.SetNewTargetNumberOfExecutors(this.targetNumberOfExecutors)) // request the executors again
      return
    }

    if(!isScaleOutPossible()) { // scale out is possible. So no action to take
      return 
    }
    
    val exactRecommendedNumberOfExecutors: Double = ClusterCacheManager.GetTotalSizeOfEvaluatedCachedRdds() / GetExecutorStorageCapacity()
    var recommendedNumberOfExecutors: Int = scala.math.ceil(exactRecommendedNumberOfExecutors).toInt

    if(recommendedNumberOfExecutors > 1 && recommendedNumberOfExecutors < this.maxNumberOfAllowedExecutors){
      recommendedNumberOfExecutors += 1
    }

    var numberOfExecutorsToAdd = recommendedNumberOfExecutors - GetCurrentNumberOfExecutors()
    if(numberOfExecutorsToAdd > 0){
      AddExecutors(recommendedNumberOfExecutors)
      ClusterCacheManager.MigrationTriggeredOnFullDataScale(GetCurrentNumberOfExecutors(), numberOfExecutorsToAdd) // notify the old executors (that were not added in the current scale out round) about the new migration ratio (Eq. 4 of the paper)
    }
  }
  
  def NewBlockManagerIsReady(blockManagerId: BlockManagerId): Unit = { // a notification of the arrival of a new executor that has been requested in the current scale out round
    ClusterCacheManager.RegisterNewBlockManagerId(blockManagerId)
    
    if(oldExecutorsIdsSet.size > 0){ // this condition is not met only in the application run startup, when executors arrive for the first time without scale out round
      ClusterCacheManager.NotifyOldExecutorsIdsAboutTheNewExecutor(oldExecutorsIdsSet, blockManagerId) // notify the old executorts about the arrival of the new executor, whereupon the executors start migrating some of their cached blocks based on the migration ratio
    }
  }

}

