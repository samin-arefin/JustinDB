package justin.db

import java.util.UUID

import akka.actor.{Actor, ActorRef, Props, RootActorPath}
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import justin.db.consistent_hashing.{NodeId, Ring}
import justin.db.StorageNodeActor.{GetValue, PutLocalValue, PutValue, RegisterNode}
import justin.db.replication.{N, W}
import justin.db.storage.PluggableStorage

import scala.concurrent.ExecutionContext

case class StorageNodeActorRef(storageNodeActor: ActorRef) extends AnyVal

class StorageNodeActor(nodeId: NodeId, storage: PluggableStorage, ring: Ring, n: N)(implicit ec: ExecutionContext) extends Actor {

  val cluster = Cluster(context.system)

  override def preStart(): Unit = cluster.subscribe(self, classOf[MemberUp])
  override def postStop(): Unit = cluster.unsubscribe(self)

  override def receive: Receive = receive(ClusterMembers.empty)

  private def receive(clusterMembers: ClusterMembers): Receive = {
    // READ part
    case GetValue(id)               => sender() ! storage.get(id.toString)

    // WRITE part
    case cmd: PutValue              => writeData(sender(), clusterMembers, cmd)
    case cmd: PutLocalValue         => writeData(sender(), clusterMembers, cmd)

    // CLUSTER part
    case RegisterNode(senderNodeId) if clusterMembers.notContains(senderNodeId) =>
      val storageActorRef = StorageNodeActorRef(sender())
      context.become(receive(clusterMembers.add(senderNodeId, storageActorRef)))
      sender() ! RegisterNode(nodeId)
    case MemberUp(m)                => register(m)
    case state: CurrentClusterState => state.members.filter(_.status == MemberStatus.Up).foreach(register)

    // NOT HANDLED
    case t                          => println("[StorageNodeActor] not handled msg: " + t)
  }

  private def writeData(sender: ActorRef, clusterMembers: ClusterMembers, writeCmd: StorageNodeActor.WriteDataReq) = {
    val cmd = writeCmd match {
      case StorageNodeActor.PutValue(w, data)   => StorageNodeWriteData.Replicate(w, data)
      case StorageNodeActor.PutLocalValue(data) => StorageNodeWriteData.Local(data)
    }
    val writeData = new StorageNodeWriteService(nodeId, clusterMembers, ring, n, storage)
    writeData.apply(cmd).map {
      case StorageNodeWritingResult.SuccessfulWrite => sender ! StorageNodeActor.SuccessfulWrite
      case StorageNodeWritingResult.FailedWrite     => sender ! StorageNodeActor.UnsuccessfulWrite
    }
  }

  private def register(member: Member) = {
    val siblingNodes = ring.nodesId.filterNot(_ == nodeId)
    val nodesNames   = siblingNodes.map(StorageNodeActor.name)

    nodesNames.foreach { name =>
      context.actorSelection(RootActorPath(member.address) / "user" / s"$name") ! RegisterNode(nodeId)
    }
  }
}

object StorageNodeActor {

  sealed trait StorageNodeCmd

  // read part
  case class GetValue(id: UUID) extends StorageNodeCmd

  // write part
  sealed trait WriteDataReq
  case class PutValue(w: W, data: Data) extends StorageNodeCmd with WriteDataReq
  case class PutLocalValue(data: Data)  extends StorageNodeCmd with WriteDataReq
  case object SuccessfulWrite           extends StorageNodeCmd
  case object UnsuccessfulWrite         extends StorageNodeCmd

  // cluster part
  case class RegisterNode(nodeId: NodeId) extends StorageNodeCmd

  def role: String = "StorageNode"

  def name(nodeId: NodeId): String = s"id-${nodeId.id}"

  def props(nodeId: NodeId, storage: PluggableStorage, ring: Ring, n: N)(implicit ec: ExecutionContext): Props = {
    Props(new StorageNodeActor(nodeId, storage, ring, n))
  }
}
