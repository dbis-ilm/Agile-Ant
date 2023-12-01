/*
 * Agile-Ant: Agile-Ant Configuration
 *
 */

package org.apache.spark.agile_ant

import org.apache.spark.internal.config._
import org.apache.spark.SparkContext

object AgileAntConf{
    // Default values could be modified in ../internal/package.scala file
    private var executorMemory: Int = 1 //GB
    private var maximumNumberOfExecutors: Int = 1 
    private var memoryFraction: Double = 0.6 
    private var memoryStorageFraction: Double = 0.5  
    private var agileAntDetesetBenefitRatio: Double = 0.25 
    private var agileAntVotingThreshold: Double = 0.33
    
    def Set(sc: SparkContext): Unit = {
        this.executorMemory = sc.conf.get(EXECUTOR_MEMORY).toInt
        this.maximumNumberOfExecutors = sc.conf.get(DYN_ALLOCATION_MAX_EXECUTORS).toInt
        this.memoryFraction = sc.conf.get(MEMORY_FRACTION).toDouble // Default is 0.6 
        this.memoryStorageFraction = sc.conf.get(MEMORY_STORAGE_FRACTION).toDouble // Default is 0.5
        this.agileAntDetesetBenefitRatio = sc.conf.get(AGILEANT_DATASET_BENEFIT_RATIO).toDouble // Default is 0.25 
        this.agileAntVotingThreshold = sc.conf.get(AGILEANT_VOTING_THRESHOLD).toDouble // Default is 0.33
    }

    def GetExecutorMemory(): Int = {
        return this.executorMemory
    }

    def GetMaximumNumberOfExecutors(): Int = {
        return this.maximumNumberOfExecutors
    }

    def GetMemoryStorageFraction(): Double = {
        return this.memoryStorageFraction
    }

    def GetMemoryFraction(): Double = {
        return this.memoryFraction
    }
    
    def GetAgileAntDetesetBenefitRatio(): Double = {
        return this.agileAntDetesetBenefitRatio
    }
    
    def GetAgileAntVotingThreshold(): Double = {
        return this.agileAntVotingThreshold
    }


}