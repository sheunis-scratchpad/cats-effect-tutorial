package ex2

import java.io._
import java.net.{ServerSocket, Socket}
import java.util.concurrent.Executors

import cats.effect.ExitCase._
import cats.effect._
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.util.Try

object EchoServer extends IOApp {

  def echoProtocol[F[_]: Sync: ContextShift](clientSocket: Socket,
                                             stopFlag: MVar[F, Unit])(
      implicit clientsExecutionContext: ExecutionContext): F[Unit] = {
    def loop(reader: BufferedReader,
             writer: BufferedWriter,
             stopFlag: MVar[F, Unit]): F[Unit] =
      for {
        lineE <- ContextShift[F].evalOn(clientsExecutionContext)(
          Sync[F].delay(reader.readLine()).attempt)
        _ <- lineE match {
          case Right(line) =>
            line match {
              case "STOP" =>
                stopFlag.put(()) // Stopping server! Also put(()) returns F[Unit] which is handy as we are done
              case "" => Sync[F].unit // Empty line, we are done
              case _ =>
                Sync[F].delay {
                  writer.write(line); writer.newLine(); writer.flush()
                } >> loop(reader, writer, stopFlag)
            }
          case Left(e) =>
            for { // readLine() failed, stopFlag will tell us whether this is a graceful shutdown
              isEmpty <- stopFlag.isEmpty
              _ <- if (!isEmpty)
                Sync[F].unit // stopFlag is set, cool, we are done
              else Sync[F].raiseError(e) // stopFlag not set, must raise error
            } yield ()
        }
      } yield ()

    def reader(clientSocket: Socket): Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay(
          new BufferedReader(
            new InputStreamReader(clientSocket.getInputStream())))
      } { reader =>
        Sync[F].delay(reader.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def writer(clientSocket: Socket): Resource[F, BufferedWriter] =
      Resource.make {
        Sync[F].delay(
          new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())))
      } { writer =>
        Sync[F].delay(writer.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def readerWriter(
        clientSocket: Socket): Resource[F, (BufferedReader, BufferedWriter)] =
      for {
        reader <- reader(clientSocket)
        writer <- writer(clientSocket)
      } yield (reader, writer)

    readerWriter(clientSocket).use {
      case (reader, writer) =>
        loop(reader, writer, stopFlag) // Let's get to work
    }
  }

  def serve[F[_]: Concurrent: ContextShift](serverSocket: ServerSocket,
                                            stopFlag: MVar[F, Unit])(
      implicit clientsExecutionContext: ExecutionContext): F[Unit] = {
    def close(socket: Socket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    for {
      fiber <- Sync[F]
        .delay(serverSocket.accept())
        .bracketCase { socket =>
          echoProtocol(socket, stopFlag)
            .guarantee(close(socket)) // Ensuring socket is closed
            .start // Client attended by its own Fiber, socket is returned
        } { (socket, exit) =>
          exit match {
            case Completed           => Sync[F].unit
            case Error(_) | Canceled => close(socket)
          }
        }
      _ <- (stopFlag.read >> fiber.cancel).start // Another Fiber to cancel the client when stopFlag is set
      _ <- serve(serverSocket, stopFlag) // Looping back to the beginning
    } yield ()
  }

  def server[F[_]: Concurrent: ContextShift](
      serverSocket: ServerSocket): F[ExitCode] = {
    val clientsThreadPool = Executors.newCachedThreadPool()
    implicit val clientsExecutionContext =
      ExecutionContext.fromExecutor(clientsThreadPool)

    for {
      stopFlag <- MVar[F].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start // Server runs on its own Fiber
      _ <- stopFlag.read // Blocked until 'stopFlag.put(())' is run
      _ <- Sync[F].delay(clientsThreadPool.shutdown()) // Shutting down clients pool
      _ <- serverFiber.cancel.start // Stopping server!
    } yield ExitCode.Success
  }

  def run(args: List[String]): IO[ExitCode] = {
    def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    IO { new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) }
      .bracket { serverSocket =>
        server[IO](serverSocket) >> IO.pure(ExitCode.Success)
      } { serverSocket =>
        close[IO](serverSocket) >> IO(println("Server finished"))
      }
  }
}
