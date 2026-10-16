package lending.domain

import java.util.UUID

import com.fasterxml.uuid.Generators

// Every UUID in the app comes from here.
object Uuids {

  // UUIDv7: time-ordered, so new primary keys land at the end of the index.
  private val timeOrdered = Generators.timeBasedEpochGenerator()

  // UUIDv4: for opaque references that get truncated, where v7's leading
  // timestamp would make the kept prefix nearly constant.
  private val randomBased = Generators.randomBasedGenerator()

  def next(): UUID = timeOrdered.generate()

  def random(): UUID = randomBased.generate()

}
