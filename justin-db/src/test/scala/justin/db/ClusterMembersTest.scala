package justin.db

import akka.actor.ActorRef
import justin.db.consistent_hashing.NodeId
import org.scalatest.{FlatSpec, Matchers}

class ClusterMembersTest extends FlatSpec with Matchers {

  behavior of "Cluster Members"

  it should "define an \"empty\" method which create an empty ClusterMember with size 0" in {
    // given
    val expectedSize = 0

    // when
    val emtpyClusterMembers = ClusterMembers.empty

    // then
    emtpyClusterMembers.size shouldBe expectedSize
  }

  it should "define immutable \"add\" method for adding pair of NodeId with ActorRef" in {
    // given
    val emptyClusterMembers = ClusterMembers.empty
    val nodeId = NodeId(100)
    val ref = StorageNodeActorRef(ActorRef.noSender)

    // when
    val updatedClusterMembers = emptyClusterMembers.add(nodeId, ref)

    // then
    updatedClusterMembers shouldBe ClusterMembers(Map(nodeId -> ref))
  }

  it should "give false result when asking for non-existent element with \"contains\" method" in {
    // given
    val emptyClusterMembers = ClusterMembers.empty

    // when
    val exists = emptyClusterMembers.contains(NodeId(1))

    // then
    exists shouldBe false
  }

  it should "give positive result when asking for existent element with \"contains\" method" in {
    // given
    val nodeId = NodeId(100)
    val ref = StorageNodeActorRef(ActorRef.noSender)
    val emptyClusterMembers = ClusterMembers.empty.add(nodeId, ref)

    // when
    val exists = emptyClusterMembers.contains(NodeId(100))

    // then
    exists shouldBe true
  }

}
