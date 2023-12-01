/*
 * Agile-Ant: The Auto-cacher
 *
 */


package org.apache.spark.agile_ant.driver_side


import scala.collection.mutable.{HashMap, Set, HashSet, ListBuffer}
import org.apache.spark.rdd._
import org.apache.spark._
import org.apache.spark.agile_ant.AgileAntConf
import org.apache.spark.agile_ant.AgileAntUtilities
    

object AutoCacherFactHub {
  
  private val cacheRddLastHit = new HashMap[Int, Int]
  def AddCacheRddLastHit(rddId: Int, currentJobId: Int): Unit = cacheRddLastHit += (rddId -> currentJobId)
  def GetCacheRddLastHit(rddId: Int): Int = cacheRddLastHit.getOrElse(rddId, 0)

  private val rddComputationCount = new HashMap[Int, Int]
  def AddRddComputation(rddId: Int): Unit = AgileAntUtilities.IncrementMapValue(rddComputationCount, rddId)  
  def GetRddNumberOfComputations(rddId: Int): Int = rddComputationCount.getOrElse(rddId, 0)

  private val upersistedRddIds: Set[Int] = Set() 
  def AddUnpersistance(rddId: Int): Unit = upersistedRddIds += rddId
  def IsUnpersisted(rddId: Int): Boolean = upersistedRddIds.contains(rddId)
  
  private val reusedCallSites: Set[String] = Set() 
  def AddReusedCallSite(callSite: String): Unit = reusedCallSites += callSite
  def IsReusedCallSite(callSite: String): Boolean = reusedCallSites.contains(callSite)

  private val nonBeneficialCallSites: Set[String] = Set() 
  def AddNonBeneficialCallSite(callSite: String): Unit = nonBeneficialCallSites += callSite
  def RemoveNonBeneficialCallSite(callSite: String): Unit = nonBeneficialCallSites -= callSite
  def IsNonBeneficialCallSite(callSite: String): Boolean = nonBeneficialCallSites.contains(callSite)

  private val beneficialCallSites: Set[String] = Set() 
  def AddBeneficialCallSite(callSite: String): Unit = beneficialCallSites += callSite
  def IsBeneficialCallSite(callSite: String): Boolean = beneficialCallSites.contains(callSite)

  private val wideTransformation = new HashMap[Int, Int]
  def AddWideTransformation(childRddId: Int, parentRddId: Int): Unit = wideTransformation += (childRddId -> parentRddId)
  def IsWideTransformationConducted(childRddId: Int, parentRddId: Int): Boolean = return wideTransformation.contains(childRddId) && wideTransformation.get(childRddId).get == parentRddId

}

object AutoCacher {

  private var currentJobId = 0

  private var DATASET_BENEFIT_RATIO: Double = AgileAntConf.GetAgileAntDetesetBenefitRatio()
  def init(): Unit = {
    DATASET_BENEFIT_RATIO = AgileAntConf.GetAgileAntDetesetBenefitRatio()
  }

  private val cachedRdds: Set[RDD[_]] = Set()
  def GetCachedRdds(): Set[RDD[_]] = cachedRdds

  def IsRddCached(rddId: Int): Boolean = {
    for(cachedRdd <- cachedRdds){
      if(cachedRdd.id == rddId){
        return true
      }
    }
    return false
  }

  def GetCachedRddNumberOfPartitions(rddId: Int): Int = {
    for(cachedRdd <- cachedRdds){
      if(cachedRdd.id == rddId){
        return cachedRdd.partitions.length
      }
    }
    return 0
  }

  def GetTotalSizeOfEvaluatedCachedRdds(): Double = {
    var totalSizeMB: Double = 0
    for(cachedRdd <- cachedRdds){
      if(!recentlyCachedRdds.contains(cachedRdd.id) && ClusterCacheManagerFactHub.RddsMetricsMap.contains(cachedRdd.id)){
        val rddMetrics = ClusterCacheManagerFactHub.RddsMetricsMap(cachedRdd.id)
        totalSizeMB += rddMetrics.totalSizeMB
      }
    }
    totalSizeMB
  }
  
  def IsRddBeneficial(rddMetrics: RddMetrics): Boolean = rddMetrics.benefitRatio > DATASET_BENEFIT_RATIO // here, the Auto-cacher decides whether a dataset is beneficial for caching or not
  
  private val nonBeneficialRdds: Set[Int] = Set() 
  private val numberOfConsecutiveNonBeneficialRddsOfCallSizes: scala.collection.mutable.Map[String, Int] = scala.collection.mutable.Map() 
  private def finalevaluationOfRecentlyCachedRdds(): Unit = { 
    val totalSizeOfEvaluatedCachedRdds: Double = GetTotalSizeOfEvaluatedCachedRdds() // evaluated Rdds are those that are beneficial (i.e. cached in previous jobs but not the one before this job) or those that are cached in the previous job
    for(recentlyCachedRddId <- recentlyCachedRdds){ // recently cached datasets here are those datasets that are cached in the previous job, whose metrics we now have
      if(ClusterCacheManagerFactHub.RddsMetricsMap.contains(recentlyCachedRddId)){
        val rddMetrics = ClusterCacheManagerFactHub.RddsMetricsMap(recentlyCachedRddId)
        if(IsRddBeneficial(rddMetrics)){ 
          for(cachedRdd <- cachedRdds){
            if(cachedRdd.id == recentlyCachedRddId){
              val rddCallSite = AgileAntUtilities.GetRddCallsite(cachedRdd)
              numberOfConsecutiveNonBeneficialRddsOfCallSizes -= rddCallSite
              AutoCacherFactHub.AddBeneficialCallSite(rddCallSite)
              AutoCacherFactHub.RemoveNonBeneficialCallSite(rddCallSite)
            }
          }
        }
        else { 
          for(cachedRdd <- cachedRdds){
            if(cachedRdd.id == recentlyCachedRddId){
              unpersistRdd(cachedRdd) 
              nonBeneficialRdds += recentlyCachedRddId 
              
              // ************* Add call-site to non-beneficial call-sites list after three consecutive non-beneficial datasets *************
              val rddCallSite = AgileAntUtilities.GetRddCallsite(cachedRdd)
              if(!AutoCacherFactHub.IsBeneficialCallSite(rddCallSite)){
                if(!numberOfConsecutiveNonBeneficialRddsOfCallSizes.contains(rddCallSite)){
                  numberOfConsecutiveNonBeneficialRddsOfCallSizes += (rddCallSite -> 1)
                }
                else{
                  numberOfConsecutiveNonBeneficialRddsOfCallSizes(rddCallSite) = numberOfConsecutiveNonBeneficialRddsOfCallSizes(rddCallSite) + 1
                  if(numberOfConsecutiveNonBeneficialRddsOfCallSizes(rddCallSite) == 3){
                    AutoCacherFactHub.AddNonBeneficialCallSite(rddCallSite)
                  }
                }
              }          
            }
          }
        }
        recentlyCachedRdds -= recentlyCachedRddId
      }
    }

    while(cachedRdds.size > 1 && !ClusterCacheManager.DoesFitInClusterMemory(GetTotalSizeOfEvaluatedCachedRdds()) && ClusterCacheManager.IsMmaxNumberOfExecutorsReached()){ 
      unpersistTheLessBeneficialEvaluatedRdd() 
    }
    
  }
  
  private val recentlyCachedRdds: Set[Int] = Set() 
  def GetRecentlyCachedRdds(): Set[Int] = recentlyCachedRdds

  private val callSiteUnpersistanceDistanceMap = new HashMap[String, Int]
  private def updateCallSiteUnpersistanceDistanceMap(rdd: RDD[_]): Unit = {
    val distance: Int = this.currentJobId - AutoCacherFactHub.GetCacheRddLastHit(rdd.id)
    AgileAntUtilities.AddOrUpdateMapValueIfLarger(callSiteUnpersistanceDistanceMap, AgileAntUtilities.GetRddCallsite(rdd), distance) 
  }
  
  private def cacheRdd(rdd: RDD[_]): Unit = { 
    rdd.AutocacherCache() // Cache using Spark API. Chech RDD.scala
    cachedRdds += rdd // cachedRdds includes all cached Rdds whether they are beneficial (i.e. cached in previous jobs) or recently cached.

    AutoCacherFactHub.AddReusedCallSite(AgileAntUtilities.GetRddCallsite(rdd)) // as the Rdd is recomputed, its call-site is reused. Note that if a call-site is reused, it does not mean that it is beneficial. After unpersisting three Rdds of a call-site consecutively, the call-site is considered as non-beneficial and thus, even though it is reused, non of its Rdds will be cached (refer to traverseAndCache function)
    
    if(AutoCacherFactHub.IsUnpersisted(rdd.id)) { // if it is unpersisted, this means it has already been evaluated 
      updateCallSiteUnpersistanceDistanceMap(rdd) 
    }
    else{
      recentlyCachedRdds += rdd.id 
    }
      
    AutoCacherFactHub.AddCacheRddLastHit(rdd.id, this.currentJobId) // with this, we just profile its last used job id
  }

  private def IsRecentlyCached(rddId: Int): Boolean = this.recentlyCachedRdds.contains(rddId)

  object TraverseType extends Enumeration
  {
      type TraverseType = Value
      
      val TraverseA = Value("TraverseA")
      val TraverseB = Value("TraverseB")
      val TraverseC = Value("TraverseC")
  }

  def isAWideTransformationThatHasBeenConducted(dependency: Dependency[_], childRddId: Int): Boolean = {
    if(dependency.isInstanceOf[ShuffleDependency[_, _, _]]){
      if(AutoCacherFactHub.IsWideTransformationConducted(childRddId, dependency.rdd.id)){
        return true
      }
      AutoCacherFactHub.AddWideTransformation(childRddId, dependency.rdd.id) // profile that the wide transformation has been conducted once
    }
    return false
  }

  
  private var usedRddsInCurrentJobSet : Set[Int] = Set()
  private def traverseAndCache(rdd: RDD[_], traverseType: TraverseType.TraverseType, skippedCount: Int = 0): Unit = { //Recursive - (Algorithm 1 of the paper)

    // TraverseType A, B and C
    
    usedRddsInCurrentJobSet += rdd.id // profile that the RDD is in the current job's DAG (Line 1 in Algorithm 1 of the paper)

    if(traverseType == TraverseType.TraverseC){
      for(parent <- rdd.dependencies)
      {
        traverseAndCache(parent.rdd, TraverseType.TraverseC) // (Line 4 in Algorithm 1 of the paper)
      }
      return
    }

    // TraverseType A and B only
    
    if (cachedRdds.contains(rdd)){ 
      AutoCacherFactHub.AddCacheRddLastHit(rdd.id, this.currentJobId) // (Line 6 in Algorithm 1 of the paper)
      for(parent <- rdd.dependencies)
      {
        traverseAndCache(parent.rdd, TraverseType.TraverseC)
      }
      return
    }

    AutoCacherFactHub.AddRddComputation(rdd.id) // (Line 11 in Algorithm 1 of the paper)
    
    if(traverseType == TraverseType.TraverseB){
      for(parent <- rdd.dependencies){
        if(isAWideTransformationThatHasBeenConducted(parent, rdd.id)){ // check if the dependency with the parent RDD is a wide transformation that has already been conducted in a previous job
          traverseAndCache(parent.rdd, TraverseType.TraverseC) // (Line 15 in Algorithm 1 of the paper)
        }
        else { // the dependency with the parent RDD is either a narrow transformation or a wide transformation conducted for the first time
          traverseAndCache(parent.rdd, TraverseType.TraverseB) // (Line 17 in Algorithm 1 of the paper)
        }
      }
      return
    }

    // TraverseType A only

    val condition1: Boolean = !AutoCacherFactHub.IsNonBeneficialCallSite(AgileAntUtilities.GetRddCallsite(rdd)) && !nonBeneficialRdds.contains(rdd.id) && AutoCacherFactHub.GetRddNumberOfComputations(rdd.id) > 1 // (Line 19 in Algorithm 1 of the paper)
    val condition2: Boolean = AutoCacherFactHub.IsReusedCallSite(AgileAntUtilities.GetRddCallsite(rdd)) && !nonBeneficialRdds.contains(rdd.id) && !AutoCacherFactHub.IsNonBeneficialCallSite(AgileAntUtilities.GetRddCallsite(rdd)) // (Line 20 in Algorithm 1 of the paper)
    val potential: Boolean = condition1 || condition2 // || (currentJobId == 0 && rdd.id == 5)

    val skipCachingParents: Int = if (skippedCount > 0 && rdd.getClass.toString.contains("ZippedPartitionsRDD2")) 0 else if (skippedCount > 0) skippedCount - 1 else if(rdd.getClass.toString.contains("ZippedPartitionsRDD2")) 3 else 0
    
    if(potential && skippedCount == 0){ 
      cacheRdd(rdd)
      for(parent <- rdd.dependencies)
      {
        if(isAWideTransformationThatHasBeenConducted(parent, rdd.id)){
          traverseAndCache(parent.rdd, TraverseType.TraverseC) // (Line 26 in Algorithm 1 of the paper)
        }
        else{
          if(condition2){
            traverseAndCache(parent.rdd, TraverseType.TraverseA, skipCachingParents) // (Line 29 in Algorithm 1 of the paper)
          }
          else{
            traverseAndCache(parent.rdd, TraverseType.TraverseB) // (Line 31 in Algorithm 1 of the paper)
          }
        }
      }
    }
    else{ 
      for(parent <- rdd.dependencies){
        if(isAWideTransformationThatHasBeenConducted(parent, rdd.id)){
          traverseAndCache(parent.rdd, TraverseType.TraverseC)
        }
        else{
          if(AutoCacherFactHub.IsUnpersisted(rdd.id)){
            traverseAndCache(parent.rdd, TraverseType.TraverseB) // if the Rdd is already cached but unpersisted later on, we do not try to cache any of its parents because if it has alot of parents in sequence, traversing with Traverse A will lead to cache/unpersist try of each of its parents
          }
          else{
            traverseAndCache(parent.rdd, TraverseType.TraverseA, skipCachingParents)
          }
        }
      }
    }
  }

  private def unpersistTheLessBeneficialEvaluatedRdd(): Unit = {

    var theLessBeneficialEvaluatedRddBenefit : Double = Double.MaxValue
    var theLessBeneficialEvaluatedRdd = cachedRdds.head
    
    for(cachedRdd <- cachedRdds){
      if(ClusterCacheManagerFactHub.RddsMetricsMap.contains(cachedRdd.id)){
        val rddMetrics = ClusterCacheManagerFactHub.RddsMetricsMap(cachedRdd.id)
        if(theLessBeneficialEvaluatedRddBenefit > rddMetrics.benefit){
          theLessBeneficialEvaluatedRddBenefit = rddMetrics.benefit
          theLessBeneficialEvaluatedRdd = cachedRdd
        }
      }
    }
    unpersistRdd(theLessBeneficialEvaluatedRdd)
  }
  
  private def unpersistRdd(unpersistedRdd: RDD[_]): Unit = { 
    unpersistedRdd.AutocacherUnpersist() // unpersist using Spark API. Refer to RDD.scala
    AutoCacherFactHub.AddUnpersistance(unpersistedRdd.id) // With this, if the Rdd is cached again, we indicate that it was unpersisted before
    cachedRdds -= unpersistedRdd 

    for(cachedRdd <- cachedRdds){
      if(IsDirectChildParentDependancy(cachedRdd.id, unpersistedRdd.id)){ 
        if(ClusterCacheManagerFactHub.RddsMetricsMap.contains(unpersistedRdd.id) && ClusterCacheManagerFactHub.RddsMetricsMap.contains(cachedRdd.id)){
          val unpersistedRddMetrics = ClusterCacheManagerFactHub.RddsMetricsMap(unpersistedRdd.id)
          val childRddMetrics = ClusterCacheManagerFactHub.RddsMetricsMap(cachedRdd.id)
          childRddMetrics.computationTime += unpersistedRddMetrics.computationTime // Eq(3) in the paper
          childRddMetrics.benefit = childRddMetrics.computationTime / childRddMetrics.totalSizeMB // Eq(3) in the paper
        }
        
      }
    }
  }

  private def unpersistUnusedRdds(): Unit = { 
    val sortedCachedRdd = new ListBuffer[RDD[_]] // unpersistance starts with the parent Rdds because each time a parent Rdd is unpersisted, the computation time and benefit of its direct children increase. Therefore, we unpersist the parents one by one and, in each unpersistance, we update the children's metrics
    var cachedRddsTmp = cachedRdds.clone()
    for(cachedRddOuter <- cachedRddsTmp) {
      var parentRdd = cachedRddOuter
      for(cachedRddInner <- cachedRddsTmp) {
        if(parentRdd.id > cachedRddInner.id){
          parentRdd = cachedRddInner
        }
      }
      sortedCachedRdd += parentRdd
      cachedRddsTmp -= parentRdd
    }
    
        // sortedCachedRdd stores the cached rdds ordered from parents to children.
    for( cachedRdd <- sortedCachedRdd) {
      val predictedNextUsageJobId = AutoCacherFactHub.GetCacheRddLastHit(cachedRdd.id) + callSiteUnpersistanceDistanceMap.getOrElse(AgileAntUtilities.GetRddCallsite(cachedRdd), 0)
      // predictedNextUsageJobId indicates when the Rdd is expected to be used in the next
      if ( this.currentJobId > predictedNextUsageJobId) {
        if(!usedRddsInCurrentJobSet.contains(cachedRdd.id) || ClusterCacheManager.IsMmaxNumberOfExecutorsReached()){
          unpersistRdd(cachedRdd)
        }
      }
    }      
  }

  private def BroadcastCachingProfilesToAllExecutors(executorId: Int = -1 /*  -1 means broadcast to all executors. Otherwise, the message is sent to a specific executor */): Unit = {

    var cachedRddsNumberOfTargetPartitionsMap : scala.collection.mutable.Map[Int, Int] = scala.collection.mutable.Map() // in this map, for each recently cached dataset, we store the target number of partitions that each executor is to cache
    val beneficialDatasetIds_Map: scala.collection.mutable.Map[Int, Double] = scala.collection.mutable.Map() // in this map, for each beneficial dataset, we store the dataset benefit (computation time (ms) / size (MB) - Eq. 1 of the paper). Thus, the benefit of each dataset is the computation time of a single MB. By sending this to each executor, the executor use these metrics during blocks migration, comparing the block migration time with the block (re)computation time, which is stored in this map and sent to each executor 

    prepareCachedRDDChildParentSet() // prepares the rddChildParentSet that are to be sent to each executor. With this set, each executor knows the parent-child dependency between cached datasets and uses this during eviction (Sect. 7.1.2 of the paper). While evicting blocks with same priority, the executor starts by evicting the blocks that belong to the child datasets (refer to GetEvictionPriorityList function in ExecutorCacheManager.scala and evictBlocksToFreeSpace function in MemoryStore.scala)
    prepareCachedRDDDirectChildParentSet()

    for(cachedRdd <- cachedRdds){
      if(recentlyCachedRdds.contains(cachedRdd.id)){
        if(this.currentJobId==0)
           cachedRddsNumberOfTargetPartitionsMap += (cachedRdd.id -> cachedRdd.partitions.size.toInt)
         else
           cachedRddsNumberOfTargetPartitionsMap += (cachedRdd.id -> scala.math.ceil(cachedRdd.partitions.size.toDouble/ClusterCacheManager.GetCurrentNumberOfExecutors().toDouble).toInt)
      }
      else{
        if(ClusterCacheManagerFactHub.RddsMetricsMap.contains(cachedRdd.id)){
          val rddMetrics = ClusterCacheManagerFactHub.RddsMetricsMap(cachedRdd.id)
          beneficialDatasetIds_Map += (cachedRdd.id.toInt -> rddMetrics.benefit)
        }
      }
      true // with this, we resolve a compilation error (https://stackoverflow.com/questions/52255769/scala-type-arguments-do-not-conform-to-trait-subtractables-type-parameter-boun)
    }

    // the message is sent to all executors if executorId equals -1. This takes place before job execution (step 4 in Section 6.1.4 of the paper). Otherwise, the message is sent to a specific newly added executor. When a new executor arrives, the Auto-cacher sends the caching decisions to the executor (like a priority list) to use them while running tasks and computing blocks. In this case, the Auto-cacher does not send the target number of blocks of each dataset that the executor is to cache because it is recently added (i.e. not expected to become overloaded in this job) and no early evaluation can be conducted anymore in the job
    ClusterCacheManager.BroadcastCachingProfilesToAllExecutors(beneficialDatasetIds_Map, rddChildParentSet, if(executorId == -1) cachedRddsNumberOfTargetPartitionsMap else scala.collection.mutable.Map(), ClusterCacheManager.GetExecutorStorageCapacity(), executorId)
    if(executorId != -1){ 
      ClusterCacheManager.Send_Migration_Ratio_And_Early_Evaluation_Results_Msg_To_A_Newly_Added_Executor(executorId) // notify the newly added executors with the early evaluation results as they were not included in this evaluation
    } 
  }
  
  def HandleJobStart(currentJobId: Int, lastRddInJob: RDD[_]): Unit = { 
    
    this.currentJobId = currentJobId  
    
    finalevaluationOfRecentlyCachedRdds() // (Step 1 in Section 6.1.1 of the paper)

    usedRddsInCurrentJobSet.clear()
    traverseAndCache(lastRddInJob, TraverseType.TraverseA) // (Step 2 in Section 6.1.2. of the paper). TraverseA is the default. 

    unpersistUnusedRdds() // (Step 3 in Section 6.1.3 of the paper)
    
    BroadcastCachingProfilesToAllExecutors() // (Step 4 in Section 6.1.4 of the paper)
    
  }
  
  // These are utilities to check dependencies between cached datasets

  private var rddChildParentSet : Set[(Int,Int)] = Set()
  private def prepareCachedRDDChildParentSet(): Unit = {
    rddChildParentSet.clear
    for(x <- cachedRdds){
      for(y <- cachedRdds){
        if(x.id != y.id && !rddChildParentSet.contains((y.id,x.id)) && !recentlyCachedRdds.contains(x.id) && !recentlyCachedRdds.contains(y.id) && isChildParentDependancy(x,y)){
          rddChildParentSet += ((x.id, y.id))
        }
      }
    }
  }

  def IsChildParentDependancy(potentialChildId: Int, potentialParentId: Int, rddChildParentSet : Set[(Int,Int)]) : Boolean = {
    for(rddDirectChildParentItem <- rddChildParentSet){
      if(rddDirectChildParentItem._1 == potentialChildId && rddDirectChildParentItem._2 == potentialParentId){
        return true
      }
    }
    return false
  }

  private def isChildParentDependancy(potentialChild: RDD[_], potentialParent: RDD[_]) : Boolean = {
    val visited = new HashSet[RDD[_]]
    val waitingForVisit = new ListBuffer[RDD[_]]
    waitingForVisit += potentialChild
    while (waitingForVisit.nonEmpty) {
      val toVisit = waitingForVisit.remove(0)
      if (!visited(toVisit)) {
        visited += toVisit
        for(parent <- toVisit.dependencies){
          if(parent.rdd.id == potentialParent.id)
            return true
          waitingForVisit.prepend(parent.rdd)
        }
      }
    }
    return false
  }

  private var RDDDirectChildParentSet : Set[(Int,Int)] = Set()
  def GetRDDDirectChildParentSet(): Set[(Int,Int)] = RDDDirectChildParentSet

  private def prepareCachedRDDDirectChildParentSet(): Unit = {
    RDDDirectChildParentSet.clear
    for(rdd <- cachedRdds){
      val visited = new HashSet[RDD[_]]
      val waitingForVisit = new ListBuffer[RDD[_]]
      waitingForVisit += rdd
      while (waitingForVisit.nonEmpty) {
        val toVisit = waitingForVisit.remove(0)
        if (!visited(toVisit)) {
          visited += toVisit
          for(parent <- toVisit.dependencies){
            if(IsRddCached(parent.rdd.id)){ 
              RDDDirectChildParentSet += ((rdd.id, parent.rdd.id))
            }
            else{
              waitingForVisit.prepend(parent.rdd)
            }
          }
        }
      }
    }
  }

  def IsDirectChildParentDependancy(potentialChildId: Int, potentialParentId: Int) : Boolean = {
    for(rddDirectChildParentItem <- RDDDirectChildParentSet){
      if(rddDirectChildParentItem._1 == potentialChildId && rddDirectChildParentItem._2 == potentialParentId){
        return true
      }
    }
    return false
  }
}
