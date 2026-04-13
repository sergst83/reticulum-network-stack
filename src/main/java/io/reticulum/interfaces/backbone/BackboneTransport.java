package io.reticulum.interfaces.backbone;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * Lazily checks whether Netty's epoll transport can actually be instantiated.
 *
 * <p>{@link Epoll#isAvailable()} only verifies that the OS is Linux and the
 * native library loaded; it does <em>not</em> guarantee that
 * {@code sun.misc.Unsafe} is accessible for {@code EpollEventArray}, which is
 * required at runtime.  This helper performs one real instantiation attempt at
 * class-load time and caches the result so that callers always receive working
 * event loop groups regardless of JVM configuration.
 */
@Slf4j
final class BackboneTransport {

    /** {@code true} when epoll is both available and functional on this JVM. */
    static final boolean EPOLL_USABLE = probeEpoll();

    private BackboneTransport() {}

    // ── Factory methods ───────────────────────────────────────────────────────

    /** Creates an {@link EventLoopGroup} with 1 thread, using epoll if usable. */
    static EventLoopGroup newBossGroup() {
        if (EPOLL_USABLE) {
            try { return new EpollEventLoopGroup(1); }
            catch (Exception e) { log.debug("EpollEventLoopGroup boss fallback: {}", e.getMessage()); }
        }
        return new NioEventLoopGroup(1);
    }

    /** Creates a worker {@link EventLoopGroup} with default thread count, using epoll if usable. */
    static EventLoopGroup newWorkerGroup() {
        if (EPOLL_USABLE) {
            try { return new EpollEventLoopGroup(); }
            catch (Exception e) { log.debug("EpollEventLoopGroup worker fallback: {}", e.getMessage()); }
        }
        return new NioEventLoopGroup();
    }

    /** Returns the appropriate server channel class. */
    static Class<? extends ServerSocketChannel> serverChannelClass() {
        return EPOLL_USABLE ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    /** Returns the appropriate client (socket) channel class. */
    static Class<? extends SocketChannel> socketChannelClass() {
        return EPOLL_USABLE ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    private static boolean probeEpoll() {
        if (!Epoll.isAvailable()) {
            return false;
        }
        try {
            EpollEventLoopGroup probe = new EpollEventLoopGroup(1);
            probe.shutdownGracefully();
            log.debug("Linux epoll transport available and functional.");
            return true;
        } catch (Exception e) {
            log.debug("Linux epoll reported available but failed to initialise "
                    + "({}); falling back to NIO.", e.getMessage());
            return false;
        }
    }
}