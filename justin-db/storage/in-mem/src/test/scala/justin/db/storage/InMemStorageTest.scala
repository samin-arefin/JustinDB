package justin.db.storage

import java.util.UUID

import justin.db.Data
import justin.db.storage.PluggableStorageProtocol.{Ack, StorageGetData, StoragePutData}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import language.postfixOps

class InMemStorageTest extends FlatSpec with Matchers {

  behavior of "In-memory storage"

  it should "store single data" in {
    // given
    val data = StoragePutData.Single(Data(id = UUID.randomUUID(), "some-data"))
    val inMemStorage = new InMemStorage

    // when
    val result = Await.result(inMemStorage.put(data), atMost = 5 seconds)

    // then
    result shouldBe Ack
  }

  it should "store conflicted data" in {
    // given
    val id = UUID.randomUUID()
    val data = StoragePutData.Conflict(id, Data(id, "some-data-1"), Data(id, "some-data-2"))
    val inMemStorage = new InMemStorage

    // when
    val result = Await.result(inMemStorage.put(data), atMost = 5 seconds)

    // then
    result shouldBe Ack
  }

  it should "get single data for appropriate identificator" in {
    // given
    val data = StoragePutData.Single(Data(id = UUID.randomUUID(), "some-data"))
    val inMemStorage = new InMemStorage
    Await.result(inMemStorage.put(data), atMost = 5 seconds)

    // when
    val result = Await.result(inMemStorage.get(data.data.id), atMost = 5 seconds)

    // then
    result shouldBe StorageGetData.Single(data.data)
  }

  it should "get none data for not existing id in memory" in {
    // given
    val noExistingId = UUID.randomUUID()
    val inMemStorage = new InMemStorage

    // when
    val result = Await.result(inMemStorage.get(noExistingId), atMost = 5 seconds)

    // then
    result shouldBe StorageGetData.None
  }
}
