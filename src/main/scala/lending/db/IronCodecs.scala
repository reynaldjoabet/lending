package lending.db

import io.github.iltotore.iron.*
import skunk.Codec

// Local copy of iron-skunk's `refined`: iron-skunk 3.3.2 is built against skunk 1.x
// and fails sbt's early-semver eviction check with skunk 2.x. Drop this and go back
// to `io.github.iltotore.iron.skunk.*` once iron ships an iron-skunk for skunk 2.x.
object IronCodecs {

  extension [A](codec: Codec[A]) {

    def refined[C](using RuntimeConstraint[A, C]): Codec[A :| C] =
      codec.eimap[A :| C](_.refineEither[C])(_.asInstanceOf[A])

  }

}
