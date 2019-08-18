import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
import scala.util.Either

def delayed[F[_]: Async]: F[String] = for {
  _ <- Sync[F].delay(println("Starting")) // Async extends Sync, so (F[_]: Async) 'brings' (F[_]: Sync)
  a <- Async[F].async{ (cb: Either[Throwable,String] => Unit) =>
    Thread.sleep(2000)
    cb(Right("abc"))
  }
  _ <- Sync[F].delay(println("Done")) // 2 seconds to get here, no matter what, as we were 'blocked' by previous call
} yield a

delayed[IO].unsafeRunSync() // a way to run an IO without IOApp
