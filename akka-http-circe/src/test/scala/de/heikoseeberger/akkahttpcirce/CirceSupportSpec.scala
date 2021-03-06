/*
 * Copyright 2015 Heiko Seeberger
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

package de.heikoseeberger.akkahttpcirce

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.{ HttpEntity, MediaTypes, RequestEntity }
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.stream.ActorMaterializer
import cats.data.NonEmptyList
import io.circe.CursorOp.DownField
import io.circe.{ DecodingFailure, Errors }
import org.scalatest.{ AsyncWordSpec, BeforeAndAfterAll, Matchers }
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object CirceSupportSpec {

  final case class Foo(bar: String) {
    require(bar == "bar", "bar must be 'bar'!")
  }

  final case class MultiFoo(a: String, b: String)
}

final class CirceSupportSpec extends AsyncWordSpec with Matchers with BeforeAndAfterAll {
  import CirceSupportSpec._

  private implicit val system = ActorSystem()
  private implicit val mat    = ActorMaterializer()
  private implicit val ec     = system.dispatcher

  /**
    * Specs common to both [[FailFastCirceSupport]] and [[ErrorAccumulatingCirceSupport]]
    */
  private def commonCirceSupport(support: BaseCirceSupport) = {
    import io.circe.generic.auto._
    import support._

    "enable marshalling and unmarshalling objects for generic derivation" in {
      val foo = Foo("bar")
      Marshal(foo)
        .to[RequestEntity]
        .flatMap(Unmarshal(_).to[Foo])
        .map(_ shouldBe foo)
    }

    "provide proper error messages for requirement errors" in {
      val entity = HttpEntity(MediaTypes.`application/json`, """{ "bar": "baz" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ should have message "requirement failed: bar must be 'bar'!")
    }

    "fail with NoContentException when unmarshalling empty entities" in {
      val entity = HttpEntity.empty(`application/json`)
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ shouldBe Unmarshaller.NoContentException)
    }

    "fail with UnsupportedContentTypeException when Content-Type is not `application/json`" in {
      val entity = HttpEntity("""{ "bar": "bar" }""")
      Unmarshal(entity)
        .to[Foo]
        .failed
        .map(_ shouldBe UnsupportedContentTypeException(`application/json`))
    }
  }

  "FailFastCirceSupport" should {
    import FailFastCirceSupport._
    import io.circe.generic.auto._

    behave like commonCirceSupport(FailFastCirceSupport)

    "fail-fast and return only the first unmarshalling error" in {
      val entity = HttpEntity(MediaTypes.`application/json`, """{ "a": 1, "b": 2 }""")
      val error  = DecodingFailure("String", List(DownField("a")))
      Unmarshal(entity)
        .to[MultiFoo]
        .failed
        .map(_ shouldBe error)
    }
  }

  "ErrorAccumulatingCirceSupport" should {
    import ErrorAccumulatingCirceSupport._
    import io.circe.generic.auto._

    behave like commonCirceSupport(ErrorAccumulatingCirceSupport)

    "accumulate and return all unmarshalling errors" in {
      val entity = HttpEntity(MediaTypes.`application/json`, """{ "a": 1, "b": 2 }""")
      val errors =
        NonEmptyList.of(
          DecodingFailure("String", List(DownField("a"))),
          DecodingFailure("String", List(DownField("b")))
        )
      Unmarshal(entity)
        .to[MultiFoo]
        .failed
        .map(_ shouldBe Errors(errors))
    }
  }

  override protected def afterAll() = {
    Await.ready(system.terminate(), 42.seconds)
    super.afterAll()
  }
}
