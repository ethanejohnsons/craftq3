package dev.bluevista.craftq3.engine.game.net;

import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.fs.FileRef;
import dev.bluevista.craftq3.engine.fs.PackMount;
import dev.bluevista.craftq3.engine.fs.PureManifest;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code SV_VerifyPaks_f}: checks a client's {@code cp <serverId> <cgame> <ui> @ <paks...>
 * <encoded>} reply against the server's pure state. The cgame and ui checksums must be those of the
 * packs the server resolves {@code vm/cgame.qvm} and {@code vm/ui.qvm} from; every listed pack must
 * be one the server loaded, none twice; and the final number must equal the checksum feed XOR every
 * listed checksum XOR their count.
 */
public final class PureVerifier {
  /** The outcome of one {@code cp}. */
  public enum Result {
    /** A reply for an older level; it changes nothing. */
    IGNORED,
    ACCEPTED,
    REJECTED
  }

  private PureVerifier() {}

  /** The pure checksum of the pack {@code path} resolves to, if it comes from a pack. */
  public static Optional<Integer> packChecksum(PureManifest manifest, String path) {
    Optional<FileRef> ref = manifest.view().find(VirtualPath.of(path));
    if (ref.isEmpty() || !(ref.get().mount() instanceof PackMount mount)) return Optional.empty();
    for (PureManifest.Pack pack : manifest.packs())
      if (pack.mount() == mount) return Optional.of(pack.pureChecksum());
    return Optional.empty();
  }

  /**
   * @param checksumFeedServerId the server id the current checksum feed was issued with; replies
   *     naming an older id are ignored
   */
  public static Result verify(
      CommandArgs args, PureManifest manifest, int checksumFeedServerId, int checksumFeed) {
    try {
      if (args.argc() < 2 || Integer.parseInt(args.argv(1).strip()) < checksumFeedServerId)
        return args.argc() < 2 ? Result.REJECTED : Result.IGNORED;
      Optional<Integer> cgame = packChecksum(manifest, "vm/cgame.qvm");
      Optional<Integer> ui = packChecksum(manifest, "vm/ui.qvm");
      if (cgame.isEmpty() || ui.isEmpty() || args.argc() < 6) return Result.REJECTED;
      if (args.argv(2).startsWith("@") || Integer.parseInt(args.argv(2)) != cgame.get())
        return Result.REJECTED;
      if (args.argv(3).startsWith("@") || Integer.parseInt(args.argv(3)) != ui.get())
        return Result.REJECTED;
      if (!args.argv(4).equals("@")) return Result.REJECTED;
      int listed = args.argc() - 5;
      int[] sums = new int[listed];
      for (int i = 0; i < listed; i++) sums[i] = Integer.parseInt(args.argv(5 + i).strip());
      int count = listed - 1;
      Set<Integer> seen = new HashSet<>();
      Set<Integer> loaded = new HashSet<>();
      for (PureManifest.Pack pack : manifest.packs()) loaded.add(pack.pureChecksum());
      int combined = checksumFeed;
      for (int i = 0; i < count; i++) {
        if (!seen.add(sums[i]) || !loaded.contains(sums[i])) return Result.REJECTED;
        combined ^= sums[i];
      }
      combined ^= count;
      return combined == sums[count] ? Result.ACCEPTED : Result.REJECTED;
    } catch (IllegalArgumentException malformed) {
      return Result.REJECTED;
    }
  }
}
