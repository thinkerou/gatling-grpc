package me.macchiatow.gatling.grpc.action

import com.trueaccord.scalapb.{GeneratedMessage => GenM}
import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.util.ClockSingleton.nowMillis
import io.gatling.commons.validation.{Failure, Success, Validation}
import io.gatling.core.CoreComponents
import io.gatling.core.action.{Action, ExitableAction}
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine
import io.gatling.core.stats.message.ResponseTimings
import io.gatling.core.util.NameGen
import io.grpc.CallOptions
import io.grpc.stub.ClientCalls
import me.macchiatow.gatling.grpc.action.GrpcActionBuilder.GrpcRequestAttributes

import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.Try

class GrpcAction[ReqT <: GenM, ResT <: GenM](grpcRequestAttributes: GrpcRequestAttributes[ReqT, ResT],
                                             checks: Seq[ResT => Boolean],
                                             coreComponents: CoreComponents,
                                             throttled: Boolean,
                                             val next: Action)(implicit reqTag: ClassTag[ReqT])
  extends ExitableAction with NameGen {

  override def statsEngine: StatsEngine = coreComponents.statsEngine

  override def name: String = genName("grpcRequest")

  def validateRequest[T](session: Session)(implicit reqT: ClassTag[T]): Validation[T] = {
    grpcRequestAttributes.request(session) flatMap {
      case req: T =>
        Success(req)
      case req =>
        val err = s"Feeder type mismatch: required $reqT, but found ${req.getClass}"
        statsEngine.reportUnbuildableRequest(session, name, err)
        Failure(err)
    }
  }

  override def execute(session: Session): Unit = recover(session) {
    validateRequest(session) flatMap { req =>

      grpcRequestAttributes.requestName(session) map { requestName =>
        val requestStartDate = nowMillis

        val result = Try {
          ClientCalls.blockingUnaryCall(grpcRequestAttributes.channel, grpcRequestAttributes.methodDescriptor, CallOptions.DEFAULT, req)
        } flatMap { response =>
          if (checks.forall(_ (response))) scala.util.Success(response)
          else scala.util.Failure(new AssertionError("check failed"))
        } toEither

        val requestEndDate = nowMillis

        statsEngine.logResponse(
          session,
          requestName,
          ResponseTimings(requestStartDate, requestEndDate),
          if (result.isRight) OK else KO,
          None,
          result.left.toOption.map(_.getMessage)
        )

        if (throttled) {
          coreComponents.throttler.throttle(session.scenario, () => next ! session)
        } else {
          next ! session
        }
      }
    }
  }
}
