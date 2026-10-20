package dev.bluevista.craftq3.engine.game.net;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.chan.DownloadSender;
import dev.bluevista.craftq3.engine.net.chan.Protocol68ServerSession;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import java.net.SocketAddress;
import java.util.Arrays;

/** One network client's connection state on the host side. Owned by the simulation thread. */
final class NetPeer {
  private static final dev.bluevista.craftq3.engine.common.log.Log LOG =
      dev.bluevista.craftq3.engine.common.log.Log.get(NetPeer.class);

  final PacketTransport transport;
  final Object baseAddress;
  SocketAddress address;
  final int qport;
  final int challenge;
  final int client;
  final boolean local;
  final Protocol68ServerSession session;

  /** Next reliable sequence of the client slot not yet handed to {@link #session}. */
  int forwardCursor = 1;

  /** Slot sequence minus session sequence (commands that could not be forwarded). */
  int sequenceOffset;

  int gamestateMessageNum = -1;
  boolean restartPending;
  boolean gotCp;
  boolean pureAuthentic;
  boolean rateDelayed;
  long lastSendMillis;
  int lastSendBytes;

  DownloadSender download;
  String downloadName = "";
  String downloadError;

  /** Dropped by the server; the peer lingers to deliver its final reliable commands. */
  boolean closing;

  long closeAt;
  long nextFinalMessage;

  byte[] lastPlayerState = new byte[Protocol68.PLAYER_STATE_BYTES];
  int lastServerTime;
  int lastFlags;

  final int[] sentSequence = new int[Protocol68.PACKET_BACKUP];
  final int[] sentTime = new int[Protocol68.PACKET_BACKUP];
  final int[] ackedTime = new int[Protocol68.PACKET_BACKUP];

  NetPeer(
      PacketTransport transport,
      Object baseAddress,
      SocketAddress address,
      int qport,
      int challenge,
      int client,
      boolean local) {
    this.transport = transport;
    this.baseAddress = baseAddress;
    this.address = address;
    this.qport = qport;
    this.challenge = challenge;
    this.client = client;
    this.local = local;
    this.session = new Protocol68ServerSession(challenge, qport);
    Arrays.fill(sentSequence, -1);
    Arrays.fill(ackedTime, -1);
  }

  /** Records that message {@code sequence} carried a snapshot built at server time {@code time}. */
  void snapshotSent(int sequence, int time) {
    int slot = sequence & (Protocol68.PACKET_BACKUP - 1);
    sentSequence[slot] = sequence;
    sentTime[slot] = time;
    ackedTime[slot] = -1;
  }

  /** The client acknowledged message {@code sequence} at server time {@code time}. */
  void acknowledged(int sequence, int time) {
    int slot = sequence & (Protocol68.PACKET_BACKUP - 1);
    if (sentSequence[slot] == sequence && ackedTime[slot] < 0) ackedTime[slot] = time;
  }

  /** {@code SV_CalcPings}: the mean round trip over acknowledged frames, or 999. */
  int ping() {
    long total = 0;
    int count = 0;
    for (int i = 0; i < Protocol68.PACKET_BACKUP; i++) {
      if (ackedTime[i] < 0) continue;
      total += ackedTime[i] - sentTime[i];
      count++;
    }
    return count == 0 ? 999 : (int) Math.min(999, total / count);
  }

  void stopDownload() {
    if (download != null)
      try {
        download.close();
      } catch (java.io.IOException failure) {
        LOG.debug("Closing the download of " + downloadName + ": " + failure.getMessage());
      }
    download = null;
    downloadName = "";
    downloadError = null;
  }
}
