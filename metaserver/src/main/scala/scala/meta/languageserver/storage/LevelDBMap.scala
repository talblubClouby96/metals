package scala.meta.languageserver.storage

import java.io.File
import com.typesafe.scalalogging.LazyLogging
import monix.execution.schedulers.TestScheduler.Task
import monix.reactive.Observable
import org.fusesource.leveldbjni.JniDBFactory
import org.iq80.leveldb.DB
import org.iq80.leveldb.DBException
import org.iq80.leveldb.Options

/**
 * A Scala-friendly wrapper around the JniDBFactory Java-wrapper around leveldb.
 *
 * @param db The leveldb, remember to close it after using. This wrapper will NOT
 *           close the db for you.
 */
class LevelDBMap(db: DB) extends LazyLogging {

  /** Returns the value matching key, if any. */
  def get[Key, Value](key: Key)(
      implicit
      keys: ToBytes[Key],
      values: FromBytes[Value]
  ): Option[Value] = {
    try {
      Option(db.get(keys.toBytes(key))).map(values.fromBytes)
    } catch {
      case e: DBException =>
        logger.error(e.getMessage, e)
        None
    }
  }

  /**
   * Gets the value if it exists, otherwise computes the fallback value and stores it.
   *
   * This method is not thread-safe, the computed fallback value may get overwritten.
   */
  def getOrElseUpdate[Key, Value](key: Key, orElse: () => Value)(
      implicit
      keys: ToBytes[Key],
      valuesFrom: FromBytes[Value],
      valuesTo: ToBytes[Value]
  ): Value = {
    get(key) match {
      case Some(value) => value
      case None =>
        val computed = orElse()
        put(key, computed)
    }
  }

  /** Inserts a new value for the given key. */
  def put[Key, Value](key: Key, value: Value)(
      implicit
      keys: ToBytes[Key],
      values: ToBytes[Value]
  ): Value = {
    try {
      db.put(keys.toBytes(key), values.toBytes(value))
      value
    } catch {
      case e: DBException =>
        logger.error(e.getMessage, e)
        value
    }
  }

  def close(): Unit = db.close()
}

object LevelDBMap {

  /** Construct new wrapper around a leveldb. */
  def apply(db: DB): LevelDBMap =
    new LevelDBMap(db)

  /**
   * Creates a new leveldb in the given directory.
   *
   * Make sure to `db.close()`.
   */
  def createDBThatIPromiseToClose(directory: File): DB = {
    val options = new Options
    options.createIfMissing(true)
    options.maxOpenFiles()
    JniDBFactory.factory.open(directory, options)
  }

  def withDB[T](directory: File)(f: LevelDBMap => T): T = {
    // TODO(olafur) gracefully fallback when the db is in use by another thread.
    // can happen with multiple language servers running at the same time.
    val db = createDBThatIPromiseToClose(directory)
    try {
      f(apply(db))
    } finally {
      db.close()
    }
  }

}
