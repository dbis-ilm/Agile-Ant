/*
 * Agile-Ant: Agile-Ant Types of messages between the Cluster-cache Manager and Executor-cache Managers
 *
 */

package org.apache.spark.agile_ant


object MessageType extends Enumeration
{
    type MessageType = Value
     
    // messages from the Cluster-cache Manager to Executor-cache Managers
    val Step_1_Msg = Value("Step_1_Msg")
    val Migration_Ratio_And_Early_Evaluation_Results_Msg = Value("Migration_Ratio_And_Early_Evaluation_Results_Msg")
    val NotifyOldExecutorsIdsAboutTheNewExecutor_Msg = Value("NotifyOldExecutorsIdsAboutTheNewExecutor_Msg")
    val BorrowRemoteMemory_Msg = Value("BorrowRemoteMemory_Msg")

    // messages from an Executor-cache Manager to the Cluster-cache Manager
    val MetricsList_Msg = Value("MetricsList_Msg")
    val SendMemoryStatusAndBlocksComputationTime_Msg = Value("SendMemoryStatusAndBlocksComputationTime_Msg")
    val NotifyMigrationEnded_Msg = Value("NotifyMigrationEnded_Msg")
    
    val Other_Msg = Value("Other_Msg")
    
}
