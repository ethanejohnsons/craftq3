/**
 * Quake III protocol-68 networking, without sockets in the codecs.
 *
 * <ul>
 *   <li>{@link dev.bluevista.craftq3.engine.net.Protocol68}: every wire constant.
 *   <li>{@code huffman}: the fixed byte codebook and the adaptive tree of compressed connects.
 *   <li>{@code msg}: bit-field {@code MessageReader}/{@code MessageWriter}, server and client
 *       message codecs, downloads, configstring commands.
 *   <li>{@code delta}: entity, player, user-command and snapshot delta codecs.
 *   <li>{@code chan}: netchan, payload XOR, handshake, client and server sessions, download
 *       windows.
 *   <li>{@code oob}: connectionless packets, compressed connect, master-server responses.
 *   <li>{@code demo}: {@code .dm_NN} framing and the protocol 66 to 68 demo reader.
 *   <li>{@code transport}: the {@code PacketTransport} SPI with UDP and loopback ends.
 * </ul>
 */
package dev.bluevista.craftq3.engine.net;
