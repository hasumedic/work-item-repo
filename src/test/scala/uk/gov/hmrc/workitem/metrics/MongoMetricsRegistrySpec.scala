/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.workitem.metrics

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.MetricsRegistry
import org.joda.time.Duration
import org.mockito.Matchers._
import org.mockito.{Matchers, Mockito}
import org.mockito.Mockito._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, LoneElement}
import play.api.test.FakeApplication
import reactivemongo.api.ReadPreference
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.workitem.{ProcessingStatus, WithWorkItemRepository}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class MongoMetricsRegistrySpec extends UnitSpec
with ScalaFutures
with WithWorkItemRepository
with BeforeAndAfterAll
with Eventually
with LoneElement
with WithFakeApplication
with MockitoSugar {

  override implicit val patienceConfig = PatienceConfig(timeout = 5 seconds, interval = 100 millis)

  override lazy val fakeApplication = FakeApplication(additionalPlugins = Seq("com.kenshoo.play.metrics.MetricsPlugin"))

  lazy val repo2 = exampleItemRepository("items2")

  override def beforeEach(): Unit = {
    super.beforeEach()
    MetricsRegistry.defaultRegistry.getGauges.asScala.foldRight(true) { case ((name, _), acc) =>
      acc && MetricsRegistry.defaultRegistry.remove(name)
    } shouldBe true
    repo2.removeAll().futureValue
  }

  private val exclusiveTimePeriodLock = new ExclusiveTimePeriodLock {
    override val lockId: String = "test-metrics"
    override val repo: LockRepository = new LockRepository()
    override val holdLockFor = Duration.millis(1)
  }

  def registryFor(sources: List[MetricsSource]) = new MongoMetricsRegistry {
    override val metricsRepository = new MongoMetricsRepository
    override val metricsSources = sources
    override val lock = exclusiveTimePeriodLock
    override val metricsRegistry: MetricRegistry = MetricsRegistry.defaultRegistry
  }


  "mongo metrics registry" should {
    "refresh all metrics of a workitemRepository" in {
      Future.traverse(ProcessingStatus.processingStatuses) { status =>
        for {
          item <- repo.pushNew(item1, DateTimeUtils.now)
          _ <- repo.markAs(item.id, status)
        } yield ()
      }.futureValue

      ProcessingStatus.processingStatuses.foreach { status =>
        MetricsRegistry.defaultRegistry.getGauges.containsKey(s"items.${status.name}") shouldBe false
      }

      val registry = registryFor(List(repo))

      registry.refreshAll().futureValue

      ProcessingStatus.processingStatuses.foreach { status =>
        MetricsRegistry.defaultRegistry.getGauges.get(s"items.${status.name}").getValue shouldBe 1
      }

    }

    "refresh all metrics of any type of source" in {

      val anySource = new MetricsSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val registry = registryFor(List(anySource))

      registry.refreshAll().futureValue

      MetricsRegistry.defaultRegistry.getGauges.get(s"a").getValue shouldBe 1
      MetricsRegistry.defaultRegistry.getGauges.get(s"b").getValue shouldBe 2

    }

    "be calculated across multiple soruces" in {

      Future.traverse(ProcessingStatus.processingStatuses) { status =>
        for {
          item <- repo.pushNew(item1, DateTimeUtils.now)
          _ <- repo.markAs(item.id, status)
        } yield ()
      }.futureValue

      val anotherSource = new MetricsSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val registry = registryFor(List(repo, anotherSource))

      registry.refreshAll().futureValue

      MetricsRegistry.defaultRegistry.getGauges.get(s"a").getValue shouldBe 1
      MetricsRegistry.defaultRegistry.getGauges.get(s"b").getValue shouldBe 2
      ProcessingStatus.processingStatuses.foreach { status =>
        MetricsRegistry.defaultRegistry.getGauges.get(s"items.${status.name}").getValue shouldBe 1
      }

    }

    "cache the metrics" in {
      val anySource = new MetricsSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val mockedRegistry = new MongoMetricsRegistry {
        override val metricsRepository = mock[MongoMetricsRepository]
        override val metricsSources = List(anySource)
        override val lock = exclusiveTimePeriodLock
        override val metricsRegistry: MetricRegistry = MetricsRegistry.defaultRegistry
      }

      when(mockedRegistry.metricsRepository.findAll(any[ReadPreference])(any[ExecutionContext]))
        .thenReturn(Future(List(MetricsCount("a",1), MetricsCount("b",2))))

      when(mockedRegistry.metricsRepository.update(any[MetricsCount])(any[ExecutionContext]))
        .thenReturn(mock[Future[Option[MetricsCount]]])

      mockedRegistry.refreshAll().futureValue

      verify(mockedRegistry.metricsRepository).findAll(any[ReadPreference])(any[ExecutionContext])
      verify(mockedRegistry.metricsRepository, times(2)).update(any[MetricsCount])(any[ExecutionContext])


      MetricsRegistry.defaultRegistry.getGauges.get(s"a").getValue shouldBe 1
      MetricsRegistry.defaultRegistry.getGauges.get(s"b").getValue shouldBe 2

      verifyNoMoreInteractions(mockedRegistry.metricsRepository)
    }

    "update the cache even if the lock is not acquired" in {
      val anySource = new MetricsSource {
        override def metrics(implicit ec: ExecutionContext) = Future.successful(Map("a" -> 1, "b" -> 2))
      }

      val mockedRegistry = new MongoMetricsRegistry {
        override val metricsRepository = mock[MongoMetricsRepository]
        override val metricsSources = List(anySource)
        override val lock = new ExclusiveTimePeriodLock {
          override val lockId: String = "test-lock"
          override val repo: LockRepository = mock[LockRepository]
          override val holdLockFor: Duration = Duration.millis(1)
        }
        override val metricsRegistry: MetricRegistry = MetricsRegistry.defaultRegistry
      }

      when(mockedRegistry.lock.repo.renew(any[String], any[String], any[Duration])).thenReturn(Future(false))
      when(mockedRegistry.lock.repo.lock(any[String], any[String], any[Duration])).thenReturn(Future(false))
      when(mockedRegistry.metricsRepository.findAll(any[ReadPreference])(any[ExecutionContext]))
        .thenReturn(Future(List(MetricsCount("a",1), MetricsCount("b",2))))

      mockedRegistry.refreshAll().futureValue

      verify(mockedRegistry.metricsRepository).findAll(any[ReadPreference])(any[ExecutionContext])

      MetricsRegistry.defaultRegistry.getGauges.get(s"a").getValue shouldBe 1
      MetricsRegistry.defaultRegistry.getGauges.get(s"b").getValue shouldBe 2

      verifyNoMoreInteractions(mockedRegistry.metricsRepository)

    }
  }

  "MetricsCache" should {

    "be empty when created" in {
      new MetricsCache {} .cache shouldBe empty
    }

    "initialize with a list of metrics" in {
      val metrics =  new MetricsCache {}
      metrics.refreshCache(List(MetricsCount("a", 1), MetricsCount("b", 2)))

      metrics.cache("a") shouldBe 1
      metrics.cache("b") shouldBe 2
    }

    "add elements that were not present before" in {
      val metrics =  new MetricsCache {}

      metrics.refreshCache(List(MetricsCount("a", 1)))

      metrics.cache("a") shouldBe 1
      metrics.cache.get("b") shouldBe None

      metrics.refreshCache(List(MetricsCount("a", 1), MetricsCount("b", 2)))

      metrics.cache("a") shouldBe 1
      metrics.cache("b") shouldBe 2

    }

    "remove elements that are no longer there" in {
      val metrics =  new MetricsCache {}

      metrics.refreshCache(List(MetricsCount("a", 1), MetricsCount("b", 2)))

      metrics.cache("a") shouldBe 1
      metrics.cache("b") shouldBe 2

      metrics.refreshCache(List(MetricsCount("a", 1)))

      metrics.cache("a") shouldBe 1
      metrics.cache.get("b") shouldBe None
    }
  }

}
