package eventstore

import akka.testkit.{ TestKitBase, TestProbe, TestActorRef }
import akka.actor.ActorRef
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.annotation.tailrec
import ReadDirection.Backward

class SubscribeToAllCatchingUpITest extends TestConnection {
  sequential

  "subscribe to all catching up" should {
    "be able to subscribe to empty db" in {
      // todo how to setup empty db ?
      todo
    }

    "read all existing events and keep listening to new ones" in new SubscribeToAllCatchingUpScope {
      appendMany()

      val existing = readAllEventsCompleted(Position.First, 1000)(Backward)
        .events.map(_.event.data).reverse
      val subscriptionActor = newSubscription()
      expectEvents(existing)

      fishForLiveProcessingStarted()

      val newEvents = writeAsync()
      expectEvents(newEvents)
    }

    "filter events and keep listening to new ones" in new SubscribeToAllCatchingUpScope {
      val many = 10
      appendMany(many)
      val position = allStreamsEvents()(Backward).take(many).last.position
      val subscriptionActor = newSubscription(Some(position))

      val processingStartedPosition = fishForLiveProcessingStarted(position)

      val events = writeAsync()
      expectEvents(events, processingStartedPosition)
    }

    "filter events and work if nothing was written after subscription" in new SubscribeToAllCatchingUpScope {
      val many = 10
      appendMany(many)
      val position = allStreamsEvents()(Backward).take(many).last.position
      val subscriptionActor = newSubscription(Some(position))

      fishForLiveProcessingStarted(position)
      expectNoEvents()
    }

    "allow multiple subscriptions" in new SubscribeToAllCatchingUpScope {
      val position = allStreamsEvents()(Backward).take(10).last.position
      val probes = List.fill(5)(TestProbe.apply)
      probes.foreach(x => newSubscription(Some(position), client = x.ref))
      probes.foreach(x => fishForLiveProcessingStarted(testKit = x))
      val event = append(newEventData).data
      probes.foreach(x => expectEvents(List(event), testKit = x))
    }

    "read link events if resolveLinkTos = false" in new SubscribeToAllCatchingUpScope {
      val last = lastPosition
      val (linked, link) = linkedAndLink()
      newSubscription(Some(last), resolveLinkTos = false)
      expectIndexedEvent.event mustEqual linked
      expectIndexedEvent
      expectIndexedEvent.event mustEqual link
      fishForLiveProcessingStarted(last)
    }

    "read link events if resolveLinkTos = true" in new SubscribeToAllCatchingUpScope {
      val last = lastPosition
      val (linked, link) = linkedAndLink()
      newSubscription(Some(last), resolveLinkTos = true)
      expectIndexedEvent.event mustEqual linked
      expectIndexedEvent
      expectIndexedEvent.event mustEqual ResolvedEvent(linked, link)
      fishForLiveProcessingStarted(last)
    }

    "catch link events if resolveLinkTos = false" in new SubscribeToAllCatchingUpScope {
      newSubscription(Some(lastPosition), resolveLinkTos = false)
      fishForLiveProcessingStarted()
      val (linked, link) = linkedAndLink()
      expectIndexedEvent.event mustEqual linked
      expectIndexedEvent
      expectIndexedEvent.event mustEqual link
    }

    "catch link events if resolveLinkTos = true" in new SubscribeToAllCatchingUpScope {
      newSubscription(Some(lastPosition), resolveLinkTos = true)
      fishForLiveProcessingStarted()
      val (linked, link) = linkedAndLink()
      expectIndexedEvent.event mustEqual linked
      expectIndexedEvent
      expectIndexedEvent.event mustEqual ResolvedEvent(linked, link)
    }
  }

  trait SubscribeToAllCatchingUpScope extends TestConnectionScope {

    def lastPosition = allStreamsEvents()(Backward).head.position

    def newSubscription(
      fromPositionExclusive: Option[Position.Exact] = None,
      resolveLinkTos: Boolean = false,
      client: ActorRef = testActor) = TestActorRef(SubscriptionActor.props(
      connection = actor,
      client = client,
      fromPositionExclusive = fromPositionExclusive,
      resolveLinkTos = resolveLinkTos,
      readBatchSize = Settings.Default.readBatchSize))

    def expectEvents(
      events: List[EventData],
      position: Position = Position.First,
      testKit: TestKitBase = this): List[IndexedEvent] = {

      def loop(events: List[EventData], position: Position): List[IndexedEvent] = events match {
        case Nil => Nil
        case head :: tail =>
          val indexedEvent = testKit.expectIndexedEvent
          indexedEvent.position must beGreaterThanOrEqualTo(position)
          indexedEvent.event must beAnInstanceOf[EventRecord]
          if (indexedEvent.event.data == head) indexedEvent :: loop(tail, indexedEvent.position)
          else loop(events, indexedEvent.position)
      }
      loop(events, position)
    }

    @tailrec final def expectNoEvents() {
      receiveOne(1.second) match {
        case null =>
        case msg =>
          msg must beAnInstanceOf[IndexedEvent]
          val indexedEvent = msg.asInstanceOf[IndexedEvent]
          val streamId = indexedEvent.event.streamId
          streamId.isSystem must beTrue
          expectNoEvents()
      }
    }

    @tailrec
    final def fishForLiveProcessingStarted(
      position: Position = Position.First,
      testKit: TestKitBase = this): Position = testKit.expectMsgType[AnyRef] match {
      case LiveProcessingStarted => position
      case IndexedEvent(_, x) =>
        x must beGreaterThanOrEqualTo(position)
        fishForLiveProcessingStarted(x, testKit)
    }

    def writeAsync(size: Int = 20): List[EventData] = {
      val events = (1 to size).map(_ => newEventData).toList
      import system.dispatcher
      Future {
        writeEventsCompleted(events, testKit = TestProbe())
      }
      events
    }

    def expectIndexedEvent: IndexedEvent = new RichTestKitBase(this).expectIndexedEvent

    implicit class RichTestKitBase(self: TestKitBase) {
      def expectIndexedEvent: IndexedEvent = self.expectMsgType[IndexedEvent].fixDate
    }
  }
}