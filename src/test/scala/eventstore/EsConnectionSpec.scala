package eventstore

import akka.actor.Status.Failure
import org.specs2.mock.Mockito
import scala.util.Success
import util.ActorSpec

class EsConnectionSpec extends ActorSpec with Mockito {
  "EventStoreConnection.future" should {
    "write events" in new TestScope {
      verifyOutIn(mock[WriteEvents], mock[WriteEventsCompleted])
    }

    "delete stream" in new TestScope {
      verifyOutIn(mock[DeleteStream], DeleteStreamCompleted())
    }

    "transaction start" in new TestScope {
      verifyOutIn(mock[TransactionStart], mock[TransactionStartCompleted])
    }

    "transaction write" in new TestScope {
      verifyOutIn(mock[TransactionWrite], mock[TransactionWriteCompleted])
    }

    "transaction commit" in new TestScope {
      verifyOutIn(mock[TransactionCommit], mock[TransactionCommitCompleted])
    }

    "read event" in new TestScope {
      verifyOutIn(mock[ReadEvent], mock[ReadEventCompleted])
    }

    "read stream events" in new TestScope {
      verifyOutIn(mock[ReadStreamEvents], mock[ReadStreamEventsCompleted])
    }

    "read all events" in new TestScope {
      verifyOutIn(mock[ReadAllEvents], mock[ReadAllEventsCompleted])
    }

    "subscribe to" in new TestScope {
      verifyOutIn(mock[SubscribeTo], mock[SubscribeToStreamCompleted])
    }

    "set stream metadata" in new TestScope {
      val content = Content(ByteString(1, 2, 3))
      val sId = streamId.metadata
      val future = connection.setStreamMetadata(streamId, content)

      expectMsgPF() {
        case WriteEvents(`sId`, List(EventData(SystemEventType.metadata, _, `content`, _)), ExpectedVersion.Any, true) => true
      }
      lastSender ! WriteEventsCompleted()
      future.await_ must beNone
    }

    "get stream metadata" in new GetMetadataScope {
      val content = Content(ByteString(1, 2, 3))
      val metadata = EventRecord(streamId.metadata, EventNumber.First, EventData(SystemEventType.metadata, data = content))
      getStreamMetadata(ReadEventCompleted(metadata)) mustEqual content
    }

    "get empty metadata when stream not found" in new GetMetadataScope {
      getStreamMetadata(failure(EsError.StreamNotFound)) mustEqual Content.Empty
    }

    "get empty metadata when stream deleted" in new GetMetadataScope {
      getStreamMetadata(failure(EsError.StreamNotFound)) mustEqual Content.Empty
    }

    "throw exception if non metadata event received" in new GetMetadataScope {
      val event = EventRecord(streamId, EventNumber.First, EventData("test", data = Content.Empty))
      getStreamMetadata(ReadEventCompleted(event)) must throwAn[EsException].like {
        case EsException(EsError.NonMetadataEvent(event), Some(_)) => ok
      }
    }
  }

  private trait TestScope extends ActorScope {
    val streamId = EventStream.Id("streamId")
    val events = Seq(EventData("test"))
    val connection = new EsConnection(testActor, system)

    def verifyOutIn[OUT <: Out, IN <: In](out: OUT, in: In)(implicit outIn: OutInTag[OUT, IN]) {
      val future = connection.future(out)(outIn = outIn)
      expectMsg(out)
      future.value must beNone
      lastSender ! in
      future.value must beSome(Success(in))
    }
  }

  private trait GetMetadataScope extends TestScope {
    def getStreamMetadata(reply: Any): Content = {
      val future = connection.getStreamMetadata(streamId)
      expectMsg(ReadEvent.StreamMetadata(streamId.metadata))
      lastSender ! reply
      future.await_
    }

    def failure(x: EsError) = Failure(EsException(x))
  }
}