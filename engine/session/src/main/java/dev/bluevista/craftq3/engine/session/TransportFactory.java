package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.net.transport.UdpTransport;
import java.io.IOException;
import java.net.InetSocketAddress;

/** Opens the datagram sockets a session needs; the default is {@link UdpTransport}. */
public interface TransportFactory {
  /** The listener of a hosted server, bound to {@code local} (port 0 picks one). */
  PacketTransport listen(InetSocketAddress local) throws IOException;

  /** A client socket for one remote server. */
  PacketTransport connect(InetSocketAddress server) throws IOException;

  /** An unconnected socket for out-of-band requests ({@code rcon}). */
  PacketTransport ephemeral(boolean ipv6) throws IOException;

  /** Real UDP sockets. */
  TransportFactory UDP =
      new TransportFactory() {
        @Override
        public PacketTransport listen(InetSocketAddress local) throws IOException {
          return UdpTransport.bind(local);
        }

        @Override
        public PacketTransport connect(InetSocketAddress server) throws IOException {
          return UdpTransport.connect(server);
        }

        @Override
        public PacketTransport ephemeral(boolean ipv6) throws IOException {
          return UdpTransport.ephemeral(
              ipv6 ? UdpTransport.Family.IPV6 : UdpTransport.Family.IPV4, false);
        }
      };
}
