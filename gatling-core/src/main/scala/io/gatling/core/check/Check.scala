/*
 * Copyright 2011-2018 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.core.check

import java.util.{ HashMap => JHashMap }

import scala.annotation.tailrec

import io.gatling.commons.validation._
import io.gatling.core.check.extractor.Extractor
import io.gatling.core.session.{ Expression, Session }

object Check {

  def check[R](response: R, session: Session, checks: List[Check[R]])(implicit preparedCache: JHashMap[Any, Any] = new JHashMap(2)): (Session => Session, Option[Failure]) = {

    @tailrec
    def checkRec(session: Session, checks: List[Check[R]], update: Session => Session, failure: Option[Failure]): (Session => Session, Option[Failure]) =
      checks match {

        case Nil => (update, failure)

        case head :: tail => head.check(response, session) match {
          case Success(checkResult) =>
            checkResult.update match {
              case Some(checkUpdate) =>
                checkRec(
                  session = checkUpdate(session),
                  tail,
                  update = update andThen checkUpdate,
                  failure
                )
              case _ =>
                checkRec(session, tail, update, failure)
            }

          case f: Failure =>
            failure match {
              case None =>
                checkRec(session, tail, update, Some(f))
              case _ => checkRec(session, tail, update, failure)
            }
        }
      }

    checkRec(session, checks, Session.Identity, None)
  }
}

trait Check[R] {

  def check(response: R, session: Session)(implicit preparedCache: JHashMap[Any, Any]): Validation[CheckResult]
}

case class CheckBase[R, P, X](
    preparer:            Preparer[R, P],
    extractorExpression: Expression[Extractor[P, X]],
    validatorExpression: Expression[Validator[X]],
    customName:          Option[String],
    saveAs:              Option[String]
) extends Check[R] {

  def check(response: R, session: Session)(implicit preparedCache: JHashMap[Any, Any]): Validation[CheckResult] = {

    def memoizedPrepared: Validation[P] =
      if (preparedCache == null) {
        preparer(response)
      } else {
        val cachedValue = preparedCache.get(preparer)
        if (cachedValue == null) {
          val prepared = preparer(response)
          preparedCache.put(preparer, prepared)
          prepared
        } else {
          cachedValue.asInstanceOf[Validation[P]]
        }
      }

    def unbuiltName: String = customName.getOrElse("Check")
    def builtName(extractor: Extractor[P, X], validator: Validator[X]): String = customName.getOrElse(s"${extractor.name}.${extractor.arity}.${validator.name}")

    for {
      extractor <- extractorExpression(session).mapError(message => s"$unbuiltName extractor resolution crashed: $message")
      validator <- validatorExpression(session).mapError(message => s"$unbuiltName validator resolution crashed: $message")
      prepared <- memoizedPrepared.mapError(message => s"${builtName(extractor, validator)} preparation crashed: $message")
      actual <- extractor(prepared).mapError(message => s"${builtName(extractor, validator)} extraction crashed: $message")
      matched <- validator(actual).mapError(message => s"${builtName(extractor, validator)}, $message")
    } yield CheckResult(matched, saveAs)
  }
}

object CheckResult {

  val NoopCheckResultSuccess: Validation[CheckResult] = CheckResult(None, None).success
}

case class CheckResult(extractedValue: Option[Any], saveAs: Option[String]) {

  def hasUpdate: Boolean = saveAs.isDefined && extractedValue.isDefined

  def update: Option[Session => Session] =
    for {
      s <- saveAs
      v <- extractedValue
    } yield (session: Session) => session.set(s, v)
}
