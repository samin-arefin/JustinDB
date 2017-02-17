package justin.db

import java.util.UUID

import justin.consistent_hashing.{NodeId, Ring, UUID2RingPartitionId}
import justin.db.ConsensusReplicatedReads.ConsensusSummary
import justin.db.StorageNodeActorProtocol._
import justin.db.replication.{N, PreferenceList, R}

import scala.concurrent.{ExecutionContext, Future}

class ReplicaReadCoordinator(
  nodeId: NodeId, ring: Ring, n: N,
  localDataReader: ReplicaLocalReader,
  remoteDataReader: ReplicaRemoteReader
)(implicit ec: ExecutionContext) extends ((StorageNodeReadData, ClusterMembers) => Future[StorageNodeReadingResult]) {

  override def apply(cmd: StorageNodeReadData, clusterMembers: ClusterMembers): Future[StorageNodeReadingResult] = cmd match {
    case StorageNodeReadData.Local(id)         => readLocalData(id)
    case StorageNodeReadData.Replicated(r, id) => coordinateReplicated(r, id, clusterMembers)
  }

  private def readLocalData(id: UUID) = localDataReader.apply(id, new ResolveDataOriginality(nodeId, ring))

  private def coordinateReplicated(r: R, id: UUID, clusterMembers: ClusterMembers) = {
    val partitionId = UUID2RingPartitionId.apply(id, ring)
    PreferenceList(partitionId, n, ring).fold(onLeft, onRight(r, id, clusterMembers))
  }

  private def onLeft(err: PreferenceList.Error) = Future.successful(StorageNodeReadingResult.FailedRead)

  private def onRight(r: R, id: UUID, clusterMembers: ClusterMembers)(preferenceList: PreferenceList) = {
    val consensusFuture = gatherReads(r, id, clusterMembers, preferenceList).map(new ConsensusReplicatedReads().reach(r))
    consensusFuture.foreach(triggerReadRepairIfConsequent)
    consensusFuture.map(consensus2ReadingResult)
  }

  private def gatherReads(r: R, id: UUID, clusterMembers: ClusterMembers, preferenceList: PreferenceList) = {
    ResolveNodeAddresses(nodeId, preferenceList, clusterMembers) match {
      case ResolvedNodeAddresses(true, remotes)  if remotes.size + 1 >= r.r => (readLocalData(id) zip remoteDataReader.apply(remotes, id)).map(converge)
      case ResolvedNodeAddresses(false, remotes) if remotes.size >= r.r     => remoteDataReader.apply(remotes, id)
      case _                                                                => Future.successful(List(StorageNodeReadingResult.FailedRead))
    }
  }

  private def triggerReadRepairIfConsequent: PartialFunction[ConsensusSummary, Unit] = {
    case ConsensusSummary.Consequent(data) => println(s"Read Repair is fired of for $data") // TODO: finish this part with real logic
  }

  private def consensus2ReadingResult: ConsensusSummary => StorageNodeReadingResult = {
    case ConsensusSummary.Consequent(data) => StorageNodeReadingResult.Found(data)
    case ConsensusSummary.Found(data)      => StorageNodeReadingResult.Found(data)
    case ConsensusSummary.Conflicts(data)  => StorageNodeReadingResult.Conflicts(data)
    case ConsensusSummary.NotEnoughFound   => StorageNodeReadingResult.NotFound
    case ConsensusSummary.AllFailed        => StorageNodeReadingResult.FailedRead
    case ConsensusSummary.AllNotFound      => StorageNodeReadingResult.NotFound
  }
}
