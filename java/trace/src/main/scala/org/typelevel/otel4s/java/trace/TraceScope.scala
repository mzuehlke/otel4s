/*
 * Copyright 2022 Typelevel
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

package org.typelevel.otel4s.java.trace

import cats.effect.IOLocal
import cats.effect.LiftIO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.Sync
import cats.syntax.all._
import io.opentelemetry.api.trace.{Span => JSpan}
import io.opentelemetry.context.{Context => JContext}
import org.typelevel.otel4s.trace.SpanContext

private[java] trait TraceScope[F[_]] {
  import TraceScope.Scope
  def root: F[Scope.Root]
  def current: F[Scope]
  def makeScope(span: JSpan): Resource[F, Unit]
  def rootScope: Resource[F, Unit]
  def noopScope: Resource[F, Unit]
}

private[java] object TraceScope {

  sealed trait Scope
  object Scope {
    final case class Root(ctx: JContext) extends Scope
    final case class Span(
        ctx: JContext,
        span: JSpan,
        spanContext: SpanContext
    ) extends Scope
    case object Noop extends Scope
  }

  def fromIOLocal[F[_]: LiftIO: Sync](
      default: JContext
  ): F[TraceScope[F]] = {
    val scopeRoot = Scope.Root(default)

    Ref.of[F, Scope](scopeRoot).flatMap { ref =>
      IOLocal[Ref[F, Scope]](ref).to[F].map { local =>
        new TraceScope[F] {
          val root: F[Scope.Root] =
            Sync[F].pure(scopeRoot)

          def current: F[Scope] =
            local.get.to[F].flatMap(_.get)

          def makeScope(span: JSpan): Resource[F, Unit] =
            for {
              current <- Resource.eval(current)
              _ <- createScope(nextScope(current, span))
            } yield ()

          def rootScope: Resource[F, Unit] =
            Resource.eval(current).flatMap {
              case Scope.Root(_) =>
                createScope(scopeRoot)

              case Scope.Span(_, _, _) =>
                createScope(scopeRoot)

              case Scope.Noop =>
                createScope(Scope.Noop)
            }

          def noopScope: Resource[F, Unit] =
            createScope(Scope.Noop)

          private def createScope(scope: Scope): Resource[F, Unit] = {
            val acquire =
              local.get.to[F].product(Ref.of[F, Scope](scopeRoot)).flatTap {
                case (_, nextRef) =>
                  local.set(nextRef).to[F]
              }
            def release(oldRef: (Ref[F, Scope], Any)): F[Unit] =
              local.set(oldRef._1).to[F]
            Resource.make(acquire)(release) >>
              Resource
                .make(local.get.to[F].flatMap(_.getAndSet(scope)))(p =>
                  local.get.to[F].flatMap(_.set(p))
                )
                .void
          }

          private def nextScope(scope: Scope, span: JSpan): Scope =
            scope match {
              case Scope.Root(ctx) =>
                Scope.Span(
                  ctx.`with`(span),
                  span,
                  WrappedSpanContext(span.getSpanContext)
                )

              case Scope.Span(ctx, _, _) =>
                Scope.Span(
                  ctx.`with`(span),
                  span,
                  WrappedSpanContext(span.getSpanContext)
                )

              case Scope.Noop =>
                Scope.Noop
            }

        }
      }
    }
  }

}
